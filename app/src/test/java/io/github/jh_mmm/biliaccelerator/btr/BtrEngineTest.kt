package io.github.jh_mmm.biliaccelerator.btr

import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import io.github.jh_mmm.biliaccelerator.core.RewriteResult
import io.github.jh_mmm.biliaccelerator.core.StatsManager
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
}
