package org.krost.unidrive.internxt

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import io.socket.client.Socket
import io.socket.engineio.client.EngineIOException
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.net.ProtocolException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#225: socket.io calls the client's listeners on its own event thread (one for the whole process), and a
 * token refresh runs on the scheduler's thread. Neither carries the MDC of the daemon that opened the client, so the client's
 * lines printed `[*]` in the log two daemons share; they carry the MDC of the code that built the client now, and socket.io's
 * thread gets its own back after each listener. The MDC is read while each line is logged, on the thread that logs it.
 */
class NotificationsClientLogContextTest {
    private class Line(
        val message: String,
        val thread: String,
        val mdc: Map<String, String>,
    ) {
        override fun toString() = "'$message' on $thread with $mdc"
    }

    private class FakeSocket : NotificationsSocket {
        private val listeners = CopyOnWriteArrayList<Pair<String, (List<Any?>) -> Unit>>()

        override fun on(
            event: String,
            listener: (List<Any?>) -> Unit,
        ) {
            listeners += event to listener
        }

        override fun connect() {}

        override fun close() {}

        /** What socket.io does: the listener runs on a thread of the library's, which carries no MDC of ours. */
        fun emitFromLibraryThread(
            event: String,
            vararg args: Any?,
        ): Map<String, String>? {
            var after: Map<String, String>? = null
            thread(name = "fake-socket.io-event-thread") {
                listeners.filter { it.first == event }.forEach { it.second(args.toList()) }
                after = MDC.getCopyOfContextMap()
            }.join()
            return after
        }
    }

    private val logger = LoggerFactory.getLogger(NotificationsClient::class.java) as Logger
    private val lines = CopyOnWriteArrayList<Line>()
    private val appender =
        object : AppenderBase<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                lines += Line(event.formattedMessage, event.threadName, HashMap(event.mdcPropertyMap))
            }
        }

    @BeforeTest
    fun attach() {
        appender.start()
        logger.level = Level.DEBUG
        logger.addAppender(appender)
    }

    @AfterTest
    fun detach() {
        logger.detachAppender(appender)
        logger.level = null
        MDC.clear()
    }

    @Test
    fun `lines from socket io's thread and the refresh thread carry the MDC of the code that built the client`() {
        val sockets = CopyOnWriteArrayList<FakeSocket>()
        MDC.put("profile", "nx-profile")
        MDC.put("build", "nx-build")
        val client =
            NotificationsClient(
                notificationsUrl = "https://example.invalid/socket.io",
                tokenSupplier = { "fresh-token" },
                onRemoteChange = {},
                socketFactory = { _, _ -> FakeSocket().also { sockets += it } },
            )
        MDC.clear()
        try {
            client.connect("first-token")
            val first = sockets.single()

            val afterConnect = first.emitFromLibraryThread(Socket.EVENT_CONNECT)
            first.emitFromLibraryThread("event", null)
            // An authentication rejection: the refresh and the rebuild run on the scheduler's thread.
            first.emitFromLibraryThread(Socket.EVENT_CONNECT_ERROR, EngineIOException("websocket error", ProtocolException("Expected HTTP 101 response but was '401 Unauthorized'")))
            val deadline = System.nanoTime() + 10_000_000_000L
            while (lines.none { it.message.contains("rebuilt") } && System.nanoTime() < deadline) Thread.sleep(10)

            assertTrue(lines.any { it.message == "Internxt notifications WS connected" }, "the connect line: $lines")
            assertTrue(lines.any { it.message.startsWith("Internxt notification (clientId=") }, "the frame line: $lines")
            assertTrue(lines.any { it.message.startsWith("Internxt notifications unreachable") }, "the outage line: $lines")
            assertTrue(lines.any { it.message.contains("rebuilt") && it.thread != "fake-socket.io-event-thread" }, "the refresh line: $lines")
            for (line in lines) {
                assertEquals("nx-profile", line.mdc["profile"], "the profile of $line")
                assertEquals("nx-build", line.mdc["build"], "the build of $line")
            }
            assertTrue(afterConnect.isNullOrEmpty(), "socket.io's thread has its own (empty) MDC back after the listener: $afterConnect")
        } finally {
            client.disconnect()
        }
    }
}
