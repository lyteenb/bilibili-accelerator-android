package io.github.jh_mmm.biliaccelerator.btr

import android.util.Log
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import io.github.jh_mmm.biliaccelerator.core.RelayHealthRegistry
import io.github.jh_mmm.biliaccelerator.core.RewriteResult
import io.github.jh_mmm.biliaccelerator.hook.RemoteClient
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import java.io.OutputStream
import java.net.SocketException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal enum class BtrDirectPipeResult {
    COMPLETED,
    FAILED_BEFORE_RESPONSE,
    FAILED_AFTER_RESPONSE
}

object BtrChunkEngine {

    private const val TAG = "BiliAccelerator-BTR"
    const val CHUNK_SIZE_AUDIO = 128 * 1024
    const val CHUNK_SIZE_VIDEO = 512 * 1024
    const val AUDIO_MAX_CONCURRENCY = 2
    const val PROBE_PREFIX_BYTES = 64 * 1024

    private const val DEFAULT_CHUNK_SIZE = 512 * 1024L // 512 KB
    private const val MAX_PREFETCH_CHUNKS = 16
    private const val MAX_MEMORY_PREFETCH_BYTES = 8 * 1024 * 1024L
    private const val MAX_PENALTY_ENTRIES = 256
    private const val MAX_HOST_PENALTY_MS = 10L * 60 * 1000L
    private const val SINGLE_RELAY_CONCURRENCY_CAP = 4
    private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36 BiliApp/7800300"

    private val cdnPenalties = ConcurrentHashMap<String, Long>()
    private val cdnFailureCounts = ConcurrentHashMap<String, Int>()
    private val failoverSequence = AtomicLong(0L)

    // Shared bounded workers keep rapid feed scrolling from creating a fresh
    // thread pool for every media session.
    private val globalWorkerPool = ThreadPoolExecutor(
        8,
        32,
        60L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(512),
        { r -> Thread(r, "BTR-GlobalWorker").apply { isDaemon = true } },
        ThreadPoolExecutor.CallerRunsPolicy()
    )
    private val globalDispatcherPool = Executors.newFixedThreadPool(16) { r ->
        Thread(r, "BTR-Dispatcher").apply { isDaemon = true }
    }
    private val activeCalls = BtrCallRegistry()

    internal fun cancelAllSessions() {
        activeCalls.cancelAll()
    }

    private inline fun <T> executeWithTracking(
        streamCallKey: String,
        sessionId: String,
        call: Call,
        block: (okhttp3.Response) -> T
    ): T {
        activeCalls.register(streamCallKey, sessionId, call)
        return try {
            call.execute().use(block)
        } finally {
            activeCalls.unregister(streamCallKey, call)
        }
    }

    internal fun registerActiveCallForTesting(sessionId: String, call: Call): String {
        val streamCallKey = activeCalls.newStreamCallKey(sessionId)
        activeCalls.register(streamCallKey, sessionId, call)
        return streamCallKey
    }

    internal fun cancelStreamCallsForTesting(streamCallKey: String) {
        activeCalls.cancelStreamCalls(streamCallKey)
    }

    internal fun unregisterActiveCallForTesting(streamCallKey: String, call: Call) {
        activeCalls.unregister(streamCallKey, call)
    }

    internal fun activeStreamCallCountForTesting(): Int = activeCalls.activeStreamCount()

    internal fun hasSessionStreamKeyForTesting(sessionId: String): Boolean {
        return activeCalls.hasSessionStreamKey(sessionId)
    }

    data class ChunkTask(
        val index: Long,
        val startByte: Long,
        val endByte: Long,
        @Volatile var data: ByteArray? = null,
        @Volatile var isSuccess: Boolean = false,
        @Volatile var isFailed: Boolean = false,
        @Volatile var errorMessage: String? = null
    )

