package org.krost.unidrive.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The worker pool the transfers of a pass run on: bounded in coroutines, and the ladder of the scope it runs in kept. */
class BoundedLauncherTest {
    @Test
    fun `200000 planned transfers hold only the worker count of coroutines`() =
        runBlocking {
            val live = AtomicInteger()
            val peakLive = AtomicInteger()
            val ran = AtomicInteger()
            var peakCoroutines = 0
            coroutineScope {
                val scopeJob = coroutineContext.job
                val workers = BoundedLauncher(this, 4)
                repeat(200_000) { i ->
                    workers.launch {
                        peakLive.accumulateAndGet(live.incrementAndGet()) { a, b -> maxOf(a, b) }
                        // Counting a scope's children walks them all: look every few thousand items, not at each one.
                        if (i % 5_000 == 0) peakCoroutines = maxOf(peakCoroutines, scopeJob.children.count())
                        yield()
                        live.decrementAndGet()
                        ran.incrementAndGet()
                    }
                }
                workers.close()
            }
            assertEquals(200_000, ran.get(), "every planned transfer ran")
            assertTrue(peakLive.get() <= 4, "no more transfers at once than workers, was ${peakLive.get()}")
            assertTrue(peakCoroutines <= 4, "the scope holds the 4 workers, not one coroutine per transfer; was $peakCoroutines")
        }

    @Test
    fun `transfers start in the order they were handed over`() =
        runBlocking {
            val started = java.util.Collections.synchronizedList(mutableListOf<Int>())
            coroutineScope {
                val workers = BoundedLauncher(this, 1)
                repeat(1_000) { i -> workers.launch { started.add(i) } }
                workers.close()
            }
            assertEquals((0 until 1_000).toList(), started)
        }

    @Test
    fun `a later transfer for the same path waits for the one before it`() =
        runBlocking {
            val events = java.util.Collections.synchronizedList(mutableListOf<String>())
            coroutineScope {
                val workers = BoundedLauncher(this, 2)
                val first =
                    workers.launch {
                        events.add("first start")
                        delay(100)
                        events.add("first end")
                    }
                workers.launch {
                    first.join()
                    events.add("second")
                }
                workers.close()
            }
            assertEquals(listOf("first start", "first end", "second"), events)
        }

    @Test
    fun `a cancellation that ends one transfer does not end its worker`() =
        runBlocking {
            // A timeout inside a provider leaves a CancellationException. A coroutine per transfer ended with it and
            // nothing else; on a pool it must not take the worker with it, or the pass would lose a worker for good.
            val ran = AtomicInteger()
            withTimeout(10_000) {
                coroutineScope {
                    val workers = BoundedLauncher(this, 1)
                    workers.launch { throw CancellationException("one transfer timed out") }
                    repeat(5) { workers.launch { ran.incrementAndGet() } }
                    workers.close()
                }
            }
            assertEquals(5, ran.get(), "the worker went on with the transfers after the cancelled one")
        }

    @Test
    fun `cancelling the scope stops the workers and the transfers that have not started`() =
        runBlocking {
            val started = AtomicInteger()
            val release = CompletableDeferred<Unit>()
            assertFailsWith<CancellationException> {
                coroutineScope {
                    val workers = BoundedLauncher(this, 2)
                    repeat(50) { i ->
                        workers.launch {
                            started.incrementAndGet()
                            // An authentication failure in the first transfer cancels the scope, as the engine does.
                            if (i == 0) cancel()
                            release.await()
                        }
                    }
                    workers.close()
                }
            }
            assertTrue(started.get() <= 2, "only the transfers on the 2 workers had started, was ${started.get()}")
        }

    @Test
    fun `an exception that leaves a transfer fails the scope`() =
        runBlocking {
            val e =
                assertFailsWith<IllegalStateException> {
                    coroutineScope {
                        val workers = BoundedLauncher(this, 2)
                        workers.launch { error("boom") }
                        repeat(10) { workers.launch { delay(50) } }
                        workers.close()
                    }
                }
            assertEquals("boom", e.message)
        }
}
