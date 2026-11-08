package io.github.jh_mmm.biliaccelerator.hook

object ProcessFilter {
    private val IGNORED_PROCESS_SUFFIXES = setOf(
        ":push",
        ":pushservice",
        ":channel",
        ":crash",
        ":daemon",
        ":widget",
        ":widgetprovider",
        ":miniapp",
        ":nimble",
        ":clean",
        ":sentry",
        ":account",
        ":mall",
        ":patch",
        ":canary",
        ":guard"
    )

    fun isRelevantProcess(packageName: String, processName: String?): Boolean {
        val proc = processName?.trim()?.takeIf { it.isNotEmpty() } ?: return true
        if (proc == packageName || !proc.contains(':')) return true
        val suffix = proc.substringAfter(':').lowercase()
        if (suffix.contains("media") || suffix.contains("player") || suffix.contains("video") || suffix.contains("ijk")) {
            return true
        }
        if (IGNORED_PROCESS_SUFFIXES.any { suffix == it.removePrefix(":") || suffix.startsWith(it.removePrefix(":") + ":") }) {
            return false
        }
        return true
    }
}
