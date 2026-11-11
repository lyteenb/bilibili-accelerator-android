package io.github.jh_mmm.biliaccelerator.btr

import java.io.OutputStream
import java.net.Socket

/** An absolute deadline for headers, then a separate deadline for each blocking write. */
internal class BtrDownstream(
    val socket: Socket,
    private val timeoutNanos: Long,
    private val clock: () -> Long = System::nanoTime
) : AutoCloseable {
    private var deadline: Long? = clock() + timeoutNanos

    @Synchronized
    fun headersRead() {
        deadline = null
    }

    @Synchronized
    fun expireIfOverdue(now: Long) {
        val limit = deadline ?: return
        if (now - limit >= 0L) {
            deadline = null
            close()
        }
    }

    fun outputStream(): OutputStream {
        val output = socket.getOutputStream()
        return object : OutputStream() {
            override fun write(value: Int) = withWriteDeadline { output.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) =
                withWriteDeadline { output.write(bytes, offset, length) }
            override fun flush() = withWriteDeadline { output.flush() }
        }
    }

    private inline fun withWriteDeadline(action: () -> Unit) {
        synchronized(this) { deadline = clock() + timeoutNanos }
        try {
            action()
        } finally {
            synchronized(this) { deadline = null }
        }
    }

    override fun close() = socket.close()
}
