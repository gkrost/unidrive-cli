package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.SafeguardTripped.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * gkrost/unidrive-cli#267: a tripped deletion safeguard reaches the caller as a typed [SafeguardTripped] carrying its numbers,
 * never only as English: a one-shot apply throws [SafeguardTrippedException], a dry run and a daemon-mode pass report
 * [ProgressReporter.onSafeguard]. If these tests are loosened, an IPC client or a GUI is back to parsing the text.
 */
class SafeguardTrippedTest : DeleteSafeguardFixture() {
    private class Trips : ProgressReporter by ProgressReporter.Silent {
        val seen = mutableListOf<Pair<SafeguardTripped, Boolean>>()

        override fun onSafeguard(
            trip: SafeguardTripped,
            deferred: Boolean,
        ) {
            seen += trip to deferred
        }
    }

    private fun root() = syncRoot.toString()

    @Test
    fun the_percentage_safeguard_throws_its_typed_outcome() =
        runTest {
            ninetyNineOfOneHundred()
            val ex = assertFailsWith<SafeguardTrippedException> { engine(maxDeletePercentage = 50).syncOnce() }
            assertEquals(SafeguardTripped(Kind.DELETE_PERCENTAGE, 99, 100, 50, null, root()), ex.trip)
            assertEquals(99, ex.trip.percent)
        }

    @Test
    fun the_absolute_safeguard_throws_its_typed_outcome() =
        runTest {
            ninetyNineOfOneHundred()
            val ex = assertFailsWith<SafeguardTrippedException> { engine(maxDeleteAbsolute = 50).syncOnce() }
            assertEquals(SafeguardTripped(Kind.DELETE_ABSOLUTE, 99, null, 50, null, root()), ex.trip)
            assertEquals(null, ex.trip.percent)
        }

    @Test
    fun the_subtree_safeguard_throws_its_typed_outcome_with_the_subtree() =
        runTest {
            nineteenOfTwentyUnderDocuments()
            val ex = assertFailsWith<SafeguardTrippedException> { engine(maxDeletePerSubtreePercent = 80).syncOnce() }
            assertEquals(SafeguardTripped(Kind.DELETE_SUBTREE, 19, 20, 80, "/Documents", root()), ex.trip)
            assertEquals(95, ex.trip.percent)
        }

    @Test
    fun a_one_shot_apply_throws_on_the_first_axis_that_trips() =
        runTest {
            // Both the percentage and the absolute cap trip; the percentage axis is checked first, as before #267.
            ninetyNineOfOneHundred()
            val ex =
                assertFailsWith<SafeguardTrippedException> {
                    engine(maxDeletePercentage = 50, maxDeleteAbsolute = 50).syncOnce()
                }
            assertEquals(Kind.DELETE_PERCENTAGE, ex.trip.kind)
        }

    @Test
    fun a_dry_run_reports_every_trip_typed_and_not_deferred() =
        runTest {
            ninetyNineOfOneHundred()
            val trips = Trips()
            val snapshot = StateDatabase.snapshotOf(dbPath)
            try {
                engine(reporter = trips, db = snapshot, maxDeletePercentage = 50, maxDeleteAbsolute = 50).syncOnce(dryRun = true)
            } finally {
                snapshot.close()
            }
            assertEquals(
                listOf(
                    SafeguardTripped(Kind.DELETE_PERCENTAGE, 99, 100, 50, null, root()) to false,
                    SafeguardTripped(Kind.DELETE_ABSOLUTE, 99, null, 50, null, root()) to false,
                ),
                trips.seen,
            )
        }

    @Test
    fun a_daemon_mode_trip_is_reported_typed_and_deferred() =
        runTest {
            nineteenOfTwentyUnderDocuments()
            val trips = Trips()
            engine(reporter = trips, maxDeletePerSubtreePercent = 80).syncOnce(reason = SyncReason.WATCH_POLL)
            assertEquals(listOf(SafeguardTripped(Kind.DELETE_SUBTREE, 19, 20, 80, "/Documents", root()) to true), trips.seen)
        }

    @Test
    fun the_kind_tokens_are_the_config_keys_of_the_caps() {
        // The wire names are a contract with IPC clients: renaming one breaks a client that keys on it.
        assertEquals(
            listOf("max_delete_percentage", "max_delete_absolute", "max_delete_per_subtree_percent"),
            Kind.entries.map { it.token },
        )
        assertEquals("safeguard_tripped", SafeguardTripped.TOKEN)
    }
}
