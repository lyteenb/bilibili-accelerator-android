package io.github.jh_mmm.biliaccelerator.core

import com.google.gson.Gson
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugReportBuilderTest {

    @Test
    fun testDetailedDebugReportContainsRuntimeAndAllLogs() {
        val now = 1_760_000_000_000L
        val snapshot = StatsSnapshot(
            totalRequests = 174,
            totalRewrites = 167,
            pcdnBlocked = 7,
            mcdnProxied = 42,
            safeFallbackCount = 3,
            btrTotalChunks = 103,
            btrTotalBytes = 31L * 1024L * 1024L,
            btrFailoverCount = 18,
            avoidedHosts = listOf("127.0.0.1", "upos-sz-302ppio.bilivideo.com"),
            recentLogs = listOf(
                RewriteLogEntry(
                    id = "log-1",
                    timestamp = now - 2_000L,
                    timeFormatted = "11-08 20:00:00",
                    originalHost = "127.0.0.1:34111",
                    targetHost = "d1--cn-gotcha204b.bilivideo.com",
                    reason = "live-pcdn-filter",
                    isPcdn = true,
                    isMcdn = false
                ),
                RewriteLogEntry(
                    id = "log-2",
                    timestamp = now - 1_000L,
                    timeFormatted = "11-08 20:00:01",
                    originalHost = "proxy-tf-all-ws.bilivideo.com",
                    targetHost = "127.0.0.1:34111",
                    reason = "BTR并发加速",
                    isPcdn = false,
                    isMcdn = true,
                    isBtr = true,
                    btrChunks = 8,
                    btrBytes = 2_500_000L,
                    btrConcurrency = 8,
                    btrCdnCount = 3,
                    btrAvgLatencyMs = 88L
                )
            ),
            installedAt = "2026-11-08 19:55:51",
            lastHeartbeatTimestamp = now - 1_000L,
            lastRewriteTimestamp = now - 1_000L,
            hookStatus = mapOf(
                "player_segment" to "IjkMediaAsset Builder (6 hooks)",
                "player_fallback" to "IjkMediaPlayer"
            ),
            isFrameworkActivated = false,
            clientPackage = "tv.danmaku.bili",
            clientVersion = "9.16.1",
            clientProcess = "tv.danmaku.bili:web",
            frameworkScope = listOf("tv.danmaku.bili"),
            hasTargetScope = true,
            hasQueriedScope = true,
            scopeQueriedAt = now - 500L,
            hasRuntimeHeartbeat = true
        )
        val config = AcceleratorConfig(
            enabled = true,
            targetHost = "upos-sz-mirrorali.bilivideo.com",
            proxyHost = "proxy-tf-all-ws.bilivideo.com",
            blockPcdn = true,
            proxyMcdn = true,
            mcdnStrategy = "proxy-all",
            forceUpos = false,
            rewriteAkamai = false,
            portHeuristic = true,
            enableMossHook = false,
            enableBtr = true,
            btrAutoConcurrency = false,
            btrConcurrency = 8,
            btrAutoChunkSize = true,
            btrChunkSizeKb = 768,
            customCdnPool = listOf("upos-sz-mirrorali.bilivideo.com"),
            btrCdnPoolSize = 4,
            enableDebugLogging = true
        )
        val environment = DebugReportEnvironment(
            generatedAt = now,
            appVersion = "2.0.0-preview.1",
            versionCode = 200L,
            packageName = "io.github.jh_mmm.biliaccelerator",
            debugBuild = true,
            androidRelease = "16",
            sdkInt = 36,
            securityPatch = "2026-10-01",
            manufacturer = "vivo",
            brand = "vivo",
            model = "V2425A",
            device = "V2425A",
            product = "V2425A",
            hardware = "qcom",
            supportedAbis = listOf("arm64-v8a"),
            locale = "zh-CN",
            timezone = "Asia/Shanghai",
            processName = "io.github.jh_mmm.biliaccelerator",
            pid = 1234,
            uid = 10001,
            activationState = "ACTIVE_EFFECTIVE",
            isServiceBound = true,
            isScopeFresh = true,
            heartbeatThresholdMs = 120_000L
        )

        val debugEvents = DebugEventSnapshot(
            events = listOf(
                DebugEvent(now - 500L, "ROUTE", "changed=false reason=live-ok source=127.0.0.1 target=127.0.0.1")
            ),
            droppedCount = 3L
        )
        val report = DebugReportBuilder.build(snapshot, config, environment, debugEvents)

        assertTrue(report.contains("DETAILED DEBUG REPORT"))
        assertTrue(report.contains("本次进程收到过心跳: true"))
        assertTrue(report.contains("btrConcurrency=8"))
        assertTrue(report.contains("enableDebugLogging=true"))
        assertTrue(report.contains("safeFallbackCount=3"))
        assertTrue(report.contains("直播策略: 完全直通"))
        assertTrue(report.contains("PCDN 策略: 仅作用于点播流"))
        assertTrue(report.contains("source=本地代理 (127.0.0.1:34111)"))
        assertTrue(report.contains("#2 [11-08 20:00:01]"))
        assertTrue(report.contains("btr: chunks=8, bytes=2500000, concurrency=8, cdnCount=3, avgLatencyMs=88"))
        assertTrue(report.contains("player_segment = IjkMediaAsset Builder (6 hooks)"))
        assertTrue(report.contains("【运行时事件时间线】 count=1 dropped=3"))
        assertTrue(report.contains("[ROUTE] changed=false reason=live-ok"))
    }

    @Test
    fun testDebugLoggingConfigSerialization() {
        val restored = Gson().fromJson(
            Gson().toJson(AcceleratorConfig(enableDebugLogging = true)),
            AcceleratorConfig::class.java
        )
        assertTrue(restored.enableDebugLogging)
        assertFalse(AcceleratorConfig().enableDebugLogging)
    }

    @Test
    fun testDebugEventBufferDropsEventsWhenDisabled() {
        DebugEventBuffer.setEnabled(false)
        try {
            DebugEventBuffer.record("TEST", "must be dropped")
            assertTrue(DebugEventBuffer.snapshot().events.isEmpty())

            DebugEventBuffer.setEnabled(true)
            DebugEventBuffer.record("TEST", "kept while enabled")
            assertTrue(DebugEventBuffer.snapshot().events.any { it.message == "kept while enabled" })

            DebugEventBuffer.setEnabled(false)
            assertFalse(DebugEventBuffer.isEnabled)
            assertTrue(DebugEventBuffer.snapshot().events.isEmpty())
        } finally {
            DebugEventBuffer.setEnabled(false)
        }
    }

    @Test
    fun testDebugReportFileNameIsStableAndSafe() {
        val name = DebugReportExporter.createFileName(1_760_000_000_000L)
        assertTrue(name.startsWith("BiliAccelerator-debug-"))
        assertTrue(name.endsWith(".txt"))
        assertTrue(name.matches(Regex("""BiliAccelerator-debug-\d{8}-\d{6}-\d{3}\.txt""")))
    }
}
