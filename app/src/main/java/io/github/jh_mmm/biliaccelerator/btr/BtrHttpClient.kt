package io.github.jh_mmm.biliaccelerator.btr

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

internal object BtrHttpClient {
    private val redirectCodes = setOf(301, 302, 303, 307, 308)
    private data class HostSlots(val permits: Semaphore, var users: Int = 0)
    private val hostSlots = mutableMapOf<Pair<String, Boolean>, HostSlots>()

    internal class HostLease(private val release: () -> Unit) : AutoCloseable {
        override fun close() = release()
    }

    val client: OkHttpClient = buildClient(32, connectMs = 3500, readMs = 6000)
        .newBuilder().callTimeout(12_000, TimeUnit.MILLISECONDS).build()
    val audioStreamClient: OkHttpClient = buildClient(8, connectMs = 3000, readMs = 5000)
    val audioClient: OkHttpClient = audioStreamClient.newBuilder()
        .callTimeout(10_000, TimeUnit.MILLISECONDS)
        .build()
    val probeClient: OkHttpClient = client.newBuilder()
        .connectTimeout(4000, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    val fallbackClient: OkHttpClient = client.newBuilder()
        .connectTimeout(5000, TimeUnit.MILLISECONDS)
        .readTimeout(8000, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private fun buildClient(maxIdle: Int, connectMs: Long, readMs: Long): OkHttpClient =
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(maxIdle, 5, TimeUnit.MINUTES))
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .dns(SafeDns)
            .connectTimeout(connectMs, TimeUnit.MILLISECONDS)
            .readTimeout(readMs, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

    fun acquireHost(
        host: String,
        isAudio: Boolean,
        stream: BtrCallRegistry.Stream,
        wait: Boolean
    ): HostLease? {
        val key = host.lowercase(Locale.ROOT) to isAudio
        val slots = synchronized(hostSlots) {
            hostSlots.getOrPut(key) { HostSlots(Semaphore(if (isAudio) 2 else 4, true)) }
                .also { it.users++ }
        }
        var acquired = false
        try {
            acquired = acquireSlot(slots.permits, stream, wait)
            if (!acquired) return null
            return HostLease {
                slots.permits.release()
                forgetHost(key, slots)
            }
        } finally {
            if (!acquired) forgetHost(key, slots)
        }
    }

    fun acquireSlot(slot: Semaphore, stream: BtrCallRegistry.Stream, wait: Boolean): Boolean {
        while (!stream.isClosed) {
            if (slot.tryAcquire(if (wait) 100L else 0L, TimeUnit.MILLISECONDS)) return true
            if (!wait) return false
        }
        throw IOException("Stream canceled")
    }

    private fun forgetHost(key: Pair<String, Boolean>, slots: HostSlots) {
        synchronized(hostSlots) {
            if (--slots.users == 0) hostSlots.remove(key)
        }
    }

    fun redirectRequest(request: Request, response: Response): Request? {
        if (response.code !in redirectCodes) return null
        val location = response.header("Location") ?: return null
        val next = request.url.resolve(location) ?: throw IOException("Invalid upstream redirect")
        if (!BtrHttpSafety.isAllowedUpstreamUrl(next.toString())) {
            throw IOException("Blocked unsafe upstream redirect")
        }
        return request.newBuilder().url(next).build()
    }
}
