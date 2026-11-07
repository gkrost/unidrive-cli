package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
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

    @Test
    fun `a result one of two readers takes is not handed over when the other is cancelled after it`() = runTest {
        val unclaimed = CopyOnWriteArrayList<String>()
        val downloads = SharedDownloads<String>(parent = null, onUnclaimed = { unclaimed += it })
        val gate = CompletableDeferred<Unit>()
        val waiterA = SharedDownloads.Waiter(connectionId = "c1", handleId = "h1")
        val waiterB = SharedDownloads.Waiter(connectionId = "c2", handleId = "h2")
        var outcomeA: SharedDownloads.Outcome<String>? = null
        var outcomeB: SharedDownloads.Outcome<String>? = null
        // The second reader runs on its own scheduler, so the order of the two unwinds is fixed: the reader that takes
        // the result unwinds first, the cancelled one leaves last.
        val readerB = TestScope(StandardTestDispatcher())
        readerB.launch { outcomeB = downloads.await("/f", waiterB) { gate.await(); "landed" } }

        launch { outcomeA = downloads.await("/f", waiterA) { gate.await(); "landed" } }
        runCurrent() // both readers wait; the download is suspended in the gate
        readerB.runCurrent() // the second reader has joined the first one's download and hangs in its select
        waiterB.cancelled.complete(Unit) // the cancel wins the second reader's select while the download still runs
        gate.complete(Unit) // the download ends; the first reader takes the result
        runCurrent() // the first reader unwinds with the result

        assertEquals(SharedDownloads.Outcome.Done("landed"), outcomeA, "the first reader took the result")
        readerB.runCurrent() // the cancelled reader unwinds last

        assertEquals(SharedDownloads.Outcome.Cancelled, outcomeB, "the second reader was cancelled")
        assertEquals(emptyList(), unclaimed, "the taken result was not handed over again")
    }
}