    private class StreamStatsTracker(
        val sessionId: String,
        val primaryHost: String,
        @Volatile var concurrency: Int,
        @Volatile var burstConcurrency: Int,
        val proxyPort: Int,
        val isEpisode: Boolean = false
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
                sessionId.startsWith("audio_") || primaryHost.contains("audio") -> "BTR音频优化"
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
            isRelayFamily -> maxOf(steady, 8).coerceAtMost(16)
            else -> maxOf(steady, 8).coerceAtMost(16)
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
     * - 60MB ~ 250MB: 1024KB (1MB) (1080P 60帧/高码率视频)
     * - >= 250MB: 2048KB (2MB) (4K/8K/高码率长视频，大幅减少高吞吐下的并发 HTTP 请求与头部握手开销)
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

    fun pipeStream(
        session: BtrMediaSession,
        requestedStart: Long,
        requestedEnd: Long?, // null 表示直至文件尾部
        isHeadOnly: Boolean,
        clientOut: OutputStream,
        concurrency: Int = 6,
        chunkSizeKb: Int = 512,
        autoConcurrency: Boolean = true,
        autoChunkSize: Boolean = true,
        proxyPort: Int = -1
    ) {
        val isAudio = session.isAudio || BiliAcceleratorCore.isAudioTrack(session.primaryUrl)
        val initialProbeChunkSize = if (isAudio) {
            128 * 1024L // 音频流采用 128KB 小切片低时延分片
        } else {
            512 * 1024L // 探测首片统一使用 512KB 快速拉取总长度
        }

        val candidateUrls = (listOf(session.primaryUrl) + session.backupUrls)
            .filter { BtrHttpSafety.isAllowedUpstreamUrl(it) }
            .distinct()
        val candidateHosts = candidateUrls
            .map { extractHostname(it) }
            .filter { it.isNotBlank() }
            .distinct()
        val isSingleRelay = candidateHosts.size <= 1 &&
                (BiliAcceleratorCore.isRelayUrl(session.primaryUrl) ||
                        candidateHosts.any { it.startsWith("proxy-tf-all-") })
        val isRelayFamily = candidateUrls.size > 1 && candidateUrls.all {
            BiliAcceleratorCore.isRelayUrl(it)
        }
        val useAuto = !isAudio && autoConcurrency
        val initialMemoryBound = (MAX_MEMORY_PREFETCH_BYTES / initialProbeChunkSize)
            .toInt()
            .coerceIn(2, MAX_PREFETCH_CHUNKS)
        var concurrencyCaps = calculateConcurrencyCaps(
            requestedConcurrency = concurrency,
            autoConcurrency = autoConcurrency,
            isAudio = isAudio,
            isSingleRelay = isSingleRelay,
            isRelayFamily = isRelayFamily,
            memoryBoundPrefetch = initialMemoryBound
        )
        val primaryHost = BiliAcceleratorCore.cleanHost(extractHostname(session.primaryUrl))

        val statsTracker = StreamStatsTracker(
            sessionId = session.sessionId,
            primaryHost = primaryHost,
            concurrency = concurrencyCaps.steady,
            burstConcurrency = concurrencyCaps.burst,
            proxyPort = proxyPort,
            isEpisode = session.isEpisode
        )
        val streamCallKey = activeCalls.newStreamCallKey(session.sessionId)

        try {
            val hasCachedMetadata = session.totalLength > 0
            val totalLength: Long
            val contentType: String
            val firstChunk: ByteArray?
            var firstChunkLatencyMs = 0L
            val currentOffset: Long
            val actualEnd: Long

            if (hasCachedMetadata && requestedStart > 0) {
                // P0: Seek 探针跳过优化！
                // 该 session 早已完成元数据初始化，用户拖动 Seek 时直接跳过 probeMedia 512KB 网络往返，0ms 秒级起播！
                totalLength = session.totalLength
                contentType = session.contentType ?: if (isAudio) "audio/mp4" else "video/mp4"
                firstChunk = null
                currentOffset = requestedStart
                actualEnd = when {
                    requestedEnd != null && requestedEnd > 0 -> minOf(requestedEnd, totalLength - 1)
                    totalLength > 0 -> totalLength - 1
                    else -> -1L
                }
            } else {
                // 首片探测 (使用 OkHttp 连接池与 HTTP/2 探针)
                val probeResult = probeMedia(candidateUrls, session, requestedStart, initialProbeChunkSize, streamCallKey)
                if (probeResult == null) {
                    val candidateHosts = candidateUrls.map { extractHostname(it) }.filter { it.isNotBlank() }.distinct()
                    Log.w(TAG, "BTR probe failed; session=${session.sessionId.take(6)} candidates=$candidateHosts")
                    session.armNativeDirectFallback()
                    io.github.jh_mmm.biliaccelerator.core.DebugEventBuffer.record(
                        "BTR",
                        "native-fallback-started session=${session.sessionId.take(6)} reason=probe candidates=${candidateHosts.size}"
                    )
                    when (pipeDirectFallback(session, requestedStart, requestedEnd, isHeadOnly, clientOut, streamCallKey)) {
                        BtrDirectPipeResult.COMPLETED -> {
                            io.github.jh_mmm.biliaccelerator.core.DebugEventBuffer.record(
                                "BTR",
                                "native-fallback-succeeded session=${session.sessionId.take(6)} reason=probe"
                            )
                            return
                        }
                        BtrDirectPipeResult.FAILED_AFTER_RESPONSE -> {
                            io.github.jh_mmm.biliaccelerator.core.DebugEventBuffer.record(
                                "BTR",
                                "native-fallback-failed session=${session.sessionId.take(6)} reason=probe responseStarted=true"
                            )
                            return
                        }
                        BtrDirectPipeResult.FAILED_BEFORE_RESPONSE -> {
                            io.github.jh_mmm.biliaccelerator.core.DebugEventBuffer.record(
                                "BTR",
                                "native-fallback-failed session=${session.sessionId.take(6)} reason=probe responseStarted=false"
                            )
                            sendHttpError(clientOut, 502, "Bad Gateway - Upstream CDN Unreachable")
                            return
                        }
                    }
                }

                if (probeResult.isNonRange) {
                    // P0: HTTP 200 非 Range 容错，直接全量流式透传，避免老视频 512KB 截断
                    pipeNonRangeStream(candidateUrls.firstOrNull() ?: session.primaryUrl, session, clientOut, statsTracker, streamCallKey)
                    return
                }

                totalLength = probeResult.totalLength
                contentType = probeResult.contentType ?: if (isAudio) "audio/mp4" else "video/mp4"
                if (totalLength > 0) {
                    session.totalLength = totalLength
                    session.contentType = contentType
                }

                firstChunk = probeResult.firstChunkData
                firstChunkLatencyMs = probeResult.probeLatencyMs
                actualEnd = when {
                    requestedEnd != null && requestedEnd > 0 -> minOf(requestedEnd, if (totalLength > 0) totalLength - 1 else requestedEnd)
                    totalLength > 0 -> totalLength - 1
                    else -> -1L
                }
                currentOffset = requestedStart + (firstChunk?.size ?: 0)
            }

            // 根据探针测得或已缓存的媒体总长度，自适应计算后续分片拉取的最佳 ChunkSize
            val activeChunkSize = calculateDynamicChunkSize(
                totalLength = totalLength,
                baseChunkSizeKb = chunkSizeKb,
                autoChunkSize = autoChunkSize,
                isAudio = isAudio
            )
            val activeMemoryBound = (MAX_MEMORY_PREFETCH_BYTES / activeChunkSize)
                .toInt()
                .coerceIn(2, MAX_PREFETCH_CHUNKS)
            concurrencyCaps = calculateConcurrencyCaps(
                requestedConcurrency = concurrency,
                autoConcurrency = autoConcurrency,
                isAudio = isAudio,
                isSingleRelay = isSingleRelay,
                isRelayFamily = isRelayFamily,
                memoryBoundPrefetch = activeMemoryBound
            )
            statsTracker.concurrency = concurrencyCaps.steady
            statsTracker.burstConcurrency = concurrencyCaps.burst

            // 2. 构造并发送 HTTP 206 Partial Content (或 200 OK) 响应头给播放器
            val isPartial = requestedStart > 0 || actualEnd > 0
            val statusLine = if (isPartial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n"
            val sb = StringBuilder(statusLine)
            sb.append("Content-Type: $contentType\r\n")
            sb.append("Accept-Ranges: bytes\r\n")
            // 明确标记 Connection: close，杜绝客户端由于伪 keep-alive 产生复用连接重置 (Connection reset) 异常
            sb.append("Connection: close\r\n")

            if (totalLength > 0 && actualEnd >= requestedStart) {
                val contentLength = actualEnd - requestedStart + 1
                sb.append("Content-Length: $contentLength\r\n")
                if (isPartial) {
                    sb.append("Content-Range: bytes $requestedStart-$actualEnd/$totalLength\r\n")
                }
            } else if (!isPartial && totalLength > 0) {
                sb.append("Content-Length: $totalLength\r\n")
            }
            sb.append("\r\n")

            clientOut.write(sb.toString().toByteArray(Charsets.UTF_8))
            clientOut.flush()

            if (isHeadOnly) return

            // 3. 写出首片探测数据（仅在未跳过探针且有首片时）
            if (firstChunk != null && firstChunk.isNotEmpty()) {
                val bytesToWrite = if (actualEnd > 0) {
                    val needed = (actualEnd - requestedStart + 1).toInt()
                    if (needed < firstChunk.size) firstChunk.copyOf(needed) else firstChunk
                } else firstChunk

                clientOut.write(bytesToWrite)
                clientOut.flush()
                statsTracker.recordChunk(primaryHost, bytesToWrite.size.toLong(), firstChunkLatencyMs)
            }

            // 计算剩余字节范围
            if (actualEnd <= 0 || currentOffset > actualEnd) {
                // 已全部写出或未知长度单片传输完成
                return
            }

            // 4. 并发切片任务切分与滑动窗口流水线
            val chunkTasks = mutableListOf<ChunkTask>()
            var offset = currentOffset
            var taskIdx = 0L
            while (offset <= actualEnd) {
                val end = minOf(offset + activeChunkSize - 1, actualEnd)
                chunkTasks.add(ChunkTask(taskIdx++, offset, end))
                offset = end + 1
            }

            val totalTasks = chunkTasks.size
            val taskMap = ConcurrentHashMap<Long, ChunkTask>()
            val completionLock = Object()
            val isClientAborted = AtomicBoolean(false)
            val deliveredIndex = AtomicLong(0L)

            // 调度分发协作者在独立轻量守护线程运行，绝不占死 workerPool 工作线程
            val dispatchFuture = globalDispatcherPool.submit {
                var dispatchIdx = 0
                while (dispatchIdx < totalTasks && !isClientAborted.get()) {
                    // 冲刺阶段使用 burst 并发填满缓冲；稳定播放后回落到 steady，
                    // 单 MCDN relay 会话固定更低并发，避免 8 路请求压同一个 relay。
                    val maxPrefetch = if (useAuto && deliveredIndex.get() < 4) {
                        concurrencyCaps.burst
                    } else {
                        concurrencyCaps.steady
                    }

                    while (dispatchIdx - deliveredIndex.get() >= maxPrefetch && !isClientAborted.get()) {
                        try {
                            Thread.sleep(15)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                    if (isClientAborted.get()) break

                    val task = chunkTasks[dispatchIdx]
                    taskMap[task.index] = task

                    globalWorkerPool.execute {
                        if (!isClientAborted.get()) {
                            try {
                                fetchChunkWithFailover(task, candidateUrls, session, statsTracker, isClientAborted, streamCallKey)
                            } finally {
                                synchronized(completionLock) {
                                    completionLock.notifyAll()
                                }
                            }
                        }
                    }
                    dispatchIdx++
                }
            }

            try {
                // 严格按序 (0, 1, 2...) 写出分片到客户端 Socket
                var nextDeliverIndex = 0L
                while (nextDeliverIndex < totalTasks && !isClientAborted.get()) {
                    val currentTask = taskMap[nextDeliverIndex]
                    if (currentTask == null || (currentTask.data == null && !currentTask.isFailed)) {
                        synchronized(completionLock) {
                            val task = taskMap[nextDeliverIndex]
                            if (!isClientAborted.get() && (task == null || (task.data == null && !task.isFailed))) {
                                completionLock.wait(400)
                            }
                        }
                        continue
                    }

                    // P0: 杜绝死等！若分片彻底拉取失败，立即中断交付并触发播放器原生自愈/切集
                    if (currentTask.isFailed || currentTask.data == null) {
                        Log.e(TAG, "Chunk task #$nextDeliverIndex failed (${currentTask.errorMessage}). Aborting stream delivery to trigger player recovery.")
                        session.armNativeDirectFallback()
                        io.github.jh_mmm.biliaccelerator.core.DebugEventBuffer.record(
                            "BTR",
                            "stream-failed session=${session.sessionId.take(6)} reason=chunk task=$nextDeliverIndex"
                        )
                        io.github.jh_mmm.biliaccelerator.core.DebugEventBuffer.record(
                            "BTR",
                            "native-fallback-armed session=${session.sessionId.take(6)} reason=chunk"
                        )
                        isClientAborted.set(true)
                        break
                    }

                    // 写出分片
                    try {
                        val data = currentTask.data
                        if (data != null) {
                            clientOut.write(data)
                            clientOut.flush()
                        }
                        // 及时置空，释放堆内存并同步已交付游标
                        currentTask.data = null
                        taskMap.remove(nextDeliverIndex)
                        nextDeliverIndex++
                        deliveredIndex.set(nextDeliverIndex)
                    } catch (e: SocketException) {
                        // 客户端断开连接 (例如用户拖动进度条 Seek 或切集)
                        Log.i(TAG, "Client disconnected (Seek or stop). Session=${session.sessionId.take(6)}…")
                        isClientAborted.set(true)
                        break
                    }
                }
            } finally {
                isClientAborted.set(true)
                dispatchFuture.cancel(true)
                taskMap.clear()
            }
        } finally {
            activeCalls.cancelStreamCalls(streamCallKey)
            statsTracker.finishAndReport()
        }
    }

    private data class ProbeResult(
        val totalLength: Long,
        val contentLength: Long,
        val contentType: String?,
        val firstChunkData: ByteArray?,
        val servingHost: String,
        val probeLatencyMs: Long,
        val isNonRange: Boolean = false
    )

    private fun pipeNonRangeStream(
        url: String,
        session: BtrMediaSession,
        clientOut: OutputStream,
        statsTracker: StreamStatsTracker,
        streamCallKey: String
    ) {
        val host = extractHostname(url)
        val startTime = System.currentTimeMillis()
        val ua = session.userAgent ?: DEFAULT_USER_AGENT
        val okClient = if (session.isAudio) BtrHttpClient.audioClient else BtrHttpClient.client
        val request = Request.Builder()
            .url(url)
            .header("Referer", session.referer)
            .header("User-Agent", ua)
            .header("Connection", "close")
            .build()

        try {
            executeWithTracking(streamCallKey, session.sessionId, okClient.newCall(request)) { response ->
                val code = response.code
                val cl = response.body?.contentLength() ?: -1L
                val ct = response.header("Content-Type") ?: if (session.isAudio) "audio/mp4" else "video/mp4"

                val sb = StringBuilder("HTTP/1.1 $code OK\r\n")
                sb.append("Content-Type: $ct\r\n")
                if (cl > 0) sb.append("Content-Length: $cl\r\n")
                sb.append("Connection: close\r\n\r\n")
                clientOut.write(sb.toString().toByteArray(Charsets.UTF_8))
                clientOut.flush()

                var totalBytes = 0L
                response.body?.byteStream()?.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        clientOut.write(buffer, 0, read)
                        totalBytes += read
                    }
                }
                clientOut.flush()
                statsTracker.recordChunk(host, totalBytes, System.currentTimeMillis() - startTime)
                markHostHealthy(host)
            }
        } catch (_: Exception) {}
    }

    /**
     * Strict direct fallback used before BTR has written any response bytes.
     * It preserves the player's Range and only accepts an official HTTPS URL.
     * Once a fallback happens, the same session is marked for direct mode so
     * subsequent retries do not re-enter a failing BTR candidate set.
     */
    internal fun pipeDirectFallback(
        session: BtrMediaSession,
        requestedStart: Long,
        requestedEnd: Long?,
        isHeadOnly: Boolean,
        clientOut: OutputStream,
        streamCallKey: String? = null
    ): BtrDirectPipeResult {
        if (!BtrHttpSafety.isAllowedUpstreamUrl(session.primaryUrl)) {
            return BtrDirectPipeResult.FAILED_BEFORE_RESPONSE
        }

        val callKey = streamCallKey ?: activeCalls.newStreamCallKey(session.sessionId)
        var headersWritten = false
        return try {
            val ua = session.userAgent ?: DEFAULT_USER_AGENT
            val rangeValue = if (requestedEnd != null) {
                "bytes=$requestedStart-$requestedEnd"
            } else {
                "bytes=$requestedStart-"
            }
            val request = Request.Builder()
                .url(session.primaryUrl)
                .header("Range", rangeValue)
                .header("Referer", session.referer)
                .header("User-Agent", ua)
                .header("Accept-Encoding", "identity")
                .header("Connection", "close")
                .build()

            executeWithTracking(callKey, session.sessionId, BtrHttpClient.fallbackClient.newCall(request)) { response ->
                val code = response.code
                if (code != 200 && code != 206) {
                    return@executeWithTracking BtrDirectPipeResult.FAILED_BEFORE_RESPONSE
                }

                val contentRange = response.header("Content-Range")
                var expectedBodyLength = response.body?.contentLength() ?: -1L
                if (code == 206) {
                    val parsedRange = BtrHttpSafety.parseContentRange(contentRange)
                        ?: return@executeWithTracking BtrDirectPipeResult.FAILED_BEFORE_RESPONSE
                    if (parsedRange.start != requestedStart) {
                        return@executeWithTracking BtrDirectPipeResult.FAILED_BEFORE_RESPONSE
                    }
                    if (requestedEnd != null && parsedRange.end > requestedEnd) {
                        return@executeWithTracking BtrDirectPipeResult.FAILED_BEFORE_RESPONSE
                    }
                    expectedBodyLength = parsedRange.end - parsedRange.start + 1
                } else if (requestedStart != 0L || requestedEnd != null) {
                    // A 200 response to a Range request would send the full file
                    // and make the local response contract incorrect.
                    return@executeWithTracking BtrDirectPipeResult.FAILED_BEFORE_RESPONSE
                }

                val contentType = response.header("Content-Type") ?: "application/octet-stream"
                val reason = response.message.ifBlank {
                    if (code == 206) "Partial Content" else "OK"
                }
                val headerBuilder = StringBuilder()
                headerBuilder.append("HTTP/1.1 $code $reason\r\n")
                headerBuilder.append("Content-Type: $contentType\r\n")
                headerBuilder.append("Accept-Ranges: bytes\r\n")
                if (contentRange != null) {
                    headerBuilder.append("Content-Range: $contentRange\r\n")
                }
                if (expectedBodyLength >= 0L) {
                    headerBuilder.append("Content-Length: $expectedBodyLength\r\n")
                }
                headerBuilder.append("Connection: close\r\n\r\n")
                headersWritten = true
                clientOut.write(headerBuilder.toString().toByteArray(Charsets.US_ASCII))
                clientOut.flush()

                if (isHeadOnly) {
                    return@executeWithTracking BtrDirectPipeResult.COMPLETED
                }

                var totalBytes = 0L
                response.body?.byteStream()?.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        clientOut.write(buffer, 0, read)
                        totalBytes += read
                    }
                }
                clientOut.flush()
                if (expectedBodyLength >= 0L && totalBytes != expectedBodyLength) {
                    BtrDirectPipeResult.FAILED_AFTER_RESPONSE
                } else {
                    BtrDirectPipeResult.COMPLETED
                }
            }
        } catch (_: Throwable) {
            if (headersWritten) {
                BtrDirectPipeResult.FAILED_AFTER_RESPONSE
            } else {
                BtrDirectPipeResult.FAILED_BEFORE_RESPONSE
            }
        } finally {
            if (streamCallKey == null) {
                activeCalls.cancelStreamCalls(callKey)
            }
        }
    }

