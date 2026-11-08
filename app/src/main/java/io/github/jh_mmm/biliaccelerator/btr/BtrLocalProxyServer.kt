package io.github.jh_mmm.biliaccelerator.btr

import android.util.Log
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore

object BtrLocalProxyServer {

    private const val TAG = "BiliAccelerator-Proxy"
    private const val MAX_CONCURRENT_CLIENTS = 16
    private const val SOCKET_TIMEOUT_MS = 15_000
    private const val MAX_SESSION_ID_LENGTH = 64
    private const val MAX_USER_AGENT_LENGTH = 1024
    private const val MAX_REFERER_LENGTH = 2048

    @Volatile
    var port: Int = -1
        private set

    val isStarted: Boolean
        get() = port > 0 && isRunning

    @Volatile
    private var isRunning = false
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var clientThreadPool: ExecutorService? = null
    @Volatile private var configuredConcurrency: Int = 6
    @Volatile private var configuredChunkSizeKb: Int = 512
    @Volatile private var configuredAutoConcurrency: Boolean = true
    @Volatile private var configuredAutoChunkSize: Boolean = true

    @Synchronized
    fun start(concurrency: Int = 6, chunkSizeKb: Int = 512, autoConcurrency: Boolean = true, autoChunkSize: Boolean = true) {
        if (isRunning && port > 0) {
            updateParameters(concurrency, chunkSizeKb, autoConcurrency, autoChunkSize)
            return
        }

        updateParameters(concurrency, chunkSizeKb, autoConcurrency, autoChunkSize)
        clientThreadPool?.shutdownNow()
        clientThreadPool = Executors.newFixedThreadPool(MAX_CONCURRENT_CLIENTS) { r ->
            Thread(r, "BTR-ProxyWorker").apply { isDaemon = true }
        }
        val slots = Semaphore(MAX_CONCURRENT_CLIENTS)

        try {
            // Bind to loopback only. Every downstream client still needs the
            // random session capability contained in the rewritten media URL.
            val ss = ServerSocket(0, MAX_CONCURRENT_CLIENTS, InetAddress.getByName("127.0.0.1"))
            serverSocket = ss
            port = ss.localPort
            isRunning = true
            Log.i(TAG, "BTR Local Proxy Server successfully started on 127.0.0.1:$port")

            val acceptThread = Thread({
                while (isRunning) {
                    try {
                        val client = ss.accept()
                        if (!slots.tryAcquire()) {
                            client.close()
                            continue
                        }
                        val executor = clientThreadPool
                        if (executor == null || executor.isShutdown) {
                            slots.release()
                            client.close()
                            continue
                        }
                        try {
                            executor.execute {
                                try {
                                    handleClientSocket(
                                        client = client,
                                        concurrency = configuredConcurrency,
                                        chunkSizeKb = configuredChunkSizeKb,
                                        autoConcurrency = configuredAutoConcurrency,
                                        autoChunkSize = configuredAutoChunkSize
                                    )
                                } finally {
                                    slots.release()
                                }
                            }
                        } catch (_: RejectedExecutionException) {
                            slots.release()
                            client.close()
                        }
                    } catch (e: Exception) {
                        if (!isRunning) break
                    }
                }
            }, "BTR-ProxyAcceptor").apply { isDaemon = true }

            acceptThread.start()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start BTR local loopback proxy: ${t.message}", t)
            try {
                serverSocket?.close()
            } catch (_: Throwable) {}
            serverSocket = null
            port = -1
            isRunning = false
            clientThreadPool?.shutdownNow()
            clientThreadPool = null
        }
    }

    fun updateParameters(concurrency: Int, chunkSizeKb: Int, autoConcurrency: Boolean, autoChunkSize: Boolean) {
        configuredConcurrency = concurrency.coerceIn(2, 16)
        configuredChunkSizeKb = chunkSizeKb.coerceIn(128, 2048)
        configuredAutoConcurrency = autoConcurrency
        configuredAutoChunkSize = autoChunkSize
    }

