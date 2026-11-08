package io.github.jh_mmm.biliaccelerator.core

import android.app.Application
import android.os.Build
import android.os.Process
import io.github.jh_mmm.biliaccelerator.BuildConfig
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class DebugReportEnvironment(
    val generatedAt: Long,
    val appVersion: String,
    val versionCode: Long,
    val packageName: String,
    val debugBuild: Boolean,
    val androidRelease: String,
    val sdkInt: Int,
    val securityPatch: String?,
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val product: String,
    val hardware: String,
    val supportedAbis: List<String>,
    val locale: String,
    val timezone: String,
    val processName: String,
    val pid: Int,
    val uid: Int,
    val activationState: String,
    val isServiceBound: Boolean,
    val isScopeFresh: Boolean,
    val heartbeatThresholdMs: Long
) {
    companion object {
        fun current(
            appVersion: String,
            versionCode: Long,
            activationState: String,
            isServiceBound: Boolean,
            isScopeFresh: Boolean,
            heartbeatThresholdMs: Long,
            now: Long = System.currentTimeMillis()
        ): DebugReportEnvironment {
            val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { Application.getProcessName() }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: BuildConfig.APPLICATION_ID
            } else {
                BuildConfig.APPLICATION_ID
            }
            return DebugReportEnvironment(
                generatedAt = now,
                appVersion = appVersion,
                versionCode = versionCode,
                packageName = BuildConfig.APPLICATION_ID,
                debugBuild = BuildConfig.DEBUG,
                androidRelease = Build.VERSION.RELEASE ?: "unknown",
                sdkInt = Build.VERSION.SDK_INT,
                securityPatch = Build.VERSION.SECURITY_PATCH,
                manufacturer = Build.MANUFACTURER ?: "unknown",
                brand = Build.BRAND ?: "unknown",
                model = Build.MODEL ?: "unknown",
                device = Build.DEVICE ?: "unknown",
                product = Build.PRODUCT ?: "unknown",
                hardware = Build.HARDWARE ?: "unknown",
                supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
                locale = Locale.getDefault().toLanguageTag(),
                timezone = ZoneId.systemDefault().id,
                processName = processName,
                pid = Process.myPid(),
                uid = Process.myUid(),
                activationState = activationState,
                isServiceBound = isServiceBound,
                isScopeFresh = isScopeFresh,
                heartbeatThresholdMs = heartbeatThresholdMs
            )
        }
    }
}