    private fun probeMedia(
        urls: List<String>,
        session: BtrMediaSession,
        startOffset: Long,
        chunkSize: Long,
        streamCallKey: String
    ): ProbeResult? {
        val sortedUrls = sortUrlsByHealth(urls)
        val candidateCount = urls.map { extractHostname(it) }.filter { it.isNotBlank() }.distinct().size
        val ua = session.userAgent ?: DEFAULT_USER_AGENT
        val primaryHost = extractHostname(session.primaryUrl)
        val okClient = if (session.isAudio) BtrHttpClient.audioClient else BtrHttpClient.probeClient
        for (url in sortedUrls) {
            if (!BtrHttpSafety.isAllowedUpstreamUrl(url)) continue
            val host = extractHostname(url)
            val startTime = System.currentTimeMillis()
            val endOffset = startOffset + chunkSize - 1
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=$startOffset-$endOffset")
                .header("Referer", session.referer)
                .header("User-Agent", ua)
                .header("Connection", "keep-alive")
                .build()

            try {
                executeWithTracking(streamCallKey, session.sessionId, okClient.newCall(request)) { response ->
                    val code = response.code
                    if (code == 200 || code == 206) {
                        val contentRange = response.header("Content-Range")
                        val isNonRange = code == 200 && contentRange == null
                        val contentLength = response.body?.contentLength() ?: -1L
                        val contentType = response.header("Content-Type")?.takeIf { it.isNotBlank() }

                        if (isNonRange) {
                            val latency = System.currentTimeMillis() - startTime
                            markHostHealthy(host)
                            return ProbeResult(
                                totalLength = contentLength,
                                contentLength = contentLength,
                                contentType = contentType,
                                firstChunkData = ByteArray(0),
                                servingHost = host,
                                probeLatencyMs = latency,
                                isNonRange = true
                            )
                        }

                        val range = BtrHttpSafety.parseContentRange(contentRange)
                        if (range == null ||
                            range.start != startOffset ||
                            range.end < startOffset ||
                            range.end > endOffset
                        ) {
                            rejectRange(host, primaryHost, session, "探测响应 Content-Range 不匹配", candidateCount)
                            return@executeWithTracking
                        }

                        val expectedBytes = range.end - range.start + 1
                        if (expectedBytes <= 0 || expectedBytes > BtrHttpSafety.MAX_UPSTREAM_BODY_BYTES) {
                            rejectRange(host, primaryHost, session, "探测响应分片大小异常", candidateCount)
                            return@executeWithTracking
                        }

                        val data = BtrHttpSafety.readBodyLimited(response.body, expectedBytes.toInt())
                        if (data == null || data.size.toLong() != expectedBytes) {
                            rejectRange(host, primaryHost, session, "探测响应正文被截断或超出上限", candidateCount)
                            return@executeWithTracking
                        }

                        val latency = System.currentTimeMillis() - startTime
                        markHostHealthy(host)
                        return ProbeResult(
                            totalLength = range.total ?: -1L,
                            contentLength = contentLength,
                            contentType = contentType,
                            firstChunkData = data,
                            servingHost = host,
                            probeLatencyMs = latency,
                            isNonRange = false
                        )
                    } else if (code == 403 || code >= 500) {
                        penalizeHost(
                            host = host,
                            baseDurationMs = penaltyForHttpStatus(code),
                            markRelayHealth = httpStatusShouldMarkRelayHealth(code),
                            adaptive = code >= 500
                        )
                        if (host == primaryHost) {
                            reportFailover(host, "HTTP $code 错误", session.sessionId, candidateCount)
                        }
                    }
                }
            } catch (e: Exception) {
                val message = e.message ?: "连接超时"
                penalizeHost(host, penaltyForFailureMessage(message))
                if (host == primaryHost) {
                    reportFailover(host, message, session.sessionId, candidateCount)
                }
            }
        }
        return null
    }

