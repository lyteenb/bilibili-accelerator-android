package io.github.jh_mmm.biliaccelerator.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.jh_mmm.biliaccelerator.BuildConfig
import io.github.jh_mmm.biliaccelerator.R
import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import io.github.jh_mmm.biliaccelerator.core.DebugReportBuilder
import io.github.jh_mmm.biliaccelerator.core.DebugReportEnvironment
import io.github.jh_mmm.biliaccelerator.core.DebugEventBuffer
import io.github.jh_mmm.biliaccelerator.core.DebugReportExporter
import io.github.jh_mmm.biliaccelerator.core.RewriteLogEntry
import io.github.jh_mmm.biliaccelerator.core.StatsManager
import io.github.jh_mmm.biliaccelerator.core.StatsSnapshot
import io.github.jh_mmm.biliaccelerator.provider.StatsProvider
import io.github.jh_mmm.biliaccelerator.provider.XposedServiceProvider
import io.github.jh_mmm.biliaccelerator.ui.navigation.AcceleratorApp
import io.github.jh_mmm.biliaccelerator.ui.theme.AcceleratorTheme
import io.github.jh_mmm.biliaccelerator.ui.theme.UiPreferencesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var uiPreferencesStore: UiPreferencesStore

    private val currentConfig = MutableStateFlow(AcceleratorConfig())
    private val currentSnapshot = MutableStateFlow(StatsSnapshot())
    private val currentActivationState = MutableStateFlow(ActivationState.INACTIVE)

    private var pendingDebugReportFileName: String? = null
    private var pendingDebugReportFilePath: String? = null
    private var debugReportExportInProgress: Boolean = false

    private val createDebugReportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val fileName = pendingDebugReportFileName
        val reportFile = pendingDebugReportFilePath?.let(::File)
        pendingDebugReportFileName = null
        pendingDebugReportFilePath = null
        debugReportExportInProgress = false
        if (uri == null || reportFile == null) {
            reportFile?.let(DebugReportExporter::deleteCacheFile)
            return@registerForActivityResult
        }
        runCatching {
            DebugReportExporter.copyCacheFileToUri(this@MainActivity, uri, reportFile)
        }.onSuccess {
            DebugReportExporter.deleteCacheFile(reportFile)
            Toast.makeText(
                this@MainActivity,
                getString(R.string.toast_debug_report_saved, fileName ?: "Download"),
                Toast.LENGTH_LONG
            ).show()
        }.onFailure { error ->
            DebugReportExporter.deleteCacheFile(reportFile)
            Toast.makeText(
                this@MainActivity,
                getString(R.string.toast_debug_report_failed, error.message ?: "unknown"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStats()
            refreshHandler.postDelayed(this, 2000L)
        }
    }

    enum class ActivationState {
        /** 已生效：模块已激活且已实际产生视频流重定向（totalRewrites > 0） */
        ACTIVE_EFFECTIVE,

        /** 已激活：当前进程已收到目标应用近期心跳，等待新的视频流量或重写 */
        ACTIVE_HEARTBEAT,

        /** 框架已连接：Service 和新鲜作用域存在，但尚未收到当前 B站会话心跳，不能确认模块开关已真正生效 */
        FRAMEWORK_READY,

        /** 未激活：未检测到框架激活、服务绑定或当前通信回执 */
        INACTIVE;

        val isActive: Boolean
            get() = this == ACTIVE_EFFECTIVE || this == ACTIVE_HEARTBEAT
    }

    companion object {
        const val DEFAULT_HEARTBEAT_THRESHOLD_MS = 2L * 60 * 1000L // 仅认可当前进程内的近期心跳
        const val SCOPE_QUERY_FRESHNESS_MS = 60L * 1000L // 作用域结果必须来自近期查询
        private const val KEY_PENDING_DEBUG_REPORT_NAME = "pending_debug_report_name"
        private const val KEY_PENDING_DEBUG_REPORT_PATH = "pending_debug_report_path"

        /**
         * 经典 Xposed / LSPosed 模块自检测入口。
         * 默认返回 false；当模块自身处于作用域中并被框架成功注入加载时，
         * HookEntry 会将此方法 hook 替换返回 true。
         */
        @Keep
        @JvmStatic
        fun isModuleActive(): Boolean = false

        fun isScopeQueryFresh(
            scopeQueriedAt: Long,
            now: Long = System.currentTimeMillis(),
            maxAgeMs: Long = SCOPE_QUERY_FRESHNESS_MS
        ): Boolean {
            if (scopeQueriedAt <= 0L) return false
            return (now - scopeQueriedAt) in 0..maxAgeMs
        }

        fun calculateActivationState(
            totalRewrites: Long,
            isServiceBound: Boolean,
            lastHeartbeat: Long,
            // Diagnostic only: a stale self-injection flag must never grant current activation.
            isSelfInjected: Boolean = false,
            hasFrameworkScope: Boolean = true,
            hasRuntimeHeartbeat: Boolean = false,
            lastRewriteTimestamp: Long = 0L,
            now: Long = System.currentTimeMillis(),
            heartbeatThresholdMs: Long = DEFAULT_HEARTBEAT_THRESHOLD_MS
        ): ActivationState {
            val diff = now - lastHeartbeat
            val hasRecentHeartbeat = hasRuntimeHeartbeat &&
                    lastHeartbeat > 0L &&
                    diff in 0..heartbeatThresholdMs

            // 当前有效的框架凭据：
            // 1. 现代 LibXposed 服务当前在线，且作用域包含目标应用。
            // 2. 当前模块进程本次启动后确实收到过目标宿主的近期心跳。
            // 历史累计重写数、持久化心跳和旧的自注入状态都不能单独证明当前已生效。
            val isBoundWithScope = isServiceBound && hasFrameworkScope
            val rewriteDiff = now - lastRewriteTimestamp
            val hasRecentRewrite = hasRuntimeHeartbeat &&
                    totalRewrites > 0L &&
                    lastRewriteTimestamp > 0L &&
                    rewriteDiff in 0..heartbeatThresholdMs

            return when {
                hasRecentRewrite -> ActivationState.ACTIVE_EFFECTIVE
                hasRecentHeartbeat -> ActivationState.ACTIVE_HEARTBEAT
                isBoundWithScope -> ActivationState.FRAMEWORK_READY
                else -> ActivationState.INACTIVE
            }
        }
    }

    fun getActivationState(): ActivationState {
        val snapshot = StatsManager.getSnapshot()
        val isSelfInjected = isModuleActive() || StatsManager.isFrameworkSelfInjected

        // 当前服务绑定必须同时满足“近期查询过作用域且包含 B站”。
        // 未完成或过期的作用域不能继续作为当前激活凭据。
        val now = System.currentTimeMillis()
        val hasFreshScope = isScopeQueryFresh(snapshot.scopeQueriedAt, now)
        val hasFrameworkScope = snapshot.hasQueriedScope &&
                snapshot.hasTargetScope &&
                hasFreshScope

        return calculateActivationState(
            totalRewrites = snapshot.totalRewrites,
            isServiceBound = XposedServiceProvider.isServiceBound,
            lastHeartbeat = snapshot.lastHeartbeatTimestamp,
            isSelfInjected = isSelfInjected,
            hasFrameworkScope = hasFrameworkScope,
            hasRuntimeHeartbeat = snapshot.hasRuntimeHeartbeat,
            lastRewriteTimestamp = snapshot.lastRewriteTimestamp,
            now = now
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingDebugReportFileName = savedInstanceState?.getString(KEY_PENDING_DEBUG_REPORT_NAME)
        pendingDebugReportFilePath = savedInstanceState?.getString(KEY_PENDING_DEBUG_REPORT_PATH)
        debugReportExportInProgress = !pendingDebugReportFilePath.isNullOrBlank()

        StatsManager.init(this)
        uiPreferencesStore = UiPreferencesStore(this)

        val loadedConfig = StatsProvider.loadConfig(this)
        DebugEventBuffer.setEnabled(loadedConfig.enableDebugLogging)
        currentConfig.value = loadedConfig

        val uposEntries = resources.getStringArray(R.array.upos_entries).toList()
        val uposValues = resources.getStringArray(R.array.upos_values).toList()

        val (versionName, versionCode) = try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
            (pInfo.versionName ?: BuildConfig.VERSION_NAME) to code
        } catch (_: Exception) {
            BuildConfig.VERSION_NAME to BuildConfig.VERSION_CODE.toLong()
        }

        refreshStats()

        setContent {
            val uiPreferences by uiPreferencesStore.preferences.collectAsStateWithLifecycle()
            val state by currentActivationState.collectAsStateWithLifecycle()
            val snapshot by currentSnapshot.collectAsStateWithLifecycle()
            val config by currentConfig.collectAsStateWithLifecycle()

            AcceleratorTheme(preferences = uiPreferences) {
                AcceleratorApp(
                    state = state,
                    snapshot = snapshot,
                    config = config,
                    onConfigChange = { newConfig ->
                        currentConfig.value = newConfig
                        DebugEventBuffer.setEnabled(newConfig.enableDebugLogging)
                        StatsProvider.saveConfig(this@MainActivity, newConfig)
                        DebugEventBuffer.record(
                            "CONFIG",
                            "updated enabled=${newConfig.enabled} blockPcdn=${newConfig.blockPcdn} " +
                                "btr=${newConfig.enableBtr} target=${newConfig.targetHost}"
                        )
                    },
                    uiPreferences = uiPreferences,
                    onUiPreferencesChange = { newPrefs ->
                        if (newPrefs.style != uiPreferences.style) {
                            uiPreferencesStore.updateStyle(newPrefs.style)
                        }
                        if (newPrefs.themeMode != uiPreferences.themeMode) {
                            uiPreferencesStore.updateThemeMode(newPrefs.themeMode)
                        }
                    },
                    onClearStats = {
                        StatsManager.clearStats(this@MainActivity)
                        refreshStats()
                        Toast.makeText(this@MainActivity, R.string.toast_stats_cleared, Toast.LENGTH_SHORT).show()
                    },
                    onExportDiagnostic = {
                        val report = StatsManager.buildDiagnosticReport(currentConfig.value, "$versionName ($versionCode)")
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("BiliAccelerator Diagnostic", report))
                        Toast.makeText(this@MainActivity, R.string.toast_diag_copied, Toast.LENGTH_SHORT).show()
                    },
                    onDownloadDebugReport = {
                        downloadDebugReport(versionName, versionCode)
                    },
                    onRefresh = {
                        refreshStats()
                    },
                    onLogClick = { logEntry ->
                        copyLogToClipboard(logEntry)
                    },
                    uposEntries = uposEntries,
                    uposValues = uposValues,
                    versionName = versionName,
                    versionCode = versionCode
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        DebugEventBuffer.record("UI", "MainActivity resumed")
        XposedServiceProvider.triggerScopeQueryAsync(force = true)
        refreshStats()
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshHandler.postDelayed(refreshRunnable, 2000L)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingDebugReportFileName?.let { outState.putString(KEY_PENDING_DEBUG_REPORT_NAME, it) }
        pendingDebugReportFilePath?.let { outState.putString(KEY_PENDING_DEBUG_REPORT_PATH, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    private fun refreshStats() {
        // 每隔一段时间重新向框架查询作用域，避免 LSPosed 中关闭模块后
        // 继续使用上一次查询的旧作用域状态。
        XposedServiceProvider.triggerScopeQueryAsync(force = false)
        val snapshot = StatsManager.getSnapshot()
        currentSnapshot.value = snapshot
        currentActivationState.value = getActivationState()
    }

    private fun downloadDebugReport(versionName: String, versionCode: Long) {
        if (!currentConfig.value.enableDebugLogging) {
            Toast.makeText(this, R.string.toast_debug_report_disabled, Toast.LENGTH_SHORT).show()
            return
        }
        if (debugReportExportInProgress) {
            Toast.makeText(this, R.string.toast_debug_report_in_progress, Toast.LENGTH_SHORT).show()
            return
        }
        debugReportExportInProgress = true
        lifecycleScope.launch {
            val fileName = DebugReportExporter.createFileName()
            DebugEventBuffer.record("EXPORT", "detailed debug report generation started name=$fileName")
            val cacheFileResult = withContext(Dispatchers.IO) {
                runCatching {
                    val report = buildDetailedDebugReport(versionName, versionCode)
                    val file = DebugReportExporter.writeCacheFile(this@MainActivity, fileName, report)
                    DebugEventBuffer.record(
                        "EXPORT",
                        "detailed debug report ready name=$fileName bytes=${report.toByteArray(Charsets.UTF_8).size}"
                    )
                    file
                }
            }
            val cacheFile = cacheFileResult.getOrElse { error ->
                debugReportExportInProgress = false
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.toast_debug_report_failed, error.message ?: "unknown"),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val directResult = withContext(Dispatchers.IO) {
                    runCatching {
                        DebugReportExporter.saveFileToDownloads(this@MainActivity, fileName, cacheFile)
                    }
                }
                if (directResult.isSuccess) {
                    withContext(Dispatchers.IO) { DebugReportExporter.deleteCacheFile(cacheFile) }
                    debugReportExportInProgress = false
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.toast_debug_report_saved, "Download/$fileName"),
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
            }

            pendingDebugReportFileName = fileName
            pendingDebugReportFilePath = cacheFile.absolutePath
            runCatching {
                createDebugReportLauncher.launch(fileName)
            }.onFailure { error ->
                pendingDebugReportFileName = null
                pendingDebugReportFilePath = null
                debugReportExportInProgress = false
                DebugReportExporter.deleteCacheFile(cacheFile)
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.toast_debug_report_failed, error.message ?: "unknown"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun buildDetailedDebugReport(versionName: String, versionCode: Long): String {
        val snapshot = StatsManager.getSnapshot()
        val state = getActivationState()
        val isScopeFresh = isScopeQueryFresh(snapshot.scopeQueriedAt)
        val environment = DebugReportEnvironment.current(
            appVersion = versionName,
            versionCode = versionCode,
            activationState = state.name,
            isServiceBound = XposedServiceProvider.isServiceBound,
            isScopeFresh = isScopeFresh,
            heartbeatThresholdMs = DEFAULT_HEARTBEAT_THRESHOLD_MS
        )
        return DebugReportBuilder.build(snapshot, currentConfig.value, environment)
    }

    private fun copyLogToClipboard(entry: RewriteLogEntry) {
        val detail = "时间: ${entry.timeFormatted}\n来源: ${BiliAcceleratorCore.formatHostForDisplay(entry.originalHost)}\n重定向至: ${entry.targetHost}\n策略: ${entry.reason}"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Rewrite Log", detail))
        Toast.makeText(this, R.string.toast_log_copied, Toast.LENGTH_SHORT).show()
    }
}
