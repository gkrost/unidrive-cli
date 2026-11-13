package org.krost.unidrive.sync

import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.TimeUnit

// gkrost/unidrive-cli#456: the wait for writability is a selector wait, not a sleep-poll. A peer that reads
// nothing used to pin one of the transport threads for the whole timeout in 5 ms sleeps — a few wedged
// connections starved the pool without any request ever being malformed. The thread now sits in select() and
// wakes the moment the peer takes bytes (or the timeout ends).
internal fun writeWhileWritable(
    sc: SocketChannel,
    line: ByteBuffer,
    deadline: Long,
) {
    Selector.open().use { selector ->
        sc.register(selector, SelectionKey.OP_WRITE)
        while (line.hasRemaining() && System.nanoTime() < deadline) {
            sc.write(line)
            if (!line.hasRemaining()) break
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remainingMs <= 0) break
            selector.select(remainingMs)
        }
    }
}