    private fun fetchChunkWithFailover(
        task: ChunkTask,
        urls: List<String>,
        session: BtrMediaSession,
        statsTracker: StreamStatsTracker,
        isClientAborted: AtomicBoolean,
        streamCallKey: String
    ) {
        val sortedUrls = sortUrlsByHealth(urls)
        val candidateCount = urls.map { extractHostname(it) }.filter { it.isNotBlank() }.distinct().size
        val healthyUrls = sortedUrls.filterNot { isPenalized(extractHostname(it)) }
        val tryUrls = if (healthyUrls.isNotEmpty()) {
            val startIndex = (task.index % healthyUrls.size).toInt()
            healthyUrls.subList(startIndex, healthyUrls.size) + healthyUrls.subList(0, startIndex)
        } else {
            sortedUrls
        }

        val ua = session.userAgent ?: DEFAULT_USER_AGENT

        for (url in tryUrls) {
            if (!BtrHttpSafety.isAllowedUpstreamUrl(url)) continue
            val host = extractHostname(url)
            if (isPenalized(host)) continue

            val startTime = System.currentTimeMillis()
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=${task.startByte}-${task.endByte}")
                .header("Referer", session.referer)
                .header("User-Agent", ua)
                .header("Connection", "keep-alive")
                .build()

            val okClient = if (session.isAudio) BtrHttpClient.audioClient else BtrHttpClient.client
            try {
                executeWithTracking(streamCallKey, session.sessionId, okClient.newCall(request)) { response ->
                    val code = response.code
                    if (code == 200 || code == 206) {
                        val bytes = readChunkPayload(response, task.startByte, task.endByte)
                        if (bytes == null) {
                            penalizeHost(host, 10_000L, markRelayHealth = false, adaptive = false)
                            reportFailover(host, "分片 Range/长度校验失败", session.sessionId, candidateCount)
                            return@executeWithTracking
                        }
                        val latency = System.currentTimeMillis() - startTime
                        task.data = bytes
                        task.isSuccess = true
                        markHostHealthy(host)
                        statsTracker.recordChunk(host, bytes.size.toLong(), latency)
                        return
                    } else if (code == 403 || code >= 500) {
                        penalizeHost(
                            host = host,
                            baseDurationMs = penaltyForHttpStatus(code),
                            markRelayHealth = httpStatusShouldMarkRelayHealth(code),
                            adaptive = code >= 500
                        )
                        reportFailover(host, "HTTP $code 熔断", session.sessionId, candidateCount)
                    }
                }
            } catch (e: Exception) {
                if (!isClientAborted.get()) {
                    val message = e.message ?: "连接超时"
                    penalizeHost(host, penaltyForFailureMessage(message))
                    reportFailover(host, "分片拉取超时/异常: $message", session.sessionId, candidateCount)
                }
            }
        }

        if (!task.isSuccess && urls.isNotEmpty()) {
            tryDirectFetch(task, urls.first(), session, statsTracker, streamCallKey)
        }

        if (!task.isSuccess) {
            task.isFailed = true
            task.errorMessage = "All candidate CDNs failed for range ${task.startByte}-${task.endByte}"
            io.github.jh_mmm.biliaccelerator.core.DebugEventBuffer.record(
                "BTR",
                "chunk-failed session=${session.sessionId.take(6)} range=${task.startByte}-${task.endByte} candidates=$candidateCount"
            )
        }
    }

