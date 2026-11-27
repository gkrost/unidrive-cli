package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * gkrost/unidrive-cli#267: the English text of the three deletion safeguards, byte for byte. The CLI prints the thrown
 * message as `Sync error: <message>` and a dry-run or daemon-mode trip as a warning; moving the safeguards onto a typed
 * outcome must not change one byte of either. If this test is loosened, the operator-visible text can drift unnoticed.
 */
class DeleteSafeguardTextTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var dbPath: Path
    private val provider = FakeCloudProvider()

    @BeforeTest
    fun setUp() {
        syncRoot = TestScratch.directory("unidrive-safeguard-text")
        dbPath = TestScratch.directory("unidrive-safeguard-text-db").resolve("state.db")
        db = StateDatabase(dbPath)
        db.initialize()
    }

    @AfterTest
    fun tearDown() = db.close()

    private class Warnings : ProgressReporter by ProgressReporter.Silent {
        val seen = mutableListOf<String>()

        override fun onWarning(message: String) {
            seen += message
        }
    }

    private fun engine(
        reporter: ProgressReporter = ProgressReporter.Silent,
        db: StateDatabase = this.db,
        maxDeletePercentage: Int = 0,
        maxDeleteAbsolute: Int = 0,
        maxDeletePerSubtreePercent: Int = 0,
    ) = SyncEngine(
        provider = provider,
        db = db,
        syncRoot = syncRoot,
        conflictPolicy = ConflictPolicy.KEEP_BOTH,
        reporter = reporter,
        maxDeletePercentage = maxDeletePercentage,
        maxDeleteAbsolute = maxDeleteAbsolute,
        maxDeletePerSubtreePercent = maxDeletePerSubtreePercent,
    )

    private fun seed(
        paths: List<String>,
        sizeOf: (Int) -> Long = { 100L },
    ) {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        paths.forEachIndexed { i, path ->
            db.upsertEntry(
                SyncEntry(
                    path = path, remoteId = "id-$i", remoteHash = "h-$i", remoteSize = sizeOf(i), remoteModified = now,
                    localMtime = now.toEpochMilli(), localSize = sizeOf(i), isFolder = false, isPinned = false,
                    isHydrated = true, lastSynced = now,
                ),
            )
        }
        // A cursor keeps the pass incremental, so the vanished local files plan DeleteRemote actions.
        db.setSyncState("delta_cursor", "seeded-cursor")
    }

    /** 100 tracked files, one left on disk: 99 deletes planned. */
    private fun ninetyNineOfOneHundred() {
        seed((0 until 100).map { "/seeded-$it.txt" })
        Files.writeString(syncRoot.resolve("seeded-0.txt"), "x")
    }

    /** 20 tracked under /Documents (one left on disk) and 9 under /Other (all on disk): 19 deletes, all in /Documents. */
    private fun nineteenOfTwentyUnderDocuments() {
        seed((0 until 20).map { "/Documents/file-$it.txt" } + (0 until 9).map { "/Other/file-$it.txt" }) { i ->
            if (i < 20) 1000L + i else 5000L + (i - 20)
        }
        Files.createDirectories(syncRoot.resolve("Documents"))
        Files.writeString(syncRoot.resolve("Documents/file-0.txt"), "x".repeat(1000))
        Files.createDirectories(syncRoot.resolve("Other"))
        for (i in 0 until 9) Files.writeString(syncRoot.resolve("Other/file-$i.txt"), "x".repeat(5000 + i))
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
