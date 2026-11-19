package org.krost.unidrive.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.krost.unidrive.testing.TestScratch
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * gkrost/unidrive-cli#760: a request whose client goes away while it runs. A verb registered as cancel-on-peer-gone
 * (the daemon registers `hydration.locate`) is cancelled and its connection slot freed; any other verb runs to its end.
 */
class IpcServerPeerGoneCancelTest {
    private lateinit var socketDir: Path
    private lateinit var socketPath: Path
    private var server: IpcServer? = null
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val clients = mutableListOf<SocketChannel>()

    @BeforeTest
    fun setUp() {
        socketDir = TestScratch.directory("unidrive-ipc-peer-gone")
        socketPath = socketDir.resolve("peer-gone.sock")
    }

    @AfterTest
    fun tearDown() {
        clients.forEach { runCatching { it.close() } }
        serverScope.cancel()
        server?.close()
        socketDir.toFile().deleteRecursively()
    }

    private fun newServer(maxClients: Int = 8): IpcServer =
        IpcServer(socketPath, maxClients = maxClients, idleTimeoutMs = 0).also {
            server = it
            it.registerHandler("ping") { _, _ -> """{"ok":true}""" }
        }

    @Test
    fun `a cancel-on-peer-gone request is cancelled when its client goes away`() =
        runBlocking(Dispatchers.IO) {
            val srv = newServer()
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            srv.registerHandler("stall") { _, _ ->
                started.complete(Unit)
                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    cancelled.complete(Unit)
                    throw e
                }
            }
            srv.registerCancelOnPeerGoneVerbs(listOf("stall"))
            srv.start(serverScope)

            val client = connect()
            send(client, """{"verb":"stall"}""")
            withTimeout(10_000) { started.await() }
            client.close()

            withTimeout(10_000) { cancelled.await() }
        }

    @Test
    fun `abandoned cancel-on-peer-gone requests free their connection slots`() =
        runBlocking(Dispatchers.IO) {
            val cap = 4
            val srv = newServer(maxClients = cap)
            val started = AtomicInteger(0)
            val running = AtomicInteger(0)
            srv.registerHandler("stall") { _, _ ->
                started.incrementAndGet()
                running.incrementAndGet()
                try {
                    awaitCancellation()
                } finally {
                    running.decrementAndGet()
                }
            }
            srv.registerCancelOnPeerGoneVerbs(listOf("stall"))
            srv.start(serverScope)

            // As many timed-out callers as the cap: each sends, waits a while, gives up and goes away.
            repeat(cap) {
                val client = connect()
                send(client, """{"verb":"stall"}""")
                waitUntil("request ${it + 1} runs") { started.get() == it + 1 }
                client.close()
            }

            waitUntil("every abandoned request ended and its slot is free") { running.get() == 0 && srv.clientCount == 0 }
            assertEquals("""{"ok":true}""", roundTrip(connect(), """{"verb":"ping"}"""), "an unrelated request is served, not refused")
        }

    @Test
    fun `a request of any other verb runs to its end after its client goes away`() =
        runBlocking(Dispatchers.IO) {
            val srv = newServer()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Boolean>()
            srv.registerHandler("write") { _, _ ->
                started.complete(Unit)
                try {
                    release.await()
                    finished.complete(true)
                } catch (e: CancellationException) {
                    finished.complete(false)
                    throw e
                }
                """{"ok":true}"""
            }
            srv.registerCancelOnPeerGoneVerbs(listOf("stall")) // another verb
            srv.start(serverScope)

            val client = connect()
            send(client, """{"verb":"write"}""")
            withTimeout(10_000) { started.await() }
            client.close()
            delay(500) // far past the peer watch's start: the close was seen
            assertEquals(1, srv.clientCount, "the running request still holds its connection")

            release.complete(Unit)
            assertEquals(true, withTimeout(10_000) { finished.await() }, "the handler ran to its end, not cancelled")
            waitUntil("the connection is removed after the request") { srv.clientCount == 0 }
        }

    private fun connect(): SocketChannel =
        SocketChannel.open(StandardProtocolFamily.UNIX).also {
            it.connect(UnixDomainSocketAddress.of(socketPath))
            clients += it
        }

    private fun send(client: SocketChannel, request: String) {
        val buf = ByteBuffer.wrap((request + "\n").toByteArray(Charsets.UTF_8))
        while (buf.hasRemaining()) client.write(buf)
    }

    private fun roundTrip(client: SocketChannel, request: String): String {
        send(client, request)
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteBuffer.allocate(4096)
        while (!out.toString(Charsets.UTF_8).contains('\n')) {
            buf.clear()
            val n = client.read(buf)
            if (n < 0) break
            out.write(buf.array(), 0, n)
        }
        return out.toString(Charsets.UTF_8).substringBefore('\n')
    }

    private suspend fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting until $what" }
            delay(10)
        }
    }
}
