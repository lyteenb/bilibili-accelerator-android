package io.github.jh_mmm.biliaccelerator.hook

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.util.Log
import com.google.gson.Gson
import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import io.github.jh_mmm.biliaccelerator.core.IpcSecurity
import io.github.jh_mmm.biliaccelerator.core.DebugEvent
import io.github.jh_mmm.biliaccelerator.core.DebugEventBuffer
import io.github.jh_mmm.biliaccelerator.core.RewriteResult
import io.github.jh_mmm.biliaccelerator.provider.StatsProvider
import io.github.jh_mmm.biliaccelerator.receiver.StatsReceiver
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object RemoteClient {

    private const val TAG = "BiliAccelerator-Remote"
    private const val TARGET_PREFS_NAME = "bili_accelerator_target_cache"
    private const val KEY_CACHED_CONFIG = "cached_config_json"

    private val gson = Gson()
    private val configExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RemoteClient-ConfigWorker").apply { isDaemon = true }
    }
    private val reportScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "RemoteClient-ReportWorker").apply { isDaemon = true }
    }

    // Fail closed until a real cached/provider configuration is available.
    // This prevents a temporarily unreachable Provider from silently turning
    // on host rewriting or BTR with hard-coded defaults.
    @Volatile
    private var cachedConfig: AcceleratorConfig = AcceleratorConfig(enabled = false)
    @Volatile
    private var hasResolvedConfig = false
    private val configRefresh = ConfigRefreshState()
    private val configObserverRegistered = AtomicBoolean(false)
    private val configCacheNanos = TimeUnit.SECONDS.toNanos(30)
    private val configRetryNanos = TimeUnit.SECONDS.toNanos(5)

    private val pendingBatch = ConcurrentLinkedQueue<RewriteResult>()
    private const val BATCH_MAX_SIZE = 10
    private const val MAX_PENDING_BATCH = 200
    private const val FLUSH_INTERVAL_MS = 1500L
    private var pendingFlushFuture: ScheduledFuture<*>? = null
    private val flushLock = Any()
    private val lastBroadcastTime = AtomicLong(0L)
    private val pendingDebug = ArrayDeque<DebugEvent>()
    private var debugFlushScheduled = false
    private const val MAX_PENDING_DEBUG = 200

    @Volatile
    private var appContext: Context? = null

    fun setAppContext(context: Context) {
        val app = try {
            context.applicationContext ?: context
        } catch (_: Throwable) {
            context
        }
        appContext = app
        DebugEventBuffer.setForwarder(::queueDebugEvent)

        registerConfigObserver(app)

        // Load the last user-approved configuration first, then make one
        // synchronous attempt to resolve the current module configuration.
        // The provider path is local and already existed on the first fetch;
        // keeping it synchronous avoids dropping the first playback request.
        if (!hasResolvedConfig) loadConfigFromLocalCache(app)
        sendHandshakeAsync(app)
        requestConfig(app, synchronous = !hasResolvedConfig)
    }

    fun getContext(): Context? {
        appContext?.let { return it }
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentAppMethod = activityThreadClass.getMethod("currentApplication")
            val app = currentAppMethod.invoke(null) as? Context
            if (app != null) {
                setAppContext(app)
                app
            } else null
        } catch (t: Throwable) {
            Log.d(TAG, "getContext via ActivityThread failed: ${t.message}")
            null
        }
    }

    private fun loadConfigFromLocalCache(context: Context) {
        try {
            val sp = context.getSharedPreferences(TARGET_PREFS_NAME, Context.MODE_PRIVATE)
            val json = sp.getString(KEY_CACHED_CONFIG, null)
            if (!json.isNullOrEmpty()) {
                val saved = gson.fromJson(json, AcceleratorConfig::class.java)
                if (saved != null) {
                    synchronized(configRefresh) {
                        if (!hasResolvedConfig) {
                            cachedConfig = saved
                            hasResolvedConfig = true
                            applyDebugLogging(saved.enableDebugLogging)
                            Log.i(TAG, "Loaded cached config from local target app storage: targetHost=${saved.targetHost}")
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load local cached config: ${t.message}")
        }
    }

    private fun saveConfigToLocalCache(context: Context, config: AcceleratorConfig) {
        try {
            val sp = context.getSharedPreferences(TARGET_PREFS_NAME, Context.MODE_PRIVATE)
            sp.edit().putString(KEY_CACHED_CONFIG, gson.toJson(config)).apply()
        } catch (_: Throwable) {}
    }

    val isConfigResolved: Boolean
        get() = hasResolvedConfig

    fun fetchConfig(): AcceleratorConfig {
        if (configRefresh.isFresh(System.nanoTime())) return cachedConfig
        val context = getContext() ?: return cachedConfig
        // getContext() can resolve the initial configuration itself.
        if (!configRefresh.isFresh(System.nanoTime())) {
            requestConfig(context, synchronous = !hasResolvedConfig)
        }
        return cachedConfig
    }

    private fun applyResolvedConfig(context: Context, config: AcceleratorConfig) {
        cachedConfig = config
        hasResolvedConfig = true
        applyDebugLogging(config.enableDebugLogging)
        saveConfigToLocalCache(context, config)
        if (config.enabled && config.enableBtr) {
            io.github.jh_mmm.biliaccelerator.btr.BtrLocalProxyServer.start(
                concurrency = config.btrConcurrency,
                chunkSizeKb = config.btrChunkSizeKb,
                autoConcurrency = config.btrAutoConcurrency,
                autoChunkSize = config.btrAutoChunkSize
            )
            val process = resolveProcessName(context) ?: context.packageName
            val statusKey = "btr_proxy_$process"
            if (io.github.jh_mmm.biliaccelerator.btr.BtrLocalProxyServer.isStarted) {
                HookStatusTracker.recordStatus(
                    statusKey,
                    "127.0.0.1:${io.github.jh_mmm.biliaccelerator.btr.BtrLocalProxyServer.port}"
                )
            } else {
                HookStatusTracker.recordStatus(statusKey, "start_failed")
            }
        } else {
            io.github.jh_mmm.biliaccelerator.btr.BtrLocalProxyServer.stop()
            val process = resolveProcessName(context) ?: context.packageName
            HookStatusTracker.recordStatus("btr_proxy_$process", "disabled")
        }
    }

    private fun fetchConfigDirect(context: Context): AcceleratorConfig? {
        return try {
            val response = context.contentResolver.call(
                StatsProvider.CONTENT_URI,
                StatsProvider.METHOD_GET_CONFIG,
                null,
                null
            )
            val json = response?.getString(StatsProvider.EXTRA_CONFIG_JSON)
            if (!json.isNullOrEmpty()) {
                gson.fromJson(json, AcceleratorConfig::class.java)
            } else null
        } catch (t: Throwable) {
            Log.d(TAG, "Direct config fetch failed: ${t.message}")
            null
        }
    }

    private fun registerConfigObserver(context: Context) {
        if (!configObserverRegistered.compareAndSet(false, true)) return
        try {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    configRefresh.invalidate()
                    requestConfig(context)
                }
            }
            context.contentResolver.registerContentObserver(StatsProvider.CONTENT_URI, true, observer)
        } catch (e: Throwable) {
            configObserverRegistered.set(false)
            Log.w(TAG, "Failed to register config observer", e)
        }
    }

    private fun requestConfig(context: Context, synchronous: Boolean = false) {
        val revision = configRefresh.begin() ?: return
        if (synchronous) {
            readConfig(context, revision, retryInBackground = true)
        } else {
            configExecutor.execute { readConfig(context, revision, retryInBackground = false) }
        }
    }

    private fun readConfig(context: Context, revision: Long, retryInBackground: Boolean) {
        var failed = false
        try {
            val config = fetchConfigDirect(context)
            if (config == null) {
                failed = true
            } else {
                configRefresh.publish(revision, System.nanoTime() + configCacheNanos) {
                    applyResolvedConfig(context, config)
                }
            }
        } catch (t: Throwable) {
            failed = true
            Log.d(TAG, "Config refresh failed: ${t.message}")
        } finally {
            if (failed && !retryInBackground) {
                configRefresh.publish(revision, System.nanoTime() + configRetryNanos) {}
            }
            val invalidated = configRefresh.finish(revision)
            if (invalidated || (failed && retryInBackground)) requestConfig(context)
        }
    }

    fun sendHandshakeAsync(context: Context) {
        configExecutor.execute {
            sendHandshakeDirect(context)
        }
    }

    internal fun resolveProcessName(context: Context? = null): String? {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            runCatching { android.app.Application.getProcessName() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val method = activityThreadClass.getDeclaredMethod("currentProcessName")
            method.isAccessible = true
            (method.invoke(null) as? String)?.takeIf { it.isNotBlank() }
        }.getOrNull()?.let { return it }
        return context?.packageName
    }

    private fun sendHandshakeDirect(context: Context) {
        val pkgName = context.packageName
        val procName = resolveProcessName(context) ?: pkgName
        if (!ProcessFilter.isRelevantProcess(pkgName, procName)) {
            return
        }
        val pm = context.packageManager
        val pInfo = runCatching { pm.getPackageInfo(pkgName, 0) }.getOrNull()
        val verName = pInfo?.versionName ?: "unknown"
        val hookSnapshotJson = gson.toJson(HookStatusTracker.getSnapshot())
        var handshakeOk = false

        // 1. 优先尝试 ContentProvider 握手
        try {
            val extras = Bundle().apply {
                putString(StatsProvider.EXTRA_CLIENT_PACKAGE, pkgName)
                putString(StatsProvider.EXTRA_CLIENT_VERSION, verName)
                putString(StatsProvider.EXTRA_CLIENT_PROCESS, procName)
                putString(StatsProvider.EXTRA_HOOK_STATUS_JSON, hookSnapshotJson)
            }
            val res = context.contentResolver.call(
                StatsProvider.CONTENT_URI,
                StatsProvider.METHOD_HANDSHAKE,
                null,
                extras
            )
            if (res?.getBoolean(StatsProvider.EXTRA_SUCCESS) == true) {
                handshakeOk = true
                Log.i(TAG, "Successfully sent handshake to module provider from $pkgName ($procName, v$verName)")
            }
        } catch (t: Throwable) {
            Log.d(TAG, "sendHandshakeDirect via provider failed: ${t.message}")
        }

        // 2. 兜底通道：若 Provider 不可达（如高版本 Android 包可见性拦截），发送握手显式广播
        if (!handshakeOk) {
            try {
                val authToken = broadcastAuthToken(context)

                val intent = Intent(StatsReceiver.ACTION_HANDSHAKE).apply {
                    setPackage(BiliAcceleratorCore.MODULE_PACKAGE)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    putExtra(StatsReceiver.EXTRA_CLIENT_PACKAGE, pkgName)
                    putExtra(StatsReceiver.EXTRA_CLIENT_VERSION, verName)
                    putExtra(StatsReceiver.EXTRA_CLIENT_PROCESS, procName)
                    putExtra(StatsReceiver.EXTRA_HOOK_STATUS_JSON, hookSnapshotJson)
                    putExtra(StatsReceiver.EXTRA_AUTH_TOKEN, authToken)
                }
                context.sendBroadcast(intent)
                Log.i(TAG, "Successfully sent handshake to module via broadcast fallback from $pkgName ($procName, v$verName)")
            } catch (t: Throwable) {
                Log.w(TAG, "sendHandshakeDirect via broadcast failed: ${t.message}")
            }
        }
    }

    private fun broadcastAuthToken(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent().setPackage(context.packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun applyDebugLogging(enabled: Boolean) {
        DebugEventBuffer.setEnabled(enabled)
        if (!enabled) synchronized(pendingDebug) { pendingDebug.clear() }
    }

    private fun queueDebugEvent(event: DebugEvent) {
        synchronized(pendingDebug) {
            if (!DebugEventBuffer.isEnabled) return
            if (pendingDebug.size >= MAX_PENDING_DEBUG) pendingDebug.removeFirst()
            pendingDebug.addLast(event)
            if (!debugFlushScheduled) {
                debugFlushScheduled = true
                reportScheduler.schedule(::flushDebugEvents, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun flushDebugEvents() {
        val batch = synchronized(pendingDebug) {
            if (!DebugEventBuffer.isEnabled) pendingDebug.clear()
            val count = minOf(pendingDebug.size, DebugEventBuffer.IPC_BATCH_SIZE)
            List(count) { pendingDebug.removeFirst() }
        }
        try {
            val context = appContext ?: return
            if (batch.isEmpty() || !DebugEventBuffer.isEnabled) return
            val json = DebugEventBuffer.encodeHostEvents(batch)
            val process = resolveProcessName(context)
            val delivered = runCatching {
                val extras = Bundle().apply {
                    putString(StatsProvider.EXTRA_DEBUG_EVENTS, json)
                    putString(StatsProvider.EXTRA_CLIENT_PROCESS, process)
                }
                context.contentResolver.call(
                    StatsProvider.CONTENT_URI, StatsProvider.METHOD_RECORD_DEBUG_EVENTS, null, extras
                )?.getBoolean(StatsProvider.EXTRA_SUCCESS) == true
            }.getOrDefault(false)
            if (!delivered) {
                runCatching {
                    val intent = Intent(StatsReceiver.ACTION_RECORD_DEBUG_EVENTS).apply {
                        setPackage(BiliAcceleratorCore.MODULE_PACKAGE)
                        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                        putExtra(StatsProvider.EXTRA_DEBUG_EVENTS, json)
                        putExtra(StatsReceiver.EXTRA_CLIENT_PROCESS, process)
                        putExtra(StatsReceiver.EXTRA_AUTH_TOKEN, broadcastAuthToken(context))
                    }
                    context.sendBroadcast(intent)
                }
            }
        } finally {
            synchronized(pendingDebug) {
                if (pendingDebug.isEmpty()) {
                    debugFlushScheduled = false
                } else {
                    reportScheduler.schedule(::flushDebugEvents, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    fun notifyRewrite(result: RewriteResult) {
        while (pendingBatch.size >= MAX_PENDING_BATCH) {
            pendingBatch.poll()
        }
        pendingBatch.offer(sanitizeForIpc(result))

        if (pendingBatch.size >= BATCH_MAX_SIZE) {
            reportScheduler.execute { flushBatch() }
        } else {
            synchronized(flushLock) {
                pendingFlushFuture?.cancel(false)
                pendingFlushFuture = reportScheduler.schedule({
                    flushBatch()
                }, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    internal fun sanitizeForIpc(result: RewriteResult): RewriteResult {
        val segKey = result.segmentKey.ifBlank {
            BiliAcceleratorCore.parseUri(result.originalUrl)?.let { BiliAcceleratorCore.extractSegmentKey(it.path) } ?: ""
        }
        return RewriteResult(
            changed = result.changed,
            originalUrl = "",
            finalUrl = "",
            originalHost = IpcSecurity.sanitizeSingleLine(result.originalHost, 128) ?: "",
            targetHost = IpcSecurity.sanitizeSingleLine(result.targetHost, 128) ?: "",
            reason = IpcSecurity.sanitizeSingleLine(result.reason, 128) ?: "unknown",
            isPcdn = result.isPcdn,
            isMcdn = result.isMcdn,
            segmentKey = IpcSecurity.sanitizeSingleLine(segKey, 96) ?: "",
            isBtr = result.isBtr,
            btrChunks = result.btrChunks.coerceIn(0, 1_000_000),
            btrBytes = result.btrBytes.coerceIn(0L, 1L shl 40),
            btrConcurrency = result.btrConcurrency.coerceIn(0, 64),
            btrCdnCount = result.btrCdnCount.coerceIn(0, 64),
            btrAvgLatencyMs = result.btrAvgLatencyMs.coerceIn(0L, 600_000L)
        )
    }

    private fun flushBatch() {
        val batch = mutableListOf<RewriteResult>()
        while (batch.size < 50) {
            val item = pendingBatch.poll() ?: break
            batch.add(sanitizeForIpc(item))
        }
        if (batch.isEmpty()) return

        val context = getContext()
        if (context == null) {
            Log.w(TAG, "统计批量上报跳过：目标进程 Context 为空 (丢弃 ${batch.size} 条)")
            return
        }

        val json = gson.toJson(batch)
        var reported = false

        // 1. 优先尝试 ContentProvider 批量 IPC
        try {
            val extras = Bundle().apply {
                putString(StatsProvider.EXTRA_REWRITE_BATCH_JSON, json)
            }
            val response = context.contentResolver.call(
                StatsProvider.CONTENT_URI,
                StatsProvider.METHOD_RECORD_BATCH_REWRITE,
                null,
                extras
            )
            if (response?.getBoolean(StatsProvider.EXTRA_SUCCESS) == true) {
                reported = true
                Log.i(TAG, "[Provider] 批量上报成功 (${batch.size} 条重定向)")
            }
        } catch (t: Throwable) {
            Log.d(TAG, "[Provider] 暂不可达: ${t.javaClass.simpleName} (${t.message})")
        }

        // 2. 兜底通道：发送带鉴权 Token 的显式广播，附带限流防止广播风暴（1.0.5 经典双通道）
        if (!reported) {
            val now = System.currentTimeMillis()
            val timeSinceLastBroadcast = now - lastBroadcastTime.get()
            if (timeSinceLastBroadcast >= 1000L) {
                lastBroadcastTime.set(now)
                try {
                    val authToken = broadcastAuthToken(context)

                    val procName = resolveProcessName(context) ?: context.packageName
                    val intent = Intent(StatsReceiver.ACTION_RECORD_REWRITE).apply {
                        setPackage(BiliAcceleratorCore.MODULE_PACKAGE)
                        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                        putExtra(StatsReceiver.EXTRA_REWRITE_BATCH_JSON, json)
                        putExtra(StatsReceiver.EXTRA_AUTH_TOKEN, authToken)
                        putExtra(StatsReceiver.EXTRA_CLIENT_PACKAGE, context.packageName)
                        putExtra(StatsReceiver.EXTRA_CLIENT_PROCESS, procName)
                    }
                    context.sendBroadcast(intent)
                    Log.i(TAG, "[Broadcast] 备用通道批量发送成功 (${batch.size} 条重定向)")
                } catch (t: Throwable) {
                    Log.w(TAG, "[Broadcast] 发送异常: $t")
                }
            } else {
                // 修复丢包缺陷：将尚未发送的批次数据重新回填到队列头部，并安排延迟重试
                for (item in batch) {
                    pendingBatch.offer(item)
                }
                val retryDelay = maxOf(100L, 1000L - timeSinceLastBroadcast)
                synchronized(flushLock) {
                    pendingFlushFuture?.cancel(false)
                    pendingFlushFuture = reportScheduler.schedule({
                        flushBatch()
                    }, retryDelay, TimeUnit.MILLISECONDS)
                }
                Log.d(TAG, "[Broadcast] 广播限流保护触发，已回填 ${batch.size} 条数据并将于 ${retryDelay}ms 后重试")
            }
        }
    }
}
