package io.github.jh_mmm.biliaccelerator.btr

import io.github.jh_mmm.biliaccelerator.provider.RewriteResultDto
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress

class BtrHttpSafetyTest {

    @Test
    fun testContentRangeParsingAndValidation() {
        val valid = BtrHttpSafety.parseContentRange("bytes 1024-2047/8192")
        assertNotNull(valid)
        assertEquals(1024L, valid?.start ?: -1L)
        assertEquals(2047L, valid?.end ?: -1L)
        assertEquals(8192L, valid?.total ?: -1L)

        assertNotNull(BtrHttpSafety.parseContentRange("bytes 0-511/*"))
        assertNull(BtrHttpSafety.parseContentRange("bytes 20-10/100"))
        assertNull(BtrHttpSafety.parseContentRange("bytes 0-100/100"))
        assertNull(BtrHttpSafety.parseContentRange("items 0-1/10"))
    }

    @Test
    fun testBoundedBodyReadRejectsOversizePayload() {
        val exact = "12345".toResponseBody()
        assertEquals("12345", String(BtrHttpSafety.readBodyLimited(exact, 5)!!))

        val oversized = "123456".toResponseBody()
        assertNull(BtrHttpSafety.readBodyLimited(oversized, 5))
    }

    @Test
    fun testBoundedBodyReadRetainsPartialPayload() {
        val result = BtrHttpSafety.readBodyWithPartial("12345".toResponseBody(), 10)
        assertNotNull(result)
        assertEquals(5, result?.bytes?.size)
        assertFalse("A short body is a resumable prefix, not an oversize response", result?.tooLarge ?: true)
    }

    @Test
    fun testBoundedBodyReadFlagsOversizedPayload() {
        val result = BtrHttpSafety.readBodyWithPartial("123456".toResponseBody(), 5)
        assertNotNull(result)
        assertTrue("An oversize body must not be retained as a prefix", result?.tooLarge == true)
    }

    @Test
    fun testPublicAddressPolicyBlocksLocalAndPrivateRanges() {
        for (value in listOf("127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.1.1", "100.64.0.1")) {
            assertFalse(value, PublicAddressPolicy.isPublicAddress(InetAddress.getByName(value)))
        }
        assertFalse(PublicAddressPolicy.isPublicAddress(InetAddress.getByName("fd00::1")))
        assertTrue(PublicAddressPolicy.isPublicAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(PublicAddressPolicy.isPublicAddress(InetAddress.getByName("2001:4860:4860::8888")))
    }

    @Test
    fun testAllowedUpstreamUrlRequiresOfficialBiliAndHttps() {
        assertTrue(BtrHttpSafety.isAllowedUpstreamUrl("https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/1.m4s"))
        assertTrue(BtrHttpSafety.isAllowedUpstreamUrl("https://upos-hz-mirrorakam.akamaized.net/upgcxcode/1.m4s"))
        assertTrue(BtrHttpSafety.isAllowedUpstreamUrl("https://video-sea.bstarstatic.com/upgcxcode/1.m4s"))

        assertFalse(BtrHttpSafety.isAllowedUpstreamUrl("http://upos-sz-mirrorcos.bilivideo.com/upgcxcode/1.m4s"))
        assertFalse(BtrHttpSafety.isAllowedUpstreamUrl("https://example.com/upgcxcode/1.m4s"))
        assertFalse(BtrHttpSafety.isAllowedUpstreamUrl("http://112.34.56.78:8080/upgcxcode/1.m4s"))
        assertFalse(BtrHttpSafety.isAllowedUpstreamUrl("https://127.0.0.1:1234/stream/abc.m4s"))
        assertFalse(BtrHttpSafety.isAllowedUpstreamUrl("https://10.0.0.1/a.m4s"))
        assertFalse(BtrHttpSafety.isAllowedUpstreamUrl("file:///etc/passwd"))
    }

    @Test
    fun testUpstreamRejectionReasonsAreSpecificAndSafe() {
        assertNull(BtrHttpSafety.upstreamRejectionReason("https://upos-sz-mirrorcos.bilivideo.com/a.m4s"))
        assertEquals(
            "scheme must be https",
            BtrHttpSafety.upstreamRejectionReason("http://upos-sz-mirrorcos.bilivideo.com/a.m4s")
        )
        assertTrue(
            BtrHttpSafety.upstreamRejectionReason("https://example.com/a.m4s")
                ?.contains("not an official Bilibili CDN") == true
        )
        assertEquals(
            "embedded credentials are not allowed",
            BtrHttpSafety.upstreamRejectionReason("https://user:pass@upos-sz-mirrorcos.bilivideo.com/a.m4s")
        )
        assertEquals(
            "host 127.0.0.1 is not an official Bilibili CDN",
            BtrHttpSafety.upstreamRejectionReason("https://127.0.0.1/a.m4s")
        )
    }

    @Test
    fun testBoundedRequestParser() {
        val raw = "GET /stream/abc123.m4s HTTP/1.1\r\n" +
            "User-Agent: Test\r\n" +
            "Range: bytes=0-511\r\n\r\n"
        val request = BoundedHttpRequestParser.parse(
            ByteArrayInputStream(raw.toByteArray(Charsets.US_ASCII))
        )
        assertNotNull(request)
        assertEquals("GET", request?.method)
        assertEquals("/stream/abc123.m4s", request?.path)
        assertEquals("bytes=0-511", request?.headers?.get("range"))
    }

    @Test
    fun testBoundedRequestParserRejectsOversizedLine() {
        val raw = "GET /stream/abc.m4s HTTP/1.1\r\nX-Test: " + "x".repeat(9000) + "\r\n\r\n"
        assertThrows(HttpRequestTooLargeException::class.java) {
            BoundedHttpRequestParser.parse(ByteArrayInputStream(raw.toByteArray(Charsets.US_ASCII)))
        }
    }

    @Test
    fun testBoundedRequestParserRejectsTooManyHeaders() {
        val headers = (1..70).joinToString("\r\n") { "X-$it: value" }
        val raw = "GET /stream/abc.m4s HTTP/1.1\r\n$headers\r\n\r\n"
        assertThrows(HttpRequestTooLargeException::class.java) {
            BoundedHttpRequestParser.parse(ByteArrayInputStream(raw.toByteArray(Charsets.US_ASCII)))
        }
    }

    @Test
    fun testIpcDtoClampsUntrustedStatistics() {
        val result = RewriteResultDto(
            originalHost = "upos.example.com\nrest",
            targetHost = "upos.example.com",
            reason = "ok",
            btrChunks = -10,
            btrBytes = Long.MAX_VALUE,
            btrConcurrency = 999,
            btrCdnCount = -1,
            btrAvgLatencyMs = Long.MAX_VALUE
        ).toDomain()

        assertEquals("upos.example.comrest", result.originalHost)
        assertEquals(0, result.btrChunks)
        assertEquals(1L shl 40, result.btrBytes)
        assertEquals(64, result.btrConcurrency)
        assertEquals(0, result.btrCdnCount)
        assertEquals(600_000L, result.btrAvgLatencyMs)
    }
}