    private fun tryDirectFetch(
        task: ChunkTask,
        url: String,
        session: BtrMediaSession,
        statsTracker: StreamStatsTracker,
        streamCallKey: String
    ) {
        if (!BtrHttpSafety.isAllowedUpstreamUrl(url)) return
        val host = extractHostname(url)
        val startTime = System.currentTimeMillis()
        val ua = session.userAgent ?: DEFAULT_USER_AGENT
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=${task.startByte}-${task.endByte}")
            .header("Referer", session.referer)
            .header("User-Agent", ua)
            .header("Connection", "keep-alive")
            .build()

        val okClient = if (session.isAudio) BtrHttpClient.audioClient else BtrHttpClient.fallbackClient
        try {
            executeWithTracking(streamCallKey, session.sessionId, okClient.newCall(request)) { response ->
                val bytes = readChunkPayload(response, task.startByte, task.endByte) ?: return@executeWithTracking
                task.data = bytes
                task.isSuccess = true
                markHostHealthy(host)
                statsTracker.recordChunk(host, bytes.size.toLong(), System.currentTimeMillis() - startTime)
            }
        } catch (_: Throwable) {
        }
    }

    private fun readChunkPayload(response: Response, start: Long, end: Long): ByteArray? {
        val code = response.code
        if (code != 200 && code != 206) return null
        val expectedBytes = end - start + 1
        if (expectedBytes <= 0 || expectedBytes > BtrHttpSafety.MAX_UPSTREAM_BODY_BYTES) return null

        val contentRange = response.header("Content-Range")
        if (code == 200 && contentRange == null) {
            // Small files may legitimately ignore Range. Only accept that
            // fallback for a request starting at zero and never read past the
            // declared chunk size.
            val contentLength = response.body?.contentLength() ?: -1L
            if (start != 0L || contentLength <= 0L || contentLength > expectedBytes) return null
            val bytes = BtrHttpSafety.readBodyLimited(response.body, expectedBytes.toInt()) ?: return null
            return bytes.takeIf { it.size.toLong() == contentLength }
        }

        val range = BtrHttpSafety.parseContentRange(contentRange) ?: return null
        if (range.start != start || range.end != end) return null
        val rangeBytes = range.end - range.start + 1
        if (rangeBytes <= 0 || rangeBytes > BtrHttpSafety.MAX_UPSTREAM_BODY_BYTES) return null
        val bytes = BtrHttpSafety.readBodyLimited(response.body, rangeBytes.toInt()) ?: return null
        return bytes.takeIf { it.size.toLong() == rangeBytes }
    }

