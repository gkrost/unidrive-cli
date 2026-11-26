package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#845 (item #746-9, owner decision 2026-11-26): a `hydration.subscribe` subscriber whose queue holds nothing
 * but outcomes beyond the hard cap ([HydrationIpcHandler.SUBSCRIBER_TERMINAL_HARD_CAP]) is disconnected instead of losing an outcome;
 * it reconnects and replays with `since_seq`. One test per invariant: the disconnect itself, the convergence of a reconnect, the
 * others' delivery, and the replay `gap` when the ring has moved on. Removing or loosening any of them lets a stalled subscriber
 * either keep its slot forever, silently lose an outcome, or hold up the others.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HydrationSubscriberHardCapTest {
    private class FakeWriter {
        val lines = ConcurrentHashMap<String, MutableList<String>>()

        // Per connection: a stalled subscriber reads nothing, the others are unaffected.
        val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        suspend fun write(connectionId: String, line: String): Boolean {
            gates[connectionId]?.await()
            lines.computeIfAbsent(connectionId) { Collections.synchronizedList(mutableListOf()) }.add(line)
            return true
        }

        fun linesFor(connectionId: String): List<String> = lines[connectionId]?.toList() ?: emptyList()
    }

    private fun seqOf(line: String): Long = (Json.parseToJsonElement(line) as JsonObject).getValue("seq").jsonPrimitive.long

    private fun completed(i: Int) =
        HydrationEvent.Completed("/$i.txt", "h$i", HydrationEvent.Completed.Direction.UPLOAD, ok = true, error = null)

    // Enough events that a reader which reads nothing has the replay ring filled past what it was given: the cap is in replay events.
    private val overTheCap = HydrationIpcHandler.REPLAY_CAPACITY - HydrationIpcHandler.SUBSCRIBER_REPLAY_HEADROOM + 2

    private fun newHandler() = HydrationIpcHandler(HydrationTestEnv().hydration, handleIdRun = "0123456789ab")

    @Test
    fun `a hydration subscriber at the hard queue cap is disconnected`() = runTest {
        val handler = newHandler()
        val writer = FakeWriter()
        handler.start(this, writer::write)
        handler.handle("slow", """{"verb":"hydration.subscribe"}""")
        handler.dispatchEvent(HydrationEvent.Hydrating("/first"))
        advanceUntilIdle()
        writer.gates["slow"] = CompletableDeferred()

        for (i in 1..overTheCap) handler.dispatchEvent(completed(i))
        advanceUntilIdle()

        assertFalse(handler.hasSubscribers(), "the subscriber at the cap is dropped, not kept with a lost sentinel")
        assertFalse(handler.holdsConnectionState("slow"))
        writer.gates.getValue("slow").complete(Unit)
        advanceUntilIdle()
        assertTrue(writer.linesFor("slow").none { it.contains(""""event":"lost"""") }, "no outcome was dropped on the way")
        handler.onSubscriberDisconnect("slow")
    }

    @Test
    fun `a subscriber disconnected at the cap during download triples replays completely, not gap`() = runTest {
        val handler = newHandler()
        val writer = FakeWriter()
        handler.start(this, writer::write)
        handler.handle("c1", """{"verb":"hydration.subscribe"}""")
        handler.dispatchEvent(HydrationEvent.Hydrated("/seen", 1))
        advanceUntilIdle()
        val applied = writer.linesFor("c1").map(::seqOf).toMutableList()
        val lastApplied = applied.last()
        writer.gates["c1"] = CompletableDeferred()

        // Every download is three replayable events; only the completed is an outcome the queue keeps.
        var downloads = 0
        while (handler.hasSubscribers() && downloads < HydrationIpcHandler.REPLAY_CAPACITY) {
            downloads++
            handler.dispatchEvent(HydrationEvent.Hydrating("/$downloads.txt"))
            handler.dispatchEvent(HydrationEvent.Hydrated("/$downloads.txt", 1))
            handler.dispatchEvent(completed(downloads))
        }
        advanceUntilIdle()
        assertFalse(handler.hasSubscribers(), "the stalled subscriber was disconnected")

        val reply = handler.handle("c2", """{"verb":"hydration.subscribe","since_seq":$lastApplied,"run_id":"0123456789ab"}""")
        assertTrue(reply.contains(""""replay":"complete""""), reply)
        handler.onSubscribeReplyWritten("c2")
        advanceUntilIdle()
        applied += writer.linesFor("c2").map(::seqOf)
        assertEquals((1L..(1L + 3L * downloads)).toList(), applied, "every event is applied once, in order")
        handler.onSubscriberDisconnect("c1")
        handler.onSubscriberDisconnect("c2")
    }

    @Test
    fun `the daemon is asked to close the connection of the subscriber it disconnects, once, and of no other`() = runTest {
        val handler = newHandler()
        val writer = FakeWriter()
        val closed = Collections.synchronizedList(mutableListOf<String>())
        handler.start(this, writer::write)
        handler.onSubscriberDisconnected { closed += it }
        handler.handle("slow", """{"verb":"hydration.subscribe"}""")
        handler.handle("fast", """{"verb":"hydration.subscribe"}""")
        handler.dispatchEvent(HydrationEvent.Hydrating("/first"))
        advanceUntilIdle()
        writer.gates["slow"] = CompletableDeferred()

        for (i in 1..overTheCap) {
            handler.dispatchEvent(completed(i))
            advanceUntilIdle()
        }

        assertEquals(listOf("slow"), closed)
        handler.onSubscriberDisconnect("fast")
        handler.onSubscriberDisconnect("slow")
    }

    @Test
    fun `a disconnected subscriber that reconnects with since_seq receives every event it missed and converges`() = runTest {
        val handler = newHandler()
        val writer = FakeWriter()
        handler.start(this, writer::write)
        handler.handle("c1", """{"verb":"hydration.subscribe"}""")
        handler.dispatchEvent(HydrationEvent.Hydrated("/seen", 1))
        advanceUntilIdle()
        val applied = writer.linesFor("c1").map(::seqOf).toMutableList()
        val lastApplied = applied.last()

        writer.gates["c1"] = CompletableDeferred()
        assertTrue(overTheCap < HydrationIpcHandler.REPLAY_CAPACITY, "the ring must hold what a subscriber at the cap missed")
        for (i in 1..overTheCap) handler.dispatchEvent(completed(i))
        advanceUntilIdle()
        assertFalse(handler.hasSubscribers())

        // The client's replay path: subscribe again with the last seq it applied and the run_id it read it in.
        val reply = handler.handle("c2", """{"verb":"hydration.subscribe","since_seq":$lastApplied,"run_id":"0123456789ab"}""")
        assertTrue(reply.contains(""""replay":"complete""""), reply)
        handler.onSubscribeReplyWritten("c2")
        advanceUntilIdle()
        applied += writer.linesFor("c2").map(::seqOf)

        assertEquals((1L..(1L + overTheCap)).toList(), applied, "every event is applied once, in order")
        handler.onSubscriberDisconnect("c2")
        handler.onSubscriberDisconnect("c1")
    }

    @Test
    fun `other subscribers keep receiving while one is at the cap and is disconnected`() = runTest {
        val handler = newHandler()
        val writer = FakeWriter()
        handler.start(this, writer::write)
        handler.handle("slow", """{"verb":"hydration.subscribe"}""")
        handler.handle("fast", """{"verb":"hydration.subscribe"}""")
        handler.dispatchEvent(HydrationEvent.Hydrating("/first"))
        advanceUntilIdle()
        writer.gates["slow"] = CompletableDeferred()

        for (i in 1..overTheCap) {
            handler.dispatchEvent(completed(i))
            advanceUntilIdle() // the fast subscriber's writer runs between events, as a live socket reader does
        }
        handler.dispatchEvent(completed(overTheCap + 1))
        advanceUntilIdle()

        val fast = writer.linesFor("fast").map(::seqOf)
        assertEquals((1L..(overTheCap + 2L)).toList(), fast, "the fast subscriber got every event, before and after the other's disconnect")
        assertTrue(handler.holdsConnectionState("fast"))
        assertFalse(handler.holdsConnectionState("slow"), "the slow one was disconnected")
        handler.onSubscriberDisconnect("fast")
        handler.onSubscriberDisconnect("slow")
    }

    @Test
    fun `a reconnect whose since_seq fell out of the ring gets replay gap`() = runTest {
        val handler = newHandler()
        val writer = FakeWriter()
        handler.start(this, writer::write)
        handler.handle("c1", """{"verb":"hydration.subscribe"}""")
        handler.dispatchEvent(HydrationEvent.Hydrated("/seen", 1))
        advanceUntilIdle()
        val lastApplied = writer.linesFor("c1").map(::seqOf).last()
        writer.gates["c1"] = CompletableDeferred()
        for (i in 1..(HydrationIpcHandler.REPLAY_CAPACITY + overTheCap)) handler.dispatchEvent(completed(i))
        advanceUntilIdle()

        val reply = handler.handle("c2", """{"verb":"hydration.subscribe","since_seq":$lastApplied,"run_id":"0123456789ab"}""")
        assertTrue(reply.contains(""""replay":"gap""""), reply)
        handler.onSubscriberDisconnect("c2")
        handler.onSubscriberDisconnect("c1")
    }
}
