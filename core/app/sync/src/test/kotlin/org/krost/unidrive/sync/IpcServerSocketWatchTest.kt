package org.krost.unidrive.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.testing.TestScratch
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
                val buf = ByteBuffer.allocate(4096)
                val deadline = System.currentTimeMillis() + 3_000
                var n = 0
                while (n <= 0 && System.currentTimeMillis() < deadline) {
                    c.configureBlocking(false)
                    n = c.read(buf)
                    if (n == 0) Thread.sleep(20)
                }
                n > 0
            }
        } catch (_: java.io.IOException) {
            false
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
                server = IpcServer(socket, socketWatchIntervalMs = 50).also { it.start(scope) }
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
                server = IpcServer(socket, socketWatchIntervalMs = 50).also { it.start(scope) }
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
                server = IpcServer(socket, socketWatchIntervalMs = 20).also { it.start(scope) }

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
                server = IpcServer(socket, socketWatchIntervalMs = 0).also { it.start(scope) }
                Files.delete(socket)

                delay(300)

                assertFalse(Files.exists(socket))
                assertEquals(0, server!!.socketRebinds)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a closed server does not bind its socket again`() =
        runBlocking(Dispatchers.IO) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val socket = base.resolve("d.sock")
                val s = IpcServer(socket, socketWatchIntervalMs = 20).also { it.start(scope) }
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
