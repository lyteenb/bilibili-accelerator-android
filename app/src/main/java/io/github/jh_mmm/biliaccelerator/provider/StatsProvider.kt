package io.github.jh_mmm.biliaccelerator.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.jh_mmm.biliaccelerator.BuildConfig
import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import io.github.jh_mmm.biliaccelerator.core.IpcSecurity
import io.github.jh_mmm.biliaccelerator.core.RewriteResult
import io.github.jh_mmm.biliaccelerator.core.StatsManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 传输层 DTO，防范反射反序列化导致的不可信字段与 NPE，且绝不接收完整签名 URL（仅传输裸域名）
 */
data class RewriteResultDto(
    val changed: Boolean? = false,
    val originalHost: String? = null,
    val targetHost: String? = null,
    val reason: String? = null,
    val isPcdn: Boolean? = false,
    val isMcdn: Boolean? = false,
    val segmentKey: String? = null,
    val isBtr: Boolean? = false,
    val btrChunks: Int? = 0,
    val btrBytes: Long? = 0L,
    val btrConcurrency: Int? = 0,
    val btrCdnCount: Int? = 0,
    val btrAvgLatencyMs: Long? = 0L
) {
    fun toDomain(): RewriteResult {
        val origHost = IpcSecurity.sanitizeSingleLine(originalHost, 128) ?: ""
        val tgtHost = IpcSecurity.sanitizeSingleLine(targetHost, 128) ?: origHost
        val safeSegKey = IpcSecurity.sanitizeSingleLine(
            segmentKey?.substringBefore('?')?.substringAfterLast('/'),
            96
        ) ?: ""
        return RewriteResult(
            changed = changed ?: false,
            originalUrl = "",
            finalUrl = "",
            originalHost = origHost,
            targetHost = tgtHost,
            reason = IpcSecurity.sanitizeSingleLine(reason, 128) ?: "unknown",
            isPcdn = isPcdn ?: false,
            isMcdn = isMcdn ?: false,
            segmentKey = safeSegKey,
            isBtr = isBtr ?: false,
            btrChunks = (btrChunks ?: 0).coerceIn(0, 1_000_000),
            btrBytes = (btrBytes ?: 0L).coerceIn(0L, 1L shl 40),
            btrConcurrency = (btrConcurrency ?: 0).coerceIn(0, 64),
            btrCdnCount = (btrCdnCount ?: 0).coerceIn(0, 64),
            btrAvgLatencyMs = (btrAvgLatencyMs ?: 0L).coerceIn(0L, 600_000L)
        )
    }
}

class StatsProvider : ContentProvider() {

