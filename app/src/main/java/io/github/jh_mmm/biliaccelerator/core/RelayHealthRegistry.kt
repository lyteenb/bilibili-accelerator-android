package io.github.jh_mmm.biliaccelerator.core

data class RelaySelection(
    val relayHost: String,
    val usedFallback: Boolean,
    val allUnavailable: Boolean
)

/**
 * Process-local relay circuit breaker and playback-session preference cache.
 *
 * A relay fallback remains sticky for the same media session. The user's
 * preferred relay is only tried again when the media key changes or the user
 * changes the preferred relay, so a healthy fallback is not switched away
 * mid-playback.
 */
object RelayHealthRegistry {

    private val lock = Any()
    private val blockedUntil = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > 128
    }

    private data class SelectionKey(val preferred: String, val media: String)
    private val activeRelays = object : LinkedHashMap<SelectionKey, String?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SelectionKey, String?>?): Boolean =
            size > 64
    }

    fun selectRelay(
        preferredHost: String,
        mediaKey: String?,
        now: Long = System.currentTimeMillis()
    ): RelaySelection = synchronized(lock) {
        val preferred = normalizedRelayHost(preferredHost)
            ?: return@synchronized RelaySelection(
                relayHost = BiliAcceleratorCore.cleanHost(preferredHost).lowercase(),
                usedFallback = false,
                allUnavailable = false
            )
        val candidates = relayCandidates(preferred)
        val key = mediaKey?.trim()?.takeIf { it.isNotEmpty() }?.let { SelectionKey(preferred, it) }
        val sticky = key?.let(activeRelays::get)
        val selected = when {
            sticky != null && isHealthy(sticky, now) -> sticky
            key != null && activeRelays.containsKey(key) -> candidates.firstOrNull { it != preferred && isHealthy(it, now) }
                ?: preferred.takeIf { isHealthy(it, now) }
            else -> candidates.firstOrNull { isHealthy(it, now) }
        }
        if (key != null) activeRelays[key] = selected

        RelaySelection(
            relayHost = selected ?: preferred,
            usedFallback = selected != null && selected != preferred,
            allUnavailable = selected == null
        )
    }

    fun markFailure(relayHost: String, durationMs: Long, now: Long = System.currentTimeMillis()) {
        val host = normalizedRelayHost(relayHost) ?: return
        val until = now + durationMs.coerceAtLeast(1L)
        synchronized(lock) {
            blockedUntil.compute(host) { _, previous ->
                if (previous == null || previous < until) until else previous
            }
        }
    }

    fun markSuccess(relayHost: String) {
        val host = normalizedRelayHost(relayHost) ?: return
        synchronized(lock) {
            blockedUntil.remove(host)
        }
    }

    fun allRelaysUnavailable(
        preferredHost: String,
        now: Long = System.currentTimeMillis()
    ): Boolean = synchronized(lock) {
        val preferred = normalizedRelayHost(preferredHost) ?: return@synchronized false
        relayCandidates(preferred).none { isHealthy(it, now) }
    }

    fun clearSession() {
        synchronized(lock) {
            activeRelays.clear()
        }
    }

    fun resetForTesting() {
        synchronized(lock) {
            blockedUntil.clear()
            activeRelays.clear()
        }
    }

    private fun relayCandidates(preferred: String): List<String> {
        return (listOf(preferred) + BiliAcceleratorCore.OFFICIAL_RELAY_HOSTS)
            .mapNotNull { normalizedRelayHost(it) }
            .distinct()
    }

    private fun normalizedRelayHost(raw: String): String? {
        val host = BiliAcceleratorCore.cleanHost(raw).lowercase().substringBefore(':')
        if (!BiliAcceleratorCore.isOfficialBiliHost(host)) return null
        return host
    }

    private fun isHealthy(host: String, now: Long): Boolean {
        val until = blockedUntil[host] ?: return true
        if (until <= now) {
            blockedUntil.remove(host, until)
            return true
        }
        return false
    }
}
