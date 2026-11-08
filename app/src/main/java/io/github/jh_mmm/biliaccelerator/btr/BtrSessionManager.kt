package io.github.jh_mmm.biliaccelerator.btr

import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val DEFAULT_REFERER = "https://www.bilibili.com/"
private const val BANGUMI_REFERER = "https://www.bilibili.com/bangumi/play/"

data class BtrMediaSession(
    val sessionId: String,
    val primaryUrl: String,
    @Volatile var backupUrls: List<String>,
    @Volatile var userAgent: String?,
    @Volatile var referer: String = DEFAULT_REFERER,
    val isAudio: Boolean = false,
    val isEpisode: Boolean = false,
    @Volatile var nativeDirectFallback: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastAccessed: AtomicLong = AtomicLong(System.currentTimeMillis()),
    @Volatile var totalLength: Long = -1L,
    @Volatile var contentType: String? = null
)

internal data class BtrCandidatePlan(
    val primaryUrl: String,
    val backupUrls: List<String>,
    val candidateHosts: List<String>,
    val isRelay: Boolean,
    val isDirectUpos: Boolean,
    val hasMultiSource: Boolean
)

object BtrSessionManager {

    private const val MAX_SESSIONS = 50
    private const val SESSION_TTL_MS = 30 * 60 * 1000L // 30 分钟无访问自动清理

    private val sessions = ConcurrentHashMap<String, BtrMediaSession>()
    private val builderSessionMap = Collections.synchronizedMap(WeakHashMap<Any, String>())

    @Volatile
    private var latestSessionId: String? = null

    fun register(
        primaryUrl: String,
        backupUrls: List<String> = emptyList(),
        userAgent: String? = null,
        isAudio: Boolean = false,
        isEpisode: Boolean = false,
        config: AcceleratorConfig? = null
    ): String {
        cleanupStaleSessions()
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val plan = buildCandidatePlan(primaryUrl, backupUrls, config, isEpisode)
        val referer = if (isEpisode) BANGUMI_REFERER else DEFAULT_REFERER
        val session = BtrMediaSession(
            sessionId = id,
            primaryUrl = primaryUrl,
            backupUrls = plan.backupUrls,
            userAgent = userAgent,
            referer = referer,
            isAudio = isAudio,
            isEpisode = isEpisode
        )
        sessions[id] = session
        latestSessionId = id
        return id
    }

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
        val isDirectUpos = !isRelay &&
                uri != null &&
                uri.path.contains("/upgcxcode/") &&
                BiliAcceleratorCore.isBiliCdnHost(primaryHost) &&
                !BiliAcceleratorCore.isAkamaiHost(primaryHost)
        val safeExplicitBackups = backupUrls
            .filter { BtrHttpSafety.isAllowedUpstreamUrl(it) && it != primaryUrl }
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
        val poolLimit = config?.btrCdnPoolSize?.coerceIn(1, 32) ?: 8
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
            hasMultiSource = candidateHosts.size >= 2
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
        if (backups.any { BiliAcceleratorCore.isLiveMediaUrl("", it) }) {
            io.github.jh_mmm.biliaccelerator.core.DebugEventBuffer.record(
                "BTR",
                "rejected live backups session=${sessionId.take(6)} count=${backups.size}"
            )
            return
        }
        val validBackups = backups
            .filter { BtrHttpSafety.isAllowedUpstreamUrl(it) && it != current.primaryUrl }
            .distinct()
        val primaryHost = cleanHost(current.primaryUrl)
        val isPrimaryRelay = BiliAcceleratorCore.isRelayUrl(current.primaryUrl)
        val isolatedBackups = if (isPrimaryRelay) {
            val relayBackups = synthesizeRelayWrapperBackups(current.primaryUrl, null)
            (validBackups.filter { BiliAcceleratorCore.isRelayUrl(it) } + relayBackups)
                .distinct()
        } else {
            validBackups.filter { !BiliAcceleratorCore.isRelayUrl(it) && isSameRegion(primaryHost, it) }
        }
        current.backupUrls = isolatedBackups
    }

    fun updateBackupsForLatest(backups: List<String>) {
        val id = latestSessionId ?: return
        updateBackups(id, backups)
    }

    fun updateBackupsByCandidateUrls(backups: List<String>) {
        if (backups.isEmpty()) return
        if (backups.any { BiliAcceleratorCore.isLiveMediaUrl("", it) }) {
            io.github.jh_mmm.biliaccelerator.core.DebugEventBuffer.record(
                "BTR",
                "rejected live candidate backup list count=${backups.size}"
            )
            return
        }
        val samplePath = extractComparablePath(backups.first())
        if (samplePath.isNotBlank()) {
            for ((sessionId, session) in sessions) {
                if (extractComparablePath(session.primaryUrl) == samplePath) {
                    updateBackups(sessionId, backups)
                    return
                }
            }
        }
        // Never fall back to the most recently created session when the media
        // path does not match. That could mix unrelated VOD/live resources.
        io.github.jh_mmm.biliaccelerator.core.DebugEventBuffer.record(
            "BTR",
            "unmatched backup list ignored path=${samplePath.take(48)}"
        )
    }

    private fun extractComparablePath(url: String): String {
        return BiliAcceleratorCore.parseUri(url)?.path ?: ""
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
        if (!host.startsWith("proxy-tf-all-")) return emptyList()
        if (uri.path.isNotBlank() && uri.path != "/") return emptyList()
        if (uri.queryParam("url").isNullOrBlank()) return emptyList()

        val preferred = config?.proxyHost?.let { BiliAcceleratorCore.cleanHost(it).lowercase() }
        val candidates = (listOfNotNull(preferred) + BiliAcceleratorCore.OFFICIAL_RELAY_HOSTS)
            .map { BiliAcceleratorCore.cleanHost(it).lowercase() }
            .filter { it.startsWith("proxy-tf-all-") && it != host }
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
