package io.github.jh_mmm.biliaccelerator.btr

import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong

private const val DEFAULT_REFERER = "https://www.bilibili.com/"
private const val BANGUMI_REFERER = "https://www.bilibili.com/bangumi/play/"
internal const val NATIVE_DIRECT_FALLBACK_DURATION_MS = 45_000L

private fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L

data class BtrMediaSession(
    val sessionId: String,
    val primaryUrl: String,
    @Volatile var backupUrls: List<String>,
    @Volatile var userAgent: String?,
    @Volatile var referer: String = DEFAULT_REFERER,
    val isAudio: Boolean = false,
    val isEpisode: Boolean = false,
    val originalUrl: String = primaryUrl,
    internal val config: AcceleratorConfig? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastAccessed: AtomicLong = AtomicLong(System.currentTimeMillis()),
    @Volatile var totalLength: Long = -1L,
    @Volatile var contentType: String? = null
) {
    internal val safeFallbackUrl = BiliAcceleratorCore.safeDirectFallbackUrl(originalUrl)
    @Volatile internal var candidatePlan: BtrCandidatePlan? = null
    @Volatile internal var sample: BtrMediaSample? = null
    internal val verifiedCandidates = ConcurrentHashMap<String, BtrMediaSample>()
    internal val routeMetrics = BtrRouteMetrics()
    private val verificationSlots = Array(16) { Semaphore(1, true) }

    internal fun verificationSlot(url: String): Semaphore =
        verificationSlots[Math.floorMod(url.hashCode(), verificationSlots.size)]
    @Volatile private var fallbackUntil = 0L
    private val rejectedUrls = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 64
    }

    @Synchronized
    internal fun cacheSample(candidate: BtrMediaSample, type: String): BtrMediaSample {
        val previous = sample
        if (previous == null || previous.totalLength != candidate.totalLength ||
            previous.bytes.size < candidate.bytes.size ||
            (previous.start == candidate.start && previous.bytes.size == candidate.bytes.size &&
                !previous.bytes.contentEquals(candidate.bytes))
        ) {
            verifiedCandidates.clear()
            sample = candidate
        }
        contentType = type
        totalLength = candidate.totalLength
        return sample ?: candidate
    }

    @Synchronized
    internal fun rejectUrl(url: String) {
        rejectedUrls[url] = monotonicMillis() + 10_000L
    }

    @Synchronized
    internal fun isUrlRejected(url: String): Boolean {
        val until = rejectedUrls[url] ?: return false
        if (until > monotonicMillis()) return true
        rejectedUrls.remove(url)
        return false
    }

    @Synchronized
    internal fun armDirectFallback(now: Long = monotonicMillis()) {
        if (safeFallbackUrl == null) return
        fallbackUntil = maxOf(fallbackUntil, now + NATIVE_DIRECT_FALLBACK_DURATION_MS)
        BtrSessionManager.recordDirectFallback(originalUrl, fallbackUntil)
    }

    internal fun activeFallbackDeadline(now: Long = monotonicMillis()): Long? =
        fallbackUntil.takeIf { it != 0L && it > now }

    internal fun isDirectFallbackActive(now: Long = monotonicMillis()): Boolean =
        activeFallbackDeadline(now) != null

    @Synchronized
    internal fun clearDirectFallback(expected: Long = fallbackUntil) {
        if (expected == 0L || fallbackUntil != expected) return
        fallbackUntil = 0L
        BtrSessionManager.clearDirectFallback(originalUrl, expected)
    }

}

internal data class BtrCandidatePlan(
    val primaryUrl: String,
    val backupUrls: List<String>,
    val candidateHosts: List<String>,
    val isRelay: Boolean,
    val isDirectUpos: Boolean,
    val hasMultiSource: Boolean,
    val synthesizedUrls: Set<String>
)

object BtrSessionManager {

    private const val MAX_SESSIONS = 50
    private const val SESSION_TTL_MS = 30 * 60 * 1000L // 30 分钟无访问自动清理

    private val sessions = ConcurrentHashMap<String, BtrMediaSession>()
    private val builderSessionMap = Collections.synchronizedMap(WeakHashMap<Any, String>())