    private fun rejectRange(
        host: String,
        primaryHost: String,
        session: BtrMediaSession,
        message: String,
        candidateCount: Int
    ) {
        penalizeHost(host, 10_000L, markRelayHealth = false, adaptive = false)
        if (host == primaryHost) {
            reportFailover(host, message, session.sessionId, candidateCount)
        }
    }

    private fun sortUrlsByHealth(urls: List<String>): List<String> {
        val now = System.currentTimeMillis()
        return urls.sortedBy { url ->
            val host = extractHostname(url)
            val penaltyUntil = cdnPenalties[host] ?: 0L
            if (penaltyUntil > now) penaltyUntil - now else 0L
        }
    }

    private fun extractHostname(url: String): String {
        return BiliAcceleratorCore.parseUri(url)?.host
            ?: runCatching { java.net.URI(url).host }.getOrNull()
            ?: ""
    }

    private fun penalizeHost(
        host: String,
        baseDurationMs: Long,
        markRelayHealth: Boolean = true,
        adaptive: Boolean = true
    ) {
        if (host.isBlank()) return
        if (cdnPenalties.size >= MAX_PENALTY_ENTRIES) {
            val now = System.currentTimeMillis()
            val expiredHosts = cdnPenalties.entries
                .filter { it.value <= now }
                .map { it.key }
            cdnPenalties.entries.removeAll { it.value <= now }
            expiredHosts.forEach { cdnFailureCounts.remove(it) }
            if (cdnPenalties.size >= MAX_PENALTY_ENTRIES && !cdnPenalties.containsKey(host)) return
        }
        val failures = if (adaptive) {
            cdnFailureCounts.compute(host) { _, previous ->
                ((previous ?: 0) + 1).coerceAtMost(8)
            } ?: 1
        } else {
            1
        }
        val effectivePenalty = if (adaptive) {
            calculateAdaptivePenaltyMs(baseDurationMs, failures)
        } else {
            baseDurationMs.coerceAtLeast(1L)
        }
        cdnPenalties[host] = System.currentTimeMillis() + effectivePenalty
        if (markRelayHealth) {
            RelayHealthRegistry.markFailure(host, effectivePenalty)
        }
    }

    private fun markHostHealthy(host: String) {
        if (host.isBlank()) return
        cdnFailureCounts.remove(host)
        cdnPenalties.remove(host)
        RelayHealthRegistry.markSuccess(host)
    }

    private fun penaltyForHttpStatus(code: Int): Long {
        return if (code == 403) 10_000L else 30_000L
    }

    internal fun httpStatusShouldMarkRelayHealth(code: Int): Boolean = code >= 500

    internal fun penaltyForFailureMessage(message: String): Long {
        val lower = message.lowercase(Locale.ROOT)
        return when {
            lower.contains("unable to resolve host") ||
                lower.contains("no address associated") ||
                lower.contains("no address returned") -> 300_000L
            lower.contains("blocked unsafe upstream") -> 120_000L
            lower.contains("unexpected end") -> 30_000L
            lower.contains("timeout") || lower.contains("timed out") -> 30_000L
            else -> 30_000L
        }
    }

    private fun isPenalized(host: String): Boolean {
        val penalty = cdnPenalties[host] ?: return false
        return penalty > System.currentTimeMillis()
    }

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

    private fun sendHttpError(out: OutputStream, code: Int, message: String) {
        try {
            val resp = "HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            out.write(resp.toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (_: Throwable) {}
    }
}
