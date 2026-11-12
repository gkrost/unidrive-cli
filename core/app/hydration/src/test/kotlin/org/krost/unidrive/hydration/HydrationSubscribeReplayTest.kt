package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#437 (2c): every event of the hydration stream carries a `seq`, the subscribe reply names the last one
 * (`tail_seq`) and the daemon run (`run_id`), and a subscriber that saw `lost` or lost its connection subscribes again with
 * `since_seq` and the `run_id` and is sent what it missed from a bounded ring, or is told that the ring no longer holds it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HydrationSubscribeReplayTest {
    private class FakeWriter {
        val lines = ConcurrentHashMap<String, MutableList<String>>()

        @Volatile var gate: CompletableDeferred<Unit>? = null

        suspend fun write(connectionId: String, line: String): Boolean {
            gate?.await()
            lines.computeIfAbsent(connectionId) { Collections.synchronizedList(mutableListOf()) }.add(line)
            return true
        }

        fun linesFor(connectionId: String): List<String> = lines[connectionId]?.toList() ?: emptyList()
    }

    private fun obj(line: String): JsonObject = Json.parseToJsonElement(line) as JsonObject

    private fun seqOf(line: String): Long = obj(line).getValue("seq").jsonPrimitive.long

    private fun pathOf(line: String): String? = (obj(line)["path"] as? JsonPrimitive)?.content

    private fun newHandler() = HydrationIpcHandler(HydrationTestEnv().hydration, handleIdRun = "0123456789ab")

    private fun completed(handle: String) =
        HydrationEvent.Completed("/$handle.txt", handle, HydrationEvent.Completed.Direction.UPLOAD, ok = true, error = null)

    @Test
    fun `every pushed event carries a seq that counts up, and the reply names the last one and the run`() = runTest {
        val handler = newHandler()
        val writer = FakeWriter()
        handler.start(this) { id, line -> writer.write(id, line) }
        handler.dispatchEvent(HydrationEvent.Hydrating("/before1"))
        handler.dispatchEvent(HydrationEvent.Hydrating("/before2"))

        val reply = handler.handle("c1", """{"verb":"hydration.subscribe"}""").trim()
        assertEquals("""{"ok":true,"tail_seq":2,"run_id":"0123456789ab"}""", reply)

        handler.dispatchEvent(HydrationEvent.Hydrated("/a.txt", 42))
        handler.dispatchEvent(HydrationEvent.Uploading("/b.txt", "h1", 1, 2))
        advanceUntilIdle()

        val out = writer.linesFor("c1")
        assertEquals(listOf("""{"event":"hydrated","seq":3,"path":"/a.txt","bytes":42}"""), out.take(1), "seq follows the event name")
        assertEquals(listOf(3L, 4L), out.map(::seqOf), "a plain subscribe gets no replay; progress is numbered too")
        handler.onSubscriberDisconnect("c1")
    }

    @Test
    fun `since_seq replays exactly the events after it, then the live ones, with no gap and no repeat`() = runTest {
        val handler = newHandler()
        val writer = FakeWriter()
        handler.start(this) { id, line -> writer.write(id, line) }
        for (i in 1..5) handler.dispatchEvent(HydrationEvent.Hydrated("/f$i", i.toLong()))

        val reply = handler.handle("c2", """{"verb":"hydration.subscribe","since_seq":3,"run_id":"0123456789ab"}""").trim()
        assertEquals("""{"ok":true,"tail_seq":5,"run_id":"0123456789ab","replay":"complete"}""", reply)
        handler.dispatchEvent(HydrationEvent.Hydrated("/f6", 6))
        advanceUntilIdle()

        val out = writer.linesFor("c2")
        assertEquals(listOf(4L, 5L, 6L), out.map(::seqOf))
        assertEquals(listOf("/f4", "/f5", "/f6"), out.map(::pathOf))
        handler.onSubscriberDisconnect("c2")
    }

    @Test
    fun `a completed the first connection dropped on overflow is replayed to the reconnect`() = runTest {
        val handler = newHandler()
        val writer = FakeWriter()
        val gate = CompletableDeferred<Unit>()
        handler.start(this) { id, line -> writer.write(id, line) }
        handler.handle("c1", """{"verb":"hydration.subscribe"}""")
        handler.dispatchEvent(HydrationEvent.Hydrating("/seen"))
        advanceUntilIdle()
        val lastSeen = seqOf(writer.linesFor("c1").last())

        // The first connection stalls and dies with its queue; the completed never reaches it.
        writer.gate = gate
        handler.dispatchEvent(HydrationEvent.Queued("/x.txt"))
        advanceUntilIdle()
        repeat(HydrationIpcHandler.SUBSCRIBER_QUEUE_CAPACITY * 2) { handler.dispatchEvent(HydrationEvent.Hydrating("/noise$it")) }
        handler.dispatchEvent(completed("h7"))
        handler.onSubscriberDisconnect("c1")
        writer.gate = null
        gate.complete(Unit)
        advanceUntilIdle()

        val reply = handler.handle("c2", """{"verb":"hydration.subscribe","since_seq":$lastSeen,"run_id":"0123456789ab"}""")
        assertTrue(reply.contains(""""replay":"complete""""), reply)
        advanceUntilIdle()
        val out = writer.linesFor("c2")
        assertTrue(out.none { it.contains(""""event":"lost"""") }, "a replay larger than the live queue is not cut: $out")
        assertEquals(HydrationIpcHandler.SUBSCRIBER_QUEUE_CAPACITY * 2 + 2, out.size)
        assertTrue(out.last().contains(""""event":"completed"""") && out.last().contains(""""handle_id":"h7""""), out.last())
        handler.onSubscriberDisconnect("c2")
    }

    @Test
    fun `progress is numbered but not kept for a replay`() = runTest {
        val handler = newHandler()
        val writer = FakeWriter()
        handler.start(this) { id, line -> writer.write(id, line) }
        handler.dispatchEvent(HydrationEvent.Uploading("/a", "h1", 1, 9))
        handler.dispatchEvent(HydrationEvent.Downloading("/b", null, 1, 9))
        handler.dispatchEvent(HydrationEvent.Hydrated("/b", 9))

        handler.handle("c", """{"verb":"hydration.subscribe","since_seq":0,"run_id":"0123456789ab"}""")
        advanceUntilIdle()
        assertEquals(listOf(3L), writer.linesFor("c").map(::seqOf))
        handler.onSubscriberDisconnect("c")
    }

    @Test
    fun `a since_seq the ring no longer holds says gap and replays what it still holds`() = runTest {
        val handler = newHandler()
        val writer = FakeWriter()
        handler.start(this) { id, line -> writer.write(id, line) }
        val total = HydrationIpcHandler.REPLAY_CAPACITY + 10
        for (i in 1..total) handler.dispatchEvent(HydrationEvent.Hydrated("/f$i", 1))

        val reply = handler.handle("c", """{"verb":"hydration.subscribe","since_seq":5,"run_id":"0123456789ab"}""").trim()
        assertEquals("""{"ok":true,"tail_seq":$total,"run_id":"0123456789ab","replay":"gap"}""", reply)
        advanceUntilIdle()
        val seqs = writer.linesFor("c").map(::seqOf)
        assertEquals(HydrationIpcHandler.REPLAY_CAPACITY, seqs.size)
        assertEquals(11L, seqs.first())
        assertEquals(total.toLong(), seqs.last())

        // The oldest the ring holds is 11: since_seq 10 is still complete.
        val again = handler.handle("d", """{"verb":"hydration.subscribe","since_seq":10,"run_id":"0123456789ab"}""")
        assertTrue(again.contains(""""replay":"complete""""), again)
        handler.onSubscriberDisconnect("c")
        handler.onSubscriberDisconnect("d")
    }

    @Test
    fun `another run's since_seq, or one ahead of the tail, is a gap and gets the whole ring`() = runTest {
        val handler = newHandler()
        val writer = FakeWriter()
        handler.start(this) { id, line -> writer.write(id, line) }
        for (i in 1..3) handler.dispatchEvent(HydrationEvent.Hydrated("/f$i", 1))

        val otherRun = handler.handle("c", """{"verb":"hydration.subscribe","since_seq":2,"run_id":"ffffffffffff"}""")
        assertTrue(otherRun.contains(""""replay":"gap""""), otherRun)
        val ahead = handler.handle("d", """{"verb":"hydration.subscribe","since_seq":9,"run_id":"0123456789ab"}""")
        assertTrue(ahead.contains(""""replay":"gap""""), ahead)
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L, 3L), writer.linesFor("c").map(::seqOf))
        assertEquals(listOf(1L, 2L, 3L), writer.linesFor("d").map(::seqOf))
        handler.onSubscriberDisconnect("c")
        handler.onSubscriberDisconnect("d")
    }

    @Test
    fun `a bad since_seq or a missing run_id is refused and subscribes nothing`() = runTest {
        val handler = newHandler()
        val writer = FakeWriter()
        handler.start(this) { id, line -> writer.write(id, line) }
        for (bad in listOf("\"3\"", "-1", "1.5", "null", "1e3")) {
            assertEquals(
                """{"ok":false,"error":"invalid_since_seq"}""",
                handler.handle("c", """{"verb":"hydration.subscribe","since_seq":$bad,"run_id":"0123456789ab"}""").trim(),
                "since_seq $bad",
            )
        }
        assertEquals(
            """{"ok":false,"error":"missing_run_id"}""",
            handler.handle("c", """{"verb":"hydration.subscribe","since_seq":1}""").trim(),
        )
        assertFalse(handler.hasSubscribers())
    }

    @Test
    fun `handle_status answers the completed line the stream carried, seq included`() = runTest {
        val handler = newHandler()
        val writer = FakeWriter()
        handler.start(this) { id, line -> writer.write(id, line) }
        handler.handle("c", """{"verb":"hydration.subscribe"}""")
        handler.dispatchEvent(completed("h1"))
        advanceUntilIdle()

        val pushed = writer.linesFor("c").single()
        assertEquals(1L, seqOf(pushed))
        assertEquals(
            """{"ok":true,"state":"done","completed":$pushed}""",
            handler.handle("c2", """{"verb":"hydration.handle_status","handle_id":"h1"}""").trim(),
        )
        handler.onSubscriberDisconnect("c")
    }
}
