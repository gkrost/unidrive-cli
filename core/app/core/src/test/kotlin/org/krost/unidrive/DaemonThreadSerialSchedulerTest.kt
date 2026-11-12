package org.krost.unidrive

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one serial daemon worker the providers' notification sockets run on (gkrost/unidrive-cli#456):
 * serial by construction, a daemon so a leak cannot hold the JVM, and silent once shut down.
 */
class DaemonThreadSerialSchedulerTest {
    private val scheduler = DaemonThreadSerialScheduler("unidrive-test-serial-worker")

    @AfterTest
    fun tearDown() {
        scheduler.shutdown()
    }

    @Test
    fun `tasks run one after another on the named daemon thread`() {
        val order = mutableListOf<String>()
        var workerThread: Thread? = null
        val first = CountDownLatch(1)
        val done = CountDownLatch(1)
        scheduler.execute {
            workerThread = Thread.currentThread()
            order.add("first")
            first.countDown()
        }
        assertTrue(first.await(5, TimeUnit.SECONDS), "the first task ran")
        scheduler.execute { order.add("second") }
        scheduler.execute {
            order.add("third")
            done.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS), "all tasks ran")
        assertEquals(listOf("first", "second", "third"), order, "one worker thread, in submission order")
        assertEquals("unidrive-test-serial-worker", workerThread?.name, "the worker carries the name it was given")
        assertTrue(workerThread?.isDaemon == true, "a leaked worker cannot hold the JVM alive")
    }

    @Test
    fun `a delayed task runs no earlier than its delay`() {
        val ranAt = CountDownLatch(1)
        var atMs = 0L
        val start = System.nanoTime()
        scheduler.schedule(150) {
            atMs = (System.nanoTime() - start) / 1_000_000
            ranAt.countDown()
        }
        assertTrue(ranAt.await(5, TimeUnit.SECONDS), "the delayed task ran")
        assertTrue(atMs >= 140, "not before its delay: ${atMs}ms")
    }

    @Test
    fun `after shutdown tasks are dropped silently`() {
        val ran = CountDownLatch(1)
        scheduler.execute { ran.countDown() }
        assertTrue(ran.await(5, TimeUnit.SECONDS), "precondition: the worker is alive")

        scheduler.shutdown()
        scheduler.shutdown() // idempotent

        val afterShutdown = CountDownLatch(1)
        scheduler.execute { afterShutdown.countDown() }
        assertFalse(afterShutdown.await(200, TimeUnit.MILLISECONDS), "nothing runs after shutdown")
        assertNull(null, "and the submit itself is not an error")
    }
}
