package io.github.jh_mmm.biliaccelerator.btr

/** Throughput observations belong to one media representation. */
internal class BtrRouteMetrics {
    private data class Measurement(val bytesPerSecond: Double, val measuredAt: Long)

    private val measurements = object : LinkedHashMap<String, Measurement>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Measurement>?): Boolean =
            size > 40
    }

    @Synchronized
    fun record(url: String, bytes: Int, elapsedNanos: Long, now: Long = System.nanoTime()) {
        if (bytes < 48 * 1024 || elapsedNanos <= 0L) return
        val measured = bytes * 1_000_000_000.0 / elapsedNanos
        val previous = measurements[url]?.takeIf { now - it.measuredAt < SAMPLE_TTL_NS }
        val blended = if (previous == null) measured else previous.bytesPerSecond * 0.7 + measured * 0.3
        measurements[url] = Measurement(blended, now)
    }

    @Synchronized
    fun order(urls: List<String>, pieceIndex: Long, now: Long = System.nanoTime()): List<String> {
        if (urls.size < 2) return urls
        val measured = urls.mapNotNull { url ->
            measurements[url]?.takeIf { now - it.measuredAt < SAMPLE_TTL_NS }?.let {
                url to it.bytesPerSecond
            }
        }.sortedByDescending { it.second }
        if (measured.isEmpty()) return rotate(urls, pieceIndex)

        val known = measured.map { it.first }.toSet()
        val unknown = urls.filterNot { it in known }
        val best = measured.first().second
        val comparable = measured.takeWhile { it.second >= best * 0.75 }
        val useful = measured.drop(comparable.size).takeWhile { it.second >= best / 12.0 }
        val slow = measured.drop(comparable.size + useful.size)
        val ranked = rotate(comparable.map { it.first }, pieceIndex) + useful.map { it.first }
        val trial = unknown.isNotEmpty() && pieceIndex % 8L == 1L
        val orderedUnknown = rotate(unknown, pieceIndex / 8L)
        return if (trial) {
            orderedUnknown.take(1) + ranked + orderedUnknown.drop(1) + slow.map { it.first }
        } else {
            ranked + orderedUnknown + slow.map { it.first }
        }
    }

    private fun rotate(urls: List<String>, index: Long): List<String> {
        if (urls.size < 2) return urls
        val offset = (index % urls.size).toInt()
        return urls.drop(offset) + urls.take(offset)
    }

    private companion object {
        const val SAMPLE_TTL_NS = 60_000_000_000L
    }
}
