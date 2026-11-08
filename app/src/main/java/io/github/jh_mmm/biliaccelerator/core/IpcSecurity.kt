package io.github.jh_mmm.biliaccelerator.core

import android.content.Context
import android.content.Intent
import android.os.Process

/**
 * Shared cross-process trust rules.
 *
 * Injection scope and IPC trust are intentionally separate: the hook may still
 * support known package families selected by the user, while IPC is limited to
 * the exact packages that are part of the supported Bilibili family.
 */
object IpcSecurity {
    const val MAX_IPC_JSON_CHARS = 512 * 1024
    const val MAX_HOOK_JSON_CHARS = 128 * 1024

    fun isTrustedPackage(context: Context, packageName: String?): Boolean {
        val normalized = packageName?.trim()?.lowercase() ?: return false
        return normalized == context.packageName.lowercase() ||
            BiliAcceleratorCore.isTrustedIpcPackage(normalized)
    }

    /**
     * Resolve a caller by UID. This also supports Android work profiles and
     * OEM app clones, because the same package may run under another user ID.
     */
    fun resolveTrustedPackage(context: Context, uid: Int): String? {
        if (uid == Process.myUid()) return context.packageName
        val packages: Array<String> = runCatching {
            context.packageManager.getPackagesForUid(uid) ?: emptyArray()
        }.getOrDefault(emptyArray())
        return packages.firstOrNull { BiliAcceleratorCore.isTrustedIpcPackage(it) }
    }

    fun isTrustedUid(context: Context, uid: Int): Boolean {
        return resolveTrustedPackage(context, uid) != null
    }

    fun isDirectedToPackage(intent: Intent, context: Context): Boolean {
        return intent.`package` == context.packageName ||
            intent.component?.packageName == context.packageName
    }

    fun sanitizeSingleLine(raw: String?, maxLength: Int): String? {
        if (raw == null) return null
        return raw
            .filter { it == '\t' || !it.isISOControl() }
            .trim()
            .take(maxLength)
            .takeIf { it.isNotEmpty() }
    }

    fun isJsonSizeAllowed(json: String?, limit: Int = MAX_IPC_JSON_CHARS): Boolean {
        return json == null || json.length <= limit
    }
}
