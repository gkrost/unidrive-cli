package org.krost.unidrive.internxt

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.krost.unidrive.internxt.model.InternxtCredentials
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.krost.unidrive.testing.TestScratch

/**
 * close() tears down without waiting for a notifications start that holds the start lock (#532). A start that is
 * still stalled (a slow constructor, a descheduled thread) and resumes afterwards must not publish its client and
 * open a socket: close() shut the gate for good.
 */
class InternxtNotificationsCloseDuringStartTest {
    private fun writeValidCredentials(dir: Path) {
        val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
        val exp = System.currentTimeMillis() / 1000 + 10L * 24 * 3600
        val jwt =
            encoder.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray()) + "." +
                encoder.encodeToString("""{"exp":$exp}""".toByteArray()) + ".fake-signature"
        val creds = InternxtCredentials(jwt = jwt, mnemonic = "m", rootFolderId = "root", email = "t@example.invalid")
        Files.writeString(dir.resolve("credentials.json"), Json.encodeToString(InternxtCredentials.serializer(), creds))
    }

    private class CountingSocket(
        private val connects: AtomicInteger,
    ) : NotificationsSocket {
        override fun on(
            event: String,
            listener: (List<Any?>) -> Unit,
        ) = Unit

        override fun connect() {
            connects.incrementAndGet()
        }

        override fun close() = Unit
    }

    @Test
    fun `a start stalled past close never connects a socket afterwards`() {
        val tmp = TestScratch.directory("internxt-notif-close-")
        try {
            writeValidCredentials(tmp)
            val provider = InternxtProvider(InternxtConfig(credentialProtector = null, tokenPath = tmp, notificationsUrl = "http://127.0.0.1:9", notificationsEnabled = true))
            val connects = AtomicInteger()
            val entered = CountingLatch()
            val release = CountDownLatch(1)
            provider.notificationsClientFactory = { tokenSupplier, onRemoteChange ->
                entered.latch.countDown()
                // The stalled constructor: parked until the test lets it go, long after close() gave up waiting.
                assertTrue(release.await(60, TimeUnit.SECONDS), "the test never released the stalled start")
                NotificationsClient(
                    notificationsUrl = "http://127.0.0.1:9",
                    tokenSupplier = tokenSupplier,
                    onRemoteChange = onRemoteChange,
                    socketFactory = { _, _ -> CountingSocket(connects) },
                )
            }
            var startFailure: Throwable? = null
            val startup =
                Thread {
                    try {
                        runBlocking {
                            provider.authenticate()
                            provider.onRemoteChangeHint { }
                        }
                    } catch (t: Throwable) {
                        startFailure = t
                    }
                }.apply { isDaemon = true }
            startup.start()
            try {
                assertTrue(entered.latch.await(30, TimeUnit.SECONDS), "the start never reached the client construction")

                provider.close() // tears down at once and shuts the gate; the start holds the start lock

                release.countDown()
                startup.join(30_000)
                assertTrue(!startup.isAlive, "the stalled start must end once released")
                assertNull(startFailure, "the start must end quietly: $startFailure")
                assertEquals(0, connects.get(), "no socket may connect after close()")
                assertNull(provider.notificationsClient, "no client may be published after close()")
            } finally {
                release.countDown()
            }
        } finally {
            Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    // #532: close() no longer spins on the start lock: the gate (notificationsClosed, under notificationsGuard) is what keeps
    // a start that is still running from publishing a client, so close() tears down at once.
    @Test
    fun `close does not wait for a start that holds the start lock`() {
        val tmp = TestScratch.directory("internxt-notif-close-fast-")
        try {
            writeValidCredentials(tmp)
            val provider = InternxtProvider(InternxtConfig(credentialProtector = null, tokenPath = tmp, notificationsUrl = "http://127.0.0.1:9", notificationsEnabled = true))
            val connects = AtomicInteger()
            val entered = CountingLatch()
            val release = CountDownLatch(1)
            provider.notificationsClientFactory = { tokenSupplier, onRemoteChange ->
                entered.latch.countDown()
                assertTrue(release.await(60, TimeUnit.SECONDS), "the test never released the stalled start")
                NotificationsClient(
                    notificationsUrl = "http://127.0.0.1:9",
                    tokenSupplier = tokenSupplier,
                    onRemoteChange = onRemoteChange,
                    socketFactory = { _, _ -> CountingSocket(connects) },
                )
            }
            val startup =
                Thread {
                    runCatching {
                        runBlocking {
                            provider.authenticate()
                            provider.onRemoteChangeHint { }
                        }
                    }
                }.apply { isDaemon = true }
            startup.start()
            try {
                assertTrue(entered.latch.await(30, TimeUnit.SECONDS), "the start never reached the client construction")

                val started = System.nanoTime()
                provider.close()
                val tookMs = (System.nanoTime() - started) / 1_000_000

                assertTrue(tookMs < 400, "close() waited ${tookMs} ms for the start lock")
                release.countDown()
                startup.join(30_000)
                assertEquals(0, connects.get(), "no socket may connect after close()")
                assertNull(provider.notificationsClient, "no client may be published after close()")
            } finally {
                release.countDown()
            }
        } finally {
            Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    // A sign-out or a credential reload overlapping close() (#532: close() does not take the start lock): both end with no
    // client, and the client is taken out once, by one of them. #830: a reload that wins the race opens a second socket
    // with the new credentials before close() runs (by design), so the closes are counted per socket, not in total.
    @Test
    fun `logout and reload overlapping close leave no client and close its socket once`() {
        repeat(20) { round ->
            val tmp = TestScratch.directory("internxt-notif-overlap-")
            try {
                writeValidCredentials(tmp)
                val provider = InternxtProvider(InternxtConfig(credentialProtector = null, tokenPath = tmp, notificationsUrl = "http://127.0.0.1:9", notificationsEnabled = true))
                val sockets = SocketLog()
                provider.notificationsClientFactory = closingClientFactory(sockets)
                runBlocking {
                    provider.authenticate()
                    provider.onRemoteChangeHint { }
                }
                assertEquals(1, sockets.connected(), "round $round: the client is up")
                val go = CountDownLatch(1)
                val other =
                    Thread {
                        go.await()
                        runCatching { runBlocking { if (round % 2 == 0) provider.logout() else provider.reloadCredentials() } }
                    }.apply { isDaemon = true }
                other.start()
                go.countDown()
                provider.close()
                other.join(30_000)

                assertNull(provider.notificationsClient, "round $round: no client after both")
                assertEquals(1, sockets.closesOf(0), "round $round: the socket is closed once")
                assertEquals(
                    List(sockets.connected()) { 1 },
                    sockets.closeCounts(),
                    "round $round: every socket that connected is closed exactly once",
                )
            } finally {
                Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    // #830: the interleaving the overlap test above can hit only by timing, made deterministic. The reload finishes first
    // and reopens the socket with the new credentials; close() then closes that second socket. Two sockets, each closed
    // once, and no client left: not a double close.
    @Test
    fun `a reload that reopens the socket before close leaves each socket closed once`() {
        val tmp = TestScratch.directory("internxt-notif-reload-then-close-")
        try {
            writeValidCredentials(tmp)
            val provider = InternxtProvider(InternxtConfig(credentialProtector = null, tokenPath = tmp, notificationsUrl = "http://127.0.0.1:9", notificationsEnabled = true))
            val sockets = SocketLog()
            provider.notificationsClientFactory = closingClientFactory(sockets)
            runBlocking {
                provider.authenticate()
                provider.onRemoteChangeHint { }
                provider.reloadCredentials()
            }
            assertEquals(2, sockets.connected(), "the reload reopened the socket with the new credentials")
            assertEquals(listOf(1, 0), sockets.closeCounts(), "the reload closed the first socket only")

            provider.close()

            assertNull(provider.notificationsClient, "no client after close()")
            assertEquals(listOf(1, 1), sockets.closeCounts(), "every socket is closed exactly once")
        } finally {
            Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun closingClientFactory(sockets: SocketLog): (suspend (Boolean) -> String, () -> Unit) -> NotificationsClient =
        { tokenSupplier, onRemoteChange ->
            NotificationsClient(
                notificationsUrl = "http://127.0.0.1:9",
                tokenSupplier = tokenSupplier,
                onRemoteChange = onRemoteChange,
                socketFactory = { _, _ -> sockets.newSocket() },
            )
        }

    // One close counter per socket built, in build order, and how many of them connected.
    private class SocketLog {
        private val connects = AtomicInteger()
        private val closes = java.util.concurrent.CopyOnWriteArrayList<AtomicInteger>()

        fun newSocket(): NotificationsSocket = ClosingSocket(connects, AtomicInteger().also { closes.add(it) })

        fun connected(): Int = connects.get()

        fun closesOf(index: Int): Int = closes[index].get()

        fun closeCounts(): List<Int> = closes.map { it.get() }
    }

    private class ClosingSocket(
        private val connects: AtomicInteger,
        private val closes: AtomicInteger,
    ) : NotificationsSocket {
        override fun on(
            event: String,
            listener: (List<Any?>) -> Unit,
        ) = Unit

        override fun connect() {
            connects.incrementAndGet()
        }

        override fun close() {
            closes.incrementAndGet()
        }
    }

    private class CountingLatch {
        val latch = CountDownLatch(1)
    }
}
