package org.krost.unidrive.cli

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.engine.EnumerationEntryPoint
import org.krost.unidrive.sync.EnumerateResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A blocking enumeration must not stall the rest of the serve scope (event writers, stop). The waits are on latches the
 * code under test releases, with a generous bound only so a regression fails instead of hanging.
 */
class ServeScopeResponsivenessTest {
    private class BlockingEnumeration(
        val entered: CountDownLatch = CountDownLatch(1),
        val release: CountDownLatch = CountDownLatch(1),
    ) : EnumerationEntryPoint {
        // Blocks the thread it runs on, like JDBC and file stats do.
        override suspend fun enumerateRemoteIntoState(reset: Boolean): EnumerateResult {
            entered.countDown()
            check(release.await(30, TimeUnit.SECONDS)) { "never released" }
            return EnumerateResult(ok = true)
        }
    }

    @Test
    fun serve_scope_does_not_run_on_the_callers_event_loop() {
        // The caller thread blocks on a latch without ever yielding its runBlocking loop: a scope bound to that loop
        // could not run the launch below.
        runBlocking {
            val job = SupervisorJob()
            val scope = DaemonRuntime.serveScopeOf(coroutineContext, job)
            val ran = CountDownLatch(1)
            scope.launch { ran.countDown() }
            assertTrue(ran.await(10, TimeUnit.SECONDS), "a launch on the serve scope needs the caller's loop")
            job.cancelAndJoin()
        }
    }

    @Test
    fun a_blocking_enumeration_does_not_stall_another_request_on_a_one_thread_scope() {
        // Worst case for the scope: a single thread. The enumeration must leave it (Dispatchers.IO), or the second
        // launch, standing for any other handler or writer, is queued behind it.
        val oneThread = Executors.newSingleThreadExecutor()
        try {
            runBlocking {
                val job = SupervisorJob()
                val scope = CoroutineScope(job + oneThread.asCoroutineDispatcher())
                val engine = BlockingEnumeration()
                val emitted = java.util.concurrent.CopyOnWriteArrayList<String>()
                val handler = EnumerateRpcHandler(OffLoopEnumeration(engine), scope, emit = { emitted.add(it) })

                handler.handle("c1", """{"verb":"sync.enumerate"}""")
                assertTrue(engine.entered.await(10, TimeUnit.SECONDS), "the enumeration never started")

                val other = CountDownLatch(1)
                scope.launch { other.countDown() }
                assertTrue(other.await(10, TimeUnit.SECONDS), "another request was stalled behind the blocking enumeration")

                engine.release.countDown()
                handler.awaitInFlight()
                assertEquals(1, emitted.size)
                job.cancelAndJoin()
            }
        } finally {
            oneThread.shutdownNow()
        }
    }
}
