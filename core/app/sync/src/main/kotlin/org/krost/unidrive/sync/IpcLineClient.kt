package org.krost.unidrive.sync

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ByteChannel
import java.nio.channels.CancelledKeyException
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.TimeUnit

/** What one [IpcLineClient.readLine] came back with. */
sealed interface IpcLineRead {
    /** One whole line, without its newline. */
    data class Line(
        val text: String,
    ) : IpcLineRead

    /** The peer closed the connection before a whole line arrived; an unterminated tail is dropped. */
    data object Closed : IpcLineRead

    /** The deadline passed before a whole line arrived. */
    data object TimedOut : IpcLineRead
}

/** A line grew past the client's limit without a newline; the connection is not read any further. */
class IpcLineTooLongException(
    val limit: Int,
) : IOException("the IPC line is longer than $limit bytes")

/**
 * The line protocol of the daemon's IPC socket for its clients: one request line out, reply lines back, each
 * terminated by a newline (docs/dev/specs/ipc-authentication.md). One instance per connection, used by one thread.
 *
 * The bytes of a line are collected as they arrive and decoded as UTF-8 only once the line is whole: a read can end
 * inside one of the sequences of a multi-byte character, and decoding each chunk on its own turns that character
 * into U+FFFD. The buffer grows with the line (up to [maxLineBytes]), so a long reply is never cut short. What a read
 * brought past the first newline stays in the client for the next [readLine]; a caller that reads one reply per
 * request never loses the event that follows it.
 *
 * Works on a blocking or a non-blocking channel. On a non-blocking channel a wait for readability or writability
 * goes through a [Selector] (`select(remaining)`), not through a sleep-poll (gkrost/unidrive-cli#456: up to one
 * wake per 5 ms per round trip before), and a deadline is a `System.nanoTime()` instant, so a wall-clock step
 * cannot cut or stretch a request. A blocking channel's own reads and writes block as they always did.
 */
class IpcLineClient(
    private val channel: ByteChannel,
    private val maxLineBytes: Int = DEFAULT_MAX_LINE_BYTES,
) {
    private var buffer = ByteArray(INITIAL_BUFFER_BYTES)
    private var length = 0

    // Bytes before this offset hold no newline: a line that arrives in pieces is scanned once, not once per piece.
    private var scanned = 0

    /** Bytes received past the end of the last line [readLine] returned. */
    fun pendingBytes(): Int = length

    /**
     * Writes [line] and its newline. False when the peer did not take all of it by [deadlineNanos] (a line that is
     * not written in full has no newline, so the daemon never dispatches it). A [line] that holds a line break would be
     * two requests: it is refused, so a caller that forgot to escape a value fails loudly instead of splitting.
     */
    fun writeLine(
        line: String,
        deadlineNanos: Long = NO_DEADLINE,
    ): Boolean {
        require('\n' !in line && '\r' !in line) { "an IPC request line holds no line break; escape it in the JSON string" }
        val out = ByteBuffer.wrap((line + "\n").toByteArray(Charsets.UTF_8))
        while (out.hasRemaining()) {
            channel.write(out)
            if (!out.hasRemaining()) break
            if (deadlinePassed(deadlineNanos)) return false
            awaitReady(SelectionKey.OP_WRITE, deadlineNanos)
        }
        return true
    }

    /**
     * The next whole line, [IpcLineRead.Closed] or [IpcLineRead.TimedOut]; throws [IpcLineTooLongException]. A line
     * that is already buffered is returned whatever the deadline; one that is still arriving in pieces is given up at
     * the deadline even when every piece comes in time (the deadline is checked between reads, after the first one).
     */
    fun readLine(deadlineNanos: Long = NO_DEADLINE): IpcLineRead {
        var read = false
        while (true) {
            takeLine()?.let { return IpcLineRead.Line(it) }
            if (length > maxLineBytes) throw IpcLineTooLongException(maxLineBytes)
            if (read && deadlinePassed(deadlineNanos)) return IpcLineRead.TimedOut
            if (length == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
            val n = channel.read(ByteBuffer.wrap(buffer, length, buffer.size - length))
            read = true
            when {
                n < 0 -> return IpcLineRead.Closed
                n > 0 -> length += n
                deadlinePassed(deadlineNanos) -> return IpcLineRead.TimedOut
                else -> awaitReady(SelectionKey.OP_READ, deadlineNanos)
            }
        }
    }

    /**
     * Waits until the channel is ready for the asked operation, or the deadline passes. One selector per wait,
     * closed with its key when the wait ends: a registration that outlived the wait would refuse the caller's
     * next `configureBlocking` (the auth client flips to blocking after the handshake). The selector is
     * level-triggered, so bytes that arrived between the empty read and the registration are not lost.
     */
    private fun awaitReady(
        interestOps: Int,
        deadlineNanos: Long,
    ) {
        val selectable = channel as? SelectableChannel ?: return fallbackWait(deadlineNanos)
        if (selectable.isBlocking) return // the next read or write blocks, as a blocking channel's always did
        try {
            Selector.open().use { selector ->
                selectable.register(selector, interestOps)
                val remainingMs =
                    if (deadlineNanos == NO_DEADLINE) 0L // select blocks until ready, as a blocking channel would
                    else TimeUnit.NANOSECONDS.toMillis((deadlineNanos - System.nanoTime()).coerceAtLeast(0L))
                if (remainingMs <= 0L && deadlineNanos != NO_DEADLINE) return
                selector.select(remainingMs)
            }
        } catch (_: CancelledKeyException) {
            // The channel is gone; the next read answers Closed.
        } catch (_: IOException) {
            // The selector died with the channel; the next read answers Closed or TimedOut.
        }
    }

    // A channel the selector cannot watch: the old 5 ms sleep, so a test fake behaves as it did.
    private fun fallbackWait(deadlineNanos: Long) {
        if (!deadlinePassed(deadlineNanos)) Thread.sleep(POLL_MS)
    }

    private fun deadlinePassed(deadlineNanos: Long): Boolean = deadlineNanos != NO_DEADLINE && System.nanoTime() >= deadlineNanos

    private fun takeLine(): String? {
        var newline = -1
        for (i in scanned until length) {
            if (buffer[i] == NEWLINE) {
                newline = i
                break
            }
        }
        if (newline < 0) {
            scanned = length
            return null
        }
        // The limit is on the line itself: bytes of later lines that the same read brought are not counted.
        if (newline > maxLineBytes) throw IpcLineTooLongException(maxLineBytes)
        val line = String(buffer, 0, newline, Charsets.UTF_8)
        val rest = length - newline - 1
        System.arraycopy(buffer, newline + 1, buffer, 0, rest)
        length = rest
        scanned = 0
        return line
    }

    companion object {
        /** No deadline: a blocking connection that the peer ends. */
        const val NO_DEADLINE: Long = Long.MAX_VALUE

        /** Far above any reply of the daemon (a listing of a large folder is megabytes), well below the heap. */
        const val DEFAULT_MAX_LINE_BYTES: Int = 64 * 1024 * 1024

        private const val INITIAL_BUFFER_BYTES = 8192
        private const val POLL_MS = 5L
        private const val NEWLINE = '\n'.code.toByte()
    }
}