    companion object {
        val AUTHORITY = "${BuildConfig.APPLICATION_ID}.provider"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY")

        const val METHOD_RECORD_BATCH_REWRITE = "recordBatchRewrite"
        const val METHOD_GET_CONFIG = "getConfig"
        const val METHOD_HANDSHAKE = "handshake"

        const val EXTRA_REWRITE_BATCH_JSON = "extra_rewrite_batch_json"
        const val EXTRA_CONFIG_JSON = "extra_config_json"
        const val EXTRA_HOOK_STATUS_JSON = "extra_hook_status_json"
        const val EXTRA_CLIENT_PACKAGE = "extra_client_package"
        const val EXTRA_CLIENT_VERSION = "extra_client_version"
        const val EXTRA_CLIENT_PROCESS = "extra_client_process"
        const val EXTRA_SUCCESS = "success"

        const val MAX_BATCH_SIZE = 200
        const val MAX_HOOK_STATUS_ENTRIES = 50

        const val PREFS_CONFIG = "bili_accelerator_config"
        const val KEY_ENABLED = "cfg_enabled"
        const val KEY_TARGET_HOST = "cfg_target_host"
        const val KEY_PROXY_HOST = "cfg_proxy_host"
        const val KEY_BLOCK_PCDN = "cfg_block_pcdn"
        const val KEY_PROXY_MCDN = "cfg_proxy_mcdn"
        const val KEY_MCDN_STRATEGY = "cfg_mcdn_strategy"
        const val KEY_FORCE_UPOS = "cfg_force_upos"
        const val KEY_REWRITE_AKAMAI = "cfg_rewrite_akamai"
        const val KEY_PORT_HEURISTIC = "cfg_port_heuristic"
        const val KEY_ENABLE_MOSS_HOOK = "cfg_enable_moss_hook"
        const val KEY_ENABLE_BTR = "cfg_enable_btr"
        const val KEY_BTR_AUTO_CONCURRENCY = "cfg_btr_auto_concurrency"
        const val KEY_BTR_CONCURRENCY = "cfg_btr_concurrency"
        const val KEY_BTR_AUTO_CHUNK_SIZE = "cfg_btr_auto_chunk_size"
        const val KEY_BTR_CHUNK_SIZE_KB = "cfg_btr_chunk_size_kb"
        const val KEY_CUSTOM_CDN_POOL = "cfg_custom_cdn_pool"
        const val KEY_BTR_CDN_POOL_SIZE = "cfg_btr_cdn_pool_size"
        const val KEY_ENABLE_EPISODE_ACCELERATION = "cfg_enable_episode_acceleration"
        const val KEY_ENABLE_DEBUG_LOGGING = "cfg_enable_debug_logging"

        private val gson = Gson()
        private val debugSettingInitialized = AtomicBoolean(false)

        private fun log(msg: String) {
            Log.i("BiliAccelerator-Stats", msg)
        }

        fun isCallerAuthorized(context: Context, uid: Int): Boolean {
            return IpcSecurity.resolveTrustedPackage(context, uid) != null
        }

        fun loadConfig(context: Context): AcceleratorConfig {
            val sp = context.getSharedPreferences(PREFS_CONFIG, Context.MODE_PRIVATE)
            val customCdnJson = sp.getString(KEY_CUSTOM_CDN_POOL, null)
            val customPool: List<String> = if (!customCdnJson.isNullOrBlank()) {
                runCatching {
                    val type = object : TypeToken<List<String>>() {}.type
                    gson.fromJson<List<String>>(customCdnJson, type)
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }

            val strategy = sp.getString(KEY_MCDN_STRATEGY, "proxy-all")?.lowercase()
                ?.takeIf { it in setOf("proxy-all", "proxy-v1", "replace") }
                ?: "proxy-all"
            val rawTarget = sp.getString(KEY_TARGET_HOST, BiliAcceleratorCore.defaultTargetHost())
                ?: BiliAcceleratorCore.defaultTargetHost()
            val cleanTarget = BiliAcceleratorCore.cleanHost(rawTarget).substringBefore(':').lowercase()
            val targetHost = if (BiliAcceleratorCore.isBiliCdnHost(cleanTarget) ||
                BiliAcceleratorCore.isOfficialBiliHost(cleanTarget)
            ) {
                cleanTarget
            } else {
                BiliAcceleratorCore.defaultTargetHost()
            }

            val config = AcceleratorConfig(
                enabled = sp.getBoolean(KEY_ENABLED, true),
                targetHost = targetHost,
                proxyHost = sp.getString(KEY_PROXY_HOST, "proxy-tf-all-ws.bilivideo.com")
                    ?: "proxy-tf-all-ws.bilivideo.com",
                blockPcdn = sp.getBoolean(KEY_BLOCK_PCDN, true),
                proxyMcdn = sp.getBoolean(KEY_PROXY_MCDN, true),
                mcdnStrategy = strategy,
                forceUpos = sp.getBoolean(KEY_FORCE_UPOS, false),
                rewriteAkamai = sp.getBoolean(KEY_REWRITE_AKAMAI, false),
                portHeuristic = sp.getBoolean(KEY_PORT_HEURISTIC, true),
                enableMossHook = sp.getBoolean(KEY_ENABLE_MOSS_HOOK, false),
                enableBtr = sp.getBoolean(KEY_ENABLE_BTR, true),
                btrAutoConcurrency = sp.getBoolean(KEY_BTR_AUTO_CONCURRENCY, true),
                btrConcurrency = sp.getInt(KEY_BTR_CONCURRENCY, 6).coerceIn(2, 16),
                btrAutoChunkSize = sp.getBoolean(KEY_BTR_AUTO_CHUNK_SIZE, true),
                btrChunkSizeKb = sp.getInt(KEY_BTR_CHUNK_SIZE_KB, 512).coerceIn(128, 2048),
                customCdnPool = customPool
                    .filter { BiliAcceleratorCore.isBiliCdnHost(it) && !BiliAcceleratorCore.isAkamaiHost(it) }
                    .distinct()
                    .take(32),
                btrCdnPoolSize = sp.getInt(KEY_BTR_CDN_POOL_SIZE, 8).coerceIn(1, 32),
                enableEpisodeAcceleration = sp.getBoolean(KEY_ENABLE_EPISODE_ACCELERATION, true),
                enableDebugLogging = sp.getBoolean(KEY_ENABLE_DEBUG_LOGGING, false)
            )
            return config.copy(proxyHost = BiliAcceleratorCore.resolveProxyHost(config))
        }

        fun saveConfig(context: Context, config: AcceleratorConfig) {
            val sp = context.getSharedPreferences(PREFS_CONFIG, Context.MODE_PRIVATE)
            sp.edit()
                .putBoolean(KEY_ENABLED, config.enabled)
                .putString(KEY_TARGET_HOST, config.targetHost)
                .putString(KEY_PROXY_HOST, config.proxyHost)
                .putBoolean(KEY_BLOCK_PCDN, config.blockPcdn)
                .putBoolean(KEY_PROXY_MCDN, config.proxyMcdn)
                .putString(KEY_MCDN_STRATEGY, config.mcdnStrategy)
                .putBoolean(KEY_FORCE_UPOS, config.forceUpos)
                .putBoolean(KEY_REWRITE_AKAMAI, config.rewriteAkamai)
                .putBoolean(KEY_PORT_HEURISTIC, config.portHeuristic)
                .putBoolean(KEY_ENABLE_MOSS_HOOK, config.enableMossHook)
                .putBoolean(KEY_ENABLE_BTR, config.enableBtr)
                .putBoolean(KEY_BTR_AUTO_CONCURRENCY, config.btrAutoConcurrency)
                .putInt(KEY_BTR_CONCURRENCY, config.btrConcurrency)
                .putBoolean(KEY_BTR_AUTO_CHUNK_SIZE, config.btrAutoChunkSize)
                .putInt(KEY_BTR_CHUNK_SIZE_KB, config.btrChunkSizeKb)
                .putString(KEY_CUSTOM_CDN_POOL, gson.toJson(config.customCdnPool))
                .putInt(KEY_BTR_CDN_POOL_SIZE, config.btrCdnPoolSize)
                .putBoolean(KEY_ENABLE_EPISODE_ACCELERATION, config.enableEpisodeAcceleration)
                .putBoolean(KEY_ENABLE_DEBUG_LOGGING, config.enableDebugLogging)
                .apply()
            io.github.jh_mmm.biliaccelerator.core.DebugEventBuffer.setEnabled(config.enableDebugLogging)
            runCatching {
                context.contentResolver.notifyChange(CONTENT_URI, null)
            }
        }
    }

    override fun onCreate(): Boolean {
        // 轻量化启动：不在 onCreate 阶段预热 StatsManager，避免未授权调用触发冷启动 I/O 放大
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context
        if (ctx == null) {
            log("调用失败：Provider Context 为空 (method=$method)")
            return null
        }

        // 最前置鉴权：未授权调用直接熔断，不执行任何反序列化与业务逻辑
        val uid = Binder.getCallingUid()
        val callingPackage = IpcSecurity.resolveTrustedPackage(ctx, uid)
        if (callingPackage == null) {
            val pkgs = ctx.packageManager.getPackagesForUid(uid).orEmpty()
            log("拒绝调用：method=$method uid=$uid pkgs=${pkgs.joinToString()}")
            return null
        }

        // 鉴权通过后惰性初始化统计管理器
        StatsManager.ensureInitialized(ctx)
        if (debugSettingInitialized.compareAndSet(false, true)) {
            io.github.jh_mmm.biliaccelerator.core.DebugEventBuffer.setEnabled(
                loadConfig(ctx).enableDebugLogging
            )
        }

        // 接收跨进程同步的 Hook 挂载状态（带上限与字段截断约束）
        extras?.getString(EXTRA_HOOK_STATUS_JSON)?.let { hookJson ->
            if (!IpcSecurity.isJsonSizeAllowed(hookJson, IpcSecurity.MAX_HOOK_JSON_CHARS)) {
                log("忽略超限 Hook 状态 JSON")
            } else {
                try {
                    val mapType = object : TypeToken<Map<String, String?>>() {}.type
                    val rawHookMap: Map<String, String?> = gson.fromJson(hookJson, mapType)
                    val sanitizedMap = rawHookMap.entries
                        .take(MAX_HOOK_STATUS_ENTRIES)
                        .filter { it.key.isNotBlank() && it.value != null }
                        .associate { entry ->
                            (IpcSecurity.sanitizeSingleLine(entry.key, 64) ?: "") to
                                (IpcSecurity.sanitizeSingleLine(entry.value, 256) ?: "")
                        }
                        .filterKeys { it.isNotEmpty() }
                    StatsManager.recordHookStatus(sanitizedMap, ctx)
                } catch (_: Exception) {}
            }
        }

        val response = Bundle()

        when (method) {
            METHOD_RECORD_BATCH_REWRITE -> {
                val json = extras?.getString(EXTRA_REWRITE_BATCH_JSON)
                if (!json.isNullOrEmpty()) {
                    if (!IpcSecurity.isJsonSizeAllowed(json)) {
                        response.putBoolean(EXTRA_SUCCESS, false)
                        log("批量记录重定向失败：JSON 超限")
                    } else {
                        try {
                            val listType = object : TypeToken<List<RewriteResultDto>>() {}.type
                            val dtos: List<RewriteResultDto> = gson.fromJson(json, listType)
                            val validResults = dtos.take(MAX_BATCH_SIZE).mapNotNull { it.toDomain() }
                            StatsManager.recordRequests(validResults, ctx)
                            StatsManager.recordHeartbeat(ctx)
                            response.putBoolean(EXTRA_SUCCESS, true)
                            log("已批量记录媒体请求: ${validResults.size} 条记录 (来自 uid=$uid)")
                        } catch (e: Exception) {
                            response.putBoolean(EXTRA_SUCCESS, false)
                            log("批量记录重定向失败: $e")
                        }
                    }
                } else {
                    response.putBoolean(EXTRA_SUCCESS, false)
                    log("批量记录重定向失败：extras 为空 (来自 uid=$uid)")
                }
            }
            METHOD_GET_CONFIG -> {
                StatsManager.recordHeartbeat(ctx)
                val config = loadConfig(ctx)
                response.putString(EXTRA_CONFIG_JSON, gson.toJson(config))
            }
            METHOD_HANDSHAKE -> {
                val clientVer = IpcSecurity.sanitizeSingleLine(extras?.getString(EXTRA_CLIENT_VERSION), 64)
                val clientProc = IpcSecurity.sanitizeSingleLine(extras?.getString(EXTRA_CLIENT_PROCESS), 128)
                StatsManager.recordHandshake(
                    pkg = callingPackage,
                    version = clientVer,
                    process = clientProc,
                    context = ctx
                )
                response.putBoolean(EXTRA_SUCCESS, true)
                log("已记录宿主握手心跳: pkg=$callingPackage ver=$clientVer proc=$clientProc (来自 uid=$uid)")
            }
        }
        return response
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
