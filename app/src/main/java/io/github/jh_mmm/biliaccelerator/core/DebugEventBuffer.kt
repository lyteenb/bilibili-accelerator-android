package io.github.jh_mmm.biliaccelerator.core

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

data class DebugEvent(
    val timestamp: Long,
    val category: String,
    val message: String
)

data class DebugEventSnapshot(
    val events: List<DebugEvent>,
    val droppedCount: Long
)

object DebugEventBuffer {

    private const val MAX_EVENTS = 1_000
    private const val MAX_CATEGORY_LENGTH = 32
    private const val MAX_MESSAGE_LENGTH = 768

    private val enabled = AtomicBoolean(false)
    private val lock = Any()
    private val events = ArrayDeque<DebugEvent>(MAX_EVENTS)
    private var droppedCount = 0L

    val isEnabled: Boolean
        get() = enabled.get()

    fun setEnabled(value: Boolean) {
        val previous = enabled.getAndSet(value)
        if (previous == value) return
        if (!value) {
            clear()
        } else {
            record("DEBUG", "debug event buffer enabled")
        }
    }

    fun record(category: String, message: String) {
        if (!enabled.get()) return
        val safeCategory = IpcSecurity.sanitizeSingleLine(category, MAX_CATEGORY_LENGTH) ?: "GENERAL"
        val safeMessage = IpcSecurity.sanitizeSingleLine(message, MAX_MESSAGE_LENGTH) ?: return
        val event = DebugEvent(
            timestamp = System.currentTimeMillis(),
            category = safeCategory,
            message = safeMessage
        )
        synchronized(lock) {
            if (events.size >= MAX_EVENTS) {
                events.removeFirst()
                droppedCount++
            }
            events.addLast(event)
        }
    }

    fun snapshot(): DebugEventSnapshot = synchronized(lock) {
        DebugEventSnapshot(
            events = events.toList(),
            droppedCount = droppedCount
        )
    }

    fun clear() {
        synchronized(lock) {
            events.clear()
            droppedCount = 0L
        }
    }
}
