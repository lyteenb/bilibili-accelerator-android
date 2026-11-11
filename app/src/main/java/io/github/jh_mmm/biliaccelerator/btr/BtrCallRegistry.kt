package io.github.jh_mmm.biliaccelerator.btr

import okhttp3.Call

/** Each local HTTP request owns its calls, including calls registered during cancellation. */
internal class BtrCallRegistry {
    private val lock = Any()
    private val streams = mutableSetOf<Stream>()

    internal inner class Stream(val sessionId: String) : AutoCloseable {
        private val calls = mutableSetOf<Call>()
        @Volatile var isClosed = false
            private set

        internal fun register(call: Call): Boolean {
            val accepted = synchronized(lock) { !isClosed && calls.add(call) }
            if (!accepted && isClosed) call.cancel()
            return accepted
        }

        internal fun unregister(call: Call) {
            synchronized(lock) { calls.remove(call) }
        }

        override fun close() {
            val toCancel = synchronized(lock) {
                if (isClosed) return
                isClosed = true
                streams.remove(this)
                calls.toList().also { calls.clear() }
            }
            toCancel.forEach { runCatching { it.cancel() } }
        }
    }

    internal fun open(sessionId: String): Stream = synchronized(lock) {
        Stream(sessionId).also { streams.add(it) }
    }

    internal fun cancelAll() {
        val active = synchronized(lock) { streams.toList() }
        active.forEach { it.close() }
    }

    internal fun activeStreamCount(): Int = synchronized(lock) { streams.size }
}
