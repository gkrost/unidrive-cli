package org.krost.unidrive.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #531: a stop of the daemon must not leave the server's own transport threads behind because one task is wedged. */
class IpcServerCloseTest {
    private lateinit var socketDir: Path
    private lateinit var socketPath: Path
    private var server: IpcServer? = null

    @BeforeTest
    fun setUp() {
        socketDir = Files.createTempDirectory("unidrive-ipc-close")
        socketPath = socketDir.resolve("c.sock")
    }

    @AfterTest
    fun tearDown() {
        server?.close()
        Files.deleteIfExists(socketPath)
        Files.deleteIfExists(socketDir)
    }

    @Test
    fun `close interrupts a transport task that is still running after the wait`() =
        runBlocking(Dispatchers.IO) {
            val serverScope = CoroutineScope(coroutineContext + SupervisorJob())
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val wedgedThread = AtomicReference<Thread>()
            var client: SocketChannel? = null
            try {
                // An unconfined handler runs on the transport thread itself, and blocks it without a suspension point.
                server = IpcServer(socketPath, handlerDispatcher = Dispatchers.Unconfined).also { it.transportShutdownWaitMs = 100L }
                server!!.registerHandler("wedge") { _, _ ->
                    wedgedThread.set(Thread.currentThread())
                    entered.countDown()
                    release.await()
                    """{"ok":true}"""
                }
                server!!.start(serverScope)
                withTimeout(5_000) { while (!Files.exists(socketPath)) delay(10) }
                client = SocketChannel.open(StandardProtocolFamily.UNIX).also { it.connect(UnixDomainSocketAddress.of(socketPath)) }
                val request = ByteBuffer.wrap("""{"verb":"wedge"}""".plus("\n").toByteArray(Charsets.UTF_8))
                while (request.hasRemaining()) client.write(request)
                assertTrue(entered.await(5, TimeUnit.SECONDS), "the handler never started")

                server!!.close()

                val thread = checkNotNull(wedgedThread.get())
                thread.join(5_000)
                assertFalse(thread.isAlive, "the transport thread ${thread.name} outlived close(): it was never interrupted")
            } finally {
                release.countDown()
                runCatching { client?.close() }
                serverScope.cancel()
            }
        }
}
