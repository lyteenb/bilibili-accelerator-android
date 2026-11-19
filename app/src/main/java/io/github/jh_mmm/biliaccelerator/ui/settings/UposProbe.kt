package io.github.jh_mmm.biliaccelerator.ui.settings

import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL
import java.util.concurrent.TimeUnit

internal data class UposProbeResponse(val statusCode: Int, val latencyMs: Long)

internal fun probeUposHost(
    host: String,
    openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
): UposProbeResponse {
    val start = System.nanoTime()
    val connection = openConnection(URL("https://$host/crossdomain.xml"))
    try {
        connection.requestMethod = "HEAD"
        connection.connectTimeout = 2500
        connection.readTimeout = 2500
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 BiliDroid/8.0.0 (bbcallen@gmail.com)")
        connection.setRequestProperty("Connection", "close")
        connection.connect()

        val status = connection.responseCode
        if (status !in 100..599) throw ProtocolException("Invalid HTTP status")
        val latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start).coerceAtLeast(1L)
        return UposProbeResponse(status, latencyMs)
    } finally {
        connection.disconnect()
    }
}
