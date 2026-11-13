package org.krost.unidrive.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds

/**
 * windows#454 item 6: the whole refresh body ran inside NonCancellable under the mutex, so a token endpoint that
 * accepted and then stalled held every request of the provider — and the daemon shutdown — until the client request
 * timeout (up to 600 s). The body is bounded by [RefreshableTokenLatch.REFRESH_TIMEOUT] now, and only the persist
 * ([RefreshableTokenLatch.commitIfCurrent]) survives a cancellation.
 */
class RefreshableTokenLatchTest {
    @Test
    fun `a refresh whose endpoint stalls is cut off at the timeout and frees the mutex`() =
        runBlocking<Unit> {
            // The deadline is real time, so this runs on the real clock with a short timeout.
            val latch = RefreshableTokenLatch(timeout = 300.milliseconds)
            val entered = CompletableDeferred<Unit>()

            launch {
                latch.withRefresh(isAlreadyFresh = { null }, body = { entered.complete(Unit); awaitCancellation() })
            }
            entered.await()

            // A second caller queued at the mutex behind the stalled refresh.
            val second = async { latch.withRefresh(isAlreadyFresh = { null }, body = { "second" }) }
            delay(100)
            assertFalse(second.isCompleted, "still queued before the refresh timeout")

            assertEquals("second", withTimeout(10_000) { second.await() })
        }

    @Test
    fun `the refresh deadline is not fired by a test dispatcher's virtual clock`() =
        runTest {
            val latch = RefreshableTokenLatch()
            // Real I/O the virtual scheduler cannot see: runTest would jump 60 virtual seconds while this runs.
            val answer = latch.withRefresh(isAlreadyFresh = { null }, body = { withContext(Dispatchers.IO) { Thread.sleep(200); "answered" } })
            assertEquals("answered", answer)
        }

    @Test
    fun `the persist survives a cancellation that lands during it`() =
        runTest {
            val latch = RefreshableTokenLatch()
            var committed = ""
            val persistReached = CompletableDeferred<Unit>()

            val job =
                launch {
                    latch.withRefresh(isAlreadyFresh = { null }, body = {
                        val generation = latch.generation()
                        // The "network call": cancelled while the persist is reached, as withTimeout does.
                        persistReached.complete(Unit)
                        latch.commitIfCurrent(generation) { committed = "stored" }
                        "first"
                    })
                }
            runCurrent()
            persistReached.await()
            job.cancel()
            job.join() // the body runs on a real dispatcher now: wait for it rather than for the test scheduler

            // The commit is NonCancellable: what the provider accepted is in memory and on disk, even though
            // the job was cancelled while the persist ran.
            assertEquals("stored", committed)
        }

    @Test
    fun `a normal refresh still answers and the mutex is free afterward`() =
        runTest {
            val latch = RefreshableTokenLatch()
            assertEquals("ok", latch.withRefresh(isAlreadyFresh = { null }, body = { "ok" }))
            assertEquals("skip", latch.withRefresh(isAlreadyFresh = { "skip" }, body = { "must not run" }))
        }
}
