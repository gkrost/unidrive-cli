package org.krost.unidrive.onedrive

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.AppenderBase
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.socket.client.Socket
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.RemoteChangeChannelStatus.State
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [OneDriveChangeChannel] (archive#111) over a fake socket, a fake clock and a hand-driven scheduler: rotation at the
 * subscription's expiry, recovery after a server disconnect or a connect error, the back-off of a failing endpoint
 * request, the hints, and that a stop ends everything. No network. Every URL and token here is made up.
 */
class OneDriveChangeChannelTest {
    private var now = 1_700_000_000_000L
    private val scheduler = FakeScheduler()
    private val sockets = mutableListOf<FakeSocket>()
    private val factory = ChangeSocketFactory { url -> FakeSocket(url).also { sockets += it } }
    private var hints = 0

    // What the next GETs answer: an endpoint, or an exception to throw.
    private val answers = ArrayDeque<Any>()
    private var fetches = 0
    private val fetchTimes = mutableListOf<Long>()

    private val lifetimeMs = 30L * 60_000L

    private fun url(n: Int) = "https://f3hb0mpua.svc.ms/zbaehwg$n?token=SECRET-$n&applicationId=APP"

    private fun endpoint(
        n: Int,
        expiresAtMs: Long? = now + lifetimeMs,
    ) = SocketIoEndpoint(url(n), expiresAtMs)

    private val channel =
        OneDriveChangeChannel(
            fetchEndpoint = {
                fetches++
                fetchTimes += now
                when (val a = answers.removeFirstOrNull() ?: error("no answer queued")) {
                    is SocketIoEndpoint -> a
                    is Exception -> throw a
                    is Function0<*> -> a() as SocketIoEndpoint
                    else -> error("bad answer $a")
                }
            },
            onHint = { hints++ },
            socketFactory = factory,
            clock = { now },
            scheduler = scheduler,
        )

    private lateinit var appender: ListAppender<ILoggingEvent>
    private val packageLogger get() = LoggerFactory.getLogger("org.krost.unidrive.onedrive") as Logger

    @BeforeTest
    fun captureLog() {
        appender = ListAppender<ILoggingEvent>().apply { start() }
        packageLogger.addAppender(appender)
        packageLogger.level = ch.qos.logback.classic.Level.DEBUG
    }

    @AfterTest
    fun releaseLog() {
        packageLogger.detachAppender(appender)
        packageLogger.level = null
        channel.stop()
    }

    /** Start the channel and let the first endpoint connect. */
    private fun startConnected(): FakeSocket {
        answers += endpoint(1)
        channel.start()
        scheduler.runDue()
        val socket = sockets.single()
        socket.fire(Socket.EVENT_CONNECT)
        scheduler.runDue()
        return socket
    }

    @Test
    fun `a synchronous socket connect failure retries with a fresh endpoint`() {
        var attempts = 0
        val failingFactory = ChangeSocketFactory { url ->
            FakeSocket(url).also { socket ->
                sockets += socket
                if (attempts++ == 0) socket.failConnect = true
            }
        }
        val retryChannel = OneDriveChangeChannel(
            fetchEndpoint = { endpoint(++fetches) },
            onHint = {},
            socketFactory = failingFactory,
            clock = { now },
            scheduler = scheduler,
        )
        try {
            retryChannel.start()
            scheduler.runDue()
            assertTrue(sockets.first().closed)
            scheduler.advanceTo(now + OneDriveChangeChannel.BACKOFF_INITIAL_MS)
            assertEquals(2, fetches)
            assertTrue(sockets.last().connected)
        } finally {
            retryChannel.stop()
        }
    }

    // gkrost/unidrive-cli#225: the channel's work runs on its own thread (the endpoint GET, the connect, socket.io's events
    // posted to it), which carries no MDC of the daemon that opened the channel; its lines carry the MDC of the code that
    // made the channel. The MDC is read while each line is logged, on the thread that logs it.
    @Test
    fun `the channel's lines carry the MDC of the code that made it, on the thread its work runs on`() {
        val seen = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
        val mdcAppender =
            object : AppenderBase<ILoggingEvent>() {
                override fun append(event: ILoggingEvent) {
                    seen += event.formattedMessage to HashMap(event.mdcPropertyMap)
                }
            }.apply { start() }
        packageLogger.addAppender(mdcAppender)
        MDC.put("profile", "od-profile")
        MDC.put("build", "od-build")
        val made =
            try {
                OneDriveChangeChannel({ endpoint(1) }, onHint = {}, socketFactory = factory, clock = { now }, scheduler = scheduler)
            } finally {
                MDC.clear()
            }
        try {
            made.start()
            kotlin.concurrent.thread(name = "channel-thread") { scheduler.runDue() }.join()
            sockets.single().fire(Socket.EVENT_CONNECT)
            kotlin.concurrent.thread(name = "channel-thread") { scheduler.runDue() }.join()

            assertTrue(seen.any { it.first.startsWith("OneDrive notifications: connecting") }, "the connect line: $seen")
            assertTrue(seen.any { it.first == "OneDrive notifications connected" }, "the connected line: $seen")
            for ((message, mdc) in seen) {
                assertEquals("od-profile", mdc["profile"], "the profile of '$message'")
                assertEquals("od-build", mdc["build"], "the build of '$message'")
            }
        } finally {
            made.stop()
            packageLogger.detachAppender(mdcAppender)
        }
    }

    @Test
    fun `connects to the notification URL exactly as returned and hints on the connect`() {
        val expiresAt = now + lifetimeMs
        answers += SocketIoEndpoint(url(1), expiresAt)
        channel.start()
        assertEquals(State.CONNECTING, channel.status().state)
        scheduler.runDue()

        val socket = sockets.single()
        assertEquals(url(1), socket.url, "the URL as Graph returned it, query included")
        assertTrue(socket.connected)
        assertEquals(0, hints)

        socket.fire(Socket.EVENT_CONNECT)
        scheduler.runDue()

        val status = channel.status()
        assertEquals(State.CONNECTED, status.state)
        assertEquals(1, hints)
        assertEquals(1, status.hints)
        assertEquals(now, status.lastHintAtMs)
        assertEquals(expiresAt, status.expiresAtMs)
        assertEquals(0, status.rotations)
    }

    @Test
    fun `every notification is a hint and its payload is not parsed`() {
        val socket = startConnected()
        val before = hints
        socket.fire(OneDriveChangeChannel.NOTIFICATION_EVENT, """{"value":[{"anything":1}]}""")
        socket.fire(OneDriveChangeChannel.NOTIFICATION_EVENT, Any())
        socket.fire(OneDriveChangeChannel.NOTIFICATION_EVENT)
        scheduler.runDue()
        assertEquals(before + 3, hints)
        assertEquals(hints.toLong(), channel.status().hints)
    }

    @Test
    fun `at the expiry the channel fetches a new endpoint, closes the old socket and connects the new URL`() {
        val first = startConnected()
        val expiresAt = channel.status().expiresAtMs!!
        val hintsBefore = hints

        answers += SocketIoEndpoint(url(2), expiresAt + lifetimeMs)
        scheduler.advanceTo(expiresAt + OneDriveChangeChannel.ROTATION_GRACE_MS - 1)
        assertEquals(1, fetches, "not before the expiry and its grace")
        assertFalse(first.closed)

        scheduler.advanceTo(expiresAt + OneDriveChangeChannel.ROTATION_GRACE_MS)
        assertEquals(2, fetches)
        assertTrue(first.closed, "the old socket goes silent at the expiry: it is closed")
        val second = sockets.last()
        assertEquals(url(2), second.url)
        assertTrue(second.connected)
        assertEquals(hintsBefore + 1, hints, "the rotation is a hint: changes just before the expiry came on the old endpoint")
        assertEquals(1, channel.status().rotations)
        assertEquals(State.CONNECTING, channel.status().state)

        second.fire(Socket.EVENT_CONNECT)
        scheduler.runDue()
        assertEquals(hintsBefore + 2, hints, "the reconnect is a hint too")
        assertEquals(State.CONNECTED, channel.status().state)
        assertEquals(expiresAt + lifetimeMs, channel.status().expiresAtMs)

        // The old socket's late events change nothing.
        first.fire(OneDriveChangeChannel.NOTIFICATION_EVENT)
        first.fire(Socket.EVENT_DISCONNECT, "io server disconnect")
        scheduler.runDue()
        assertEquals(hintsBefore + 2, hints)
        assertEquals(State.CONNECTED, channel.status().state)

        // And the next expiry rotates again.
        answers += SocketIoEndpoint(url(3), expiresAt + 2 * lifetimeMs)
        scheduler.advanceTo(expiresAt + lifetimeMs + OneDriveChangeChannel.ROTATION_GRACE_MS)
        assertEquals(3, fetches)
        assertEquals(url(3), sockets.last().url)
        assertEquals(2, channel.status().rotations)
    }

    @Test
    fun `an endpoint without an expiry rotates after the default lifetime`() {
        answers += endpoint(1, expiresAtMs = null)
        channel.start()
        scheduler.runDue()
        answers += endpoint(2, expiresAtMs = now + 2 * lifetimeMs)
        scheduler.advanceTo(now + OneDriveChangeChannel.DEFAULT_LIFETIME_MS + OneDriveChangeChannel.ROTATION_GRACE_MS)
        assertEquals(2, fetches)
        assertEquals(url(2), sockets.last().url)
    }

    @Test
    fun `a server disconnect is followed by a new GET and a connect after the back-off`() {
        val first = startConnected()
        val hintsBefore = hints
        val t0 = now

        first.fire(Socket.EVENT_DISCONNECT, "io server disconnect")
        scheduler.runDue()
        assertTrue(first.closed)
        assertEquals(State.CONNECTING, channel.status().state)
        assertNull(channel.status().expiresAtMs)
        assertEquals(1, fetches, "not at once")

        answers += endpoint(2, expiresAtMs = t0 + 2 * lifetimeMs)
        scheduler.advanceTo(t0 + OneDriveChangeChannel.BACKOFF_INITIAL_MS)
        assertEquals(2, fetches, "a fresh endpoint, not the library's reconnect to the old one")
        val second = sockets.last()
        assertEquals(url(2), second.url)
        assertEquals(1, channel.status().rotations)

        second.fire(Socket.EVENT_CONNECT)
        scheduler.runDue()
        assertEquals(hintsBefore + 1, hints, "the reconnect covers what happened while the channel was down")
        assertEquals(State.CONNECTED, channel.status().state)

        // The first socket's expiry timer was dropped with it: only the second endpoint's own expiry rotates.
        answers += endpoint(3)
        scheduler.advanceTo(t0 + lifetimeMs + OneDriveChangeChannel.ROTATION_GRACE_MS)
        assertEquals(2, fetches)
    }

    @Test
    fun `a connect error is handled the same way`() {
        answers += endpoint(1)
        channel.start()
        scheduler.runDue()
        val t0 = now
        sockets.single().fire(Socket.EVENT_CONNECT_ERROR, RuntimeException("websocket error"))
        scheduler.runDue()
        assertTrue(sockets.single().closed)
        answers += endpoint(2)
        scheduler.advanceTo(t0 + 1_000)
        assertEquals(2, fetches)
        sockets.last().fire(Socket.EVENT_CONNECT)
        scheduler.runDue()
        assertEquals(State.CONNECTED, channel.status().state)
    }

    @Test
    fun `a failing GET backs off from 1 s doubling to 60 s, and a connect ends the outage`() {
        repeat(9) { answers += GraphApiException("API error: 503 Service Unavailable", 503) }
        answers += endpoint(1)
        channel.start()
        scheduler.runDue()
        repeat(9) { scheduler.advanceTo(scheduler.nextDueAt()!!) }

        assertEquals(10, fetches)
        val gaps = fetchTimes.zipWithNext { a, b -> b - a }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L, 60_000L), gaps)
        assertEquals(State.CONNECTING, channel.status().state)
        assertEquals(0, hints, "a failed GET is not a hint")

        sockets.single().fire(Socket.EVENT_CONNECT)
        scheduler.runDue()
        assertEquals(State.CONNECTED, channel.status().state)
        assertEquals(1, hints)
        assertEquals(1, appender.list.count { it.level.toString() == "WARN" }, "one WARN per outage, DEBUG for the rest")
    }

    @Test
    fun `the back-off keeps growing while connections drop at once, and starts over after a stable one`() {
        answers += endpoint(1)
        channel.start()
        scheduler.runDue()
        // Accepted, then dropped at once, three times: 1 s, 2 s, 4 s.
        val gaps = mutableListOf<Long>()
        repeat(3) { i ->
            sockets.last().fire(Socket.EVENT_CONNECT)
            sockets.last().fire(Socket.EVENT_DISCONNECT, "transport close")
            scheduler.runDue()
            val dropAt = now
            answers += endpoint(i + 2)
            scheduler.advanceTo(scheduler.nextDueAt()!!)
            gaps += now - dropAt
        }
        assertEquals(listOf(1_000L, 2_000L, 4_000L), gaps)

        // Connected for a minute: the next drop retries after 1 s again.
        sockets.last().fire(Socket.EVENT_CONNECT)
        scheduler.runDue()
        scheduler.advanceTo(now + OneDriveChangeChannel.STABLE_CONNECTION_MS)
        sockets.last().fire(Socket.EVENT_DISCONNECT, "transport close")
        scheduler.runDue()
        val dropAt = now
        answers += endpoint(9)
        scheduler.advanceTo(scheduler.nextDueAt()!!)
        assertEquals(1_000L, now - dropAt)
    }

    @Test
    fun `an endpoint that is already expired is retried with the back-off, not at once`() {
        answers += endpoint(1, expiresAtMs = now - 1)
        channel.start()
        scheduler.runDue()
        assertTrue(sockets.isEmpty(), "no socket to a dead endpoint")
        assertEquals(1, fetches)
        answers += endpoint(2)
        scheduler.advanceTo(now + 1_000)
        assertEquals(2, fetches)
        assertEquals(url(2), sockets.single().url)
    }

    @Test
    fun `stop closes the socket, drops every timer and ignores late events`() {
        val socket = startConnected()
        val hintsBefore = hints
        channel.stop()

        assertTrue(socket.closed)
        assertTrue(scheduler.isShutdown)
        val status = channel.status()
        assertEquals(State.OFF, status.state)
        assertNull(status.expiresAtMs)
        assertEquals(hintsBefore.toLong(), status.hints, "the counters stay")

        socket.fire(OneDriveChangeChannel.NOTIFICATION_EVENT)
        socket.fire(Socket.EVENT_DISCONNECT, "io server disconnect")
        scheduler.advanceTo(now + 2 * lifetimeMs)
        channel.start()
        scheduler.runDue()
        assertEquals(hintsBefore, hints)
        assertEquals(1, fetches)
        assertEquals(1, sockets.size)
        assertEquals(State.OFF, channel.status().state, "a stopped channel stays stopped")
    }

    @Test
    fun `a stop during the GET leaves no socket open`() {
        answers += { channel.stop(); endpoint(1) }
        channel.start()
        scheduler.runDue()
        assertTrue(sockets.all { it.closed })
        assertEquals(State.OFF, channel.status().state)
    }

    @Test
    fun `a throwing callback does not stop the channel`() {
        val throwing =
            OneDriveChangeChannel(
                fetchEndpoint = { endpoint(1) },
                onHint = { error("boom") },
                socketFactory = factory,
                clock = { now },
                scheduler = scheduler,
            )
        throwing.start()
        scheduler.runDue()
        sockets.single().fire(Socket.EVENT_CONNECT)
        sockets.single().fire(OneDriveChangeChannel.NOTIFICATION_EVENT)
        scheduler.runDue()
        assertEquals(State.CONNECTED, throwing.status().state)
        assertEquals(2, throwing.status().hints)
        throwing.stop()
    }

    @Test
    fun `the notification URL never reaches the log`() {
        val socket = startConnected()
        socket.fire(OneDriveChangeChannel.NOTIFICATION_EVENT, "{}")
        // A connect error that quotes the endpoint, as an engine.io exception could.
        socket.fire(Socket.EVENT_CONNECT_ERROR, RuntimeException("failed to connect to ${url(1)}", RuntimeException("at ${url(1)}")))
        scheduler.runDue()
        answers += IllegalStateException("GET answered ${url(2)}")
        scheduler.advanceTo(scheduler.nextDueAt()!!)
        answers += endpoint(3)
        scheduler.advanceTo(scheduler.nextDueAt()!!)
        sockets.last().fire(Socket.EVENT_CONNECT)
        scheduler.runDue()
        assertEquals(url(3), sockets.last().url)

        val logged =
            appender.list.joinToString("\n") { e ->
                e.formattedMessage + (e.throwableProxy?.let { "\n" + ThrowableProxyUtil.asString(it) } ?: "")
            }
        assertTrue(appender.list.isNotEmpty(), "precondition: the channel logged something")
        assertFalse("SECRET" in logged, logged)
        assertFalse("token=" in logged, logged)
        assertTrue(SocketIoEndpoint.shortHash(url(1)) in logged, "the log names an endpoint by its short hash")
        assertFalse(url(1) in endpoint(1).toString())
    }

    @Test
    fun `the back-off schedule`() {
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L),
            (1..8).map { OneDriveChangeChannel.backoffMs(it) },
        )
        assertEquals(60_000L, OneDriveChangeChannel.backoffMs(1_000))
    }

    // ── the provider's side: lazily opened, closed by close / logout / forgetCredentials, the profile switch ──

    @Test
    fun `the provider opens the channel lazily and close, logout and forgetCredentials stop it`() =
        runBlocking {
            val dir = Files.createTempDirectory("od-channel-")
            try {
                val made = mutableListOf<OneDriveChangeChannel>()
                fun provider(enabled: Boolean) =
                    OneDriveProvider(OneDriveConfig(tokenPath = dir, remoteNotifications = enabled)).apply {
                        changeChannelFactory = { _, onHint, counters ->
                            OneDriveChangeChannel({ endpoint(made.size + 1) }, onHint, counters, factory, { now }, FakeScheduler())
                                .also { made += it }
                        }
                    }

                val off = provider(enabled = false)
                off.onRemoteChangeHint { hints++ }
                assertTrue(made.isEmpty(), "remote_notifications = false keeps today's behaviour")
                assertEquals(State.OFF, off.remoteChangeChannelStatus().state)
                off.close()

                val p = provider(enabled = true)
                assertEquals(State.OFF, p.remoteChangeChannelStatus().state, "nothing is opened before a hint is asked for")
                assertTrue(made.isEmpty())
                p.onRemoteChangeHint { hints++ }
                assertEquals(1, made.size)
                assertEquals(State.CONNECTING, p.remoteChangeChannelStatus().state)
                p.onRemoteChangeHint { hints += 10 }
                assertEquals(1, made.size, "a second registration replaces the callback, not the channel")

                p.logout()
                assertEquals(State.OFF, made[0].status().state)
                assertEquals(State.OFF, p.remoteChangeChannelStatus().state)

                p.reloadCredentials()
                assertEquals(2, made.size, "a sign-in after the sign-out opens it again")
                p.forgetCredentials()
                assertEquals(State.OFF, made[1].status().state)

                p.reloadCredentials()
                assertEquals(3, made.size)
                p.close()
                assertEquals(State.OFF, made[2].status().state)
                p.onRemoteChangeHint { }
                p.reloadCredentials()
                assertEquals(3, made.size, "a closed provider opens nothing")
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

    @Test
    fun `the profile key switches the channel and defaults to on`() {
        val dir = Files.createTempDirectory("od-channel-key-")
        try {
            fun configOf(props: Map<String, String?>) =
                (OneDriveProviderFactory().create(props, dir) as OneDriveProvider).let { p ->
                    p.config.also { p.close() }
                }
            assertTrue(configOf(emptyMap()).remoteNotifications)
            assertTrue(configOf(mapOf("remote_notifications" to "true")).remoteNotifications)
            assertFalse(configOf(mapOf("remote_notifications" to "false")).remoteNotifications)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ── the GET ──

    @Test
    fun `the endpoint request reads notificationUrl and expirationDateTime from Graph's reply`() =
        runBlocking {
            val dir = Files.createTempDirectory("od-socketio-")
            try {
                var path: String? = null
                val engine =
                    MockEngine { request ->
                        path = request.url.encodedPath
                        respond(
                            """{"id":"sub-1","resource":"/me/drive/root","notificationUrl":"${url(7)}","expirationDateTime":"2026-10-21T10:40:00Z"}""",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                GraphApiService(OneDriveConfig(tokenPath = dir), engine = engine) { "made-up-access-token" }.use { api ->
                    val e = api.getSocketIoEndpoint()
                    assertEquals("/v1.0/me/drive/root/subscriptions/socketIo", path)
                    assertEquals(url(7), e.notificationUrl)
                    assertEquals(java.time.Instant.parse("2026-10-21T10:40:00Z").toEpochMilli(), e.expiresAtMs)
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

    // ── fakes ──

    private class FakeSocket(
        val url: String,
    ) : ChangeSocket {
        private val listeners = mutableMapOf<String, MutableList<(List<Any?>) -> Unit>>()
        var connected = false
        var closed = false
        var failConnect = false

        override fun on(
            event: String,
            listener: (List<Any?>) -> Unit,
        ) {
            listeners.getOrPut(event) { mutableListOf() } += listener
        }

        override fun connect() {
            if (failConnect) error("socket connect failed")
            connected = true
        }

        override fun close() {
            closed = true
            connected = false
        }

        /** Delivers even after close(): an event socket.io queued before the close still reaches the channel. */
        fun fire(
            event: String,
            vararg args: Any?,
        ) {
            listeners[event]?.forEach { it(args.toList()) }
        }
    }

    /** Runs tasks in due order on the test thread; time moves only when the test says so. */
    private inner class FakeScheduler : ChangeChannelScheduler {
        private var seq = 0L
        private val tasks = sortedMapOf<Pair<Long, Long>, Runnable>(compareBy({ it.first }, { it.second }))
        var isShutdown = false

        override fun execute(task: Runnable) = schedule(0, task)

        override fun schedule(
            delayMs: Long,
            task: Runnable,
        ) {
            if (isShutdown) return
            tasks[(now + delayMs) to seq++] = task
        }

        override fun shutdown() {
            isShutdown = true
            tasks.clear()
        }

        fun nextDueAt(): Long? = tasks.keys.firstOrNull()?.first

        fun runDue() = advanceTo(now)

        fun advanceTo(target: Long) {
            while (true) {
                val key = tasks.keys.firstOrNull() ?: break
                if (key.first > target) break
                val task = tasks.remove(key)!!
                if (key.first > now) now = key.first
                task.run()
            }
            if (target > now) now = target
        }
    }
}
