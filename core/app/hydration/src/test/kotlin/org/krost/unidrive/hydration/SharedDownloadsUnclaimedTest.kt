package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

/** gkrost/unidrive-cli#438: a download that finishes as its last waiter leaves hands its result over instead of dropping it. */
class SharedDownloadsUnclaimedTest {
    @Test
    fun `a result that finished as the last waiter was cancelled is handed to onUnclaimed once`() = runTest {
        val unclaimed = CopyOnWriteArrayList<String>()
        val downloads = SharedDownloads<String>(parent = null, onUnclaimed = { unclaimed += it })
        val waiter = SharedDownloads.Waiter(connectionId = "c1", handleId = "h1")
        var outcome: SharedDownloads.Outcome<String>? = null

        val caller =
            launch {
                outcome =
                    downloads.await("/f", waiter) {
                        // Past its last suspension point: the cancel arrives while the body returns.
                        waiter.cancelled.complete(Unit)
                        "landed"
                    }
            }
        runCurrent()
        caller.join()

        assertEquals(SharedDownloads.Outcome.Cancelled, outcome, "the waiter was cancelled")
        assertEquals(listOf("landed"), unclaimed, "the finished result was not dropped")
    }

    @Test
    fun `a result a waiter takes is not handed to onUnclaimed`() = runTest {
        val unclaimed = CopyOnWriteArrayList<String>()
        val downloads = SharedDownloads<String>(parent = null, onUnclaimed = { unclaimed += it })
        val waiter = SharedDownloads.Waiter(connectionId = "c1", handleId = "h1")
        var outcome: SharedDownloads.Outcome<String>? = null

        val caller = launch { outcome = downloads.await("/f", waiter) { "landed" } }
        runCurrent()
        caller.join()

        assertEquals(SharedDownloads.Outcome.Done("landed"), outcome)
        assertEquals(emptyList(), unclaimed)
    }
}
