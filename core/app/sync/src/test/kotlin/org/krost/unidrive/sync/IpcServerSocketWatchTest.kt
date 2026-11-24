package org.krost.unidrive.sync

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.testing.TestScratch
import org.slf4j.LoggerFactory
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
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
 * gkrost/unidrive-cli#794: a daemon binds its socket once, and a cleanup of the folder it lies in left it healthy but
 * unreachable. The server now checks that its socket file is there and binds it again when it is not.
 */
class IpcServerSocketWatchTest {
    private lateinit var base: Path
    private var server: IpcServer? = null

    @BeforeTest
    fun setUp() {
        base = TestScratch.directory("ipc-watch-test")
    }

    @AfterTest
    fun tearDown() {
        server?.close()
    }

    private fun canConnect(socket: Path): Boolean =
        try {
            SocketChannel.open(UnixDomainSocketAddress.of(socket)).close()
            true
        } catch (_: java.io.IOException) {
            false
        }

    /** A request line the server answers (an unknown verb gets an error reply), so the new listener is shown to serve. */
    private fun answers(socket: Path): Boolean =
        try {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { c ->
                c.connect(UnixDomainSocketAddress.of(socket))
                c.write(ByteBuffer.wrap("""{"verb":"no.such.verb"}""".toByteArray() + '\n'.code.toByte()))
                readSomeBytes(c) > 0
            }
        } catch (_: java.io.IOException) {
            false
        }

    // The bytes the server sends back, waiting up to 3 s for the first of them.
    private fun readSomeBytes(c: SocketChannel): Int {
        c.configureBlocking(false)
        val buf = ByteBuffer.allocate(4096)
        val deadline = System.currentTimeMillis() + 3_000
        var n = c.read(buf)
        while (n == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            n = c.read(buf)
        }
        return n
    }

    private suspend fun await(
        timeoutMs: Long = 10_000,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(25)
        }
        return condition()
    }

    @Test
    fun `a deleted socket file is bound again and a new client is served`() =
        runBlocking(Dispatchers.IO) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val socket = base.resolve("d.sock")
                server = IpcServer(socket).also { it.socketWatchIntervalMs = 50; it.start(scope) }
                assertTrue(canConnect(socket), "the daemon answers before the cleanup")

                Files.delete(socket) // what a temp cleanup does to a live daemon
                assertFalse(canConnect(socket), "and is unreachable after it")

                assertTrue(await { canConnect(socket) }, "the server binds the file again")
                assertTrue(answers(socket), "the new listener serves requests")
                assertEquals(1, server!!.socketRebinds)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a deleted folder is made again and the socket bound in it`() =
        runBlocking(Dispatchers.IO) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val folder = Files.createDirectory(base.resolve("ipc"))
                val socket = folder.resolve("d.sock")
                server = IpcServer(socket).also { it.socketWatchIntervalMs = 50; it.start(scope) }
                assertTrue(canConnect(socket))

                Files.delete(socket)
                Files.delete(folder) // the whole folder, not only the file

                assertTrue(await { canConnect(socket) }, "the folder and the socket are made again")
                assertTrue(Files.isDirectory(folder))
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a socket that is there is left alone`() =
        runBlocking(Dispatchers.IO) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val socket = base.resolve("d.sock")
                server = IpcServer(socket).also { it.socketWatchIntervalMs = 20; it.start(scope) }

                delay(400) // many rounds of the watcher

                assertEquals(0, server!!.socketRebinds, "nothing was rebound while the file was there")
                assertTrue(canConnect(socket))
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a server whose watcher is off does not bind a deleted file again`() =
        runBlocking(Dispatchers.IO) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val socket = base.resolve("d.sock")
                server = IpcServer(socket).also { it.socketWatchIntervalMs = 0; it.start(scope) }
                Files.delete(socket)

                delay(300)

                assertFalse(Files.exists(socket))
                assertEquals(0, server!!.socketRebinds)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun healthy_probes_do_not_enter_client_accounting_or_log_refusals() =
        runBlocking(Dispatchers.IO) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val logger = LoggerFactory.getLogger(IpcServer::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val closedClients = java.util.concurrent.atomic.AtomicInteger()
            val socket = base.resolve("d.sock")
            val ipc = IpcServer(socket, maxClients = 1).also { server = it }
            ipc.socketWatchIntervalMs = 20
            ipc.registerConnectionCloseListener { closedClients.incrementAndGet() }
            logger.addAppender(appender)
            try {
                ipc.start(scope)
                assertTrue(await { ipc.socketProbeSuccesses >= 2 })
                assertEquals(0, closedClients.get(), "idle probes did not create client sessions")

                SocketChannel.open(UnixDomainSocketAddress.of(socket)).use {
                    assertTrue(await { ipc.clientCount == 1 })
                    val before = ipc.socketProbeSuccesses
                    assertTrue(await { ipc.socketProbeSuccesses >= before + 2 })
                    assertEquals(1, ipc.clientCount)
                    assertEquals(0, closedClients.get())
                    assertEquals(0, ipc.socketRebinds)
                    assertFalse(appender.list.any { event -> "refusing connection" in event.formattedMessage })
                }
            } finally {
                logger.detachAppender(appender)
                appender.stop()
                scope.cancel()
            }
        }

    @Test
    fun watcher_rebinds_when_the_socket_file_is_replaced_by_another_inode() =
        runBlocking(Dispatchers.IO) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val foreign = java.nio.channels.ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            try {
                val socket = base.resolve("d.sock")
                server = IpcServer(socket).also { it.socketWatchIntervalMs = 200; it.start(scope) }
                assertTrue(answers(socket), "the daemon answers before the swap")

                // Another listener takes the name between two rounds of the watcher: the file is there, but not ours.
                Files.delete(socket)
                foreign.bind(UnixDomainSocketAddress.of(socket))

                assertTrue(await { server!!.socketRebinds == 1 }, "the watcher sees another inode and binds the file again")
                assertTrue(answers(socket), "our listener serves the name again")
            } finally {
                foreign.close()
                scope.cancel()
            }
        }

    // A file system can give the replacement the inode the deleted file freed (CI's did): the same dev+ino, another listener.
    @Test
    fun watcher_rebinds_when_a_replacement_reuses_the_inode() =
        runBlocking(Dispatchers.IO) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val foreign = java.nio.channels.ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            try {
                val socket = base.resolve("d.sock")
                server =
                    IpcServer(socket).also {
                        it.socketWatchIntervalMs = 200
                        it.socketFileIdentity = { "the same inode" }
                        it.start(scope)
                    }
                assertTrue(answers(socket), "the daemon answers before the swap")

                Files.delete(socket)
                foreign.bind(UnixDomainSocketAddress.of(socket))

                assertTrue(await { server!!.socketRebinds == 1 }, "the watcher sees that its listener no longer answers there")
                assertTrue(answers(socket), "our listener serves the name again")
            } finally {
                foreign.close()
                scope.cancel()
            }
        }

    @Test
    fun `a closed server does not bind its socket again`() =
        runBlocking(Dispatchers.IO) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val socket = base.resolve("d.sock")
                val s = IpcServer(socket).also { it.socketWatchIntervalMs = 20; it.start(scope) }
                server = s
                s.close()
                assertFalse(Files.exists(socket), "close removes the socket it bound")

                delay(300)

                assertFalse(Files.exists(socket), "and the watcher is stopped with it")
            } finally {
                scope.cancel()
            }
        }
}
