package io.github.jh_mmm.biliaccelerator.btr

import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import io.github.jh_mmm.biliaccelerator.core.DebugEventBuffer
import io.github.jh_mmm.biliaccelerator.core.RelayHealthRegistry
import io.github.jh_mmm.biliaccelerator.core.RewriteResult
import io.github.jh_mmm.biliaccelerator.hook.RemoteClient
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.ProtocolException
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal class BtrMediaSample(val totalLength: Long, val start: Long, val bytes: ByteArray) {
    val end: Long get() = start + bytes.size - 1L
}

object BtrChunkEngine {

    const val CHUNK_SIZE_AUDIO = 128 * 1024
    const val CHUNK_SIZE_VIDEO = 512 * 1024
    const val AUDIO_MAX_CONCURRENCY = 2
    const val PROBE_PREFIX_BYTES = 64 * 1024

    private const val VIDEO_PROBE_BYTES = 128 * 1024
    private const val AUDIO_PROBE_BYTES = 64 * 1024
    private const val MAX_PREFETCH_CHUNKS = 16
    private const val MAX_MEMORY_PREFETCH_BYTES = 8 * 1024 * 1024L
    private const val MAX_PENALTY_ENTRIES = 256
    private const val MAX_HOST_PENALTY_MS = 10L * 60 * 1000L
    private const val SINGLE_RELAY_CONCURRENCY_CAP = 4
    private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36 BiliApp/7800300"

