package org.krost.unidrive.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * windows#454 item 6: the whole refresh body ran inside NonCancellable under the mutex, so a token endpoint that
 * accepted and then stalled held every request of the provider — and the daemon shutdown — until the client request
 * timeout (up to 600 s). The body is bounded by [RefreshableTokenLatch.REFRESH_TIMEOUT] now, and only the persist
 * ([RefreshableTokenLatch.commitIfCurrent]) survives a cancellation.
 */
class RefreshableTokenLatchTest {
    @Test
    fun `a refresh whose endpoint stalls is cut off at the timeout and frees the mutex`() =
        runTest {
            val latch = RefreshableTokenLatch()
            val entered = CompletableDeferred<Unit>()

            launch {
                latch.withRefresh(isAlreadyFresh = { null }, body = { entered.complete(Unit); awaitCancellation() })
            }
            runCurrent()
            entered.await()

            assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
                withTimeout(5_000) {
                    // A second caller queued at the mutex: it must not wait for the stalled refresh beyond the
                    // refresh timeout. Virtual time: advance past it.
                    latch.withRefresh(isAlreadyFresh = { null }, body = { "second" })
                }
            }
            advanceTimeBy(RefreshableTokenLatch.REFRESH_TIMEOUT.inWholeMilliseconds + 1)
            runCurrent()
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
            runCurrent()

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
