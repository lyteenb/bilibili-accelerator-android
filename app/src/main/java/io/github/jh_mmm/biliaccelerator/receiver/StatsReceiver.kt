package io.github.jh_mmm.biliaccelerator.receiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import io.github.jh_mmm.biliaccelerator.core.IpcSecurity
import io.github.jh_mmm.biliaccelerator.core.RewriteResult
import io.github.jh_mmm.biliaccelerator.core.StatsManager
import io.github.jh_mmm.biliaccelerator.provider.RewriteResultDto
import io.github.jh_mmm.biliaccelerator.provider.StatsProvider
import java.util.concurrent.atomic.AtomicBoolean

class StatsReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_RECORD_REWRITE = "io.github.jh_mmm.biliaccelerator.ACTION_RECORD_REWRITE"
        const val ACTION_RECORD_DEBUG_EVENTS = "io.github.jh_mmm.biliaccelerator.ACTION_RECORD_DEBUG_EVENTS"
        const val ACTION_HANDSHAKE = "io.github.jh_mmm.biliaccelerator.ACTION_HANDSHAKE"
        const val EXTRA_REWRITE_RESULT = "extra_rewrite_result"
        const val EXTRA_REWRITE_BATCH_JSON = "extra_rewrite_batch_json"
        const val EXTRA_AUTH_TOKEN = "extra_auth_token"
        const val EXTRA_CLIENT_PACKAGE = "extra_client_package"
        const val EXTRA_CLIENT_VERSION = "extra_client_version"
        const val EXTRA_CLIENT_PROCESS = "extra_client_process"
        const val EXTRA_HOOK_STATUS_JSON = "extra_hook_status_json"

        private const val TAG = "BiliAccelerator-StatsReceiver"
        private val gson = Gson()
        private val debugSettingInitialized = AtomicBoolean(false)

        private val VALID_REASONS = setOf(
            "szbdyd-source",
            "mountaintoys-source",
            "mcdn-proxy",
            "pcdn-host",
            "mcdn-host",
            "force-upos",
            "ok",
            "already-proxied",
            "live-skip",
            "live-szbdyd-source",
            "live-mountaintoys-source",
            "live-pcdn-filter",
            "live-pcdn-detected",
            "live-ok",
            BiliAcceleratorCore.SAFE_FALLBACK_REASON,
            "akamai-pass",
            "akamai-redirect"
        )

        internal fun isPayloadLegitimate(batchJson: String?, singleJson: String?): Boolean {
            if (!IpcSecurity.isJsonSizeAllowed(batchJson) || !IpcSecurity.isJsonSizeAllowed(singleJson)) {
                return false
            }
            if (!batchJson.isNullOrEmpty()) {
                return try {
                    val listType = object : TypeToken<List<RewriteResultDto>>() {}.type
                    val results: List<RewriteResultDto> = gson.fromJson(batchJson, listType) ?: return false
                    if (results.isEmpty() || results.size > 200) return false
                    results.all { isValidResultItem(it.toDomain()) }
                } catch (_: Throwable) {
                    false
                }
            }
            if (!singleJson.isNullOrEmpty()) {
                return try {
                    val result: RewriteResultDto = gson.fromJson(singleJson, RewriteResultDto::class.java) ?: return false
                    isValidResultItem(result.toDomain())
                } catch (_: Throwable) {
                    false
                }
            }
            return false
        }

        private fun isValidResultItem(item: RewriteResult): Boolean {
            val orig = item.originalHost.trim()
            val tgt = item.targetHost.trim()
            val reason = item.reason.trim()
            val isKnownReason = reason in VALID_REASONS ||
                    reason.startsWith("BTR") ||
                    reason.startsWith("live-") ||
                    reason.startsWith("akamai-")
            if (orig.isBlank() || tgt.isBlank() || !isKnownReason) {
                return false
            }
            if (item.isBtr || tgt.startsWith("127.0.0.1") || tgt.contains("BTR") || tgt.contains("镜像")) {
                return true
            }
            return BiliAcceleratorCore.isBiliFamilyHost(orig) ||
                    BiliAcceleratorCore.isBiliFamilyHost(tgt) ||
                    BiliAcceleratorCore.CANDIDATE_POOL.contains(tgt) ||
                    BiliAcceleratorCore.isIpAddress(orig)
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action
        if (action != ACTION_RECORD_REWRITE && action != ACTION_HANDSHAKE && action != ACTION_RECORD_DEBUG_EVENTS) return

        // AMS 会为 PendingIntent 填充 creatorUid/creatorPackage。Uid is the
        // primary identity; creatorPackage can be null in clones/work profiles.
        val authToken: PendingIntent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { intent.getParcelableExtra(EXTRA_AUTH_TOKEN, PendingIntent::class.java) }.getOrNull()
        } else {
            @Suppress("DEPRECATION")
            runCatching { intent.getParcelableExtra<PendingIntent>(EXTRA_AUTH_TOKEN) }.getOrNull()
        }
        val creatorUid = runCatching { authToken?.creatorUid }.getOrNull()
        val creatorPackage = runCatching { authToken?.creatorPackage }.getOrNull()
        val trustedCreator = if (
            creatorPackage != null && IpcSecurity.isTrustedPackage(context, creatorPackage)
        ) {
            creatorPackage
        } else {
            creatorUid?.let { IpcSecurity.resolveTrustedPackage(context, it) }
        }
        if (!IpcSecurity.isDirectedToPackage(intent, context) || trustedCreator == null) {
            Log.w(TAG, "拒绝未鉴权广播：creatorPackage=$creatorPackage, creatorUid=$creatorUid")
            return
        }

        StatsManager.ensureInitialized(context)
        if (debugSettingInitialized.compareAndSet(false, true)) {
            io.github.jh_mmm.biliaccelerator.core.DebugEventBuffer.setEnabled(
                StatsProvider.loadConfig(context).enableDebugLogging
            )
        }

        if (action == ACTION_RECORD_DEBUG_EVENTS) {
            io.github.jh_mmm.biliaccelerator.core.DebugEventBuffer.ingestHostEvents(
                intent.getStringExtra(StatsProvider.EXTRA_DEBUG_EVENTS),
                trustedCreator,
                intent.getStringExtra(EXTRA_CLIENT_PROCESS)
            )
            return
        }

        // 3. 处理独立握手广播 (ACTION_HANDSHAKE)
        if (action == ACTION_HANDSHAKE) {
            val clientVer = IpcSecurity.sanitizeSingleLine(intent.getStringExtra(EXTRA_CLIENT_VERSION), 64)
            val clientProc = IpcSecurity.sanitizeSingleLine(intent.getStringExtra(EXTRA_CLIENT_PROCESS), 128)

            StatsManager.recordHandshake(
                pkg = trustedCreator,
                version = clientVer,
                process = clientProc,
                context = context
            )

            intent.getStringExtra(EXTRA_HOOK_STATUS_JSON)?.let { hookJson ->
                if (!IpcSecurity.isJsonSizeAllowed(hookJson, IpcSecurity.MAX_HOOK_JSON_CHARS)) {
                    Log.w(TAG, "忽略超限 Hook 状态 JSON")
                    return@let
                }
                try {
                    val mapType = object : TypeToken<Map<String, String?>>() {}.type
                    val rawHookMap: Map<String, String?> = gson.fromJson(hookJson, mapType)
                    val sanitizedMap = rawHookMap.entries
                        .take(50)
                        .filter { it.key.isNotBlank() && it.value != null }
                        .associate { entry ->
                            (IpcSecurity.sanitizeSingleLine(entry.key, 64) ?: "") to
                                (IpcSecurity.sanitizeSingleLine(entry.value, 256) ?: "")
                        }
                        .filterKeys { it.isNotEmpty() }
                    StatsManager.recordHookStatus(sanitizedMap, context)
                } catch (_: Exception) {}
            }
            Log.i(TAG, "通过 Broadcast 成功记录宿主握手心跳: pkg=$trustedCreator ver=$clientVer proc=$clientProc")
            return
        }

        // 4. 处理重定向统计广播 (ACTION_RECORD_REWRITE)
        val batchJson = intent.getStringExtra(EXTRA_REWRITE_BATCH_JSON)
        val singleJson = intent.getStringExtra(EXTRA_REWRITE_RESULT)
        if (!isPayloadLegitimate(batchJson, singleJson)) {
            Log.w(TAG, "拒绝非法或被截断的统计广播")
            return
        }

        val reportProc = IpcSecurity.sanitizeSingleLine(intent.getStringExtra(EXTRA_CLIENT_PROCESS), 128)

        // 优先处理批量上报
        if (!batchJson.isNullOrEmpty()) {
            try {
                val listType = object : TypeToken<List<RewriteResultDto>>() {}.type
                val dtos: List<RewriteResultDto> = gson.fromJson(batchJson, listType)
                val results = dtos.take(200).map { it.toDomain() }
                StatsManager.recordRequests(results, context)
                StatsManager.recordHeartbeat(context)
                StatsManager.recordHandshake(pkg = trustedCreator, version = null, process = reportProc, context = context)
                Log.i(TAG, "通过 Broadcast 成功记录批量重定向 (${results.size} 条)")
                return
            } catch (e: Exception) {
                Log.e(TAG, "Broadcast 批量记录解析失败: $e")
            }
        }

        // 单条记录兜底兼容
        if (!singleJson.isNullOrEmpty()) {
            try {
                val dto = gson.fromJson(singleJson, RewriteResultDto::class.java)
                val result = dto.toDomain()
                StatsManager.recordRequests(listOf(result), context)
                StatsManager.recordHeartbeat(context)
                StatsManager.recordHandshake(pkg = trustedCreator, version = null, process = reportProc, context = context)
                Log.i(TAG, "通过 Broadcast 成功记录单条重定向: ${result.originalHost} -> ${result.targetHost}")
            } catch (e: Exception) {
                Log.e(TAG, "Broadcast 记录失败: $e")
            }
        }
    }
}
