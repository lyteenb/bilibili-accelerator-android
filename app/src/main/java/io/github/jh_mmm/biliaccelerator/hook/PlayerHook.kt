package io.github.jh_mmm.biliaccelerator.hook

import android.net.Uri
import android.util.Log
import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import io.github.jh_mmm.biliaccelerator.core.RelayHealthRegistry
import io.github.jh_mmm.biliaccelerator.core.RelaySelection
import io.github.jh_mmm.biliaccelerator.core.RewriteResult
import io.github.libxposed.api.XposedModule
import java.util.Collections
import java.util.WeakHashMap

object PlayerHook {

    private const val TAG = "BiliAccelerator-Player"

    @Volatile
    var isHooked: Boolean = false
        internal set

    private val builderPendingBackups = Collections.synchronizedMap(WeakHashMap<Any, List<String>>())

    private data class EffectiveRelay(
        val config: AcceleratorConfig,
        val selection: RelaySelection,
        val mediaKey: String
    )

    // Both spellings are observed across different Bilibili versions (Assert vs Asset)
    private val SEGMENT_BUILDER_CLASSES = listOf(
        "tv.danmaku.ijk.media.player.IjkMediaAsset\$MediaAssertSegment\$Builder",
        "tv.danmaku.ijk.media.player.IjkMediaAsset\$MediaAssetSegment\$Builder",
        "tv.danmaku.ijk.media.player.IjkMediaAsset\$MediaSegment\$Builder"
    )

    private fun resolveEffectiveRelay(originalUrl: String, config: AcceleratorConfig): EffectiveRelay {
        if (!BiliAcceleratorCore.usesRelay(originalUrl, config)) {
            return EffectiveRelay(config, RelaySelection(config.proxyHost, false, false), "")
        }
        val mediaKey = BiliAcceleratorCore.playbackSessionKey(originalUrl)
        val selection = RelayHealthRegistry.selectRelay(config.proxyHost, mediaKey)
        return EffectiveRelay(
            config = config.copy(proxyHost = selection.relayHost),
            selection = selection,
            mediaKey = mediaKey
        )
    }

    private fun notifySafeFallback(originalUrl: String, fallbackUrl: String) {
        val original = BiliAcceleratorCore.parseUri(originalUrl)
        val fallback = BiliAcceleratorCore.parseUri(fallbackUrl)
        RemoteClient.notifyRewrite(
            RewriteResult(
                changed = originalUrl != fallbackUrl,
                originalUrl = originalUrl,
                finalUrl = fallbackUrl,
                originalHost = original?.host ?: "",
                targetHost = fallback?.host ?: "",
                reason = BiliAcceleratorCore.SAFE_FALLBACK_REASON,
                isPcdn = false,
                isMcdn = false,
                segmentKey = original?.path?.let(BiliAcceleratorCore::extractSegmentKey) ?: ""
            )
        )
        io.github.jh_mmm.biliaccelerator.core.DebugEventBuffer.record(
            "RELAY",
            "safe direct fallback source=${original?.host ?: "unknown"} target=${fallback?.host ?: "unknown"}"
        )
    }

    fun init(module: XposedModule, classLoader: ClassLoader) {
        var hooked = false
        for (className in SEGMENT_BUILDER_CLASSES) {
            val builderClass = try {
                classLoader.loadClass(className)
            } catch (_: Throwable) {
                null
            } ?: continue

            hookSegmentBuilder(module, builderClass)
            hooked = true
            HookStatusTracker.recordStatus("player_segment", className)
            Log.i(TAG, "Successfully hooked player segment builder: $className")
        }

        // Fallback: Hook IjkMediaPlayer directly if segment builder is missing or bypassed
        val fallbackHooked = hookFallbackPlayer(module, classLoader)
        if (fallbackHooked) {
            hooked = true
        }

        if (hooked) {
            isHooked = true
        } else {
            Log.i(TAG, "Standard IjkMediaAsset builder not found, relying on player fallback hooks")
        }
    }

