package org.krost.unidrive.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#926 S3: the queue and the writer of one `sync.subscribe` subscriber, without a socket.
 * [IpcSyncFanOutTest] shows over a socket that a subscriber which reads nothing delays nobody else and is told what it lost;
 * a socket cannot show what one subscriber's queue keeps, drops and still writes after it was stopped, so that is pinned here.
 *
 * If any of these tests is deleted or loosened, the invariant in its name silently regresses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IpcSyncFanOutQueueTest {
    // What the writers wrote. The first write may be held: the line is then the one in flight, and it still goes out whole
    // when its writer is stopped meanwhile, like the server's write (which cannot be cancelled half way).
    private class Recorder {
        val lines = mutableListOf<String>()
        val entered = CompletableDeferred<Unit>()
        var holdFirstWriteOn: CompletableDeferred<Unit>? = null
        var answer = true

        suspend fun write(
            connectionId: String,
            line: String,
        ): Boolean {
            if (!entered.isCompleted) {
                entered.complete(Unit)
                holdFirstWriteOn?.let { withContext(NonCancellable) { it.await() } }
            }
            lines += "$connectionId:$line"
            return answer
        }
    }

    @Test
    fun a_full_queue_drops_its_oldest_lines_and_reports_how_many_before_the_next_one() =
        runTest {
            val writes = Recorder()
            val fanOut = IpcSyncFanOut(this, writes::write, capacity = 4)
            fanOut.add("c")
            val gate = CompletableDeferred<Unit>()
            writes.holdFirstWriteOn = gate
            fanOut.offer("in flight")
            runCurrent()
            assertTrue(writes.entered.isCompleted, "the writer is inside its write")
            repeat(7) { fanOut.offer("line $it") } // seven behind a queue that holds four: the first three are gone
            gate.complete(Unit)
            runCurrent()

            assertEquals(
                listOf(
                    "c:in flight",
                    """c:{"event":"lost","since_last":3}""",
                    "c:line 3",
                    "c:line 4",
                    "c:line 5",
                    "c:line 6",
                ),
                writes.lines,
            )
            assertTrue(fanOut.isSubscribed("c"), "a subscriber that lost lines stays subscribed")
            fanOut.clear()
            runCurrent()
        }

    @Test
    fun a_stopped_subscriber_writes_nothing_after_its_line_in_flight() =
        runTest {
            val writes = Recorder()
            val fanOut = IpcSyncFanOut(this, writes::write, capacity = 4)
            fanOut.add("c")
            val gate = CompletableDeferred<Unit>()
            writes.holdFirstWriteOn = gate
            fanOut.offer("in flight")
            runCurrent()
            fanOut.offer("queued") // behind the line in flight
            fanOut.remove("c")
            fanOut.offer("offered to an iteration that may still hold it")
            gate.complete(Unit)
            runCurrent()

            assertEquals(listOf("c:in flight"), writes.lines)
            assertFalse(fanOut.isSubscribed("c"))
            fanOut.clear()
            runCurrent()
        }

    @Test
    fun a_repeat_subscribe_leaves_one_writer_and_the_first_writes_nothing_more() =
        runTest {
            val writes = Recorder()
            val fanOut = IpcSyncFanOut(this, writes::write, capacity = 4)
            fanOut.add("c")
            val gate = CompletableDeferred<Unit>()
            writes.holdFirstWriteOn = gate
            fanOut.offer("first") // the first writer's line in flight
            runCurrent()
            fanOut.offer("from before the repeat subscribe")
            fanOut.add("c") // a fresh queue and writer for the same connection
            fanOut.offer("after")
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            fanOut.offer("later")
            runCurrent()

            assertEquals(setOf("c:first", "c:after", "c:later"), writes.lines.toSet())
            assertEquals(3, writes.lines.size, "no line reaches the connection twice")
            assertTrue(fanOut.isSubscribed("c"), "the new writer stays")
            fanOut.clear()
            runCurrent()
        }

    @Test
    fun a_subscriber_held_in_its_write_does_not_hold_another_one() =
        runTest {
            val writes = Recorder()
            val fanOut = IpcSyncFanOut(this, writes::write, capacity = 4)
            fanOut.add("slow")
            val gate = CompletableDeferred<Unit>()
            writes.holdFirstWriteOn = gate
            fanOut.offer("to the slow one")
            runCurrent()
            fanOut.add("fast")
            fanOut.offer("marker")
            runCurrent()

            assertTrue("fast:marker" in writes.lines, "the other subscriber is written while the first is still held")
            assertFalse(gate.isCompleted, "the first subscriber's write is still held")
            gate.complete(Unit)
            fanOut.clear()
            runCurrent()
        }

    @Test
    fun a_write_that_fails_ends_the_writer_and_forgets_the_subscriber() =
        runTest {
            val writes =
                Recorder().apply {
                    answer = false
                }
            val fanOut = IpcSyncFanOut(this, writes::write, capacity = 4)
            fanOut.add("c")
            runCurrent()
            assertTrue(fanOut.isSubscribed("c"))
            fanOut.offer("one")
            fanOut.offer("two")
            runCurrent()

            assertEquals(listOf("c:one"), writes.lines, "the writer ends at the failed write")
            assertFalse(fanOut.isSubscribed("c"))
        }

    @Test
    fun clear_ends_every_writer_and_what_is_offered_after_it_reaches_nobody() =
        runTest {
            val writes = Recorder()
            val fanOut = IpcSyncFanOut(this, writes::write, capacity = 4)
            fanOut.add("a")
            fanOut.add("b")
            fanOut.offer("before")
            runCurrent()
            fanOut.clear()
            fanOut.offer("after")
            runCurrent()

            assertEquals(setOf("a:before", "b:before"), writes.lines.toSet())
            assertEquals(2, writes.lines.size)
            assertFalse(fanOut.isSubscribed("a"))
            assertFalse(fanOut.isSubscribed("b"))
        }
}
