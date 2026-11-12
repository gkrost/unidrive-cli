package org.krost.unidrive.sync

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#456: on a non-blocking channel the client waits for readiness in a selector instead of
 * waking every 5 ms, and its deadlines are `System.nanoTime()` instants, so a wall-clock step cannot cut or
 * stretch a request. These tests drive the waits over a real unix-domain pair whose peer is silent (the slow
 * reader of the transport-pool starvation note), slow, and then cooperative.
 */
class IpcLineClientSelectorTest {
    private lateinit var dir: Path
    private lateinit var server: ServerSocketChannel
    private lateinit var peer: SocketChannel

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ud-line-sel-")
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(dir.resolve("s.sock")))
    }

    @AfterTest
    fun tearDown() {
        runCatching { peer?.close() }
        runCatching { server.close() }
        runCatching { dir.toFile().deleteRecursively() }
    }

    private fun connect(): SocketChannel {
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        channel.connect(server.getLocalAddress())
        peer = server.accept()
        channel.configureBlocking(false)
        return channel
    }

    @Test
    fun `a read on a silent peer gives up at the deadline`() {
        connect().use { channel ->
            val lines = IpcLineClient(channel)
                val deadline = System.nanoTime() + 200_000_000L
                val start = System.nanoTime()

                val read = lines.readLine(deadline)

                assertEquals(IpcLineRead.TimedOut, read)
                assertTrue(System.nanoTime() - start >= 150_000_000L, "the wait lasted for the deadline")
        }
    }

    @Test
    fun `a write the peer never reads gives up at the deadline`() {
        connect().use { channel ->
            val lines = IpcLineClient(channel)
                val deadline = System.nanoTime() + 300_000_000L
                val start = System.nanoTime()

                // Far more than any socket send buffer: the peer never reads, so the line cannot go out whole.
                val written = lines.writeLine("x".repeat(4_000_000), deadline)

                assertFalse(written, "the line did not go out whole")
                assertTrue(System.nanoTime() - start >= 250_000_000L, "the wait lasted for the deadline")
        }
    }

    @Test
    fun `a write goes out whole once the peer starts reading`() {
        connect().use { channel ->
            val lines = IpcLineClient(channel)
                // The peer reads everything only after a moment: the writer waits for readiness, not for its deadline.
                val slowReader = Thread {
                    runCatching {
                        val buffer = java.nio.ByteBuffer.allocate(64 * 1024)
                        while (peer.read(buffer) >= 0 || !Thread.currentThread().isInterrupted) {
                            buffer.clear()
                            Thread.sleep(10)
                        }
                    }
                }
                slowReader.isDaemon = true
                slowReader.start()
                Thread.sleep(100)

                val deadline = System.nanoTime() + 10_000_000_000L
                assertTrue(lines.writeLine("y".repeat(2_000_000), deadline), "the line went out whole once the peer read")
        }
    }
}