    @Synchronized
    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Throwable) {}
        serverSocket = null
        port = -1
        clientThreadPool?.shutdownNow()
        clientThreadPool = null
        runCatching { BtrChunkEngine.cancelAllSessions() }
    }

    private fun handleClientSocket(
        client: Socket,
        concurrency: Int,
        chunkSizeKb: Int,
        autoConcurrency: Boolean,
        autoChunkSize: Boolean
    ) {
        client.use { socket ->
            val output = runCatching { socket.getOutputStream() }.getOrNull() ?: return@use
            try {
                socket.tcpNoDelay = true
                runCatching { socket.sendBufferSize = 256 * 1024 }
                runCatching { socket.receiveBufferSize = 256 * 1024 }
                socket.soTimeout = SOCKET_TIMEOUT_MS
                val request = BoundedHttpRequestParser.parse(socket.getInputStream()) ?: return
                if (!request.path.startsWith("/stream/")) {
                    sendError(output, 404, "Not Found")
                    return
                }

                val sessionId = request.path
                    .substringAfter("/stream/")
                    .substringBefore(".m4s")
                    .substringBefore("?")
                    .substringBefore("/")
                if (sessionId.length !in 1..MAX_SESSION_ID_LENGTH ||
                    sessionId.any { !it.isLetterOrDigit() }
                ) {
                    sendError(output, 400, "Bad Request")
                    return
                }

                val session = BtrSessionManager.get(sessionId)
                if (session == null) {
                    sendError(output, 404, "Not Found")
                    return
                }

                // Forward only bounded, single-line player metadata. OkHttp
                // rejects CR/LF header injection; these limits also prevent
                // local callers from growing session state without bound.
                request.headers["user-agent"]?.takeIf { it.isNotBlank() }?.let { userAgent ->
                    session.userAgent = userAgent.take(MAX_USER_AGENT_LENGTH)
                }
                request.headers["referer"]?.takeIf { it.isNotBlank() }?.let { referer ->
                    val trimmed = referer.trim()
                    if (trimmed.startsWith("https://") || trimmed.startsWith("http://")) {
                        session.referer = trimmed.take(MAX_REFERER_LENGTH)
                    }
                }

                val range = parseRangeHeader(request.headers["range"])
                if (range == null) {
                    sendError(output, 416, "Range Not Satisfiable")
                    return
                }

                BtrChunkEngine.pipeStream(
                    session = session,
                    requestedStart = range.first,
                    requestedEnd = range.second,
                    isHeadOnly = request.method == "HEAD",
                    clientOut = output,
                    concurrency = concurrency,
                    chunkSizeKb = chunkSizeKb,
                    autoConcurrency = autoConcurrency,
                    autoChunkSize = autoChunkSize,
                    proxyPort = port
                )
            } catch (e: HttpRequestTooLargeException) {
                sendError(output, 431, "Request Header Fields Too Large")
            } catch (_: IllegalArgumentException) {
                sendError(output, 400, "Bad Request")
            } catch (_: Exception) {
                // Player seek/stop is expected to close the socket at any time.
            }
        }
    }

    internal fun parseRangeHeader(rangeHeader: String?): Pair<Long, Long?>? {
        if (rangeHeader.isNullOrBlank()) return 0L to null
        if (!rangeHeader.startsWith("bytes=", ignoreCase = true)) return null
        val rangeSpec = rangeHeader.substringAfter('=', "").trim()
        if (rangeSpec.isEmpty() || rangeSpec.contains(',')) return null
        val parts = rangeSpec.split('-', limit = 2)
        if (parts.size != 2 || parts[0].isBlank()) return null

        val start = parts[0].toLongOrNull() ?: return null
        val end = if (parts[1].isBlank()) null else parts[1].toLongOrNull() ?: return null
        if (start < 0 || (end != null && end < start)) return null
        return start to end
    }

    private fun sendError(out: OutputStream, code: Int, message: String) {
        try {
            val resp = "HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            out.write(resp.toByteArray(Charsets.US_ASCII))
            out.flush()
        } catch (_: Throwable) {}
    }
}
