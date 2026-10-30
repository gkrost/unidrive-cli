package org.krost.unidrive.sync

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ByteChannel

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
 * Works on a blocking or a non-blocking channel. A non-blocking read that returns 0 is polled until the deadline; a
 * blocking read is only interrupted by the peer, as a blocking read always is.
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
     * Writes [line] and its newline. False when the peer did not take all of it by [deadlineMs] (a line that is not
     * written in full has no newline, so the daemon never dispatches it). A [line] that holds a line break would be
     * two requests: it is refused, so a caller that forgot to escape a value fails loudly instead of splitting.
     */
    fun writeLine(
        line: String,
        deadlineMs: Long = NO_DEADLINE,
    ): Boolean {
        require('\n' !in line && '\r' !in line) { "an IPC request line holds no line break; escape it in the JSON string" }
        val out = ByteBuffer.wrap((line + "\n").toByteArray(Charsets.UTF_8))
        while (out.hasRemaining()) {
            channel.write(out)
            if (out.hasRemaining()) {
                if (System.currentTimeMillis() >= deadlineMs) return false
                Thread.sleep(POLL_MS)
            }
        }
        return true
    }

    /** The next whole line, [IpcLineRead.Closed] or [IpcLineRead.TimedOut]; throws [IpcLineTooLongException]. */
    fun readLine(deadlineMs: Long = NO_DEADLINE): IpcLineRead {
        while (true) {
            takeLine()?.let { return IpcLineRead.Line(it) }
            if (length > maxLineBytes) throw IpcLineTooLongException(maxLineBytes)
            if (length == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
            val n = channel.read(ByteBuffer.wrap(buffer, length, buffer.size - length))
            when {
                n < 0 -> return IpcLineRead.Closed
                n > 0 -> length += n
                System.currentTimeMillis() >= deadlineMs -> return IpcLineRead.TimedOut
                else -> Thread.sleep(POLL_MS)
            }
        }
    }

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
