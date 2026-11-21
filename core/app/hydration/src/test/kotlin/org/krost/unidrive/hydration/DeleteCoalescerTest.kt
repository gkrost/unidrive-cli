package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.DeleteRequest
import org.krost.unidrive.ProviderException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#634 B, #831: the synchronous coalescing window of the mount's deletes. Concurrent deletes share a
 * provider call of at most 50 items; a lone delete is sent at once; every caller is answered with its own item's result.
 * Virtual time: each provider call takes [RTT] ms (the gateway's p50 of the issue, ~140 ms).
 */
class DeleteCoalescerTest {
    private class CountingFlush(
        private val lanes: Semaphore = Semaphore(LANES),
        private val failing: Set<String> = emptySet(),
    ) {
        val calls = mutableListOf<List<String>>()

        suspend fun flush(items: List<DeleteRequest>): List<Throwable?> =
            lanes.withPermit {
                calls += items.map { it.remoteId }
                delay(RTT)
                items.map { if (it.remoteId in failing) ProviderException("Item not found: ${it.remotePath}") else null }
            }
    }

    private fun req(i: Int) = DeleteRequest("f$i", "/D/f$i.txt", isFolder = false)

    @Test
    fun `a lone delete is sent at once, with no added wait`() =
        runTest {
            val flush = CountingFlush()
            val coalescer = DeleteCoalescer(flush::flush)

            coalescer.delete(req(1))

            assertEquals(listOf(listOf("f1")), flush.calls)
            assertEquals(RTT, currentTime, "one round trip, no timer on top")
        }

    @Test
    fun `sequential deletes are one call each and no slower than before`() =
        runTest {
            val flush = CountingFlush()
            val coalescer = DeleteCoalescer(flush::flush)
            repeat(10) { coalescer.delete(req(it)) }
            assertEquals(10, flush.calls.size)
            assertEquals(10 * RTT, currentTime)
        }

    @Test
    fun `120 concurrent deletes share calls of at most 50 items, and each caller is answered`() =
        runTest {
            val flush = CountingFlush()
            val coalescer = DeleteCoalescer(flush::flush)

            coroutineScope { (1..120).map { i -> async { coalescer.delete(req(i)) } }.awaitAll() }

            assertTrue(flush.calls.all { it.size <= DeleteCoalescer.MAX_BATCH }, flush.calls.map { it.size }.toString())
            assertEquals((1..120).map { "f$it" }.toSet(), flush.calls.flatten().toSet())
            assertEquals(120, flush.calls.sumOf { it.size }, "every item sent once")
            assertTrue(flush.calls.size <= 5, "${flush.calls.size} calls for 120 concurrent deletes")
        }

    @Test
    fun `an item's error is thrown to its own caller only`() =
        runTest {
            val flush = CountingFlush(failing = setOf("f7"))
            val coalescer = DeleteCoalescer(flush::flush)

            val results = coroutineScope { (1..20).map { i -> async { runCatching { coalescer.delete(req(i)) } } }.awaitAll() }

            assertTrue(results[6].exceptionOrNull() is ProviderException)
            assertTrue(results.filterIndexed { i, _ -> i != 6 }.all { it.isSuccess })
        }

    @Test
    fun `a call that fails as a whole fails every caller in it`() =
        runTest {
            val coalescer = DeleteCoalescer({ throw ProviderException("Server error 503") })
            val results = coroutineScope { (1..5).map { i -> async { runCatching { coalescer.delete(req(i)) } } }.awaitAll() }
            assertTrue(results.all { it.exceptionOrNull()?.message == "Server error 503" })
        }

    @Test
    fun `the caller that sends a batch can be cancelled without stranding the others in it`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val sent = mutableListOf<List<String>>()
            val coalescer =
                DeleteCoalescer({ items ->
                    sent += items.map { it.remoteId }
                    gate.await()
                    items.map { null }
                }, maxInFlight = 1)
            // f1 occupies the one call; f2 opens the next batch and sends it, f3 joins that batch.
            val first = launch { coalescer.delete(req(1)) }
            delay(1)
            val leader = launch { coalescer.delete(req(2)) }
            delay(1)
            val follower = async { coalescer.delete(req(3)) }
            delay(1)
            leader.cancel()
            gate.complete(Unit)
            follower.await()
            first.join()
            leader.join()
            assertTrue(leader.isCancelled, "the cancelled caller is answered with its cancellation")
            assertEquals(listOf(listOf("f1"), listOf("f3")), sent)
        }

    // ── the measurement the issues ask for ────────────────────────────────────────────────────────────────

    // A burst of [n] deletes from [callers] concurrent callers (each waits for its reply before its next delete, as the
    // IPC write verbs do), and one content request that joins the provider's lanes just after the burst began. Answers
    // the provider calls and the content request's wait for a lane (its stall), in virtual ms.
    private suspend fun TestScope.burst(
        n: Int,
        callers: Int,
        coalesced: Boolean,
    ): Pair<Int, Long> {
        val lanes = Semaphore(LANES)
        val flush = CountingFlush(lanes)
        val coalescer = DeleteCoalescer(flush::flush)
        val next = java.util.concurrent.atomic.AtomicInteger(0)
        var stall = 0L
        coroutineScope {
            repeat(callers) {
                launch {
                    while (true) {
                        val i = next.incrementAndGet()
                        if (i > n) break
                        if (coalesced) coalescer.delete(req(i)) else flush.flush(listOf(req(i)))
                    }
                }
            }
            launch {
                delay(1)
                val asked = currentTime
                lanes.withPermit { stall = currentTime - asked }
            }
        }
        return flush.calls.size to stall
    }

    @Test
    fun `measured - calls and content stall per 100 and per 2729 deletes, before and after`() =
        runTest {
            for (n in listOf(100, 2729)) {
                for (callers in listOf(8, 50, 100)) {
                    val (callsBefore, stallBefore) = burst(n, callers, coalesced = false)
                    val (callsAfter, stallAfter) = burst(n, callers, coalesced = true)
                    println(
                        "#634/#831 measured: $n deletes, $callers concurrent callers: provider calls $callsBefore -> $callsAfter, " +
                            "content stall ${stallBefore} ms -> ${stallAfter} ms (RTT $RTT ms, $LANES lanes)",
                    )
                    assertEquals(n, callsBefore)
                    assertTrue(callsAfter < callsBefore)
                    assertTrue(stallAfter <= stallBefore)
                }
            }
        }

    @Test
    fun `a delete that fails the caller still ends in an exception of its own type`() =
        runTest {
            val coalescer = DeleteCoalescer(CountingFlush(failing = setOf("f1"))::flush)
            assertFailsWith<ProviderException> { coalescer.delete(req(1)) }
        }

    private companion object {
        const val RTT = 140L
        const val LANES = 2
    }
}
