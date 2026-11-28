package org.krost.unidrive.cli

import org.krost.unidrive.sync.ProgressReporter
import org.krost.unidrive.sync.SafeguardTripped
import org.krost.unidrive.sync.SafeguardTripped.Kind
import org.krost.unidrive.sync.SafeguardTrippedException
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#267: what the CLI prints for a tripped deletion safeguard is byte for byte what it printed before the
 * outcome became typed. The expected strings are literals on purpose: if they are rebuilt from SafeguardText, a change to
 * the text passes unnoticed.
 */
class SafeguardCliTextTest {
    private val root = "/home/u/OneDrive"
    private val percentage = SafeguardTripped(Kind.DELETE_PERCENTAGE, 99, 100, 50, null, root)
    private val absolute = SafeguardTripped(Kind.DELETE_ABSOLUTE, 99, null, 50, null, root)
    private val subtree = SafeguardTripped(Kind.DELETE_SUBTREE, 19, 20, 80, "/Documents", root)

    @Test
    fun a_tripped_safeguard_aborts_with_the_same_sync_error_line() {
        assertEquals(
            listOf(
                "Sync error: Deletion safeguard: 99 of 100 files (99%) would be deleted, exceeding max_delete_percentage=50%. " +
                    "sync_root='/home/u/OneDrive'. Use --force-delete to override.",
                "Sync error: UD-265 Deletion safeguard: 99 deletes planned, exceeding max_delete_absolute=50. " +
                    "sync_root='/home/u/OneDrive'. Use --force-delete to override.",
                "Sync error: UD-265 Deletion safeguard: 19 of 20 entries under top-level '/Documents' (95%) would be deleted, " +
                    "exceeding max_delete_per_subtree_percent=80%. sync_root='/home/u/OneDrive'. Use --force-delete to override.",
            ),
            listOf(percentage, absolute, subtree).map { SyncCommand.renderSyncFailure(SafeguardTrippedException(it)) },
        )
        // Operator-facing as before: no stack trace under -v.
        assertTrue(SyncCommand.isOperatorFacing(SafeguardTrippedException(absolute)))
    }

    @Test
    fun a_dry_run_or_daemon_mode_trip_prints_the_same_warn_line() {
        val originalErr = System.err
        val err = ByteArrayOutputStream()
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        try {
            CliProgressReporter().onSafeguard(absolute, deferred = false)
            CliProgressReporter().onSafeguard(subtree, deferred = true)
        } finally {
            System.setErr(originalErr)
        }
        val nl = System.lineSeparator()
        assertEquals(
            "  WARN: UD-265 Deletion safeguard: 99 deletes planned, exceeding max_delete_absolute=50. " +
                "sync_root='/home/u/OneDrive'. Use --force-delete to override.$nl" +
                "  WARN: UD-265 Deletion safeguard: 19 of 20 entries under top-level '/Documents' (95%) would be deleted, " +
                "exceeding max_delete_per_subtree_percent=80%. sync_root='/home/u/OneDrive'. Use --force-delete to override. " +
                "Deferring deletes this cycle (daemon mode).$nl",
            err.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun the_sync_reporter_hands_a_safeguard_on_typed_to_every_delegate() {
        // The interface default would turn it into onWarning here, and the IPC reporter would lose its token.
        val seen = mutableListOf<String>()
        val recorder =
            object : ProgressReporter by ProgressReporter.Silent {
                override fun onWarning(message: String) {
                    seen += "warning"
                }

                override fun onSafeguard(
                    trip: SafeguardTripped,
                    deferred: Boolean,
                ) {
                    seen += "safeguard ${trip.kind.token} deferred=$deferred"
                }
            }
        CompositeReporter(listOf(recorder, recorder)).onSafeguard(subtree, deferred = true)
        assertEquals(List(2) { "safeguard max_delete_per_subtree_percent deferred=true" }, seen)
    }
}
