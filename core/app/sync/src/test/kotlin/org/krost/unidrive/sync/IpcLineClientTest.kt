package org.krost.unidrive.sync

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.ByteChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#217: the one line client of the IPC socket. It reads through channels that hand out a few bytes
 * at a time, as a socket may, and the bytes of a line are decoded once the line is whole.
 */
class IpcLineClientTest {
    /** A channel that returns [chunks] bytes per read in turn (0 = nothing yet), then [eof]; writes [writeChunks] per call. */
    private class Trickle(
        incoming: ByteArray,
        private val chunks: IntArray = intArrayOf(1, 2, 3),
        private val eof: Boolean = true,
        private val writeChunks: IntArray = intArrayOf(1, 0, 3),
    ) : ByteChannel {
        private val data = ByteBuffer.wrap(incoming)
        private var reads = 0
        private var writes = 0
        val written = ByteArrayOutputStream()

        override fun read(dst: ByteBuffer): Int {
            if (!data.hasRemaining()) return if (eof) -1 else 0
            val n = minOf(chunks[reads++ % chunks.size], data.remaining(), dst.remaining())
            repeat(n) { dst.put(data.get()) }
            return n
        }

        override fun write(src: ByteBuffer): Int {
            val n = minOf(writeChunks[writes++ % writeChunks.size], src.remaining())
            repeat(n) { written.write(src.get().toInt()) }
            return n
        }

        override fun isOpen(): Boolean = true

        override fun close() {}
    }

    private fun lines(
        text: String,
        chunks: IntArray = intArrayOf(1, 2, 3),
        eof: Boolean = true,
    ) = IpcLineClient(Trickle(text.toByteArray(Charsets.UTF_8), chunks, eof))

    private fun IpcLineClient.next(deadline: Long = IpcLineClient.NO_DEADLINE) = (readLine(deadline) as IpcLineRead.Line).text

    @Test
    fun `a line over 4 KiB whose multi-byte characters straddle every read comes out byte-exact`() {
        val line = (0 until 400).joinToString("") { "€ü😀$it-" }
        assertTrue(line.toByteArray().size > 4096)

        assertEquals(line, lines(line + "\n").next())
    }

    @Test
    fun `every split of a multi-byte line decodes the same`() {
        val line = "a€ü😀\u0001}b"
        for (chunk in 1..9) assertEquals(line, lines(line + "\n", intArrayOf(chunk)).next(), "reads of $chunk byte(s)")
    }

    @Test
    fun `a line far past 16 KiB is not truncated`() {
        val line = "x".repeat(300_000)

        assertEquals(line, lines(line + "\n", intArrayOf(65_536, 7, 8_192)).next())
    }

    @Test
    fun `what a read brought past the newline is kept for the next line`() {
        val client = lines("one\ntwo\nthree\n", intArrayOf(64))

        assertEquals("one", client.next())
        assertEquals(10, client.pendingBytes())
        assertEquals("two", client.next())
        assertEquals("three", client.next())
        assertEquals(0, client.pendingBytes())
        assertEquals(IpcLineRead.Closed, client.readLine())
    }

    @Test
    fun `an empty line is a line`() {
        val client = lines("\nx\n")

        assertEquals("", client.next())
        assertEquals("x", client.next())
    }

    @Test
    fun `an unterminated tail is closed, not a line`() {
        val client = lines("whole\npart")

        assertEquals("whole", client.next())
        assertEquals(IpcLineRead.Closed, client.readLine())
    }

    @Test
    fun `no reply by the deadline is timed out, with the bytes so far kept`() {
        val client = IpcLineClient(Trickle("par".toByteArray(), eof = false))

        assertEquals(IpcLineRead.TimedOut, client.readLine(System.currentTimeMillis() + 50))
    }

    @Test
    fun `a line that has arrived is returned past the deadline`() {
        assertEquals("ready", lines("ready\n", chunks = intArrayOf(64)).next(deadline = 0))
    }

    @Test
    fun `a second line that came with the first is returned past the deadline`() {
        val client = lines("one\ntwo\n", chunks = intArrayOf(64))

        assertEquals("one", client.next())
        assertEquals("two", client.next(deadline = 0))
    }

    @Test
    fun `a line that keeps arriving in pieces is timed out at the deadline`() {
        // One byte per read, each read taking 15 ms: every piece is in time, the whole line never is.
        val slow =
            object : ByteChannel {
                private val data = ByteBuffer.wrap(ByteArray(1000) { 'a'.code.toByte() })

                override fun read(dst: ByteBuffer): Int {
                    Thread.sleep(15)
                    dst.put(data.get())
                    return 1
                }

                override fun write(src: ByteBuffer): Int = 0

                override fun isOpen() = true

                override fun close() {}
            }
        val start = System.currentTimeMillis()

        assertEquals(IpcLineRead.TimedOut, IpcLineClient(slow).readLine(start + 100))
        assertTrue(System.currentTimeMillis() - start < 1000, "the deadline bounds the wait")
    }

    @Test
    fun `an oversized line is refused even when it arrives together with its newline`() {
        assertFailsWith<IpcLineTooLongException> {
            IpcLineClient(Trickle("123456789\n".toByteArray(), intArrayOf(64)), maxLineBytes = 4).readLine()
        }
    }

    @Test
    fun `later lines in the same read do not count against the limit of the first`() {
        val client = IpcLineClient(Trickle("abcd\nefgh\n".toByteArray(), intArrayOf(64)), maxLineBytes = 4)

        assertEquals("abcd", client.next())
        assertEquals("efgh", client.next())
    }

    @Test
    fun `a line longer than the limit fails instead of growing`() {
        val client = IpcLineClient(Trickle(ByteArray(10_000) { 'a'.code.toByte() }, intArrayOf(512)), maxLineBytes = 4096)

        assertFailsWith<IpcLineTooLongException> { client.readLine() }
    }

    @Test
    fun `a line at the limit is returned`() {
        val line = "a".repeat(4096)

        assertEquals(line, IpcLineClient(Trickle((line + "\n").toByteArray(), intArrayOf(512)), maxLineBytes = 4096).next())
    }

    @Test
    fun `writeLine writes the whole line and its newline through partial writes`() {
        val channel = Trickle(ByteArray(0))

        assertTrue(IpcLineClient(channel).writeLine("""{"verb":"x","n":"€😀"}"""))

        assertEquals("""{"verb":"x","n":"€😀"}""" + "\n", channel.written.toString(Charsets.UTF_8))
    }

    @Test
    fun `writeLine gives up at the deadline when the peer takes nothing`() {
        val channel = Trickle(ByteArray(0), writeChunks = intArrayOf(0))

        assertFalse(IpcLineClient(channel).writeLine("abc", System.currentTimeMillis() + 50))
    }

    @Test
    fun `writeLine refuses a line break so that a request cannot split in two`() {
        val client = IpcLineClient(Trickle(ByteArray(0)))

        assertFailsWith<IllegalArgumentException> { client.writeLine("{\"prefix\":\"a\nb\"}") }
        assertFailsWith<IllegalArgumentException> { client.writeLine("a\rb") }
    }

    @Test
    fun `a read that fails is the caller's exception`() {
        val channel =
            object : ByteChannel {
                override fun read(dst: ByteBuffer): Int = throw java.io.IOException("reset")

                override fun write(src: ByteBuffer): Int = 0

                override fun isOpen() = true

                override fun close() {}
            }

        val e = assertFailsWith<java.io.IOException> { IpcLineClient(channel).readLine() }
        assertEquals("reset", e.message)
    }
}
