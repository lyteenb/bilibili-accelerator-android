package io.github.jh_mmm.biliaccelerator.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.URL

class UposProbeTest {
    @Test
    fun reportsHttpErrorsAsResponsesWithLatency() {
        for (status in listOf(200, 302, 403, 404, 429, 503)) {
            val connection = TestConnection(status)
            val result = probeUposHost("upos.example.com") { url ->
                assertEquals("https://upos.example.com/crossdomain.xml", url.toExternalForm())
                connection
            }

            assertEquals(status, result.statusCode)
            assertTrue(result.latencyMs > 0L)
            assertEquals("HEAD", connection.requestMethod)
            assertFalse(connection.instanceFollowRedirects)
            assertTrue(connection.disconnected)
        }
    }

    @Test
    fun propagatesSocketTimeoutAndDisconnects() {
        val timeout = SocketTimeoutException("Read timed out")
        val connection = TestConnection(failure = timeout)

        assertSame(timeout, assertThrows(SocketTimeoutException::class.java) {
            probeUposHost("upos.example.com") { connection }
        })
        assertTrue(connection.connectTimeout > 0)
        assertTrue(connection.readTimeout > 0)
        assertTrue(connection.disconnected)
    }

    @Test
    fun rejectsMissingHttpStatusAndDisconnects() {
        val connection = TestConnection(status = -1)
        assertThrows(ProtocolException::class.java) {
            probeUposHost("upos.example.com") { connection }
        }
        assertTrue(connection.disconnected)
    }

    private class TestConnection(
        private val status: Int = 200,
        private val failure: SocketTimeoutException? = null
    ) : HttpURLConnection(URL("https://upos.example.com/")) {
        var disconnected = false
            private set

        override fun connect() = Unit

        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy() = false

        override fun getResponseCode(): Int {
            failure?.let { throw it }
            return status
        }
    }
}
