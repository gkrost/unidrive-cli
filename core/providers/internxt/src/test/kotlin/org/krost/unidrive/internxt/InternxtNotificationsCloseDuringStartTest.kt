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

/**
 * close() waits a short while for a notifications start that holds the start lock and then tears down
 * regardless. A start that is still stalled past that wait (a slow constructor, a descheduled thread) and
 * resumes afterwards must not publish its client and open a socket: close() shut the gate for good.
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
        val tmp = Files.createTempDirectory("internxt-notif-close-")
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

                provider.close() // waits its ~500 ms for the start lock, then tears down without it

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

    private class CountingLatch {
        val latch = CountDownLatch(1)
    }
}
