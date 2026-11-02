package org.krost.unidrive.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The write timeout is a deadline on progress, not on the whole reply: a large reply that a client reads slowly but
 * steadily arrives whole. A peer that stops reading is waited out without holding the thread the writer runs on (the
 * hydration subscriber writers run on the daemon's single event loop), and is dropped when the timeout passes with no
 * progress.
 */
class IpcServerWriteProgressTest {
    private lateinit var socketDir: Path
    private lateinit var socketPath: Path
    private var server: IpcServer? = null

    @BeforeTest
    fun setUp() {
        socketDir = Files.createTempDirectory("unidrive-ipc-write")
        socketPath = socketDir.resolve("w.sock")
    }

    @AfterTest
    fun tearDown() {
        server?.close()
        Files.deleteIfExists(socketPath)
        Files.deleteIfExists(socketDir)
    }

    @Test
    fun `a large reply read slowly but steadily arrives whole`() =
        runBlocking(Dispatchers.IO) {
            val serverScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val replySize = 8 * 1024 * 1024
                server = IpcServer(socketPath, writeTimeoutMs = WRITE_TIMEOUT_MS)
                server!!.registerHandler("big") { _, _ -> "x".repeat(replySize) }
                server!!.start(serverScope)
                waitForSocket()

                val client = connectClient()
                send(client, """{"verb":"big"}""")
                // About 6 MB/s: well over the write timeout for the whole reply, but never a pause near it.
                val received =
                    withTimeout(30_000) {
                        val buf = ByteBuffer.allocate(128 * 1024)
                        var total = 0L
                        var lastByte: Byte = 0
                        while (lastByte != '\n'.code.toByte()) {
                            buf.clear()
                            val n = client.read(buf)
                            if (n < 0) break
                            if (n > 0) {
                                total += n
                                lastByte = buf.get(n - 1)
                            }
                            delay(20)
                        }
                        total
                    }
                assertEquals(replySize + 1L, received, "the whole reply and its newline arrive; the connection is not cut while bytes move")
                client.close()
            } finally {
                serverScope.cancel()
            }
        }

    @Test
    fun `a peer that stops reading neither holds the writer's thread nor stays connected`() {
        val closed = CopyOnWriteArrayList<String>()
        // The daemon's shape: one event loop thread runs the subscriber writers and everything else.
        runBlocking {
            val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            try {
                var connId: String? = null
                server = IpcServer(socketPath, writeTimeoutMs = WRITE_TIMEOUT_MS)
                server!!.registerHandler("hello") { id, _ ->
                    connId = id
                    """{"ok":true}"""
                }
                server!!.registerConnectionCloseListener { closed += it }
                server!!.start(serverScope)
                waitForSocket()

                val client = connectClient()
                send(client, """{"verb":"hello"}""")
                withTimeout(5_000) { while (connId == null) delay(10) }
                // The client reads nothing from here on.

                var ticking = true
                val ticks = mutableListOf<Long>()
                val ticker =
                    launch {
                        while (ticking) {
                            ticks += System.nanoTime()
                            delay(10)
                        }
                    }
                val writer = async { server!!.writeToConnection(connId!!, "y".repeat(16 * 1024 * 1024)) }
                val delivered = writer.await()
                ticking = false
                ticker.join()

                assertFalse(delivered, "a write to a peer that stopped reading fails once the timeout passes with no progress")
                assertTrue(connId in closed, "the stalled connection is dropped and its close listeners fire")
                val longestGapMs = ticks.zipWithNext { a, b -> (b - a) / 1_000_000 }.maxOrNull() ?: Long.MAX_VALUE
                assertTrue(
                    longestGapMs < WRITE_TIMEOUT_MS / 2,
                    "the event loop kept running while the write waited (longest gap $longestGapMs ms, write timeout $WRITE_TIMEOUT_MS ms)",
                )
                client.close()
            } finally {
                serverScope.cancel()
            }
        }
    }

    private suspend fun waitForSocket() {
        withTimeout(5_000) { while (!Files.exists(socketPath)) delay(10) }
    }

    private fun connectClient(): SocketChannel {
        val client = SocketChannel.open(StandardProtocolFamily.UNIX)
        client.connect(UnixDomainSocketAddress.of(socketPath))
        client.configureBlocking(false)
        return client
    }

    private fun send(
        client: SocketChannel,
        line: String,
    ) {
        val w = ByteBuffer.wrap((line + "\n").toByteArray(Charsets.UTF_8))
        while (w.hasRemaining()) client.write(w)
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 600L
    }
}
