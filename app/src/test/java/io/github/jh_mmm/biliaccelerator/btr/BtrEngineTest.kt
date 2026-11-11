package io.github.jh_mmm.biliaccelerator.btr

import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import io.github.jh_mmm.biliaccelerator.core.RewriteResult
import io.github.jh_mmm.biliaccelerator.core.StatsManager
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import java.net.Socket
import java.net.SocketException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BtrEngineTest {

    @Test
    fun testSessionManagerRegistrationAndRetrieval() {
        val primary = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/12345.m4s"
        val backups = listOf(
            "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/54/12/12345.m4s",
            "https://upos-sz-mirrorhw.bilivideo.com/upgcxcode/54/12/12345.m4s"
        )

        val sessionId = BtrSessionManager.register(primary, backups, isAudio = false)
        assertTrue("Session ID must not be blank", sessionId.isNotBlank())

        val session = BtrSessionManager.get(sessionId)
        assertNotNull("Session must be retrievable", session)
        assertEquals(primary, session?.primaryUrl)
        assertEquals(2, session?.backupUrls?.size)
        assertFalse("Session isAudio should be false", session?.isAudio ?: true)
    }

    @Test
    fun testAudioSessionRegistration() {
        val audioUrl = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/12345-30280.m4s"
        val sessionId = BtrSessionManager.register(audioUrl, isAudio = true)

        val session = BtrSessionManager.get(sessionId)
        assertNotNull("Audio session must be retrievable", session)
        assertTrue("Audio flag must be true", session?.isAudio ?: false)
    }

    @Test
    fun testBuilderSessionBindingAndDynamicBackupUpdate() {
        val primary = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/12345.m4s"
        val dummyBuilder = Any()
        val sessionId = BtrSessionManager.register(primary)

        BtrSessionManager.bindBuilderSession(dummyBuilder, sessionId)
        assertEquals(sessionId, BtrSessionManager.getSessionForBuilder(dummyBuilder))

        val officialBackups = listOf(
            "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/54/12/12345.m4s"
        )
        BtrSessionManager.updateBackups(sessionId, officialBackups)

        val session = BtrSessionManager.get(sessionId)
        assertEquals(1, session?.backupUrls?.size)
        assertEquals(officialBackups[0], session?.backupUrls?.firstOrNull())
    }

    @Test
    fun testLiveBackupsCannotPolluteVodSession() {
        val primary = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/77/88/live-isolation-vod.m4s"
        val officialBackups = listOf(
            "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/77/88/live-isolation-vod.m4s"
        )
        val sessionId = BtrSessionManager.register(primary, officialBackups)
        assertEquals(officialBackups, BtrSessionManager.get(sessionId)?.backupUrls)

        BtrSessionManager.updateBackups(
            sessionId,
            listOf("https://d1--cn-gotcha204b.bilivideo.com/live-bvc/123/live.flv")
        )
        assertEquals(
            "Direct live backup update must be rejected",
            officialBackups,
            BtrSessionManager.get(sessionId)?.backupUrls
        )

        BtrSessionManager.updateBackupsByCandidateUrls(
            listOf("https://d1--cn-gotcha209b.bilivideo.com/live-bvc/123/live.flv")
        )
        assertEquals(
            "Unbound live backup list must not replace latest VOD session",
            officialBackups,
            BtrSessionManager.get(sessionId)?.backupUrls
        )
    }

    @Test
    fun testUnmatchedVodBackupsDoNotFallbackToLatestSession() {
        val primary = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/91/92/unmatched-latest-test.m4s"
        val officialBackups = listOf(
            "https://upos-sz-mirrorhw.bilivideo.com/upgcxcode/91/92/unmatched-latest-test.m4s"
        )
        val sessionId = BtrSessionManager.register(primary, officialBackups)

        BtrSessionManager.updateBackupsByCandidateUrls(
            listOf("https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/99/88/unrelated.m4s")
        )
        assertEquals(
            "Unmatched path must not overwrite the latest BTR session",
            officialBackups,
            BtrSessionManager.get(sessionId)?.backupUrls
        )
    }

    @Test
    fun testSynthesizeCompatibleBackupsForOverseasAkamai() {
        val akamaiPrimary = "https://upos-hz-mirrorakam.akamaized.net/upgcxcode/54/12/12345.m4s?deadline=1700000000"
        val backups = BtrSessionManager.synthesizeCompatibleBackups(akamaiPrimary)
        assertTrue("Overseas Akamai stream should synthesize overseas mirrors", backups.isNotEmpty())
        for (backup in backups) {
            assertTrue("Synthesized backup must be an overseas ov node", backup.contains("ov.bilivideo.com"))
        }
    }

    @Test
    fun testSynthesizeCompatibleBackupsExcludesOverseasAndAkamai() {
        val primary = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/12345.m4s?deadline=1700000000"
        val backups = BtrSessionManager.synthesizeCompatibleBackups(primary)

        assertTrue("Compatible backups should be generated", backups.isNotEmpty())
        for (backup in backups) {
            assertFalse("Must not include primary host", backup.contains("upos-sz-mirrorali.bilivideo.com"))
            assertFalse("Must not include overseas ov mirrors", backup.contains("ov.bilivideo.com"))
            assertFalse("Must not include Akamai mirrors", backup.contains("akamaized.net") || backup.contains("mirrorakam"))
        }
    }

    @Test
    fun testSynthesizeCompatibleBackupsExcludesRelayAndMcdnUrls() {
        // 对齐 BTR Issue #47：中继/MCDN/P2P 流绝不合成普通 UPOS 镜像，杜绝 HTTP 403 熔断
        val relay1 = "http://xy123.mcdn.bilivideo.cn:4483/v1/resource/upgcxcode/54/12/12345.m4s"
        val relay2 = "https://proxy-tf-all-ws.bilivideo.com/?url=http%3A%2F%2Fxy.mcdn.bilivideo.cn"
        val relay3 = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/12345.m4s?os=mcdn"

        assertTrue("Relay URL with /v1/resource/ must not synthesize UPOS mirrors", BtrSessionManager.synthesizeCompatibleBackups(relay1).isEmpty())
        val relay2Backups = BtrSessionManager.synthesizeCompatibleBackups(relay2)
        assertTrue("Proxy wrapper should synthesize relay backups", relay2Backups.isNotEmpty())
        assertTrue("Proxy wrapper backups must remain official relay hosts", relay2Backups.all { it.startsWith("https://proxy-tf-all-") })
        assertTrue("URL with os=mcdn must not synthesize UPOS mirrors", BtrSessionManager.synthesizeCompatibleBackups(relay3).isEmpty())
    }

    @Test
    fun testProxyRelaySynthesizesAlternateOfficialRelayHosts() {
        val primary = "https://proxy-tf-all-ws.bilivideo.com/?url=http%3A%2F%2Fxy.mcdn.bilivideo.cn%2Fv1%2Fresource%2F123.m4s"
        val backups = BtrSessionManager.synthesizeCompatibleBackups(
            primary,
            AcceleratorConfig(btrCdnPoolSize = 8)
        )
        assertEquals(2, backups.size)
        assertTrue(backups.any { it.startsWith("https://proxy-tf-all-tx.bilivideo.com/") })
        assertTrue(backups.any { it.startsWith("https://proxy-tf-all-hw.bilivideo.com/") })
        assertFalse(backups.any { it.startsWith("https://proxy-tf-all-ws.bilivideo.com/") })
        assertTrue(backups.all { it.contains("url=http%3A%2F%2Fxy.mcdn.bilivideo.cn") })

        val sessionId = BtrSessionManager.register(
            primary,
            emptyList(),
            config = AcceleratorConfig(btrCdnPoolSize = 8)
        )
        val sessionBackups = BtrSessionManager.get(sessionId)?.backupUrls.orEmpty()
        assertEquals(2, sessionBackups.size)
        assertTrue(sessionBackups.all { BiliAcceleratorCore.isRelayUrl(it) })

        val tencentPrimary = "https://proxy-tf-all-tx.bilivideo.com/?url=http%3A%2F%2Fxy.mcdn.bilivideo.cn%2Fv1%2Fresource%2F456.m4s"
        val tencentBackups = BtrSessionManager.synthesizeCompatibleBackups(
            tencentPrimary,
            AcceleratorConfig(proxyHost = "proxy-tf-all-tx.bilivideo.com", btrCdnPoolSize = 8)
        )
        assertEquals(2, tencentBackups.size)
        assertTrue(tencentBackups.any { it.startsWith("https://proxy-tf-all-ws.bilivideo.com/") })
        assertTrue(tencentBackups.any { it.startsWith("https://proxy-tf-all-hw.bilivideo.com/") })
        assertFalse(tencentBackups.any { it.startsWith("https://proxy-tf-all-tx.bilivideo.com/") })
    }

    @Test
    fun testDnsFailureUsesLongBackoff() {
        assertEquals(
            300_000L,
            BtrChunkEngine.penaltyForFailureMessage(
                "Unable to resolve host \"proxy-tf-all-tx.bilivideo.com\": No address associated with hostname"
            )
        )
        assertEquals(120_000L, BtrChunkEngine.penaltyForFailureMessage("Blocked unsafe upstream URL: host foo"))
        assertEquals(30_000L, BtrChunkEngine.penaltyForFailureMessage("unexpected end of stream"))
    }

    @Test
    fun testSessionManagerIsolatesRelayAndOrdinaryBackups() {
        val relayPrimary = "https://proxy-tf-all-ws.bilivideo.com/?url=http%3A%2F%2Fxy.mcdn.bilivideo.cn%2Fv1%2Fresource%2F123.m4s"
        val mixedBackups = listOf(
            "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/123.m4s",
            "https://xy2.mcdn.bilivideo.cn/v1/resource/123.m4s"
        )

        val sessionId = BtrSessionManager.register(relayPrimary, mixedBackups)
        val session = BtrSessionManager.get(sessionId)
        assertNotNull(session)
        // 普通 UPOS 镜像严禁混入中继 session
        assertFalse("Ordinary UPOS host must be filtered out from relay session", session?.backupUrls?.any { it.contains("mirrorali") } ?: true)
        assertTrue("Compatible relay backup should be retained", session?.backupUrls?.any { it.contains("xy2.mcdn") } ?: false)
    }

    @Test
    fun testChunkEngineAudioVsVideoConstants() {
        // 音频必须使用 128KB 极低时延切片与 2 线程隔离并发，防止与视频争夺带宽及产生音画不同步
        assertEquals(128 * 1024, BtrChunkEngine.CHUNK_SIZE_AUDIO)
        assertEquals(2, BtrChunkEngine.AUDIO_MAX_CONCURRENCY)

        // 视频使用 512KB 分片
        assertEquals(512 * 1024, BtrChunkEngine.CHUNK_SIZE_VIDEO)
        assertEquals(64 * 1024, BtrChunkEngine.PROBE_PREFIX_BYTES)
    }

    @Test
    fun testStatsManagerTracksBtrMetrics() {
        val btrResult = RewriteResult(
            changed = true,
            originalUrl = "http://112.34.56.78:8080/upgcxcode/54/12/12345.m4s",
            finalUrl = "http://127.0.0.1:38472/stream/abcdef123456.m4s",
            originalHost = "112.34.56.78",
            targetHost = "127.0.0.1:38472 (6线程)",
            reason = "BTR并发加速 · 24分片(12.0MB) · 3个CDN · 均时45ms",
            isPcdn = false,
            isMcdn = false,
            segmentKey = "btr_test_segment_1",
            isBtr = true,
            btrChunks = 24,
            btrBytes = 12 * 1024 * 1024L,
            btrConcurrency = 6,
            btrCdnCount = 3,
            btrAvgLatencyMs = 45L
        )

        val beforeSnapshot = StatsManager.getSnapshot()
        StatsManager.recordRequests(listOf(btrResult))
        val afterSnapshot = StatsManager.getSnapshot()

        assertEquals(beforeSnapshot.btrTotalChunks + 24, afterSnapshot.btrTotalChunks)
        assertEquals(beforeSnapshot.btrTotalBytes + (12 * 1024 * 1024L), afterSnapshot.btrTotalBytes)

        val latestLog = afterSnapshot.recentLogs.firstOrNull()
        assertNotNull("Recent logs must contain BTR entry", latestLog)
        assertTrue("Log entry must be flagged as BTR", latestLog?.isBtr == true)
        assertEquals(24, latestLog?.btrChunks)
        assertEquals(6, latestLog?.btrConcurrency)
    }

    @Test
    fun testBtrFailoverTracking() {
        val failoverResult = RewriteResult(
            changed = true,
            originalUrl = "",
            finalUrl = "",
            originalHost = "upos-sz-mirrorali.bilivideo.com",
            targetHost = "upos-sz-mirrorcos.bilivideo.com",
            reason = "BTR节点熔断: HTTP 403 熔断",
            isPcdn = false,
            isMcdn = false,
            segmentKey = "btr_failover_test_1",
            isBtr = true
        )

        val before = StatsManager.getSnapshot().btrFailoverCount
        StatsManager.recordRequests(listOf(failoverResult))
        val after = StatsManager.getSnapshot().btrFailoverCount

        assertEquals(before + 1, after)
    }

    @Test
    fun testConcurrencyCapsForBurstSteadyAndSingleRelay() {
        val multi = BtrChunkEngine.calculateConcurrencyCaps(
            requestedConcurrency = 6,
            autoConcurrency = true,
            isAudio = false,
            isSingleRelay = false,
            isRelayFamily = false,
            memoryBoundPrefetch = 16
        )
        assertEquals(8, multi.burst)
        assertEquals(6, multi.steady)

        val singleRelay = BtrChunkEngine.calculateConcurrencyCaps(
            requestedConcurrency = 6,
            autoConcurrency = true,
            isAudio = false,
            isSingleRelay = true,
            isRelayFamily = false,
            memoryBoundPrefetch = 16
        )
        assertEquals(4, singleRelay.burst)
        assertEquals(4, singleRelay.steady)

        val relayFamily = BtrChunkEngine.calculateConcurrencyCaps(
            requestedConcurrency = 6,
            autoConcurrency = true,
            isAudio = false,
            isSingleRelay = false,
            isRelayFamily = true,
            memoryBoundPrefetch = 16
        )
        assertEquals(8, relayFamily.burst)
        assertEquals(4, relayFamily.steady)

        val audio = BtrChunkEngine.calculateConcurrencyCaps(
            requestedConcurrency = 16,
            autoConcurrency = true,
            isAudio = true,
            isSingleRelay = false,
            isRelayFamily = false,
            memoryBoundPrefetch = 16
        )
        assertEquals(2, audio.burst)
        assertEquals(2, audio.steady)

        val memoryBound = BtrChunkEngine.calculateConcurrencyCaps(
            requestedConcurrency = 16,
            autoConcurrency = true,
            isAudio = false,
            isSingleRelay = false,
            isRelayFamily = false,
            memoryBoundPrefetch = 8
        )
        assertEquals(8, memoryBound.burst)
        assertEquals(8, memoryBound.steady)

        val manual = BtrChunkEngine.calculateConcurrencyCaps(
            requestedConcurrency = 6,
            autoConcurrency = false,
            isAudio = false,
            isSingleRelay = false,
            isRelayFamily = false,
            memoryBoundPrefetch = 16
        )
        assertEquals(6, manual.burst)
        assertEquals(6, manual.steady)
    }

    @Test
    fun testAdaptivePenaltyBackoffIsBounded() {
        assertEquals(60_000L, BtrChunkEngine.calculateAdaptivePenaltyMs(60_000L, 1))
        assertEquals(120_000L, BtrChunkEngine.calculateAdaptivePenaltyMs(60_000L, 2))
        assertEquals(240_000L, BtrChunkEngine.calculateAdaptivePenaltyMs(60_000L, 3))
        assertEquals(480_000L, BtrChunkEngine.calculateAdaptivePenaltyMs(60_000L, 4))
        assertEquals(480_000L, BtrChunkEngine.calculateAdaptivePenaltyMs(60_000L, 8))
        assertEquals(600_000L, BtrChunkEngine.calculateAdaptivePenaltyMs(120_000L, 8))
    }

    @Test
    fun testSafeFallbackStatisticsCounter() {
        val before = StatsManager.getSnapshot().safeFallbackCount
        StatsManager.recordRequests(
            listOf(
                RewriteResult(
                    changed = false,
                    originalUrl = "https://proxy-tf-all-tx.bilivideo.com/?url=https%3A%2F%2Fxy.mcdn.bilivideo.cn%2Fupgcxcode%2F1.m4s",
                    finalUrl = "https://xy.mcdn.bilivideo.cn/upgcxcode/1.m4s",
                    originalHost = "proxy-tf-all-tx.bilivideo.com",
                    targetHost = "xy.mcdn.bilivideo.cn",
                    reason = BiliAcceleratorCore.SAFE_FALLBACK_REASON,
                    isPcdn = false,
                    isMcdn = false,
                    segmentKey = "safe-fallback-test-${System.nanoTime()}"
                )
            )
        )
        assertEquals(before + 1L, StatsManager.getSnapshot().safeFallbackCount)
    }

    @Test
    fun testCalculateDynamicChunkSize() {
        // 音频始终保持 128KB
        assertEquals(128 * 1024L, BtrChunkEngine.calculateDynamicChunkSize(100 * 1024 * 1024L, 512, autoChunkSize = true, isAudio = true))
        assertEquals(128 * 1024L, BtrChunkEngine.calculateDynamicChunkSize(100 * 1024 * 1024L, 512, autoChunkSize = false, isAudio = true))

        // 关闭自适应切片时，使用基准大小
        assertEquals(512 * 1024L, BtrChunkEngine.calculateDynamicChunkSize(300 * 1024 * 1024L, 512, autoChunkSize = false, isAudio = false))
        assertEquals(1024 * 1024L, BtrChunkEngine.calculateDynamicChunkSize(5 * 1024 * 1024L, 1024, autoChunkSize = false, isAudio = false))

        // 开启自适应时按体量动态缩放
        // < 15MB -> 256KB
        assertEquals(256 * 1024L, BtrChunkEngine.calculateDynamicChunkSize(10 * 1024 * 1024L, 512, autoChunkSize = true, isAudio = false))
        // 15MB ~ 60MB -> 512KB
        assertEquals(512 * 1024L, BtrChunkEngine.calculateDynamicChunkSize(40 * 1024 * 1024L, 512, autoChunkSize = true, isAudio = false))
        // 60MB ~ 250MB -> 1024KB
        assertEquals(1024 * 1024L, BtrChunkEngine.calculateDynamicChunkSize(150 * 1024 * 1024L, 512, autoChunkSize = true, isAudio = false))
        // >= 250MB -> 1024KB (移动端 4K/8K 大文件切片上限收敛为 1MB 防 OOM)
        assertEquals(1024 * 1024L, BtrChunkEngine.calculateDynamicChunkSize(500 * 1024 * 1024L, 512, autoChunkSize = true, isAudio = false))
    }

    @Test
    fun testSessionMetadataCachingForSeekBypass() {
        val primary = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/12345.m4s"
        val sessionId = BtrSessionManager.register(primary)
        val session = BtrSessionManager.get(sessionId)
        assertNotNull(session)

        assertEquals(-1L, session?.totalLength)
        session?.totalLength = 50_000_000L
        session?.contentType = "video/mp4"

        val retrieved = BtrSessionManager.get(sessionId)
        assertEquals(50_000_000L, retrieved?.totalLength)
        assertEquals("video/mp4", retrieved?.contentType)
    }

    @Test
    fun testOverseasStreamSynthesizesOverseasMirrors() {
        val overseasPrimary = "https://upos-sz-mirrorcosov.bilivideo.com/upgcxcode/54/12/12345.m4s?deadline=1700000000"
        val backups = BtrSessionManager.synthesizeCompatibleBackups(overseasPrimary)

        assertTrue("Overseas stream should synthesize overseas mirrors", backups.isNotEmpty())
        for (backup in backups) {
            assertTrue("Synthesized backup must be an overseas ov node", backup.contains("ov.bilivideo.com"))
            assertFalse("Must not include primary host", backup.contains("mirrorcosov"))
        }
    }

    @Test
    fun testCustomCdnPoolUsageInSessionManager() {
        val primary = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/12345.m4s?deadline=1700000000"
        val customPool = listOf(
            "upos-tf-all-tx.bilivideo.com",
            "upos-tf-all-hw.bilivideo.com",
            "upos-sz-mirrorhw.bilivideo.com"
        )
        val config = AcceleratorConfig(customCdnPool = customPool)
        val backups = BtrSessionManager.synthesizeCompatibleBackups(primary, config)

        assertTrue("Backups should be synthesized using custom pool", backups.isNotEmpty())
        for (backup in backups) {
            assertFalse("Must not include primary host", backup.contains("upos-sz-mirrorali.bilivideo.com"))
            val hasCustomHost = customPool.any { backup.contains(it) }
            assertTrue("Synthesized backup must belong to custom pool", hasCustomHost)
        }
    }

    @Test
    fun testDirectUposRequiresTwoTrustedCandidatesBeforeEnteringBtr() {
        val primary = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/12345.m4s"
        val onlySameHost = AcceleratorConfig(
            customCdnPool = listOf("upos-sz-mirrorali.bilivideo.com"),
            btrCdnPoolSize = 8
        )
        val plan = BtrSessionManager.buildCandidatePlan(primary, config = onlySameHost)
        assertTrue("Direct UPOS should be recognized", plan.isDirectUpos)
        assertFalse("A single host must not enter multi-source BTR", plan.hasMultiSource)
        assertFalse(BtrSessionManager.shouldUseBtr(primary, config = onlySameHost))
    }

    @Test
    fun testDirectUposWithDefaultRegionalPoolCanUseBtr() {
        val primary = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/12345.m4s"
        val config = AcceleratorConfig(btrCdnPoolSize = 8)
        val plan = BtrSessionManager.buildCandidatePlan(primary, config = config)
        assertTrue(plan.hasMultiSource)
        assertTrue(plan.candidateHosts.all { !it.contains("ov.bilivideo.com") })
        assertTrue(BtrSessionManager.shouldUseBtr(primary, config = config))
    }

    @Test
    fun testCrossRegionExplicitBackupIsRejected() {
        val primary = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/12345.m4s"
        val backup = "https://upos-sz-mirrorcosov.bilivideo.com/upgcxcode/54/12/12345.m4s"
        val plan = BtrSessionManager.buildCandidatePlan(primary, listOf(backup))
        assertTrue("Cross-region backup must be removed", plan.backupUrls.none { it.contains("ov.bilivideo.com") })
        assertTrue("Safe mainland fallback candidates should be synthesized", plan.hasMultiSource)
    }

    @Test
    fun testEpisodeRegionalStreamUsesOverseasMirrorsAndBangumiReferer() {
        val primary = "https://cn-hk-eq-01-01.bilivideo.com/upgcxcode/54/12/12345.m4s?ep_id=12345"
        val plan = BtrSessionManager.buildCandidatePlan(primary, isEpisode = true)
        assertTrue(plan.hasMultiSource)
        assertTrue(plan.backupUrls.all { it.contains("ov.bilivideo.com") || it.contains("cn-hk-eq") })

        val sessionId = BtrSessionManager.register(primary, isEpisode = true)
        val session = BtrSessionManager.get(sessionId)
        assertTrue(session?.isEpisode == true)
        assertEquals("https://www.bilibili.com/bangumi/play/", session?.referer)
    }

    @Test
    fun testEpisodeDetectionDoesNotMatchOrdinaryUgc() {
        val episode = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/12345.m4s?ep_id=12345"
        val ugc = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/12345.m4s?deadline=1700000000"
        assertTrue(BiliAcceleratorCore.isEpisodeMediaUrl(episode))
        assertFalse(BiliAcceleratorCore.isEpisodeMediaUrl(ugc))
        assertTrue(BiliAcceleratorCore.isEpisodePreview("https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/12345.m4s?is_preview=1"))
        assertFalse(BiliAcceleratorCore.isEpisodePreview("https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/12345.m4s?is_preview=0"))
    }

    @Test
    fun testCancellingOldStreamDoesNotCancelNewStreamOnSameSession() {
        val registry = BtrCallRegistry()
        val old = registry.open("same-session")
        val current = registry.open("same-session")
        val oldCall = FakeCall()
        val currentCall = FakeCall()
        val lateCall = FakeCall()
        old.register(oldCall)
        current.register(currentCall)

        old.close()
        assertTrue(oldCall.isCanceled())
        assertFalse(currentCall.isCanceled())
        assertFalse(old.register(lateCall))
        assertTrue(lateCall.isCanceled())
        assertEquals(1, registry.activeStreamCount())

        registry.cancelAll()
        assertTrue(currentCall.isCanceled())
        assertEquals(0, registry.activeStreamCount())
    }

    @Test(timeout = 5000)
    fun testLocalProxyStopClosesAcceptedSocketAndCanRestart() {
        BtrLocalProxyServer.start()
        try {
            Socket("127.0.0.1", BtrLocalProxyServer.port).use { socket ->
                socket.soTimeout = 2000
                socket.getOutputStream().write("GET /stream/incomplete.m4s HTTP/1.1\r\n".toByteArray())
                val deadline = System.nanoTime() + 1_000_000_000L
                while (BtrLocalProxyServer.activeClientCountForDiagnostics() == 0 && System.nanoTime() < deadline) {
                    Thread.sleep(5)
                }
                assertEquals(1, BtrLocalProxyServer.activeClientCountForDiagnostics())
                BtrLocalProxyServer.stop()
                try {
                    assertEquals(-1, socket.getInputStream().read())
                } catch (_: SocketException) {
                    // A reset also proves the accepted connection was closed.
                }
            }
            BtrLocalProxyServer.start()
            Socket("127.0.0.1", BtrLocalProxyServer.port).use { socket ->
                socket.soTimeout = 2000
                socket.getOutputStream().write("GET /unknown HTTP/1.1\r\n\r\n".toByteArray())
                assertTrue(socket.getInputStream().bufferedReader().readLine().startsWith("HTTP/1.1 404"))
            }
        } finally {
            BtrLocalProxyServer.stop()
        }
    }

    @Test
    fun testCompletedCallIsRemovedWithoutClosingTheStream() {
        val registry = BtrCallRegistry()
        val stream = registry.open("session-cleanup")
        val finished = FakeCall()
        val pending = FakeCall()
        assertTrue(stream.register(finished))
        stream.unregister(finished)
        assertTrue(stream.register(pending))
        stream.close()
        assertFalse(finished.isCanceled())
        assertTrue(pending.isCanceled())
        assertEquals(0, registry.activeStreamCount())
    }

    @Test
    fun testHttp403IsNotTreatedAsGlobalRelayFailure() {
        assertFalse("403 is usually URL-specific", BtrChunkEngine.httpStatusShouldMarkRelayHealth(403))
        assertTrue("5xx can indicate a host-level problem", BtrChunkEngine.httpStatusShouldMarkRelayHealth(500))
        assertTrue("5xx can indicate a host-level problem", BtrChunkEngine.httpStatusShouldMarkRelayHealth(503))
    }

    @Test
    fun testDirectFallbackExpiresAndUsesFreshlySignedUrlOnReentry() {
        val url = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/fallback-test.m4s"
        val original = "$url?deadline=1"
        val sessionId = BtrSessionManager.register(original)
        val now = 1_760_000_000_000L
        val session = requireNotNull(BtrSessionManager.get(sessionId))
        try {
            assertFalse(session.isDirectFallbackActive(now))
            session.armDirectFallback(now)
            assertTrue(session.isDirectFallbackActive(now + 1_000L))
            assertFalse(session.isDirectFallbackActive(now + 46_000L))
            val fresh = "$url?deadline=2"
            assertEquals(fresh, BtrSessionManager.activeDirectFallback(fresh))
        } finally {
            session.clearDirectFallback()
        }
    }

    private class FakeCall : Call {
        private val request = Request.Builder()
            .url("https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/1.m4s")
            .build()
        @Volatile
        private var canceled = false

        override fun request(): Request = request
        override fun execute(): Response = throw UnsupportedOperationException("Not used by registry tests")
        override fun enqueue(responseCallback: Callback) = throw UnsupportedOperationException("Not used by registry tests")
        override fun cancel() { canceled = true }
        override fun isCanceled(): Boolean = canceled
        override fun isExecuted(): Boolean = false
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = FakeCall()
    }
}
