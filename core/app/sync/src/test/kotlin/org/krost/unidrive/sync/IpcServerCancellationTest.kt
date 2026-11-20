package org.krost.unidrive.sync

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
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
import org.slf4j.LoggerFactory
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * gkrost/unidrive-cli#841 (#743 finding 12): cancelling the server's scope (daemon stop) while a handler or a post-reply
 * hook runs must propagate the cancellation, not be reported as a handler failure. Before the fix the generic catch turned
 * it into an ERROR "handler threw" plus a `handler_threw` reply, and the post-reply hook's runCatching into a WARN.
 */
class IpcServerCancellationTest {
    private lateinit var socketDir: Path
    private lateinit var socketPath: Path
    private var server: IpcServer? = null
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val clients = mutableListOf<SocketChannel>()
    private val logger = LoggerFactory.getLogger(IpcServer::class.java) as ch.qos.logback.classic.Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeTest
    fun setUp() {
        socketDir = TestScratch.directory("unidrive-ipc-cancel")
        socketPath = socketDir.resolve("cancel.sock")
        appender.start()
        logger.addAppender(appender)
    }

    @AfterTest
    fun tearDown() {
        logger.detachAppender(appender)
        clients.forEach { runCatching { it.close() } }
        serverScope.cancel()
        server?.close()
        socketDir.toFile().deleteRecursively()
    }

    @Test
    fun `cancelling a running handler is neither logged as handler threw nor answered with handler_threw`() =
        runBlocking(Dispatchers.IO) {
            val srv = IpcServer(socketPath, maxClients = 8, idleTimeoutMs = 0).also { server = it }
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
            srv.start(serverScope)
            val client = connect()
            send(client, """{"verb":"stall"}""")
            withTimeout(10_000) { started.await() }

            serverScope.cancel()
            withTimeout(10_000) { cancelled.await() }
            delay(500)

            assertFalse(readAvailable(client).contains("handler_threw"), "no handler_threw reply for a cancellation")
            assertEquals(emptyList(), messagesAtLeast(Level.WARN).filter { "threw" in it })
        }

    @Test
    fun `cancelling a running post-reply hook is not logged as a hook failure`() =
        runBlocking(Dispatchers.IO) {
            val srv = IpcServer(socketPath, maxClients = 8, idleTimeoutMs = 0).also { server = it }
            val hookStarted = CompletableDeferred<Unit>()
            val hookCancelled = CompletableDeferred<Unit>()
            srv.registerHandler("subscribe") { connId, _ ->
                srv.scheduleAfterReply(connId) {
                    hookStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } catch (e: CancellationException) {
                        hookCancelled.complete(Unit)
                        throw e
                    }
                }
                """{"ok":true}"""
            }
            srv.start(serverScope)
            val client = connect()
            send(client, """{"verb":"subscribe"}""")
            withTimeout(10_000) { hookStarted.await() }

            serverScope.cancel()
            withTimeout(10_000) { hookCancelled.await() }
            delay(500)

            assertEquals(emptyList(), messagesAtLeast(Level.WARN).filter { "threw" in it })
        }

    private fun messagesAtLeast(level: Level): List<String> =
        appender.list.filter { it.level.isGreaterOrEqual(level) }.map { it.formattedMessage }

    private fun connect(): SocketChannel =
        SocketChannel.open(StandardProtocolFamily.UNIX).also {
            it.connect(UnixDomainSocketAddress.of(socketPath))
            clients += it
        }

    private fun send(client: SocketChannel, request: String) {
        val buf = ByteBuffer.wrap((request + "\n").toByteArray(Charsets.UTF_8))
        while (buf.hasRemaining()) client.write(buf)
    }

    private fun readAvailable(client: SocketChannel): String {
        client.configureBlocking(false)
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteBuffer.allocate(4096)
        while (true) {
            buf.clear()
            val n = runCatching { client.read(buf) }.getOrDefault(-1)
            if (n <= 0) break
            out.write(buf.array(), 0, n)
        }
        return out.toString(Charsets.UTF_8)
    }
}
