package io.github.jh_mmm.biliaccelerator.btr

import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.ResponseBody
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BtrProxyResponseTest {
    private val primary = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/10/20/test.m4s"
    private val backup = primary.replace("mirrorali", "mirrorcos")

    @Test
    fun normalSingleByteSuffixAndUnsatisfiableRangesHaveAccurateHeadersAndPayloads() {
        val content = ByteArray(32) { it.toByte() }
        val source: (Request) -> Response = { rangedResponse(it, content) }
        val session = session(primary, listOf(backup))

        val whole = run(session, responder = source)
        assertEquals("HTTP/1.1 200 OK", whole.status)
        assertFalse(whole.headers.contains("Content-Range:"))
        assertArrayEquals(content, whole.body)

        val first = run(session, "bytes=0-0", responder = source)
        assertEquals("HTTP/1.1 206 Partial Content", first.status)
        assertTrue(first.headers.contains("Content-Range: bytes 0-0/32"))
        assertTrue(first.headers.contains("Content-Length: 1"))
        assertArrayEquals(content.copyOf(1), first.body)

        val suffix = run(session, "bytes=-3", responder = source)
        assertTrue(suffix.headers.contains("Content-Range: bytes 29-31/32"))
        assertArrayEquals(content.copyOfRange(29, 32), suffix.body)

        val invalid = run(session, "bytes=32-", responder = { error("Known length must resolve before requesting upstream") })
        assertEquals("HTTP/1.1 416 Range Not Satisfiable", invalid.status)
        assertTrue(invalid.headers.contains("Content-Range: bytes */32"))
        assertEquals(0, invalid.body.size)
        assertNull(BtrRangeRequest.parse("bytes=-0"))
        assertNull(BtrRangeRequest.parse("bytes=999999999999999999999-"))
    }

    @Test
    fun nonRangeFallbackStreamsTheSuccessfulResponseAndHeadSendsNoBody() {
        val content = "backup contents".toByteArray()
        val requests = mutableListOf<Request>()
        val responder: (Request) -> Response = { request ->
            requests.add(request)
            if (request.url.toString() == primary) response(request, 404)
            else response(request, 200, content, contentLength = content.size)
        }

        val session = session(primary, listOf(backup))
        val response = run(session, "bytes=0-", responder = responder)
        assertEquals(listOf(primary, backup), requests.map { it.url.toString() })
        assertEquals("HTTP/1.1 200 OK", response.status)
        assertArrayEquals(content, response.body)

        requests.clear()
        val head = run(session, "bytes=0-0", head = true, responder = responder)
        assertEquals(listOf("HEAD", "HEAD"), requests.map { it.method })
        assertTrue(requests.all { it.header("Range") == null })
        assertTrue(head.headers.contains("Content-Length: " + content.size))
        assertEquals(0, head.body.size)
    }

    @Test
    fun headCanUseMetadataProbeIfUpstreamRejectsHead() {
        val bytes = ByteArray(42)
        val methods = mutableListOf<String>()
        val result = run(session(primary, maxNodes = 1), head = true, responder = {
            methods.add(it.method)
            if (it.method == "HEAD") response(it, 405) else rangedResponse(it, bytes)
        })
        assertEquals(listOf("HEAD", "GET"), methods)
        assertTrue(result.headers.contains("Content-Length: 42"))
        assertEquals(0, result.body.size)
    }

    @Test
    fun relayFailureUsesSafeUnwrappedOriginAndCooldownDoesNotRepeatTheRelay() {
        val relay = wrap("proxy-tf-all-ws.bilivideo.com", primary)
        val session = session(relay, original = relay, maxNodes = 1)
        val requests = mutableListOf<String>()
        val content = ByteArray(32) { it.toByte() }
        try {
            val responder: (Request) -> Response = {
                val url = it.url.toString()
                requests.add(url)
                if (url == relay) response(it, 403) else rangedResponse(it, content)
            }
            val initial = run(session, "bytes=0-0", responder = responder)
            assertEquals(listOf(relay, primary), requests)
            assertArrayEquals(content.copyOf(1), initial.body)
            requests.clear()
            assertEquals("HTTP/1.1 206 Partial Content", run(session, "bytes=1-1", responder = responder).status)
            assertEquals(listOf(primary), requests)
        } finally {
            session.clearDirectFallback()
        }
    }

    @Test
    fun failedDirectCooldownTriesTheRelayWithoutRepeatingTheDirectUrl() {
        val relay = wrap("proxy-tf-all-ws.bilivideo.com", primary)
        val session = session(relay, maxNodes = 1)
        val requests = mutableListOf<String>()
        try {
            session.armDirectFallback()
            val result = run(session, "bytes=0-0", responder = {
                requests.add(it.url.toString())
                response(it, 404)
            })
            assertEquals("HTTP/1.1 502 Bad Gateway", result.status)
            assertEquals(listOf(primary, relay), requests)
            assertFalse(session.isDirectFallbackActive())
        } finally {
            session.clearDirectFallback()
        }
    }

    @Test
    fun alternateRelayWithIdenticalWrappedMediaCanSupplyTheProbe() {
        val relay = wrap("proxy-tf-all-ws.bilivideo.com", primary)
        val requested = mutableListOf<String>()
        val content = "alternate relay".toByteArray()
        val result = run(session(relay), responder = {
            requested.add(it.url.host)
            if (it.url.host == "proxy-tf-all-ws.bilivideo.com") response(it, 404) else rangedResponse(it, content)
        })
        assertEquals(listOf("proxy-tf-all-ws.bilivideo.com", "proxy-tf-all-tx.bilivideo.com"), requested)
        assertArrayEquals(content, result.body)
    }

    @Test
    fun unboundRelayBackupsMatchInnerTrackAndAmbiguousSessionsAreSkipped() {
        val other = primary.replace("test.m4s", "other-30280.m4s")
        val one = session(wrap("proxy-tf-all-ws.bilivideo.com", primary), maxNodes = 2)
        val two = session(wrap("proxy-tf-all-ws.bilivideo.com", other), maxNodes = 2)
        val oneBackups = one.backupUrls
        val intended = wrap("proxy-tf-all-hw.bilivideo.com", other)
        BtrSessionManager.updateBackupsByCandidateUrls(listOf(intended))
        assertEquals(oneBackups, one.backupUrls)
        assertEquals(listOf(intended), two.backupUrls)
        val competing = session(two.primaryUrl, maxNodes = 2)
        val competingBackups = competing.backupUrls
        BtrSessionManager.updateBackupsByCandidateUrls(listOf(wrap("proxy-tf-all-tx.bilivideo.com", other)))
        assertEquals(listOf(intended), two.backupUrls)
        assertEquals(competingBackups, competing.backupUrls)
    }

    @Test
    fun authFailureDoesNotBlockAnotherMediaUsingSameHost() {
        val denied = session(primary, maxNodes = 1)
        val allowed = session(primary.replace("test.m4s", "allowed.m4s"), maxNodes = 1)
        assertEquals("HTTP/1.1 502 Bad Gateway", run(denied, responder = { response(it, 403) }).status)
        val content = "allowed".toByteArray()
        assertArrayEquals(content, run(allowed, responder = { rangedResponse(it, content) }).body)
    }

    @Test
    fun unsafeRedirectIsRejectedBeforeCreatingAnotherCall() {
        val calls = mutableListOf<String>()
        val result = run(session(primary, maxNodes = 1), responder = {
            calls.add(it.url.host)
            response(it, 302, location = "https://example.com/untrusted")
        })
        assertEquals("HTTP/1.1 502 Bad Gateway", result.status)
        assertEquals(listOf(primary.toHttpUrl().host), calls)
    }

    @Test
    fun synthesizedMirrorCannotSupplyChunksBeforeMatchingTheOriginalSample() {
        val data = ByteArray(900_000) { (it % 251).toByte() }
        val calls = ConcurrentLinkedQueue<Request>()
        val session = session(primary, maxNodes = 2)
        val result = run(session, responder = {
            calls.add(it)
            if (it.url.toString() == primary) rangedResponse(it, data)
            else rangedResponse(it, ByteArray(data.size))
        })
        assertArrayEquals(data, result.body)
        val mirrorCalls = calls.filter { it.url.toString() != primary }
        assertEquals("bytes=0-131071", calls.peek()?.header("Range"))
        assertEquals(1, mirrorCalls.size)
        assertEquals("bytes=0-65535", mirrorCalls.single().header("Range"))
    }

    @Test(timeout = 4000)
    fun saturatedHostDoesNotBlockAnAvailableBackup() {
        val bytes = ByteArray(65_537) { it.toByte() }
        val session = session(primary, listOf(backup))
        session.totalLength = bytes.size.toLong()
        session.routeMetrics.record(primary, 65_536, 100_000_000L)
        val requests = mutableListOf<String>()
        BtrCallRegistry().open("held-host-slots").use { holder ->
            val leases = List(4) {
                requireNotNull(BtrHttpClient.acquireHost(primary.toHttpUrl().host, false, holder, wait = false))
            }
            try {
                val result = run(session, "bytes=1-", responder = {
                    requests.add(it.url.toString())
                    rangedResponse(it, bytes)
                })
                assertArrayEquals(bytes.copyOfRange(1, bytes.size), result.body)
                assertEquals(listOf(backup), requests)
            } finally {
                leases.forEach { it.close() }
            }
        }
    }

    @Test
    fun measuredRoutesLeadAndUnknownRoutesGetLimitedTrials() {
        val metrics = BtrRouteMetrics()
        val now = 1_000_000_000L
        val candidates = listOf("slow", "unmeasured", "fast")
        metrics.record("slow", 65_536, 3_000_000_000L, now)
        metrics.record("fast", 65_536, 100_000_000L, now)
        assertEquals(listOf("fast", "unmeasured", "slow"), metrics.order(candidates, 0, now))
        assertEquals("unmeasured", metrics.order(candidates, 1, now).first())
        assertEquals("fast", metrics.order(candidates, 2, now).first())
        metrics.record("fast", 12, 1, now + 59_000_000_000L)
        assertEquals(candidates, metrics.order(candidates, 0, now + 60_000_000_000L))
    }

    @Test
    fun responseTimeoutOnOneMediaLeavesOtherMediaOnSameCdnAvailable() {
        val failed = session(primary, maxNodes = 1)
        val allowed = session(primary.replace("test.m4s", "same-host.m4s"), maxNodes = 1)
        val result = run(failed, responder = { throw SocketTimeoutException("read stalled") })
        assertEquals("HTTP/1.1 502 Bad Gateway", result.status)
        val content = "same CDN is healthy".toByteArray()
        assertArrayEquals(content, run(allowed, responder = { rangedResponse(it, content) }).body)
        failed.clearDirectFallback()
    }

    @Test
    fun interruptedChunkResumesOnlyItsMissingTailWithMatchingStrongTag() {
        val tag = "\"signed-representation\""
        val data = ByteArray(131_080) { (it % 241).toByte() }
        val requests = mutableListOf<Request>()
        val session = session(primary, listOf(backup))
        session.totalLength = data.size.toLong()
        val result = run(session, "bytes=4-131075", responder = { request ->
            requests.add(request)
            if (request.url.toString() == primary) interrupted(request, data, tag)
            else rangedResponse(request, data).newBuilder().header("ETag", tag).build()
        })
        assertArrayEquals(data.copyOfRange(4, 131_076), result.body)
        assertEquals(listOf("bytes=4-131075", "bytes=65540-131075"), requests.map { it.header("Range") })
        assertEquals(tag, requests.last().header("If-Range"))
    }

    @Test
    fun differentTagRestartsWholeChunkBeforeUsingAnAlternateResponse() {
        val data = ByteArray(131_080) { (it % 241).toByte() }
        val requests = mutableListOf<Request>()
        val session = session(primary, listOf(backup))
        session.totalLength = data.size.toLong()
        val result = run(session, "bytes=4-131075", responder = { request ->
            requests.add(request)
            if (request.url.toString() == primary) interrupted(request, data, "\"original\"")
            else rangedResponse(request, data).newBuilder().header("ETag", "\"other\"").build()
        })
        assertArrayEquals(data.copyOfRange(4, 131_076), result.body)
        assertEquals(listOf("bytes=4-131075", "bytes=65540-131075", "bytes=4-131075"), requests.map { it.header("Range") })
        assertNull(requests.last().header("If-Range"))
    }

    private fun interrupted(request: Request, content: ByteArray, tag: String): Response {
        val range = requireNotNull(BtrRangeRequest.parse(request.header("Range"))?.resolve(content.size.toLong()))
        val prefix = Buffer().write(content, range.start.toInt(), 65_536)
        val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (prefix.exhausted()) throw EOFException("fixture disconnected")
                return prefix.read(sink, byteCount)
            }
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() = Unit
        }.buffer()
        val truncated = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = range.length
            override fun source(): BufferedSource = source
        }
        return rangedResponse(request, content).newBuilder().header("ETag", tag).body(truncated).build()
    }

    @Test
    fun headerDeadlineIsAbsoluteAndDoesNotExpireAnIdleWriter() {
        Socket().use { socket ->
            val client = BtrDownstream(socket, timeoutNanos = 50L, clock = { 100L })
            client.expireIfOverdue(149L)
            assertFalse(socket.isClosed)
            client.expireIfOverdue(150L)
            assertTrue(socket.isClosed)
        }
        Socket().use { socket ->
            val client = BtrDownstream(socket, timeoutNanos = 50L, clock = { 100L })
            client.headersRead()
            client.expireIfOverdue(500L)
            assertFalse(socket.isClosed)
        }
    }

    @Test(timeout = 4000)
    fun blockedWriteIsReleasedWhenDownstreamDeadlineExpires() {
        val writing = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val socket = object : Socket() {
            override fun getOutputStream(): OutputStream = object : OutputStream() {
                override fun write(value: Int) {
                    writing.countDown()
                    closed.await()
                }
            }
            override fun close() {
                closed.countDown()
                super.close()
            }
        }
        BtrDownstream(socket, timeoutNanos = 50L, clock = { 100L }).use { client ->
            client.headersRead()
            Thread {
                try {
                    client.outputStream().write(1)
                } finally {
                    finished.countDown()
                }
            }.apply { isDaemon = true }.start()
            assertTrue(writing.await(1, TimeUnit.SECONDS))
            client.expireIfOverdue(149L)
            assertFalse(socket.isClosed)
            client.expireIfOverdue(150L)
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertTrue(socket.isClosed)
        }
    }

    private fun session(
        url: String,
        backups: List<String> = emptyList(),
        original: String = url,
        maxNodes: Int = 3
    ): BtrMediaSession {
        val id = BtrSessionManager.register(url, backups, config = AcceleratorConfig(btrCdnPoolSize = maxNodes), originalUrl = original)
        return requireNotNull(BtrSessionManager.get(id))
    }

    private fun wrap(host: String, url: String) =
        "https://$host/".toHttpUrl().newBuilder().addQueryParameter("url", url).build().toString()

    private data class LocalResponse(val headers: String, val body: ByteArray) {
        val status get() = headers.substringBefore("\r\n")
    }

    private fun run(
        session: BtrMediaSession,
        range: String? = null,
        head: Boolean = false,
        responder: (Request) -> Response
    ): LocalResponse {
        val output = ByteArrayOutputStream()
        BtrCallRegistry().open(session.sessionId).use { stream ->
            BtrChunkEngine.pipeStream(
                session, if (head) BtrRangeRequest.Full else requireNotNull(BtrRangeRequest.parse(range)),
                head, output, stream, 3, 128, false, false, 12345, null, session.referer
            ) { _, request -> FakeCall(request, responder) }
        }
        val bytes = output.toByteArray()
        val boundary = bytes.toString(Charsets.ISO_8859_1).indexOf("\r\n\r\n")
        assertTrue("Response must include complete headers", boundary >= 0)
        return LocalResponse(bytes.copyOfRange(0, boundary).toString(Charsets.US_ASCII), bytes.copyOfRange(boundary + 4, bytes.size))
    }

    private fun rangedResponse(request: Request, content: ByteArray): Response {
        val range = BtrRangeRequest.parse(request.header("Range"))?.resolve(content.size.toLong())
            ?: return response(request, 416, contentRange = "bytes */" + content.size)
        val bytes = content.copyOfRange(range.start.toInt(), range.end.toInt() + 1)
        return response(request, 206, bytes, "bytes ${range.start}-${range.end}/${content.size}", bytes.size)
    }

    private fun response(
        request: Request,
        code: Int,
        body: ByteArray = ByteArray(0),
        contentRange: String? = null,
        contentLength: Int? = null,
        location: String? = null
    ): Response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        .code(code).message("fixture").body(body.toResponseBody()).apply {
            if (contentRange != null) header("Content-Range", contentRange)
            if (contentLength != null) header("Content-Length", contentLength.toString())
            if (location != null) header("Location", location)
        }.build()

    private class FakeCall(
        private val request: Request,
        private val responder: (Request) -> Response
    ) : Call {
        @Volatile private var canceled = false
        override fun request(): Request = request
        override fun execute(): Response = responder(request)
        override fun enqueue(responseCallback: Callback) = error("Fixture calls are synchronous")
        override fun cancel() { canceled = true }
        override fun isCanceled(): Boolean = canceled
        override fun isExecuted(): Boolean = false
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = FakeCall(request, responder)
    }
}
