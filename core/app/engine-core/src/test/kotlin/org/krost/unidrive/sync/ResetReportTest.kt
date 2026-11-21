package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.EntryStatus
import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#863 item 2: a reset (`refresh --reset`, `sync --reset`) of an emptied or changed account drops the
 * tombstones along with the rest, gives the space of the rows and of the staged scan back, and says what it removed.
 * Real database files in a scratch folder.
 */
class ResetReportTest {
    private lateinit var dir: Path
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        dir = TestScratch.directory("unidrive-reset-report")
        db = StateDatabase(dir.resolve("state.db")).also { it.initialize() }
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    private fun row(
        path: String,
        status: EntryStatus,
        remoteId: String? = "id-$path",
        hydrated: Boolean = false,
    ) = SyncEntry(
        path = path, remoteId = remoteId, remoteHash = "h", remoteSize = 1, remoteModified = null, localMtime = 1, localSize = 1,
        isFolder = false, isPinned = false, isHydrated = hydrated, lastSynced = Instant.parse("2026-10-01T00:00:00Z"), status = status,
    )

    private fun bytes(vararg names: String): Long =
        names.sumOf { name -> dir.resolve(name).let { if (Files.exists(it)) Files.size(it) else 0L } }

    private fun stateBytes() = bytes("state.db", "state.db-wal")

    private fun fillTheProfile() {
        db.batch {
            for (i in 0 until 6_000) db.upsertEntry(row("/dead/f$i.txt", EntryStatus.DELETED))
            for (i in 0 until 100) db.upsertEntry(row("/live/f$i.txt", EntryStatus.EXISTS))
        }
        db.upsertEntry(row("/pending.txt", EntryStatus.EXISTS, remoteId = "local:p1", hydrated = true))
        val scanId = db.beginScan(null)
        db.persistScanPage(
            scanId,
            (0 until 4_000).map { i ->
                CloudItem(
                    id = "c-$i", name = "n$i.txt", path = "/n$i.txt", size = 1, isFolder = false, modified = null, created = null,
                    hash = "h".repeat(40), mimeType = null,
                )
            },
            "m-1",
        )
        db.pragma("wal_checkpoint(TRUNCATE)")
    }

    @Test
    fun `a reset that keeps pending uploads drops the tombstones, shrinks both files and reports the numbers`() {
        fillTheProfile()
        val stateBefore = stateBytes()
        val stagingBefore = bytes("scan-staging.db", "scan-staging.db-wal")

        val report = db.resetKeepingPendingReporting()

        assertEquals(1, report.keptRows, "the row that awaits upload stays")
        assertEquals(6_000, report.removedTombstones)
        assertEquals(100, report.removedRows - report.removedTombstones, "the alive rows that were not kept")
        assertEquals(0, db.tombstoneCount())
        assertEquals(listOf("/pending.txt"), db.recovery.allEntriesAnyStatus().map { it.path })
        assertTrue(report.stateBytesBefore >= stateBefore / 2 && report.stateBytesAfter < report.stateBytesBefore / 4, report.toString())
        assertTrue(stateBytes() < stateBefore / 4, "state.db ${stateBytes()} < ${stateBefore / 4}")
        assertTrue(report.stagingBytesAfter < stagingBefore / 4, "scan-staging.db ${report.stagingBytesAfter} < ${stagingBefore / 4}")
        assertTrue(report.summary().contains("6000 tombstone"), report.summary())
    }

    @Test
    fun `a full reset drops every row, shrinks the files and reports the numbers`() {
        fillTheProfile()
        val stateBefore = stateBytes()

        val report = db.resetAllReporting()

        assertEquals(0, report.keptRows)
        assertEquals(6_000, report.removedTombstones)
        assertEquals(6_101, report.removedRows)
        assertTrue(stateBytes() < stateBefore / 4, "state.db ${stateBytes()} < ${stateBefore / 4}")
        assertEquals(0, db.getEntryCount())
    }
}
