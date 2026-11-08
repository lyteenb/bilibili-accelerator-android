package io.github.jh_mmm.biliaccelerator.btr

import okhttp3.Call
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks BTR upstream calls by stream invocation rather than only by session.
 *
 * A media session may receive multiple overlapping local-proxy requests when
 * the player seeks or aborts an old connection. Cancelling a stale request must
 * never cancel calls belonging to a newer request on the same session.
 */
internal class BtrCallRegistry {
    private val streamCalls = ConcurrentHashMap<String, MutableSet<Call>>()
    private val sessionStreamKeys = ConcurrentHashMap<String, MutableSet<String>>()
    private val streamSessions = ConcurrentHashMap<String, String>()
    private val streamSequence = AtomicLong(0L)

    fun newStreamCallKey(sessionId: String): String {
        return "${sessionId}_${streamSequence.incrementAndGet()}"
    }

    fun register(streamCallKey: String, sessionId: String, call: Call) {
        streamCalls.computeIfAbsent(streamCallKey) { ConcurrentHashMap.newKeySet() }.add(call)
        sessionStreamKeys.computeIfAbsent(sessionId) { ConcurrentHashMap.newKeySet() }.add(streamCallKey)
        streamSessions[streamCallKey] = sessionId
    }

    fun unregister(streamCallKey: String, call: Call) {
        val calls = streamCalls[streamCallKey] ?: return
        calls.remove(call)
        if (calls.isEmpty() && streamCalls.remove(streamCallKey, calls)) {
            cleanupStreamMetadata(streamCallKey)
        }
    }

    fun cancelStreamCalls(streamCallKey: String) {
        val calls = streamCalls.remove(streamCallKey)
        cleanupStreamMetadata(streamCallKey)
        calls?.forEach { call -> runCatching { call.cancel() } }
    }

    fun cancelSessionCalls(sessionId: String) {
        sessionStreamKeys.remove(sessionId)?.toList().orEmpty().forEach { streamCallKey ->
            cancelStreamCalls(streamCallKey)
        }
        // Defensive cleanup for an orphaned stream whose session index was
        // already removed by a concurrent unregister/cancel path.
        streamSessions.entries
            .filter { it.value == sessionId }
            .map { it.key }
            .forEach { streamCallKey -> cancelStreamCalls(streamCallKey) }
    }

    fun cancelAll() {
        sessionStreamKeys.keys.toList().forEach { sessionId -> cancelSessionCalls(sessionId) }
        streamSessions.keys.toList().forEach { streamCallKey -> cancelStreamCalls(streamCallKey) }
    }

    internal fun activeStreamCount(): Int = streamCalls.size

    internal fun activeCallCount(): Int = streamCalls.values.sumOf { it.size }

    internal fun hasSessionStreamKey(sessionId: String): Boolean {
        return sessionStreamKeys[sessionId]?.isNotEmpty() == true
    }

    private fun cleanupStreamMetadata(streamCallKey: String) {
        val sessionId = streamSessions.remove(streamCallKey) ?: return
        val keys = sessionStreamKeys[sessionId] ?: return
        keys.remove(streamCallKey)
        if (keys.isEmpty()) {
            sessionStreamKeys.remove(sessionId, keys)
        }
    }
}