    private const val MAX_DIRECT_FALLBACKS = 128
    private data class FallbackKey(val host: String, val media: BiliAcceleratorCore.MediaIdentity)
    private val directFallbacks = object : LinkedHashMap<FallbackKey, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<FallbackKey, Long>?): Boolean =
            size > MAX_DIRECT_FALLBACKS
    }

    private fun fallbackKey(url: String): FallbackKey? {
        val direct = BiliAcceleratorCore.safeDirectFallbackUrl(url) ?: return null
        val identity = BiliAcceleratorCore.mediaIdentity(direct) ?: return null
        return FallbackKey(cleanHost(direct), identity)
    }

    @Synchronized
    internal fun recordDirectFallback(url: String, until: Long = monotonicMillis() + NATIVE_DIRECT_FALLBACK_DURATION_MS) {
        val key = fallbackKey(url) ?: return
        directFallbacks[key] = maxOf(directFallbacks[key] ?: until, until)
    }

    @Synchronized
    internal fun clearDirectFallback(url: String, expected: Long) {
        val key = fallbackKey(url) ?: return
        directFallbacks.remove(key, expected)
    }

    @Synchronized
    internal fun activeDirectFallback(url: String): String? {
        val key = fallbackKey(url) ?: return null
        val until = directFallbacks[key] ?: return null
        if (until > monotonicMillis()) return BiliAcceleratorCore.safeDirectFallbackUrl(url)
        directFallbacks.remove(key)
        return null
    }

    @Synchronized
    fun register(
        primaryUrl: String,
        backupUrls: List<String> = emptyList(),
        userAgent: String? = null,
        isAudio: Boolean = false,
        isEpisode: Boolean = false,
        config: AcceleratorConfig? = null,
        originalUrl: String = primaryUrl
    ): String {
        cleanupStaleSessions()
        val id = UUID.randomUUID().toString().replace("-", "")
        val plan = buildCandidatePlan(primaryUrl, backupUrls, config, isEpisode)
        val referer = if (isEpisode) BANGUMI_REFERER else DEFAULT_REFERER
        val session = BtrMediaSession(
            sessionId = id,
            primaryUrl = primaryUrl,
            backupUrls = plan.backupUrls,
            userAgent = userAgent,
            referer = referer,
            isAudio = isAudio,
            isEpisode = isEpisode,
            originalUrl = originalUrl,
            config = config
        )
        session.candidatePlan = plan
        sessions[id] = session
        io.github.jh_mmm.biliaccelerator.core.DebugEventBuffer.record(
            "BTR",
            "session_created session=${id.take(6)} active=${sessions.size}"
        )
        return id
    }

    fun activeSessionCountForDiagnostics(): Int = sessions.size

    /**
     * Direct UPOS streams are only accelerated when at least two trusted,
     * same-region candidates are available. A single host can still be a valid
     * native URL, but entering BTR would add a loopback hop without providing
     * multi-source acceleration and makes host-bound signatures prone to 403s.
     */
    fun shouldUseBtr(
        primaryUrl: String,
        backupUrls: List<String> = emptyList(),
        config: AcceleratorConfig? = null,
        isEpisode: Boolean = false
    ): Boolean {
        val plan = buildCandidatePlan(primaryUrl, backupUrls, config, isEpisode)
        if (plan.isDirectUpos && !plan.hasMultiSource) {
            io.github.jh_mmm.biliaccelerator.core.DebugEventBuffer.record(
                "BTR",
                "bypass direct-upos reason=insufficient-candidates primary=${cleanHost(primaryUrl)} candidates=${plan.candidateHosts.size}"
            )
            return false
        }
        return true
    }

    internal fun buildCandidatePlan(
        primaryUrl: String,
        backupUrls: List<String> = emptyList(),
        config: AcceleratorConfig? = null,
        isEpisode: Boolean = false
    ): BtrCandidatePlan {
        val uri = BiliAcceleratorCore.parseUri(primaryUrl)
        val primaryHost = BiliAcceleratorCore.cleanHost(uri?.host).lowercase()
        val isRelay = BiliAcceleratorCore.isRelayUrl(primaryUrl)
        val identity = BiliAcceleratorCore.mediaIdentity(primaryUrl)
        val isDirectUpos = !isRelay &&
                uri != null &&
                uri.path.contains("/upgcxcode/") &&
                BiliAcceleratorCore.isBiliCdnHost(primaryHost) &&
                !BiliAcceleratorCore.isAkamaiHost(primaryHost)
        val safeExplicitBackups = backupUrls
            .filter { BtrHttpSafety.isAllowedUpstreamUrl(it) && it != primaryUrl }
            .filter { !BiliAcceleratorCore.isLiveMediaUrl("", it) &&
                !BiliAcceleratorCore.hasDrmSignal(BiliAcceleratorCore.unwrapRelayUrl(it).orEmpty()) &&
                !BiliAcceleratorCore.isEpisodePreview(BiliAcceleratorCore.unwrapRelayUrl(it).orEmpty()) }
            .filter { identity != null && BiliAcceleratorCore.mediaIdentity(it) == identity }
            .filter { BiliAcceleratorCore.isRelayUrl(it) == isRelay }
            .filter { isRelay || isSameRegion(primaryHost, it) }
            .distinct()
        val synthesized = if (isRelay) {
            synthesizeRelayWrapperBackups(primaryUrl, config)
        } else if (safeExplicitBackups.isEmpty()) {
            synthesizeCompatibleBackups(primaryUrl, config, isEpisode)
        } else {
            emptyList()
        }
        val poolLimit = config?.btrCdnPoolSize?.coerceIn(1, 32)?.minus(1) ?: 8
        val finalBackups = (safeExplicitBackups + synthesized)
            .filter { BtrHttpSafety.isAllowedUpstreamUrl(it) && it != primaryUrl }
            .filter { BiliAcceleratorCore.isRelayUrl(it) == isRelay }
            .filter { isRelay || isSameRegion(primaryHost, it) }
            .distinct()
            .take(poolLimit)
        val candidateHosts = (listOf(primaryUrl) + finalBackups)
            .map { cleanHost(it) }
            .filter { it.isNotBlank() }
            .distinct()
        return BtrCandidatePlan(
            primaryUrl = primaryUrl,
            backupUrls = finalBackups,
            candidateHosts = candidateHosts,
            isRelay = isRelay,
            isDirectUpos = isDirectUpos,
            hasMultiSource = candidateHosts.size >= 2,
            synthesizedUrls = finalBackups.filterNot { it in safeExplicitBackups }.toSet()
        )
    }

    fun bindBuilderSession(builder: Any, sessionId: String) {
        builderSessionMap[builder] = sessionId
    }

    fun getSessionForBuilder(builder: Any): String? {
        return builderSessionMap[builder]
    }

    fun get(sessionId: String): BtrMediaSession? {
        val s = sessions[sessionId]
        s?.lastAccessed?.set(System.currentTimeMillis())
        return s
    }

    fun updateBackups(sessionId: String, backups: List<String>) {
        val current = sessions[sessionId] ?: return
        if (backups.isEmpty() || backups.any { BiliAcceleratorCore.isLiveMediaUrl("", it) }) return
        val identity = BiliAcceleratorCore.mediaIdentity(current.primaryUrl) ?: return
        if (backups.none { BiliAcceleratorCore.mediaIdentity(it) == identity }) return

        val plan = buildCandidatePlan(current.primaryUrl, backups, current.config, current.isEpisode)
        current.candidatePlan = plan
        current.backupUrls = plan.backupUrls
    }

    fun updateBackupsByCandidateUrls(backups: List<String>) {
        if (backups.isEmpty() || backups.any { BiliAcceleratorCore.isLiveMediaUrl("", it) }) return
        val identities = backups.mapNotNull(BiliAcceleratorCore::mediaIdentity).distinct()
        val identity = identities.singleOrNull() ?: return
        val session = sessions.values.filter {
            BiliAcceleratorCore.mediaIdentity(it.primaryUrl) == identity
        }.singleOrNull() ?: return
        updateBackups(session.sessionId, backups)
    }

    private fun cleanHost(value: String): String {
        val parsedHost = BiliAcceleratorCore.parseUri(value)?.host
        return BiliAcceleratorCore.cleanHost(parsedHost ?: value).lowercase()
    }

    private fun isSameRegion(primaryHost: String, candidateUrl: String): Boolean {
        return isSameRegionHosts(primaryHost, cleanHost(candidateUrl))
    }

    private fun isSameRegionHosts(primaryHost: String, candidateHost: String): Boolean {
        if (primaryHost.isBlank() || candidateHost.isBlank()) return false
        val primaryOverseas = isOverseasFamily(primaryHost)
        if (primaryOverseas != isOverseasFamily(candidateHost)) return false
        if (!primaryOverseas) return true
        // Overseas signatures are safest when only the known ov/bstar/
        // Akamai families are used. cn-hk-eq is accepted as a primary region
        // marker, but is not treated as a generic interchangeable backup.
        return candidateHost.contains("ov.bilivideo.com") ||
                candidateHost.contains("bstarstatic.com") ||
                candidateHost.endsWith(".akamaized.net")
    }

    private fun isOverseasFamily(host: String): Boolean {
        val clean = host.lowercase()
        return clean.contains("ov.bilivideo.com") ||
                clean.contains("cn-hk-eq") ||
                clean.contains("bstarstatic.com") ||
                clean.endsWith(".akamaized.net")
    }

    /**
     * 安全合成同源通用 UPOS 备用节点：
     * 当宿主未提供 backupUrls 时，在同组通用 UPOS 节点之间安全互换，
     * 解决海外/大陆多源并发拉取，彻底杜绝 403 熔断。
     */
    fun synthesizeCompatibleBackups(
        primaryUrl: String,
        config: AcceleratorConfig? = null,
        isEpisode: Boolean = false
    ): List<String> {
        val relayBackups = synthesizeRelayWrapperBackups(primaryUrl, config)
        if (relayBackups.isNotEmpty()) return relayBackups

        val results = mutableListOf<String>()
        val uri = BiliAcceleratorCore.parseUri(primaryUrl) ?: return emptyList()
        val path = uri.path
        if (!path.contains("/upgcxcode/")) return emptyList()

        // 对齐 BTR Issue #47：中继地址（Relay/MCDN）与 DRM 版权流严禁作为 Donor 母本合成普通镜像
        if (BiliAcceleratorCore.isRelayUrl(primaryUrl) || path.startsWith("/v1/resource/") || BiliAcceleratorCore.hasDrmSignal(primaryUrl)) {
            return emptyList()
        }

        val host = BiliAcceleratorCore.cleanHost(uri.host).lowercase()
        val isAkamai = host.endsWith(".akamaized.net")
        val isRegionalPgcHost = host.contains("bstar") ||
                host.contains("cn-hk-eq") ||
                host.contains("ov.bilivideo.com") ||
                isAkamai
        val isExplicitOverseas = host.contains("ov.bilivideo.com") ||
                isAkamai ||
                host.endsWith(".bstarstatic.com")
        val isExplicitMainland = BiliAcceleratorCore.isBiliCdnHost(host) && !isExplicitOverseas
        val isOverseasStream = when {
            isExplicitOverseas -> true
            isEpisode && isRegionalPgcHost -> true
            isExplicitMainland -> false
            else -> BiliAcceleratorCore.isOverseasEnvironment()
        }

        val customPool = config?.customCdnPool
            ?.filter { BiliAcceleratorCore.isBiliCdnHost(it) && !BiliAcceleratorCore.isAkamaiHost(it) }
            ?.distinct()
            .orEmpty()
        val useCustomPool = customPool.isNotEmpty()
        val candidateList = if (useCustomPool) {
            BiliAcceleratorCore.getEffectiveCdnPool(config ?: return emptyList())
        } else if (isOverseasStream) {
            BiliAcceleratorCore.OVERSEAS_CANDIDATE_POOL
        } else {
            BiliAcceleratorCore.MAINLAND_CANDIDATE_POOL
        }
        if (candidateList.isEmpty()) return emptyList()

        val maxBackups = if (config != null) config.btrCdnPoolSize.coerceIn(1, candidateList.size) else 3

        for (m in candidateList) {
            val cleanM = BiliAcceleratorCore.cleanHost(m).lowercase()
            if (cleanM == host || cleanM.endsWith(".akamaized.net")) continue
            if (!isSameRegionHosts(host, cleanM)) continue

            results.add(uri.replaceAuthority(cleanM))
            if (results.size >= maxBackups) break
        }
        return results
    }

    private fun synthesizeRelayWrapperBackups(
        primaryUrl: String,
        config: AcceleratorConfig?
    ): List<String> {
        val uri = BiliAcceleratorCore.parseUri(primaryUrl) ?: return emptyList()
        val host = BiliAcceleratorCore.cleanHost(uri.host).lowercase()
        if (!BiliAcceleratorCore.isRelayWrapper(primaryUrl)) return emptyList()

        val preferred = config?.proxyHost?.let { BiliAcceleratorCore.cleanHost(it).lowercase() }
        val candidates = (listOfNotNull(preferred) + BiliAcceleratorCore.OFFICIAL_RELAY_HOSTS)
            .map { BiliAcceleratorCore.cleanHost(it).lowercase() }
            .filter { BiliAcceleratorCore.isOfficialBiliHost(it) && it != host }
            .distinct()
        val limit = config?.btrCdnPoolSize?.coerceIn(1, 32) ?: candidates.size
        return candidates.take(limit).map { candidate -> uri.replaceAuthority(candidate) }
    }

    private fun cleanupStaleSessions() {
        val now = System.currentTimeMillis()
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.lastAccessed.get() > SESSION_TTL_MS) {
                iterator.remove()
            }
        }

        // MAX_SESSIONS is a hard cap, not only a trigger for TTL cleanup.
        // Evict the least-recently-used session if a burst of active streams
        // would otherwise grow the in-memory session map without bound.
        while (sessions.size >= MAX_SESSIONS) {
            val oldest = sessions.entries.minByOrNull { it.value.lastAccessed.get() } ?: break
            sessions.remove(oldest.key, oldest.value)
        }
    }
}
