package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * gkrost/unidrive-cli#267: the English text of the three deletion safeguards, byte for byte. The CLI prints the thrown
 * message as `Sync error: <message>` and a dry-run or daemon-mode trip as a warning; moving the safeguards onto a typed
 * outcome must not change one byte of either. If this test is loosened, the operator-visible text can drift unnoticed.
 */
class DeleteSafeguardTextTest : DeleteSafeguardFixture() {
    private class Warnings : ProgressReporter by ProgressReporter.Silent {
        val seen = mutableListOf<String>()

        override fun onWarning(message: String) {
            seen += message
        }

        // The interface default (the text a text-only reporter shows), not Silent's delegated copy of it.
        override fun onSafeguard(
            trip: SafeguardTripped,
            deferred: Boolean,
        ) = super<ProgressReporter>.onSafeguard(trip, deferred)
    }

    @Test
    fun the_percentage_safeguard_aborts_with_its_exact_text() =
        runTest {
            ninetyNineOfOneHundred()
            val ex = assertFailsWith<IllegalStateException> { engine(maxDeletePercentage = 50).syncOnce() }
            assertEquals(
                "Deletion safeguard: 99 of 100 files (99%) would be deleted, exceeding max_delete_percentage=50%. " +
                    "sync_root='$syncRoot'. Use --force-delete to override.",
                ex.message,
            )
        }

    @Test
    fun the_absolute_safeguard_aborts_with_its_exact_text() =
        runTest {
            ninetyNineOfOneHundred()
            val ex = assertFailsWith<IllegalStateException> { engine(maxDeleteAbsolute = 50).syncOnce() }
            assertEquals(
                "UD-265 Deletion safeguard: 99 deletes planned, exceeding max_delete_absolute=50. " +
                    "sync_root='$syncRoot'. Use --force-delete to override.",
                ex.message,
            )
        }

    @Test
    fun the_subtree_safeguard_aborts_with_its_exact_text() =
        runTest {
            nineteenOfTwentyUnderDocuments()
            val ex = assertFailsWith<IllegalStateException> { engine(maxDeletePerSubtreePercent = 80).syncOnce() }
            assertEquals(
                "UD-265 Deletion safeguard: 19 of 20 entries under top-level '/Documents' (95%) would be deleted, " +
                    "exceeding max_delete_per_subtree_percent=80%. sync_root='$syncRoot'. Use --force-delete to override.",
                ex.message,
            )
        }

    @Test
    fun a_dry_run_trip_warns_with_the_same_exact_text() =
        runTest {
            ninetyNineOfOneHundred()
            val warnings = Warnings()
            // A dry run plans against a throwaway snapshot, as `sync --dry-run` does.
            val snapshot = StateDatabase.snapshotOf(dbPath)
            try {
                engine(reporter = warnings, db = snapshot, maxDeletePercentage = 50, maxDeleteAbsolute = 50).syncOnce(dryRun = true)
            } finally {
                snapshot.close()
            }
            assertEquals(
                listOf(
                    "Deletion safeguard: 99 of 100 files (99%) would be deleted, exceeding max_delete_percentage=50%. " +
                        "sync_root='$syncRoot'. Use --force-delete to override.",
                    "UD-265 Deletion safeguard: 99 deletes planned, exceeding max_delete_absolute=50. " +
                        "sync_root='$syncRoot'. Use --force-delete to override.",
                ),
                warnings.seen.filter { "safeguard" in it },
            )
        }

    @Test
    fun a_daemon_mode_trip_warns_with_the_deferral_suffix() =
        runTest {
            nineteenOfTwentyUnderDocuments()
            val warnings = Warnings()
            engine(reporter = warnings, maxDeletePerSubtreePercent = 80).syncOnce(reason = SyncReason.WATCH_POLL)
            assertEquals(
                listOf(
                    "UD-265 Deletion safeguard: 19 of 20 entries under top-level '/Documents' (95%) would be deleted, " +
                        "exceeding max_delete_per_subtree_percent=80%. sync_root='$syncRoot'. Use --force-delete to override. " +
                        "Deferring deletes this cycle (daemon mode).",
                ),
                warnings.seen.filter { "safeguard" in it },
            )
        }
}
