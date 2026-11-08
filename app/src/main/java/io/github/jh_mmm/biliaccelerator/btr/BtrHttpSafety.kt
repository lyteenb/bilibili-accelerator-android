package io.github.jh_mmm.biliaccelerator.btr

import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.ResponseBody
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress

internal data class ParsedContentRange(
    val start: Long,
    val end: Long,
    val total: Long?
)

internal data class BoundedHttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>
)

internal class HttpRequestTooLargeException(message: String) : Exception(message)

internal object BtrHttpSafety {
    const val MAX_UPSTREAM_BODY_BYTES = 2 * 1024 * 1024
    private val CONTENT_RANGE_REGEX = Regex("""^bytes\s+(\d+)-(\d+)/(\d+|\*)$""", RegexOption.IGNORE_CASE)
    private val IPV4_LITERAL = Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""")

    /**
     * Every BTR upstream request must be an HTTPS URL on an official
     * Bilibili media/CDN domain. Public custom hosts, HTTP URLs and literal
     * private addresses are intentionally not accepted for proxying.
     */
    fun upstreamRejectionReason(raw: String): String? {
        val url = raw.toHttpUrlOrNull() ?: return "invalid URL"
        if (url.scheme != "https") return "scheme must be https"
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return "embedded credentials are not allowed"
        val host = url.host.trim()
        if (host.isEmpty()) return "empty host"
        if (!BiliAcceleratorCore.isOfficialBiliHost(host)) return "host $host is not an official Bilibili CDN"

        val literal = host.removePrefix("[").removeSuffix("]")
        if (IPV4_LITERAL.matches(literal) || literal.contains(':')) {
            val address = runCatching { InetAddress.getByName(literal) }.getOrNull()
                ?: return "invalid literal address $host"
            if (!PublicAddressPolicy.isPublicAddress(address)) {
                return "literal host $host is not public"
            }
        }
        return null
    }

    fun isAllowedUpstreamUrl(raw: String): Boolean {
        return upstreamRejectionReason(raw) == null
    }

    /**
     * Read at most [maxBytes]. Returning null means the peer attempted to send
     * more than the caller declared safe; callers should fail over rather than
     * allocating an unbounded byte array.
     */
    fun readBodyLimited(body: ResponseBody?, maxBytes: Int): ByteArray? {
        if (body == null) return ByteArray(0)
        if (maxBytes < 0 || maxBytes > MAX_UPSTREAM_BODY_BYTES) return null

        val hardLimit = maxBytes + 1
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(32 * 1024)
        var total = 0

        body.byteStream().use { input ->
            while (total < hardLimit) {
                val wanted = minOf(buffer.size, hardLimit - total)
                val read = input.read(buffer, 0, wanted)
                if (read == -1) break
                output.write(buffer, 0, read)
                total += read
            }
        }

        return if (total > maxBytes) null else output.toByteArray()
    }

    fun parseContentRange(value: String?): ParsedContentRange? {
        val match = value?.trim()?.let { CONTENT_RANGE_REGEX.matchEntire(it) } ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
        if (start < 0 || end < start) return null
        if (total != null && (total <= end || total <= 0L)) return null
        return ParsedContentRange(start = start, end = end, total = total)
    }
}

/**
 * Strict, bounded HTTP/1.1 request parser for the loopback proxy.
 *
 * java.io.BufferedReader.readLine() has no size cap; a local process
 * could otherwise force the Bilibili process to allocate arbitrarily large
 * header strings. This parser rejects oversized requests before reading them
 * into memory.
 */
internal object BoundedHttpRequestParser {
    const val MAX_REQUEST_LINE_BYTES = 8 * 1024
    const val MAX_HEADER_LINE_BYTES = 8 * 1024
    const val MAX_TOTAL_HEADER_BYTES = 32 * 1024
    const val MAX_HEADERS = 64

    fun parse(input: InputStream): BoundedHttpRequest? {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input)
        val requestLine = readLineLimited(buffered, MAX_REQUEST_LINE_BYTES) ?: return null
        var totalHeaderBytes = requestLine.length + 2
        if (totalHeaderBytes > MAX_TOTAL_HEADER_BYTES) {
            throw HttpRequestTooLargeException("Request line exceeds header limit")
        }

        val parts = requestLine.split(' ', limit = 3)
        if (parts.size < 2) throw IllegalArgumentException("Malformed request line")
        val method = parts[0].uppercase()
        if (method != "GET" && method != "HEAD") {
            throw IllegalArgumentException("Unsupported method: $method")
        }
        val path = parts[1]
        if (!path.startsWith('/')) throw IllegalArgumentException("Invalid request path")

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLineLimited(buffered, MAX_HEADER_LINE_BYTES)
                ?: throw IllegalArgumentException("Unexpected EOF in headers")
            if (line.isEmpty()) break

            totalHeaderBytes += line.length + 2
            if (totalHeaderBytes > MAX_TOTAL_HEADER_BYTES) {
                throw HttpRequestTooLargeException("Headers exceed total limit")
            }
            if (headers.size >= MAX_HEADERS) {
                throw HttpRequestTooLargeException("Too many headers")
            }

            val colon = line.indexOf(':')
            if (colon <= 0) throw IllegalArgumentException("Malformed header")
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            if (!isValidHeaderName(name)) {
                throw IllegalArgumentException("Invalid header")
            }
            if (value.any { it != '\t' && it.isISOControl() }) {
                throw IllegalArgumentException("Control character in header")
            }
            headers[name] = value
        }

        return BoundedHttpRequest(method = method, path = path, headers = headers)
    }

    private fun readLineLimited(input: InputStream, maxBytes: Int): String? {
        val output = ByteArrayOutputStream(minOf(maxBytes, 256))
        var sawAny = false
        while (true) {
            val value = input.read()
            if (value == -1) {
                if (!sawAny) return null
                break
            }
            sawAny = true
            if (value == '\n'.code) break
            if (value == '\r'.code) continue
            if (output.size() >= maxBytes) {
                throw HttpRequestTooLargeException("HTTP line exceeds $maxBytes bytes")
            }
            output.write(value)
        }
        return String(output.toByteArray(), Charsets.US_ASCII)
    }

    private fun isValidHeaderName(value: String): Boolean {
        if (value.isEmpty()) return false
        return value.all { it.isLetterOrDigit() || it in "!#$%&'*+-.^_`|~" }
    }
}
