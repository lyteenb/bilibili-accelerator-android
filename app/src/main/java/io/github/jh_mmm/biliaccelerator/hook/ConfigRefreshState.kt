package io.github.jh_mmm.biliaccelerator.hook

/** Tracks the settings revision independently of the provider read. */
internal class ConfigRefreshState {
    @Volatile
    private var expiresAt: Long? = null
    private var revision = 0L
    private var reading = false

    fun isFresh(now: Long): Boolean = expiresAt?.let { now - it < 0L } ?: false

    @Synchronized
    fun invalidate() {
        expiresAt = null
        revision++
    }

    @Synchronized
    fun begin(): Long? {
        if (reading) return null
        reading = true
        return revision
    }

    @Synchronized
    fun publish(readRevision: Long, expiresAt: Long, apply: () -> Unit) {
        if (readRevision != revision) return
        apply()
        this.expiresAt = expiresAt
    }

    @Synchronized
    fun finish(readRevision: Long): Boolean {
        reading = false
        return readRevision != revision
    }
}
