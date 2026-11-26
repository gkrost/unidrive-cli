package org.krost.unidrive.sync

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

// The longest pause between two tries of a write whose send buffer is full.
private const val WRITE_POLL_MAX_MS = 10L

// Writes all of [buf] (moved out of IpcServer, no behaviour change). The write timeout is a deadline on progress: it starts
// again whenever bytes go out, so a large reply that a client reads slowly is never cut while it moves, and a peer that takes
// nothing for the whole timeout fails the write (its connection is then dropped). A full send buffer is waited out with delay,
// not by sleeping: the hydration subscriber writers run on the daemon's event loop, which a sleeping write would stall. Not
// cancellable half way, so a cancelled writer cannot leave a torn line on the connection.
internal suspend fun writeAllNonBlocking(
    client: SocketChannel,
    buf: ByteBuffer,
    writeTimeoutNs: Long,
) = withContext(NonCancellable) {
    var deadline = System.nanoTime() + writeTimeoutNs
    var pauseMs = 1L
    while (buf.hasRemaining()) {
        if (client.write(buf) > 0) {
            deadline = System.nanoTime() + writeTimeoutNs
            pauseMs = 1L
            continue
        }
        if (System.nanoTime() > deadline) {
            throw IOException("Write timeout exceeded for IPC client: no progress for ${writeTimeoutNs / 1_000_000L} ms")
        }
        delay(pauseMs)
        pauseMs = minOf(pauseMs * 2, WRITE_POLL_MAX_MS)
    }
}