    private data class HostPenalty(val until: Long, val failures: Int)
    private val cdnPenalties = object : LinkedHashMap<String, HostPenalty>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, HostPenalty>?): Boolean =
            size > MAX_PENALTY_ENTRIES
    }
    private val failoverSequence = AtomicLong(0L)

    // Each queued or completed video chunk holds its process-wide reservation
    // until delivered or discarded. Audio has dedicated workers and capacity.
    private const val BUFFER_UNIT = 64 * 1024
    private val videoBudget = Semaphore(24 * 1024 * 1024 / BUFFER_UNIT, true)
    private val audioBudget = Semaphore(4 * 1024 * 1024 / BUFFER_UNIT, true)
    private val videoWorkers = workerPool(16, 192, "BTR-Video")
    private val audioWorkers = workerPool(4, 32, "BTR-Audio")

    private fun workerPool(threads: Int, queued: Int, name: String) = ThreadPoolExecutor(
        threads, threads, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(queued),
        { task -> Thread(task, name).apply { isDaemon = true } }
    )

    private data class DownloadedChunk(val bytes: ByteArray, val host: String, val latency: Long)

    private class PendingChunk(
        private val budget: Semaphore,
        private val permits: Int
    ) {
        private val ready = CountDownLatch(1)
        private var running = false
        private var discarded = false
        private var result: DownloadedChunk? = null

        @Synchronized
        fun start(): Boolean {
            if (discarded) return false
            running = true
            return true
        }

        @Synchronized
        fun finish(chunk: DownloadedChunk?) {
            running = false
            if (discarded) budget.release(permits) else result = chunk
            ready.countDown()
        }

        fun await(stream: BtrCallRegistry.Stream): DownloadedChunk? {
            while (!stream.isClosed) {
                if (ready.await(100, TimeUnit.MILLISECONDS)) return result
            }
            return null
        }

        @Synchronized
        fun discard() {
            if (discarded) return
            discarded = true
            result = null
            if (!running) budget.release(permits)
        }
    }

    private class StreamStatsTracker(
        val sessionId: String,
        val primaryHost: String,
        @Volatile var concurrency: Int,
        @Volatile var burstConcurrency: Int,
        val proxyPort: Int,
        val isEpisode: Boolean,
        val isAudio: Boolean
    ) {
        val totalChunks = AtomicInteger(0)
        val totalBytes = AtomicLong(0L)
        val totalLatency = AtomicLong(0L)
        val hitHosts = ConcurrentHashMap.newKeySet<String>()

        fun recordChunk(host: String, bytes: Long, latencyMs: Long) {
            totalChunks.incrementAndGet()
            totalBytes.addAndGet(bytes)
            totalLatency.addAndGet(latencyMs)
            hitHosts.add(host)
        }

        fun finishAndReport() {
            val count = totalChunks.get()
            if (count == 0) return

            val bytes = totalBytes.get()
            val avgLatency = totalLatency.get() / count
            val mbStr = String.format(Locale.getDefault(), "%.1f", bytes / (1024.0 * 1024.0))

            val prefix = when {
                isEpisode -> "BTR剧集加速"
                isAudio -> "BTR音频优化"
                else -> "BTR并发加速"
            }
            val summaryReason = "$prefix · ${count}分片(${mbStr}MB) · ${hitHosts.size}个CDN · 均时${avgLatency}ms"
            val concurrencyLabel = if (burstConcurrency > concurrency) {
                "冲刺${burstConcurrency}/稳定${concurrency}线程"
            } else {
                "${concurrency}线程"
            }

            val result = RewriteResult(
                changed = true,
                originalUrl = "",
                finalUrl = "",
                originalHost = primaryHost,
                targetHost = "127.0.0.1:$proxyPort ($concurrencyLabel)",
                reason = summaryReason,
                isPcdn = false,
                isMcdn = false,
                segmentKey = "btr_stream_$sessionId",
                isBtr = true,
                btrChunks = count,
                btrBytes = bytes,
                btrConcurrency = concurrency,
                btrCdnCount = hitHosts.size,
                btrAvgLatencyMs = avgLatency
            )
            RemoteClient.notifyRewrite(result)
            io.github.jh_mmm.biliaccelerator.core.DebugEventBuffer.record(
                "BTR",
                "stream-complete session=$sessionId chunks=$count bytes=$bytes cdns=${hitHosts.size}"
            )
        }
    }

    internal data class BtrConcurrencyCaps(
        val burst: Int,
        val steady: Int
    )

    internal fun calculateConcurrencyCaps(
        requestedConcurrency: Int,
        autoConcurrency: Boolean,
        isAudio: Boolean,
        isSingleRelay: Boolean,
        isRelayFamily: Boolean,
        memoryBoundPrefetch: Int
    ): BtrConcurrencyCaps {
        val memoryCap = memoryBoundPrefetch.coerceIn(1, MAX_PREFETCH_CHUNKS)
        val requested = requestedConcurrency.coerceIn(2, 16)
        val steady = when {
            isAudio -> AUDIO_MAX_CONCURRENCY
            isSingleRelay || isRelayFamily -> minOf(requested, SINGLE_RELAY_CONCURRENCY_CAP)
            else -> requested
        }.coerceAtMost(memoryCap)
        val burst = when {
            isAudio || !autoConcurrency || isSingleRelay -> steady
            else -> maxOf(steady, 8)
        }.coerceAtMost(memoryCap)
        return BtrConcurrencyCaps(burst = burst, steady = steady)
    }

    internal fun calculateAdaptivePenaltyMs(
        baseDurationMs: Long,
        consecutiveFailures: Int,
        maxDurationMs: Long = MAX_HOST_PENALTY_MS
    ): Long {
        val failures = consecutiveFailures.coerceAtLeast(1)
        val shift = (failures - 1).coerceAtMost(3)
        val multiplied = baseDurationMs.coerceAtLeast(1L) shl shift
        return multiplied.coerceAtMost(maxDurationMs.coerceAtLeast(baseDurationMs.coerceAtLeast(1L)))
    }

    /**
     * 自适应切片尺寸计算：
     * 根据视频总长度（清晰度与体积表征）动态调整切片大小：
     * - 音频流保持 128KB 极低延时
     * - < 15MB: 256KB (小文件/低码率，保证极低首帧起播时延)
     * - 15MB ~ 60MB: 512KB (720P/1080P 常规基准切片)
     * - >= 60MB: 1024KB (1MB)，以限制高码率视频的预取内存
     */
    fun calculateDynamicChunkSize(
        totalLength: Long,
        baseChunkSizeKb: Int,
        autoChunkSize: Boolean,
        isAudio: Boolean
    ): Long {
        if (isAudio) return 128 * 1024L
        if (!autoChunkSize) return (baseChunkSizeKb.coerceIn(128, 2048) * 1024).toLong()
        return when {
            totalLength <= 0 -> (baseChunkSizeKb.coerceIn(128, 2048) * 1024).toLong()
            totalLength < 15 * 1024 * 1024L -> 256 * 1024L
            totalLength < 60 * 1024 * 1024L -> 512 * 1024L
            else -> 1024 * 1024L // 移动端自适应优化：大文件/4K 自动切片上限建议为 1024KB (1MB)，大幅缓解堆内存与 OOM 压力
        }
    }

    private data class SourceRequest(
        val session: BtrMediaSession,
        val stream: BtrCallRegistry.Stream,
        val userAgent: String,
        val referer: String,
        val callFactory: (OkHttpClient, Request) -> Call,
        val excludedFallbackUrl: String? = null
    )

    private enum class PipeResult { COMPLETE, FAILED_BEFORE_RESPONSE, FAILED_AFTER_RESPONSE }

    private data class Probe(
        val totalLength: Long = -1L,
        val contentType: String? = null,
        val offset: Long = 0L,
        val firstBytes: ByteArray? = null,
        val host: String = "",
        val latency: Long = 0L,
        val transferred: Boolean = false,
        val sourceUrl: String? = null
    )

    internal fun pipeStream(
        session: BtrMediaSession,
        range: BtrRangeRequest,
        head: Boolean,
        out: OutputStream,
        stream: BtrCallRegistry.Stream,
        concurrency: Int,
        chunkSizeKb: Int,
        autoConcurrency: Boolean,
        autoChunkSize: Boolean,
        proxyPort: Int,
        userAgent: String?,
        referer: String,
        callFactory: (OkHttpClient, Request) -> Call = OkHttpClient::newCall
    ) {
        val source = SourceRequest(session, stream, userAgent ?: session.userAgent ?: DEFAULT_USER_AGENT, referer, callFactory)
        val plan = session.candidatePlan
        val candidates = (listOf(session.primaryUrl) + (plan?.backupUrls ?: session.backupUrls))
            .filter(BtrHttpSafety::isAllowedUpstreamUrl).distinct()
        val synthesized = plan?.synthesizedUrls.orEmpty()
        val primaryRelay = session.primaryUrl.takeIf(BiliAcceleratorCore::isRelayWrapper)
            ?.let(BiliAcceleratorCore::unwrapRelayUrl)
        val probeCandidates = candidates.filter { url ->
            url !in synthesized || (primaryRelay != null && BiliAcceleratorCore.unwrapRelayUrl(url) == primaryRelay)
        }
        val safeFallback = session.safeFallbackUrl
        val fallbackCandidates = (probeCandidates + listOfNotNull(safeFallback)).distinct()
        if (head) {
            val length = session.totalLength
            if (length >= 0L) {
                writeHeaders(out, 200, "OK", session.contentType, length, null, true)
                return
            }
            for (url in orderedUrls(fallbackCandidates, session)) {
                val result = pipeUpstream(source, url, BtrRangeRequest.Full, head = true, out)
                if (result != PipeResult.FAILED_BEFORE_RESPONSE) return
            }
            if (stream.isClosed) return
            // Some media CDNs accept range GETs and reject HEAD.
            val probe = probeMedia(source, fallbackCandidates, BtrRangeRequest.Full, 1, out, headOnly = true)
            if (probe?.transferred == true) return
            if (probe != null && probe.totalLength >= 0L) {
                writeHeaders(out, 200, "OK", probe.contentType, probe.totalLength, null, true)
            } else if (!stream.isClosed) {
                writeError(out, 502, "Bad Gateway")
            }
            return
        }

        val directDeadline = session.activeFallbackDeadline()
        val attemptedDirect = safeFallback?.takeIf { directDeadline != null }
        if (attemptedDirect != null && directDeadline != null) {
            when (pipeUpstream(source, attemptedDirect, range, head = false, out)) {
                PipeResult.COMPLETE -> return
                PipeResult.FAILED_AFTER_RESPONSE -> {
                    session.clearDirectFallback(directDeadline)
                    return
                }
                PipeResult.FAILED_BEFORE_RESPONSE -> session.clearDirectFallback(directDeadline)
            }
        }

        val isAudio = session.isAudio
        val probeSize = if (isAudio) AUDIO_PROBE_BYTES else VIDEO_PROBE_BYTES
        val requestedStart = range.start ?: 0L
        val knownLength = session.totalLength
        val cached = session.sample
        val probe = if (knownLength > 0L && (requestedStart > 0L || range.start == null && range.isPartial)) {
            Probe(totalLength = knownLength, contentType = session.contentType)
        } else {
            probeMedia(source, probeCandidates.filterNot { it == attemptedDirect }, range, probeSize, out)
        }

        if (probe == null) {
            if (stream.isClosed) return
            if (safeFallback != null && safeFallback != attemptedDirect && safeFallback !in probeCandidates) {
                when (pipeUpstream(source, safeFallback, range, head = false, out)) {
                    PipeResult.COMPLETE -> {
                        session.armDirectFallback()
                        DebugEventBuffer.record("BTR", "direct_fallback_succeeded stage=probe")
                        return
                    }
                    PipeResult.FAILED_AFTER_RESPONSE -> return
                    PipeResult.FAILED_BEFORE_RESPONSE -> Unit
                }
            } else if (safeFallback != null && safeFallback != attemptedDirect && !session.isUrlRejected(safeFallback)) {
                BtrSessionManager.recordDirectFallback(session.originalUrl)
            }
            DebugEventBuffer.record("BTR", "probe_failed candidates=${probeCandidates.size}")
            writeError(out, 502, "Bad Gateway")
            return
        }
        if (probe.transferred) return

        val total = probe.totalLength
        val resolved = range.resolve(total)
        if (resolved == null) {
            if (!range.isPartial && total == 0L) {
                writeHeaders(out, 200, "OK", probe.contentType, 0L, null)
            } else {
                writeError(out, 416, "Range Not Satisfiable", total)
            }
            return
        }

        val contentType = probe.contentType ?: if (isAudio) "audio/mp4" else "video/mp4"
        val probeBytes = probe.firstBytes
        val sample = if (probeBytes != null) {
            val candidate = BtrMediaSample(total, probe.offset, probeBytes.copyOf(minOf(probeBytes.size, PROBE_PREFIX_BYTES)))
            session.cacheSample(candidate, contentType).also { reference ->
                if (reference === candidate) {
                    probe.sourceUrl?.let { session.verifiedCandidates[it] = reference }
                }
            }
        } else cached?.takeIf { it.totalLength == total }
        val activeChunkSize = calculateDynamicChunkSize(total, chunkSizeKb, autoChunkSize, isAudio)
        val hosts = candidates.map(::extractHostname).distinct()
        val isRelay = BiliAcceleratorCore.isRelayUrl(session.primaryUrl)
        val caps = calculateConcurrencyCaps(
            concurrency, autoConcurrency, isAudio, isRelay && hosts.size <= 1,
            isRelay, (MAX_MEMORY_PREFETCH_BYTES / activeChunkSize).toInt()
        )
        val stats = StreamStatsTracker(
            session.sessionId, extractHostname(session.primaryUrl), caps.steady, caps.burst,
            proxyPort, session.isEpisode, session.isAudio
        )

        val contentRange = if (range.isPartial) "bytes ${resolved.start}-${resolved.end}/$total" else null
        writeHeaders(out, if (range.isPartial) 206 else 200,
            if (range.isPartial) "Partial Content" else "OK", contentType, resolved.length, contentRange, true)
        var offset = resolved.start
        try {
            if (probeBytes != null && offset >= probe.offset && offset - probe.offset < probeBytes.size) {
                val from = (offset - probe.offset).toInt()
                val length = minOf((probeBytes.size - from).toLong(), resolved.length).toInt()
                out.write(probeBytes, from, length)
                out.flush()
                stats.recordChunk(probe.host, length.toLong(), probe.latency)
                offset += length
            }
            deliverChunks(
                source.copy(excludedFallbackUrl = attemptedDirect), candidates.filterNot { it == attemptedDirect },
                synthesized, sample, total, offset, resolved.end, activeChunkSize, caps, out, stats
            )
        } finally {
            stats.finishAndReport()
        }
    }

    private fun deliverChunks(
        source: SourceRequest,
        urls: List<String>,
        synthesized: Set<String>,
        sample: BtrMediaSample?,
        total: Long,
        start: Long,
        end: Long,
        chunkSize: Long,
        caps: BtrConcurrencyCaps,
        out: OutputStream,
        stats: StreamStatsTracker
    ) {
        val budget = if (source.session.isAudio) audioBudget else videoBudget
        val workers = if (source.session.isAudio) audioWorkers else videoWorkers
        val pending = ArrayDeque<PendingChunk>()
        var offset = start
        var dispatched = 0L
        var delivered = 0L
        try {
            while ((offset <= end || pending.isNotEmpty()) && !source.stream.isClosed) {
                val window = if (delivered < 4) caps.burst else caps.steady
                while (offset <= end && pending.size < window && !source.stream.isClosed) {
                    val chunkStart = offset
                    val chunkEnd = minOf(end - chunkStart + 1, chunkSize) + chunkStart - 1
                    val permits = ((chunkEnd - chunkStart + BUFFER_UNIT) / BUFFER_UNIT).toInt()
                    if (!budget.tryAcquire(permits, if (pending.isEmpty()) 100L else 0L, TimeUnit.MILLISECONDS)) break

                    val index = dispatched++
                    val task = PendingChunk(budget, permits)
                    pending.addLast(task)
                    try {
                        workers.execute {
                            if (task.start()) {
                                var result: DownloadedChunk? = null
                                try {
                                    result = fetchChunk(source, urls, synthesized, sample, total, chunkStart, chunkEnd, index)
                                } finally {
                                    task.finish(result)
                                }
                            }
                        }
                    } catch (_: RejectedExecutionException) {
                        task.finish(null)
                    }
                    offset = chunkEnd + 1
                }

                val task = pending.pollFirst() ?: continue
                try {
                    val chunk = task.await(source.stream)
                    if (chunk == null) {
                        if (!source.stream.isClosed) {
                            DebugEventBuffer.record("BTR", "chunk_failed index=$delivered")
                            val fallback = source.session.safeFallbackUrl
                            if (fallback != null && fallback != source.excludedFallbackUrl && !source.session.isUrlRejected(fallback)) {
                                BtrSessionManager.recordDirectFallback(source.session.originalUrl)
                            }
                        }
                        break
                    }
                    out.write(chunk.bytes)
                    out.flush()
                    stats.recordChunk(chunk.host, chunk.bytes.size.toLong(), chunk.latency)
                    delivered++
                } finally {
                    task.discard()
                }
            }
        } finally {
            // Closing the request prevents queued work from registering a late call.
            source.stream.close()
            pending.forEach { it.discard() }
        }
    }

    private fun request(source: SourceRequest, url: String, range: String?, head: Boolean = false): Request =
        Request.Builder().url(url)
            .header("Referer", source.referer)
            .header("User-Agent", source.userAgent)
            .header("Accept-Encoding", "identity")
            .apply {
                if (head) head() else if (range != null) header("Range", range)
            }.build()

    private inline fun <T> execute(
        source: SourceRequest,
        client: OkHttpClient,
        request: Request,
        waitForHost: Boolean = true,
        block: (Response, Long) -> T
    ): T {
        var current = request
        var redirectNanos = 0L
        repeat(5) {
            if (!BtrHttpSafety.isAllowedUpstreamUrl(current.url.toString())) {
                throw IOException("Blocked unsafe upstream URL")
            }
            val slots = BtrHttpClient.acquireHost(current.url.host, source.session.isAudio, source.stream, waitForHost)
                ?: throw RouteBusy()
            try {
                val call = source.callFactory(client, current)
                if (!source.stream.register(call)) throw IOException("Stream canceled")
                try {
                    val began = System.nanoTime()
                    call.execute().use { response ->
                        current = BtrHttpClient.redirectRequest(current, response)
                            ?: return block(response, began - redirectNanos)
                    }
                    redirectNanos += System.nanoTime() - began
                } finally {
                    source.stream.unregister(call)
                }
            } finally {
                slots.close()
            }
        }
        throw IOException("Too many upstream redirects")
    }

    private fun probeMedia(
        source: SourceRequest,
        urls: List<String>,
        range: BtrRangeRequest,
        probeSize: Int,
        out: OutputStream,
        headOnly: Boolean = false
    ): Probe? {
        val start = range.start ?: 0L
        val end = minOf(range.end ?: Long.MAX_VALUE, start + minOf(probeSize - 1L, Long.MAX_VALUE - start))
        val client = if (source.session.isAudio) BtrHttpClient.audioStreamClient else BtrHttpClient.probeClient
        for (url in orderedUrls(urls, source.session)) {
            if (source.stream.isClosed) return null
            var writing = false
            try {
                execute(source, client, request(source, url, "bytes=$start-$end")) { response, begin ->
                    val host = response.request.url.host
                    if (response.code == 200 && response.header("Content-Range") == null &&
                        usesIdentityEncoding(response) && response.body != null
                    ) {
                        // This exact upstream supplied the full representation.
                        writing = true
                        writeUpstreamResponse(response, out, head = headOnly)
                        markHealthy(url)
                        DebugEventBuffer.record("BTR", "non_range_passthrough host=$host")
                        return Probe(transferred = true)
                    }
                    if (response.code == 416) {
                        val length = BtrHttpSafety.parseUnsatisfiedLength(response.header("Content-Range"))
                        if (length != null && (length == 0L || range.resolve(length) == null)) {
                            return Probe(totalLength = length, contentType = response.header("Content-Type"))
                        }
                    }
                    if (response.code != 206) {
                        handleStatus(source, url, response)
                        return@execute
                    }
                    val parsed = BtrHttpSafety.parseContentRange(response.header("Content-Range"))
                    val total = parsed?.total
                    if (total == null || parsed.start != start || parsed.end != minOf(end, total - 1)) {
                        rejectUrl(source, url, "probe_range_invalid")
                        return@execute
                    }
                    val bytes = readChunk(response, start, parsed.end, total)
                    if (bytes == null) {
                        rejectUrl(source, url, "probe_body_invalid")
                        return@execute
                    }
                    markHealthy(url)
                    val elapsed = System.nanoTime() - begin
                    source.session.routeMetrics.record(url, bytes.size, elapsed)
                    return Probe(total, response.header("Content-Type"), start, bytes, host,
                        TimeUnit.NANOSECONDS.toMillis(elapsed), sourceUrl = url)
                }
            } catch (error: IOException) {
                if (writing) throw error
                handleNetworkError(source, url, error)
            }
        }
        return null
    }

    private fun pipeUpstream(
        source: SourceRequest,
        url: String,
        range: BtrRangeRequest,
        head: Boolean,
        out: OutputStream
    ): PipeResult {
        if (source.stream.isClosed || source.session.isUrlRejected(url)) return PipeResult.FAILED_BEFORE_RESPONSE
        var writing = false
        return try {
            val client = if (source.session.isAudio) BtrHttpClient.audioStreamClient else BtrHttpClient.fallbackClient
            execute(source, client, request(source, url, range.headerValue(), head)) { response, _ ->
                val contentRange = response.header("Content-Range")
                if (response.code == 416) {
                    val length = BtrHttpSafety.parseUnsatisfiedLength(contentRange)
                    if (range.isPartial && length != null && range.resolve(length) == null) {
                        writing = true
                        writeError(out, 416, "Range Not Satisfiable", length)
                        return@execute PipeResult.COMPLETE
                    }
                }
                if (response.code != 200 && response.code != 206) {
                    if (!head || response.code !in listOf(403, 405, 501)) handleStatus(source, url, response)
                    return@execute PipeResult.FAILED_BEFORE_RESPONSE
                }
                if (!usesIdentityEncoding(response) || (!head && response.body == null)) {
                    return@execute PipeResult.FAILED_BEFORE_RESPONSE
                }
                if (response.code == 206) {
                    val actual = BtrHttpSafety.parseContentRange(contentRange)
                    val requested = actual?.total?.let(range::resolve)
                    if (!range.isPartial || requested == null || actual.start != requested.start || actual.end != requested.end) {
                        return@execute PipeResult.FAILED_BEFORE_RESPONSE
                    }
                    val length = response.body?.contentLength() ?: -1L
                    if (length >= 0L && length != requested.length) return@execute PipeResult.FAILED_BEFORE_RESPONSE
                } else if (contentRange != null) {
                    return@execute PipeResult.FAILED_BEFORE_RESPONSE
                }
                writing = true
                writeUpstreamResponse(response, out, head)
                markHealthy(url)
                PipeResult.COMPLETE
            }
        } catch (error: IOException) {
            if (writing) {
                PipeResult.FAILED_AFTER_RESPONSE
            } else {
                handleNetworkError(source, url, error)
                PipeResult.FAILED_BEFORE_RESPONSE
            }
        }
    }

    private fun writeUpstreamResponse(response: Response, out: OutputStream, head: Boolean) {
        val range = BtrHttpSafety.parseContentRange(response.header("Content-Range"))
        val length = range?.let { it.end - it.start + 1L }
            ?: response.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0L }
            ?: -1L
        writeHeaders(out, response.code, if (response.code == 206) "Partial Content" else "OK",
            response.header("Content-Type"), length, response.header("Content-Range"),
            range != null || response.header("Accept-Ranges").equals("bytes", ignoreCase = true))
        if (head) return

        val input = response.body?.byteStream() ?: throw EOFException("Missing upstream body")
        val buffer = ByteArray(BUFFER_UNIT)
        var written = 0L
        while (length < 0L || written < length) {
            val limit = if (length < 0L) buffer.size else minOf(buffer.size.toLong(), length - written).toInt()
            val read = input.read(buffer, 0, limit)
            if (read < 0) break
            out.write(buffer, 0, read)
            written += read
        }
        if (length >= 0L && written != length) throw EOFException("Truncated upstream body")
        out.flush()
    }

    private class RouteBusy : Exception()

    private class ChunkBuffer(length: Int) {
        val bytes = ByteArray(length)
        var received = 0
            private set
        private var validator: String? = null

        fun resumeTag(): String? = validator?.takeIf { received in 32 * 1024 until bytes.size }

        fun read(response: Response, tag: String?, offset: Int): Boolean {
            val body = response.body ?: return false
            val expected = bytes.size - offset
            val declared = body.contentLength()
            if (declared >= 0L && declared != expected.toLong()) return false

            received = offset
            validator = tag
            val input = body.byteStream()
            while (received < bytes.size) {
                val count = input.read(bytes, received, bytes.size - received)
                if (count < 0) throw EOFException("Truncated upstream range")
                received += count
            }
            if (input.read() < 0) return true
            received = 0
            validator = null
            return false
        }
    }

    private fun fetchChunk(
        source: SourceRequest,
        urls: List<String>,
        synthesized: Set<String>,
        sample: BtrMediaSample?,
        total: Long,
        start: Long,
        end: Long,
        index: Long
    ): DownloadedChunk? {
        val ordered = source.session.routeMetrics.order(orderedUrls(urls, source.session), index)
        val client = if (source.session.isAudio) BtrHttpClient.audioClient else BtrHttpClient.client
        val fallback = source.session.safeFallbackUrl?.takeUnless { it == source.excludedFallbackUrl || it in urls }
        val buffer = ChunkBuffer((end - start + 1).toInt())

        fun download(url: String, waitForHost: Boolean): DownloadedChunk? {
            if (source.stream.isClosed || source.session.isUrlRejected(url) ||
                isHostPenalized(extractHostname(url))
            ) return null
            if ((url in synthesized || url == fallback) &&
                !verifyCandidate(source, url, sample, client, waitForHost)
            ) return null

            var tag = buffer.resumeTag()
            var restartWhole: Boolean
            do {
                restartWhole = false
                val prefix = if (tag == null) 0 else buffer.received
                val actualStart = start + prefix
                val request = request(source, url, "bytes=$actualStart-$end").let { base ->
                    val validator = tag
                    if (validator == null) base else base.newBuilder().header("If-Range", validator).build()
                }
                var readingBody = false
                try {
                    val result = execute(source, client, request, waitForHost) { response, begin ->
                        val rangeMatches = matchesChunkRange(response, actualStart, end, total)
                        val responseTag = strongEtag(response)
                        if (tag != null && (response.code == 200 || response.code == 412 || response.code == 416 ||
                                (response.code == 206 && (!rangeMatches || responseTag != tag)))
                        ) {
                            // The node uses a different validator or ignored If-Range.
                            // Fetch this piece from its beginning before accepting any of its bytes.
                            restartWhole = true
                            return@execute null
                        }
                        if (response.code != 206) {
                            handleStatus(source, url, response)
                            if (response.code == 200) rejectUrl(source, url, "chunk_range_ignored")
                            return@execute null
                        }
                        readingBody = rangeMatches
                        if (!rangeMatches || !buffer.read(response, responseTag, prefix)) {
                            rejectUrl(source, url, "chunk_body_invalid")
                            return@execute null
                        }
                        val elapsed = System.nanoTime() - begin
                        source.session.routeMetrics.record(url, buffer.bytes.size - prefix, elapsed)
                        markHealthy(url)
                        if (url == fallback) source.session.armDirectFallback()
                        if (prefix > 0) DebugEventBuffer.record("BTR", "chunk_resumed saved_bytes=$prefix")
                        DownloadedChunk(buffer.bytes, response.request.url.host, TimeUnit.NANOSECONDS.toMillis(elapsed))
                    }
                    if (!restartWhole) return result
                    tag = null
                } catch (error: IOException) {
                    if (readingBody) rejectUrl(source, url, "chunk_body_interrupted")
                    else handleNetworkError(source, url, error)
                    return null
                }
            } while (restartWhole)
            return null
        }

        // A queued request for a saturated node must not tie up a worker while
        // a different signed candidate has room.
        val deferred = mutableListOf<String>()
        for (url in ordered) {
            if (source.stream.isClosed) return null
            try {
                download(url, waitForHost = false)?.let { return it }
            } catch (_: RouteBusy) {
                deferred.add(url)
            }
        }
        while (deferred.size > 1 && !source.stream.isClosed) {
            val waiting = deferred.iterator()
            while (waiting.hasNext()) {
                try {
                    download(waiting.next(), waitForHost = false)?.let { return it }
                    waiting.remove()
                } catch (_: RouteBusy) {
                    // A different host can release its slot first.
                }
            }
            if (deferred.size > 1) Thread.sleep(35)
        }
        for (url in deferred + listOfNotNull(fallback)) {
            if (source.stream.isClosed) return null
            download(url, waitForHost = true)?.let { return it }
        }
        return null
    }

    private fun verifyCandidate(
        source: SourceRequest,
        url: String,
        sample: BtrMediaSample?,
        client: OkHttpClient,
        waitForHost: Boolean
    ): Boolean {
        if (sample == null || sample.bytes.size < 4096) return false
        val session = source.session
        if (session.verifiedCandidates[url] === sample) return true
        val slot = session.verificationSlot(url)
        return try {
            if (!BtrHttpClient.acquireSlot(slot, source.stream, waitForHost)) throw RouteBusy()
            try {
                if (session.verifiedCandidates[url] === sample) return true
                if (session.isUrlRejected(url) || isHostPenalized(extractHostname(url))) return false
                execute(source, client, request(source, url, "bytes=${sample.start}-${sample.end}"), waitForHost) { response, _ ->
                    if (response.code != 206) {
                        handleStatus(source, url, response)
                        if (response.code == 200) rejectUrl(source, url, "candidate_range_ignored")
                        return@execute false
                    }
                    val data = readChunk(response, sample.start, sample.end, sample.totalLength)
                    if (data == null || !data.contentEquals(sample.bytes)) {
                        rejectUrl(source, url, "candidate_sample_mismatch")
                        false
                    } else {
                        session.verifiedCandidates[url] = sample
                        true
                    }
                }
            } finally {
                slot.release()
            }
        } catch (error: IOException) {
            handleNetworkError(source, url, error)
            false
        }
    }

    private fun matchesChunkRange(response: Response, start: Long, end: Long, total: Long): Boolean {
        if (response.code != 206 || !usesIdentityEncoding(response)) return false
        val actual = BtrHttpSafety.parseContentRange(response.header("Content-Range")) ?: return false
        return actual.start == start && actual.end == end && actual.total == total
    }

    private fun strongEtag(response: Response): String? {
        val tag = response.header("ETag") ?: return null
        if (tag.length !in 2..256 || tag.first() != '"' || tag.last() != '"') return null
        return tag.takeIf { value -> value.substring(1, value.lastIndex).all { it == '!' || it in '#'..'~' } }
    }

    private fun readChunk(response: Response, start: Long, end: Long, total: Long): ByteArray? {
        if (!matchesChunkRange(response, start, end, total)) return null
        val length = end - start + 1L
        if (length !in 1L..BtrHttpSafety.MAX_UPSTREAM_BODY_BYTES.toLong()) return null
        return BtrHttpSafety.readBodyLimited(response.body, length.toInt())?.takeIf { it.size.toLong() == length }
    }

    private fun usesIdentityEncoding(response: Response): Boolean =
        response.header("Content-Encoding")?.equals("identity", ignoreCase = true) != false

    private fun orderedUrls(urls: List<String>, session: BtrMediaSession): List<String> =
        urls.filterNot { session.isUrlRejected(it) || isHostPenalized(extractHostname(it)) }

    private fun extractHostname(url: String): String =
        BiliAcceleratorCore.parseUri(url)?.host?.lowercase(Locale.ROOT).orEmpty()

    private fun rejectUrl(source: SourceRequest, url: String, reason: String) {
        if (source.stream.isClosed) return
        source.session.rejectUrl(url)
        DebugEventBuffer.record("BTR", "$reason host=${extractHostname(url)}")
    }

    private fun handleStatus(source: SourceRequest, url: String, response: Response) {
        if (source.stream.isClosed) return
        val code = response.code
        if (code == 403 || code == 401) {
            rejectUrl(source, url, "auth_http_$code")
        } else if (httpStatusShouldMarkRelayHealth(code)) {
            penalizeHost(url, 30_000L)
            reportFailover(extractHostname(url), "HTTP $code", source.session.sessionId, 0)
        }
    }

    private fun handleNetworkError(source: SourceRequest, url: String, error: IOException) {
        if (source.stream.isClosed) return
        val reason = error.message.orEmpty()
        if (reason.startsWith("Blocked unsafe upstream")) {
            rejectUrl(source, url, "unsafe_upstream")
            return
        }
        if (error is EOFException || error is ProtocolException ||
            error is java.io.InterruptedIOException
        ) {
            // A partial or stalled response is evidence against this signed URL.
            // Other videos on the same CDN can still be healthy.
            rejectUrl(source, url, "request_io_" + error.javaClass.simpleName)
            return
        }
        penalizeHost(url, penaltyForFailureMessage(reason))
        reportFailover(extractHostname(url), error.javaClass.simpleName, source.session.sessionId, 0)
    }

    private fun penalizeHost(url: String, base: Long) {
        val host = extractHostname(url)
        if (host.isEmpty()) return
        val now = System.nanoTime() / 1_000_000L
        val penalty = synchronized(cdnPenalties) {
            val previous = cdnPenalties[host]
            val failures = ((previous?.takeIf { it.until > now }?.failures ?: 0) + 1).coerceAtMost(8)
            val duration = calculateAdaptivePenaltyMs(base, failures)
            cdnPenalties[host] = HostPenalty(maxOf(previous?.until ?: now, now + duration), failures)
            duration
        }
        if (BiliAcceleratorCore.isRelayWrapper(url)) RelayHealthRegistry.markFailure(host, penalty)
    }

    private fun isHostPenalized(host: String): Boolean = synchronized(cdnPenalties) {
        val penalty = cdnPenalties[host] ?: return@synchronized false
        if (penalty.until > System.nanoTime() / 1_000_000L) return@synchronized true
        cdnPenalties.remove(host)
        false
    }

    private fun markHealthy(url: String) {
        val host = extractHostname(url)
        synchronized(cdnPenalties) { cdnPenalties.remove(host) }
        if (BiliAcceleratorCore.isRelayWrapper(url)) RelayHealthRegistry.markSuccess(host)
    }

    internal fun httpStatusShouldMarkRelayHealth(code: Int): Boolean = code >= 500

    internal fun penaltyForFailureMessage(message: String): Long {
        val lower = message.lowercase(Locale.ROOT)
        return when {
            lower.contains("unable to resolve host") ||
                lower.contains("no address associated") ||
                lower.contains("no address returned") -> 300_000L
            lower.contains("blocked unsafe upstream") -> 120_000L
            else -> 30_000L
        }
    }

    private fun writeHeaders(
        out: OutputStream,
        code: Int,
        reason: String,
        contentType: String?,
        length: Long,
        contentRange: String?,
        acceptRanges: Boolean = false
    ) {
        val headers = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            if (contentType != null) append("Content-Type: $contentType\r\n")
            if (length >= 0L) append("Content-Length: $length\r\n")
            if (contentRange != null) append("Content-Range: $contentRange\r\n")
            if (acceptRanges) append("Accept-Ranges: bytes\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(headers.toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private fun writeError(out: OutputStream, code: Int, reason: String, length: Long = -1L) =
        writeHeaders(out, code, reason, null, 0L, if (code == 416 && length >= 0L) "bytes */$length" else null)

    private fun reportFailover(
        badHost: String,
        reason: String,
        sessionId: String,
        candidateCount: Int
    ) {
        val failoverId = failoverSequence.incrementAndGet()
        val result = RewriteResult(
            changed = true,
            originalUrl = "",
            finalUrl = "",
            originalHost = badHost,
            targetHost = "单节点退避/继续尝试",
            reason = "BTR节点熔断: $reason",
            isPcdn = false,
            isMcdn = false,
            segmentKey = "btr_failover_${sessionId}_${failoverId}_${Integer.toHexString(badHost.hashCode())}",
            isBtr = true,
            btrCdnCount = candidateCount.coerceIn(0, 64)
        )
        RemoteClient.notifyRewrite(result)
        io.github.jh_mmm.biliaccelerator.core.DebugEventBuffer.record(
            "BTR",
            "node-backoff session=${sessionId.take(6)} host=$badHost candidates=$candidateCount reason=$reason"
        )
    }

}
