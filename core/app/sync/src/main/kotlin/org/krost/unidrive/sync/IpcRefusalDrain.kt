package org.krost.unidrive.sync

import kotlinx.coroutines.delay
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

/** How [IpcServer]'s refusal (`too_many_clients`) reads what the refused peer still sends; moved out of IpcServer.kt for its size ratchet. */
internal object IpcRefusalDrain {
    private const val REFUSAL_LINGER_POLL_MS = 10L

    // Reads and drops what the refused peer sends until it closes (end of stream), a reset, the cap on
    // what is worth reading ([cap]) or [lingerMs]. A read that finds nothing yields the thread.
    suspend fun untilEofOrDeadline(
        sc: SocketChannel,
        lingerMs: Long,
        cap: Int,
    ) {
        val deadline = System.nanoTime() + lingerMs * 1_000_000L
        val sink = ByteBuffer.allocate(4096)
        var drained = 0
        try {
            while (drained < cap && System.nanoTime() < deadline) {
                sink.clear()
                val n = sc.read(sink)
                if (n < 0) return
                if (n == 0) delay(REFUSAL_LINGER_POLL_MS) else drained += n
            }
        } catch (_: IOException) {
            // A reset ends the wait like an end of stream does.
        }
    }

    // What has already arrived, without waiting (the fallback when too many refusals are lingering).
    fun available(
        sc: SocketChannel,
        cap: Int,
    ) {
        val sink = ByteBuffer.allocate(4096)
        var drained = 0
        while (drained < cap) {
            sink.clear()
            val n = sc.read(sink)
            if (n <= 0) break
            drained += n
        }
    }
}