object DebugReportBuilder {

    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun build(
        snapshot: StatsSnapshot,
        config: AcceleratorConfig,
        environment: DebugReportEnvironment,
        debugEvents: DebugEventSnapshot = DebugEventBuffer.snapshot()
    ): String {
        val sb = StringBuilder(32 * 1024)
        val generated = formatTimestamp(environment.generatedAt)
        val heartbeatAge = ageOf(snapshot.lastHeartbeatTimestamp, environment.generatedAt)
        val rewriteAge = ageOf(snapshot.lastRewriteTimestamp, environment.generatedAt)
        val scopeAge = ageOf(snapshot.scopeQueriedAt, environment.generatedAt)
        val heartbeatFresh = snapshot.hasRuntimeHeartbeat &&
                heartbeatAge != null &&
                heartbeatAge in 0..environment.heartbeatThresholdMs
        val rewriteFresh = snapshot.hasRuntimeHeartbeat &&
                rewriteAge != null &&
                rewriteAge in 0..environment.heartbeatThresholdMs
        val runtime = Runtime.getRuntime()

        sb.appendLine("============================================================")
        sb.appendLine(" Bili Accelerator Android - DETAILED DEBUG REPORT")
        sb.appendLine("============================================================")
        sb.appendLine("报告格式版本: 2")
        sb.appendLine("生成时间: $generated")
        sb.appendLine("说明: 本报告不包含完整媒体 URL 或鉴权 token，仅包含 Host、状态和统计字段。")
        sb.appendLine()

        sb.appendLine("【应用信息】")
        sb.appendLine("包名: ${environment.packageName}")
        sb.appendLine("版本: ${environment.appVersion} (${environment.versionCode})")
        sb.appendLine("构建类型: ${if (environment.debugBuild) "debug" else "release"}")
        sb.appendLine("当前进程: ${environment.processName}")
        sb.appendLine("PID / UID: ${environment.pid} / ${environment.uid}")
        sb.appendLine("JVM 内存: used=${(runtime.totalMemory() - runtime.freeMemory()) / 1024} KiB, max=${runtime.maxMemory() / 1024} KiB")
        sb.appendLine()

        sb.appendLine("【系统环境】")
        sb.appendLine("Android: ${environment.androidRelease} (API ${environment.sdkInt})")
        sb.appendLine("系统安全补丁: ${environment.securityPatch ?: "unknown"}")
        sb.appendLine("厂商 / 品牌: ${environment.manufacturer} / ${environment.brand}")
        sb.appendLine("型号 / device / product / hardware: ${environment.model} / ${environment.device} / ${environment.product} / ${environment.hardware}")
        sb.appendLine("ABI: ${environment.supportedAbis.joinToString(", ")}")
        sb.appendLine("Locale / TimeZone: ${environment.locale} / ${environment.timezone}")
        sb.appendLine()

        sb.appendLine("【激活判定与框架状态】")
        sb.appendLine("当前状态: ${environment.activationState}")
        sb.appendLine("框架 Service 当前绑定: ${environment.isServiceBound}")
        sb.appendLine("模块自注入标记: ${snapshot.isFrameworkActivated}")
        sb.appendLine("本次进程收到过心跳: ${snapshot.hasRuntimeHeartbeat}")
        sb.appendLine("最近心跳时间: ${formatTimestampOrNone(snapshot.lastHeartbeatTimestamp)} (age=${formatAge(heartbeatAge)}, fresh=$heartbeatFresh)")
        sb.appendLine("最近真实重写时间: ${formatTimestampOrNone(snapshot.lastRewriteTimestamp)} (age=${formatAge(rewriteAge)}, fresh=$rewriteFresh)")
        sb.appendLine("作用域查询时间: ${formatTimestampOrNone(snapshot.scopeQueriedAt)} (age=${formatAge(scopeAge)}, fresh=${environment.isScopeFresh})")
        sb.appendLine("作用域已查询 / 包含 B站: ${snapshot.hasQueriedScope} / ${snapshot.hasTargetScope}")
        sb.appendLine("LSPosed 作用域: ${if (snapshot.frameworkScope.isEmpty()) "<empty>" else snapshot.frameworkScope.joinToString(", ")}")
        sb.appendLine("宿主包 / 版本 / 进程: ${snapshot.clientPackage ?: "<none>"} / ${snapshot.clientVersion ?: "<none>"} / ${snapshot.clientProcess ?: "<none>"}")
        sb.appendLine("判定规则: 已生效必须同时满足本次进程近期心跳 + 近期真实重写；历史心跳、历史重写和旧自注入均不作为当前生效依据。")
        sb.appendLine()

        sb.appendLine("【完整配置快照】")
        sb.appendLine("enabled=${config.enabled}")
        sb.appendLine("targetHost=${config.targetHost}")
        sb.appendLine("proxyHost=${config.proxyHost}")
        sb.appendLine("blockPcdn=${config.blockPcdn}")
        sb.appendLine("proxyMcdn=${config.proxyMcdn}")
        sb.appendLine("mcdnStrategy=${config.mcdnStrategy}")
        sb.appendLine("forceUpos=${config.forceUpos}")
        sb.appendLine("rewriteAkamai=${config.rewriteAkamai}")
        sb.appendLine("portHeuristic=${config.portHeuristic}")
        sb.appendLine("enableMossHook=${config.enableMossHook}")
        sb.appendLine("enableBtr=${config.enableBtr}")
        sb.appendLine("btrAutoConcurrency=${config.btrAutoConcurrency}")
        sb.appendLine("btrConcurrency=${config.btrConcurrency}")
        sb.appendLine("btrAutoChunkSize=${config.btrAutoChunkSize}")
        sb.appendLine("btrChunkSizeKb=${config.btrChunkSizeKb}")
        sb.appendLine("btrCdnPoolSize=${config.btrCdnPoolSize}")
        sb.appendLine("enableDebugLogging=${config.enableDebugLogging}")
        sb.appendLine("customCdnPool=${if (config.customCdnPool.isEmpty()) "<empty>" else config.customCdnPool.joinToString(", ")}")
        sb.appendLine("直播策略: 完全直通，不执行 PCDN Host 替换，不进入 BTR")
        sb.appendLine("PCDN 策略: 仅作用于点播流")
        sb.appendLine()

        sb.appendLine("【运行时事件时间线】 count=${debugEvents.events.size} dropped=${debugEvents.droppedCount}")
        if (debugEvents.events.isEmpty()) {
            sb.appendLine("<empty>")
        } else {
            debugEvents.events.forEach { event ->
                sb.appendLine("[${formatTimestamp(event.timestamp)}] [${event.category}] ${event.message}")
            }
        }
        sb.appendLine()

        sb.appendLine("【Hook 挂载状态】")
        if (snapshot.hookStatus.isEmpty()) {
            sb.appendLine("<empty>")
        } else {
            snapshot.hookStatus.toSortedMap().forEach { (key, value) ->
                sb.appendLine("$key = $value")
            }
        }
        sb.appendLine()

        sb.appendLine("【统计总览】")
        sb.appendLine("totalRequests=${snapshot.totalRequests}")
        sb.appendLine("totalRewrites=${snapshot.totalRewrites}")
        sb.appendLine("rewriteRatio=${if (snapshot.totalRequests > 0L) String.format(Locale.US, "%.2f%%", snapshot.totalRewrites * 100.0 / snapshot.totalRequests) else "n/a"}")
        sb.appendLine("pcdnBlocked=${snapshot.pcdnBlocked}")
        sb.appendLine("mcdnProxied=${snapshot.mcdnProxied}")
        sb.appendLine("safeFallbackCount=${snapshot.safeFallbackCount}")
        sb.appendLine("btrTotalChunks=${snapshot.btrTotalChunks}")
        sb.appendLine("btrTotalBytes=${snapshot.btrTotalBytes}")
        sb.appendLine("btrAvgBytesPerChunk=${if (snapshot.btrTotalChunks > 0L) snapshot.btrTotalBytes / snapshot.btrTotalChunks else 0L}")
        sb.appendLine("btrFailoverCount=${snapshot.btrFailoverCount}")
        sb.appendLine("avoidedHostCount=${snapshot.avoidedHosts.size}")
        sb.appendLine("installedAt=${snapshot.installedAt}")
        sb.appendLine()

        sb.appendLine("【规避/惩罚 Host 列表】")
        if (snapshot.avoidedHosts.isEmpty()) {
            sb.appendLine("<empty>")
        } else {
            snapshot.avoidedHosts.forEachIndexed { index, host ->
                sb.appendLine("${index + 1}. ${BiliAcceleratorCore.formatHostForDisplay(host)}")
            }
        }
        sb.appendLine()

        sb.appendLine("【最近重写/重定向日志】 count=${snapshot.recentLogs.size}")
        if (snapshot.recentLogs.isEmpty()) {
            sb.appendLine("<empty>")
        } else {
            snapshot.recentLogs.forEachIndexed { index, log ->
                sb.appendLine()
                sb.appendLine("#${index + 1} [${log.timeFormatted}] id=${log.id}")
                sb.appendLine("  reason=${log.reason}")
                sb.appendLine("  source=${BiliAcceleratorCore.formatHostForDisplay(log.originalHost)}")
                sb.appendLine("  target=${log.targetHost}")
                sb.appendLine("  flags: pcdn=${log.isPcdn}, mcdn=${log.isMcdn}, btr=${log.isBtr}")
                sb.appendLine("  btr: chunks=${log.btrChunks}, bytes=${log.btrBytes}, concurrency=${log.btrConcurrency}, cdnCount=${log.btrCdnCount}, avgLatencyMs=${log.btrAvgLatencyMs}")
            }
        }
        sb.appendLine()
        sb.appendLine("============================================================")
        sb.appendLine(" END OF DEBUG REPORT")
        sb.appendLine("============================================================")
        return sb.toString()
    }

    private fun ageOf(timestamp: Long, now: Long): Long? {
        if (timestamp <= 0L) return null
        return now - timestamp
    }

    private fun formatAge(age: Long?): String {
        return if (age == null) "n/a" else "${age}ms"
    }

    private fun formatTimestampOrNone(timestamp: Long): String {
        return if (timestamp > 0L) formatTimestamp(timestamp) else "<none>"
    }

    private fun formatTimestamp(timestamp: Long): String {
        return LocalDateTimeCompat.format(timestamp)
    }

    private object LocalDateTimeCompat {
        fun format(timestamp: Long): String {
            return java.time.LocalDateTime.ofInstant(
                Instant.ofEpochMilli(timestamp),
                ZoneId.systemDefault()
            ).format(timestampFormatter)
        }
    }
}
