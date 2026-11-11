package io.github.jh_mmm.biliaccelerator.btr

internal data class BtrResolvedRange(val start: Long, val end: Long) {
    val length: Long get() = end - start + 1L
}

internal class BtrRangeRequest private constructor(
    val start: Long?,
    val end: Long?,
    private val suffixLength: Long?
) {
    val isPartial: Boolean get() = start != null || suffixLength != null

    fun resolve(totalLength: Long): BtrResolvedRange? {
        if (totalLength <= 0L) return null
        val first = start ?: suffixLength?.let { (totalLength - it).coerceAtLeast(0L) } ?: 0L
        if (first >= totalLength) return null
        return BtrResolvedRange(first, minOf(end ?: Long.MAX_VALUE, totalLength - 1L))
    }

    fun headerValue(): String? = when {
        suffixLength != null -> "bytes=-$suffixLength"
        start != null -> "bytes=$start-${end ?: ""}"
        else -> null
    }

    companion object {
        val Full = BtrRangeRequest(null, null, null)
        private val singleRange = Regex("""bytes=(\d*)-(\d*)""", RegexOption.IGNORE_CASE)

        fun parse(header: String?): BtrRangeRequest? {
            if (header == null) return Full
            val match = singleRange.matchEntire(header.trim()) ?: return null
            val (first, last) = match.destructured
            if (first.isEmpty()) {
                val suffix = last.toLongOrNull()?.takeIf { it > 0L } ?: return null
                return BtrRangeRequest(null, null, suffix)
            }
            val start = first.toLongOrNull() ?: return null
            val end = if (last.isEmpty()) null else last.toLongOrNull() ?: return null
            if (end != null && end < start) return null
            return BtrRangeRequest(start, end, null)
        }
    }
}
