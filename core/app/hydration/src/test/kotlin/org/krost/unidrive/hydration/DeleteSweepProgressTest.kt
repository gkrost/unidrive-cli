package org.krost.unidrive.hydration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The whole-sweep progress of a released delete hold (gkrost/unidrive-cli#851): the client marks the deletes it sends
 * for a released hold with the hold's size, and the engine reports the sweep's total, its decreasing remaining and its
 * start time beside a stable sweep id, so a client's removal card can show "remaining of total" with an ETA from the
 * rate and keeps two consecutive sweeps apart. Counts only, no paths — the held record is counts-only by design
 * (windows#1310), and this status sits where other programs read it.
 */
class DeleteSweepProgressTest {
    private var now = 1_000_000L

    private fun sweep(): DeleteSweepProgress = DeleteSweepProgress(nowMs = { now })

    @Test
    fun `a declared sweep carries total remaining start time and an id`() {
        val sweep = sweep()

        sweep.declared(3)

        val snap = assertNotNull(sweep.snapshot())
        assertEquals(3, snap.total)
        assertEquals(3, snap.remaining)
        assertEquals(1_000_000L, snap.startedAtMs)
        assertTrue(snap.sweepId.isNotBlank(), "the sweep id is what keeps two sweeps apart")
    }

    @Test
    fun `a completion with no sweep under way counts nothing`() {
        val sweep = sweep()

        sweep.completed()

        assertNull(sweep.snapshot(), "an ordinary delete never starts a bar")
    }

    @Test
    fun `each marked delete that lands decrements the remaining`() {
        val sweep = sweep()
        sweep.declared(3)

        sweep.completed()
        now += 5

        val snap = assertNotNull(sweep.snapshot())
        assertEquals(2, snap.remaining)
        assertEquals(3, snap.total, "the total stays the size the sweep started with")
    }

    @Test
    fun `the fields clear when the last marked delete lands`() {
        val sweep = sweep()
        sweep.declared(2)

        sweep.completed()
        sweep.completed()

        assertNull(sweep.snapshot(), "remaining 0: the sweep is over and the fields are gone")
    }

    @Test
    fun `a second sweep gets a new id`() {
        val sweep = sweep()
        sweep.declared(1)
        val first = assertNotNull(sweep.snapshot())
        sweep.completed()
        now += 5

        sweep.declared(2)

        val second = assertNotNull(sweep.snapshot())
        assertNotEquals(first.sweepId, second.sweepId, "two consecutive sweeps must not share one bar")
        assertEquals(2, second.total)
        assertEquals(2, second.remaining)
        assertNotEquals(first.startedAtMs, second.startedAtMs, "the new sweep's clock starts at its own first delete")
    }

    @Test
    fun `a declaration of another total mid-sweep starts a new sweep`() {
        val sweep = sweep()
        sweep.declared(3)
        sweep.completed()
        val first = assertNotNull(sweep.snapshot())
        now += 5

        sweep.declared(5)

        val second = assertNotNull(sweep.snapshot())
        assertNotEquals(first.sweepId, second.sweepId, "a hold that changed is a new question")
        assertEquals(5, second.total)
        assertEquals(5, second.remaining, "the new hold's size is the new sweep's whole remaining")
    }

    @Test
    fun `re-declaring the same total keeps the sweep`() {
        val sweep = sweep()
        sweep.declared(3)
        sweep.completed()
        val first = assertNotNull(sweep.snapshot())
        now += 5

        sweep.declared(3)

        val second = assertNotNull(sweep.snapshot())
        assertEquals(first.sweepId, second.sweepId, "the same hold's re-declaration is the same sweep")
        assertEquals(2, second.remaining, "the completions already counted stay counted")
        assertEquals(first.startedAtMs, second.startedAtMs, "the sweep's clock does not restart")
    }

    @Test
    fun `a sweep the deletes stopped reaching clears after the quiet window`() {
        val sweep = sweep()
        sweep.declared(2)

        now += DeleteSweepProgress.SWEEP_QUIET_MS + 1

        assertNull(sweep.snapshot(), "no marked delete for the whole window: the sweep is stale, the fields clear")
    }

    @Test
    fun `a sweep still reaching deletes reports through the quiet window`() {
        val sweep = sweep()
        sweep.declared(2)
        sweep.completed()

        now += DeleteSweepProgress.SWEEP_QUIET_MS - 1

        assertNotNull(sweep.snapshot(), "the sweep is alive while its deletes keep landing")
    }

    @Test
    fun `after the quiet window a fresh declaration starts a new sweep`() {
        val sweep = sweep()
        sweep.declared(2)
        now += DeleteSweepProgress.SWEEP_QUIET_MS + 1
        assertNull(sweep.snapshot())
        now += 5

        sweep.declared(2)

        val snap = assertNotNull(sweep.snapshot())
        assertEquals(2, snap.remaining, "the stale sweep's owed deletes are forgotten")
    }

    @Test
    fun `a non-positive total declares nothing`() {
        val sweep = sweep()

        sweep.declared(0)

        assertNull(sweep.snapshot())
    }
}