    private fun hookSegmentBuilder(module: XposedModule, clazz: Class<*>) {
        // 1. Hook all constructors (distinct 防止 public 构造器重复注入)
        val constructors = (clazz.declaredConstructors + clazz.constructors).distinct()
        for (constructor in constructors) {
            try {
                module.hook(constructor).intercept { chain ->
                    val newArgs = rewriteArgIfMedia(chain.args, chain.thisObject)
                    if (newArgs != null) {
                        chain.proceed(newArgs)
                    } else {
                        chain.proceed()
                    }
                }
            } catch (t: Throwable) {
                Log.d(TAG, "Could not hook constructor: ${t.message}")
            }
        }

        // 2. Hook setter methods: setUrl, setBaseUrl, setResolveUrl
        val setterNames = setOf("setUrl", "setBaseUrl", "setResolveUrl")
        val methods = (clazz.declaredMethods + clazz.methods).distinct()
        for (m in methods) {
            if (m.name in setterNames) {
                try {
                    module.hook(m).intercept { chain ->
                        val newArgs = rewriteArgIfMedia(chain.args, chain.thisObject)
                        if (newArgs != null) {
                            chain.proceed(newArgs)
                        } else {
                            chain.proceed()
                        }
                    }
                } catch (_: Throwable) {}
            }
        }

        // 3. Hook setBackupUrls(Collection/List or Array)
        val backupMethods = (clazz.declaredMethods + clazz.methods).distinct().filter {
            it.name == "setBackupUrls" && it.parameterTypes.size == 1
        }

        for (method in backupMethods) {
            try {
                module.hook(method).intercept { chain ->
                    val arg = chain.args.firstOrNull() ?: return@intercept chain.proceed()
                    val rawList: List<String> = when (arg) {
                        is Collection<*> -> arg.filterIsInstance<String>()
                        is Array<*> -> arg.filterIsInstance<String>()
                        else -> return@intercept chain.proceed()
                    }
                    if (rawList.isEmpty()) return@intercept chain.proceed()

                    val config = RemoteClient.fetchConfig()
                    if (!config.enabled) {
                        return@intercept chain.proceed()
                    }

                    // Live backup lists must never enter BTR session storage or
                    // candidate propagation. A live FLV/HLS URL must not replace
                    // the backups of the most recent VOD BTR session.
                    val isLiveBackupList = rawList.any {
                        BiliAcceleratorCore.isLiveMediaUrl("", it)
                    }
                    if (isLiveBackupList) {
                        io.github.jh_mmm.biliaccelerator.core.DebugEventBuffer.record(
                            "LIVE",
                            "backup passthrough count=${rawList.size} btrUpdateSkipped=true"
                        )
                        return@intercept chain.proceed()
                    }

                    val effectiveRelay = resolveEffectiveRelay(rawList.first(), config)
                    if (effectiveRelay.selection.allUnavailable) {
                        io.github.jh_mmm.biliaccelerator.core.DebugEventBuffer.record(
                            "RELAY",
                            "backup list passthrough because all relays are blocked count=${rawList.size}"
                        )
                        return@intercept chain.proceed()
                    }
                    val effectiveConfig = effectiveRelay.config
                    val wrappedBackups = mutableMapOf<String, String>()
                    val distinctFiltered = if (effectiveConfig.blockPcdn || effectiveConfig.proxyMcdn || effectiveConfig.forceUpos) {
                        filterAndFillBackupUrls(rawList, effectiveConfig) { res ->
                            if (BiliAcceleratorCore.isRelayWrapper(res.finalUrl) &&
                                BiliAcceleratorCore.unwrapRelayUrl(res.finalUrl) == BiliAcceleratorCore.unwrapRelayUrl(res.originalUrl)
                            ) {
                                wrappedBackups[res.originalUrl] = res.finalUrl
                            }
                            RemoteClient.notifyRewrite(res)
                        }
                    } else {
                        rawList.distinct()
                    }

                    // Only native backups and wrappers that preserve the signed media
                    // are explicit BTR candidates. Authority substitutions need probing.
                    val btrBackups = rawList.map { wrappedBackups[it] ?: it }.distinct()
                    chain.thisObject?.let { builder ->
                        builderPendingBackups[builder] = btrBackups
                    }

                    if (effectiveConfig.enableBtr && io.github.jh_mmm.biliaccelerator.btr.BtrLocalProxyServer.isStarted) {
                        val boundSessionId = chain.thisObject?.let {
                            io.github.jh_mmm.biliaccelerator.btr.BtrSessionManager.getSessionForBuilder(it)
                        }
                        if (boundSessionId != null) {
                            io.github.jh_mmm.biliaccelerator.btr.BtrSessionManager.updateBackups(boundSessionId, btrBackups)
                        } else {
                            io.github.jh_mmm.biliaccelerator.btr.BtrSessionManager.updateBackupsByCandidateUrls(btrBackups)
                        }
                    }

                    if (distinctFiltered.isNotEmpty()) {
                        val paramType = method.parameterTypes[0]
                        val convertedArg: Any = when {
                            paramType.isArray -> distinctFiltered.toTypedArray()
                            java.util.Set::class.java.isAssignableFrom(paramType) -> distinctFiltered.toSet()
                            else -> distinctFiltered
                        }
                        val newArgs = chain.args.toTypedArray().apply { this[0] = convertedArg }
                        return@intercept chain.proceed(newArgs)
                    }
                    chain.proceed()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to hook ${method.name}: ${t.message}")
            }
        }
    }

    fun filterAndFillBackupUrls(
        rawList: List<String>,
        config: AcceleratorConfig,
        onRewrite: ((RewriteResult) -> Unit)? = null
    ): List<String> {
        return BiliAcceleratorCore.filterAndFillBackupUrls(rawList, config, onRewrite)
    }

    private fun tryRewriteToBtr(
        originalUrl: String,
        result: RewriteResult,
        config: AcceleratorConfig,
        builderInstance: Any? = null
    ): String? {
        if (!config.enabled || !config.enableBtr) {
            return null
        }
        // 直播流（FLV/HLS/Live）绝对不重写至 BTR 本地代理
        if (result.reason.startsWith("live-") || BiliAcceleratorCore.isLiveMediaUrl("", originalUrl)) {
            return null
        }
        // 版权保护/DRM 加密流（Widevine/ChinaDRM/PSSH）绝对不重写至 BTR 本地代理，交由原生直连放行
        if (BiliAcceleratorCore.hasDrmSignal(originalUrl) || (result.changed && BiliAcceleratorCore.hasDrmSignal(result.finalUrl))) {
            Log.i(TAG, "DRM protected stream detected, bypassing BTR proxy")
            return null
        }
        val mediaUrl = BiliAcceleratorCore.unwrapRelayUrl(originalUrl) ?: originalUrl
        if (BiliAcceleratorCore.hasDrmSignal(mediaUrl)) return null
        val isEpisode = BiliAcceleratorCore.isEpisodeMediaUrl(mediaUrl) ||
                (result.changed && BiliAcceleratorCore.isEpisodeMediaUrl(result.finalUrl))
        if (isEpisode) {
            if (!config.enableEpisodeAcceleration) {
                Log.i(TAG, "Episode acceleration disabled, leaving episode stream to native player")
                return null
            }
            if (BiliAcceleratorCore.isEpisodePreview(mediaUrl) ||
                (result.changed && BiliAcceleratorCore.isEpisodePreview(result.finalUrl))
            ) {
                Log.i(TAG, "Episode preview detected, leaving stream to native player")
                return null
            }
        }

        if (result.reason == "akamai-pass") return null
        val targetUrl = if (result.changed) result.finalUrl else originalUrl
        if (!io.github.jh_mmm.biliaccelerator.btr.BtrHttpSafety.isAllowedUpstreamUrl(targetUrl)) {
            return null
        }

        // A direct UPOS stream is not worth a local loopback hop unless we can
        // build at least two trusted, same-region candidates. MCDN/relay
        // streams keep their existing priority because their outer relay host
        // can be swapped without changing the nested signed URL.
        val pendingBackups = builderInstance?.let { builderPendingBackups[it] }.orEmpty()
        if (!io.github.jh_mmm.biliaccelerator.btr.BtrSessionManager.shouldUseBtr(
                targetUrl,
                pendingBackups,
                config,
                isEpisode
            )
        ) {
            return null
        }

        // The proxy may not have been started during PackageLoaded because the
        // configuration provider was not reachable yet. Start it lazily once a
        // resolved, enabled configuration reaches the player path.
        if (!io.github.jh_mmm.biliaccelerator.btr.BtrLocalProxyServer.isStarted) {
            io.github.jh_mmm.biliaccelerator.btr.BtrLocalProxyServer.start(
                concurrency = config.btrConcurrency,
                chunkSizeKb = config.btrChunkSizeKb,
                autoConcurrency = config.btrAutoConcurrency,
                autoChunkSize = config.btrAutoChunkSize
            )
        }
        if (!io.github.jh_mmm.biliaccelerator.btr.BtrLocalProxyServer.isStarted) {
            io.github.jh_mmm.biliaccelerator.core.DebugEventBuffer.record(
                "BTR",
                "btr_skip reason=proxy_start_failed"
            )
            return null
        }

        val isAudio = BiliAcceleratorCore.isAudioTrack(originalUrl) || BiliAcceleratorCore.isAudioTrack(targetUrl)

        val sessionId = io.github.jh_mmm.biliaccelerator.btr.BtrSessionManager.register(
            primaryUrl = targetUrl,
            backupUrls = pendingBackups,
            isAudio = isAudio,
            isEpisode = isEpisode,
            config = config,
            originalUrl = originalUrl
        )
        if (builderInstance != null) {
            io.github.jh_mmm.biliaccelerator.btr.BtrSessionManager.bindBuilderSession(builderInstance, sessionId)
        }
        val proxyPort = io.github.jh_mmm.biliaccelerator.btr.BtrLocalProxyServer.port
        if (proxyPort <= 0) return null
        return "http://127.0.0.1:$proxyPort/stream/$sessionId.m4s"
    }

    private fun rewriteArgIfMedia(
        args: List<Any?>,
        builderInstance: Any? = null,
        strIndex: Int = args.indexOfFirst { it is String && BiliAcceleratorCore.hasMediaSignal(it) }
    ): Array<Any?>? {
        if (strIndex == -1) return null

        val originalUrl = args.getOrNull(strIndex) as? String ?: return null
        if (!BiliAcceleratorCore.hasMediaSignal(originalUrl)) return null
        val config = RemoteClient.fetchConfig()
        if (!config.enabled) return null

        val parsed = BiliAcceleratorCore.parseUri(originalUrl)
        val isLive = parsed != null && BiliAcceleratorCore.isLiveMediaUrl(parsed.path, originalUrl)

        if (isLive) {
            RemoteClient.notifyRewrite(BiliAcceleratorCore.rewriteUrl(originalUrl, config))
            return null
        }

        val effectiveRelay = resolveEffectiveRelay(originalUrl, config)
        val cachedFallback = if (config.enableBtr) {
            io.github.jh_mmm.biliaccelerator.btr.BtrSessionManager.activeDirectFallback(originalUrl)
        } else null
        if (cachedFallback != null || effectiveRelay.selection.allUnavailable) {
            val fallbackUrl = cachedFallback ?: BiliAcceleratorCore.safeDirectFallbackUrl(originalUrl)
            if (fallbackUrl != null) {
                notifySafeFallback(originalUrl, fallbackUrl)
                if (fallbackUrl != originalUrl) return args.toTypedArray().apply { this[strIndex] = fallbackUrl }
            }
            return null
        }

        val result = BiliAcceleratorCore.rewriteUrl(originalUrl, effectiveRelay.config)
        val btrUrl = tryRewriteToBtr(originalUrl, result, effectiveRelay.config, builderInstance)
        if (btrUrl != null) {
            Log.i(TAG, "Rewrote media url to BTR proxy: ${result.originalHost} -> 127.0.0.1:${io.github.jh_mmm.biliaccelerator.btr.BtrLocalProxyServer.port}")
            val newArgs = args.toTypedArray()
            newArgs[strIndex] = btrUrl
            return newArgs
        }

        RemoteClient.notifyRewrite(result)
        if (result.changed) {
            Log.i(TAG, "Rewrote media url: ${result.originalHost} -> ${result.targetHost} [${result.reason}]")
            val newArgs = args.toTypedArray()
            newArgs[strIndex] = result.finalUrl
            return newArgs
        }
        return null
    }

    private fun hookFallbackPlayer(module: XposedModule, classLoader: ClassLoader): Boolean {
        val ijkPlayerClass = try {
            classLoader.loadClass("tv.danmaku.ijk.media.player.IjkMediaPlayer")
        } catch (_: Throwable) {
            null
        } ?: return false

        var fallbackHooked = false

        try {
            // 1. Hook setDataSource(String path)
            try {
                val setDataSourceMethod = ijkPlayerClass.getDeclaredMethod("setDataSource", String::class.java)
                module.hook(setDataSourceMethod).intercept { chain ->
                    val args = rewriteArgIfMedia(chain.args, chain.thisObject, strIndex = 0)
                    if (args == null) chain.proceed() else chain.proceed(args)
                }
                fallbackHooked = true
            } catch (_: NoSuchMethodException) {}

            // 2. Hook setDataSource(Context context, Uri uri) 及重载
            val uriMethods = (ijkPlayerClass.declaredMethods + ijkPlayerClass.methods).distinct().filter {
                it.name == "setDataSource" && it.parameterTypes.size >= 2 && it.parameterTypes[1] == Uri::class.java
            }
            for (m in uriMethods) {
                try {
                    module.hook(m).intercept { chain ->
                        val originalUri = chain.args.getOrNull(1) as? Uri ?: return@intercept chain.proceed()
                        val stringArgs = chain.args.toMutableList().apply { this[1] = originalUri.toString() }
                        val args = rewriteArgIfMedia(stringArgs, chain.thisObject, strIndex = 1)
                            ?: return@intercept chain.proceed()
                        args[1] = Uri.parse(args[1] as String)
                        chain.proceed(args)
                    }
                    fallbackHooked = true
                } catch (_: Throwable) {}
            }

            if (fallbackHooked) {
                HookStatusTracker.recordStatus("player_fallback", "tv.danmaku.ijk.media.player.IjkMediaPlayer")
                Log.i(TAG, "Successfully hooked IjkMediaPlayer setDataSource methods")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "hookFallbackPlayer failed: ${t.message}")
        }
        return fallbackHooked
    }
}
