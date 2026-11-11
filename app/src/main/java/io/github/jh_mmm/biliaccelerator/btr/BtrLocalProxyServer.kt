package io.github.jh_mmm.biliaccelerator.btr

import android.util.Log
import io.github.jh_mmm.biliaccelerator.core.DebugEventBuffer
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

object BtrLocalProxyServer {
    private const val TAG = "BiliAccelerator-Proxy"
    private const val MAX_CONCURRENT_CLIENTS = 16
    private const val SOCKET_TIMEOUT_MS = 15_000

    private data class Parameters(
        val concurrency: Int,
        val chunkSizeKb: Int,
        val autoConcurrency: Boolean,
        val autoChunkSize: Boolean
    )

    private class Proxy(val server: ServerSocket) {
        val clients = ConcurrentHashMap.newKeySet<BtrDownstream>()
        val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "BTR-ProxyWatchdog").apply { isDaemon = true }
        }
        val workers = Executors.newFixedThreadPool(MAX_CONCURRENT_CLIENTS) { task ->
            Thread(task, "BTR-ProxyWorker").apply { isDaemon = true }
        }
    }

    @Volatile private var current: Proxy? = null
    @Volatile private var parameters = Parameters(6, 512, true, true)
    private val activeCalls = BtrCallRegistry()

    val port: Int get() = current?.server?.localPort ?: -1
    val isStarted: Boolean get() = current != null

    @Synchronized
    fun start(concurrency: Int = 6, chunkSizeKb: Int = 512, autoConcurrency: Boolean = true, autoChunkSize: Boolean = true) {
        updateParameters(concurrency, chunkSizeKb, autoConcurrency, autoChunkSize)
        if (current != null) return
        try {
            val server = ServerSocket(0, MAX_CONCURRENT_CLIENTS, InetAddress.getByName("127.0.0.1"))
            val proxy = Proxy(server)
            current = proxy
            proxy.watchdog.scheduleWithFixedDelay({
                val now = System.nanoTime()
                proxy.clients.forEach { client -> runCatching { client.expireIfOverdue(now) } }
            }, 1L, 1L, TimeUnit.SECONDS)
            Thread({ acceptClients(proxy) }, "BTR-ProxyAcceptor").apply {
                isDaemon = true
                start()
            }
            DebugEventBuffer.record("BTR", "proxy_started port=${server.localPort}")
        } catch (error: Exception) {
            Log.e(TAG, "BTR proxy startup failed", error)
            stop()
        }
    }

    fun updateParameters(concurrency: Int, chunkSizeKb: Int, autoConcurrency: Boolean, autoChunkSize: Boolean) {
        parameters = Parameters(concurrency.coerceIn(2, 16), chunkSizeKb.coerceIn(128, 2048), autoConcurrency, autoChunkSize)
    }

    @Synchronized
    fun stop() {
        val proxy = current
        current = null
        if (proxy != null) {
            runCatching { proxy.server.close() }
            proxy.watchdog.shutdownNow()
            proxy.clients.forEach { runCatching { it.close() } }
            proxy.workers.shutdownNow()
            DebugEventBuffer.record("BTR", "proxy_stopped")
        }
        activeCalls.cancelAll()
    }

    internal fun activeClientCountForDiagnostics(): Int = current?.clients?.size ?: 0

    private fun acceptClients(proxy: Proxy) {
        while (!proxy.server.isClosed) {
            val client = try {
                proxy.server.accept()
            } catch (_: java.io.IOException) {
                synchronized(this) {
                    if (current === proxy) stop()
                }
                return
            }
            synchronized(this) {
                if (proxy.clients.size >= MAX_CONCURRENT_CLIENTS || current !== proxy) {
                    runCatching { client.close() }
                } else {
                    val downstream = BtrDownstream(client, TimeUnit.MILLISECONDS.toNanos(SOCKET_TIMEOUT_MS.toLong()))
                    proxy.clients.add(downstream)
                    try {
                        proxy.workers.execute {
                            try {
                                downstream.use { handleClientSocket(proxy, it, parameters) }
                            } finally {
                                proxy.clients.remove(downstream)
                            }
                        }
                    } catch (_: RejectedExecutionException) {
                        proxy.clients.remove(downstream)
                        runCatching { downstream.close() }
                    }
                }
            }
        }
    }

    @Synchronized
    private fun openStream(proxy: Proxy, socket: Socket, sessionId: String): BtrCallRegistry.Stream? =
        if (current === proxy && !socket.isClosed) activeCalls.open(sessionId) else null

    private fun handleClientSocket(proxy: Proxy, downstream: BtrDownstream, config: Parameters) {
        val socket = downstream.socket
        val output = runCatching { downstream.outputStream() }.getOrNull() ?: return
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val request = BoundedHttpRequestParser.parse(socket.getInputStream()) ?: return
            downstream.headersRead()
            val path = request.path.substringBefore('?')
            if (!path.startsWith("/stream/") || !path.endsWith(".m4s")) {
                sendError(output, 404, "Not Found")
                return
            }
            val sessionId = path.removePrefix("/stream/").removeSuffix(".m4s")
            if (sessionId.length !in 1..64 || sessionId.any { !it.isLetterOrDigit() }) {
                sendError(output, 400, "Bad Request")
                return
            }
            val session = BtrSessionManager.get(sessionId)
            if (session == null) {
                sendError(output, 404, "Not Found")
                return
            }
            val head = request.method == "HEAD"
            val range = if (head) BtrRangeRequest.Full else BtrRangeRequest.parse(request.headers["range"])
            if (range == null) {
                sendError(output, 416, "Range Not Satisfiable", session.totalLength)
                return
            }
            val stream = openStream(proxy, socket, sessionId) ?: return
            stream.use {
                val userAgent = request.headers["user-agent"]?.takeIf(::isUpstreamHeader)?.take(1024)
                val referer = request.headers["referer"]
                    ?.takeIf { isUpstreamHeader(it) && (it.startsWith("https://") || it.startsWith("http://")) }
                    ?.take(2048) ?: session.referer
                BtrChunkEngine.pipeStream(
                    session, range, head, output, stream,
                    config.concurrency, config.chunkSizeKb, config.autoConcurrency, config.autoChunkSize,
                    proxy.server.localPort, userAgent, referer
                )
            }
        } catch (_: HttpRequestTooLargeException) {
            sendError(output, 431, "Request Header Fields Too Large")
        } catch (_: IllegalArgumentException) {
            sendError(output, 400, "Bad Request")
        } catch (_: java.io.IOException) {
            // Seeking, stopping or disabling BTR closes the downstream socket.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun isUpstreamHeader(value: String): Boolean =
        value.isNotBlank() && value.all { it in ' '..'~' || it == '\t' }

    private fun sendError(out: OutputStream, code: Int, message: String, length: Long = -1L) {
        runCatching {
            val contentRange = if (code == 416 && length >= 0L) "Content-Range: bytes */$length\r\n" else ""
            out.write("HTTP/1.1 $code $message\r\n${contentRange}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
        }
    }
}
