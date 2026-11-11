package io.github.jh_mmm.biliaccelerator.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

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
    internal const val IPC_BATCH_SIZE = 40
    private const val MAX_IPC_CHARS = 96 * 1024
    private val urls = Regex("""(?i)\b(?:https?|wss?)://[^\s<>"']+""")
    private val sensitiveValues = Regex(
        """(?i)\b(authorization|cookie|token|sign|signature|deadline|w_rid|wssecret|session|access_key|auth_key)=[^\s,;]+"""
    )

    private val sensitiveHeaders = Regex(
        """(?i)\b(authorization|proxy-authorization|cookie|set-cookie)\s*:[^\r\n]*"""
    )

    @Volatile private var forwarder: ((DebugEvent) -> Unit)? = null

    internal fun setForwarder(forward: (DebugEvent) -> Unit) {
        forwarder = forward
    }

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
        val event = sanitize(category, message, System.currentTimeMillis()) ?: return
        if (append(event)) forwarder?.invoke(event)
    }

    private fun sanitize(category: String, message: String, timestamp: Long): DebugEvent? {
        val safeCategory = IpcSecurity.sanitizeSingleLine(category, MAX_CATEGORY_LENGTH)
            ?.takeIf { label -> label.all { it.isLetterOrDigit() || it in "_/-" } } ?: "GENERAL"
        val headersRemoved = sensitiveHeaders.replace(message) { "${it.groupValues[1]}: [redacted]" }
        val redacted = sensitiveValues.replace(urls.replace(headersRemoved, "[URL]")) { "${it.groupValues[1]}=[redacted]" }
        val safeMessage = IpcSecurity.sanitizeSingleLine(redacted, MAX_MESSAGE_LENGTH) ?: return null
        return DebugEvent(timestamp, safeCategory, safeMessage)
    }

    private fun append(event: DebugEvent): Boolean = synchronized(lock) {
        if (!enabled.get()) return@synchronized false
        if (events.size >= MAX_EVENTS) {
            events.removeFirst()
            droppedCount++
        }
        events.addLast(event)
        true
    }

    internal fun encodeHostEvents(batch: List<DebugEvent>): String = JsonArray().apply {
        batch.take(IPC_BATCH_SIZE).forEach { event ->
            add(JsonObject().apply {
                addProperty("time", event.timestamp)
                addProperty("category", event.category)
                addProperty("message", event.message)
            })
        }
    }.toString()

    internal fun ingestHostEvents(json: String?, packageName: String, processName: String?) {
        if (!isEnabled || json.isNullOrEmpty() || json.length > MAX_IPC_CHARS) return
        val source = IpcSecurity.sanitizeSingleLine(processName, 128) ?: packageName
        val now = System.currentTimeMillis()
        val batch = runCatching { JsonParser.parseString(json).asJsonArray }.getOrNull() ?: return
        if (batch.size() > IPC_BATCH_SIZE) return
        batch.forEach { item ->
            val event = runCatching {
                val record = item.asJsonObject
                val timestamp = record.get("time").asLong.takeIf { it in 1L..now + 60_000L } ?: now
                val category = record.get("category").asString
                val message = record.get("message").asString
                sanitize("HOST/$category", "source=$source $message", timestamp)
            }.getOrNull()
            if (event != null) append(event)
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
