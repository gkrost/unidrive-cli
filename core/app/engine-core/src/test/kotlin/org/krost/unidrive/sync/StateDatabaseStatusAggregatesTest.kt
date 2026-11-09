package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.EntryStatus
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

// gkrost/unidrive-cli#455: `status` asks for four numbers; one aggregate query must give what counting every row gave.
class StateDatabaseStatusAggregatesTest {
    private fun row(
        path: String,
        remoteId: String?,
        remoteSize: Long = 0,
        localSize: Long? = null,
        hydrated: Boolean = false,
        folder: Boolean = false,
        status: EntryStatus = EntryStatus.EXISTS,
    ) = SyncEntry(
        path = path,
        remoteId = remoteId,
        remoteHash = null,
        remoteSize = remoteSize,
        remoteModified = null,
        localMtime = localSize?.let { 1L },
        localSize = localSize,
        isFolder = folder,
        isPinned = false,
        isHydrated = hydrated,
        lastSynced = Instant.EPOCH,
        status = status,
    )

    @Test
    fun `the aggregates equal the per-row computation, folders and tombstones excluded`() {
        val db = StateDatabase(Files.createTempDirectory("status-agg").resolve("state.db"))
        try {
            db.initialize()
            db.upsertEntry(row("/hydrated-a", "r1", remoteSize = 10, localSize = 1_000, hydrated = true))
            db.upsertEntry(row("/hydrated-b", "r2", remoteSize = 20, localSize = null, hydrated = true))
            db.upsertEntry(row("/pending", null, remoteSize = 0, localSize = 5_000, hydrated = true))
            db.upsertEntry(row("/sparse-a", "r3", remoteSize = 300))
            db.upsertEntry(row("/sparse-b", "r4", remoteSize = 400))
            db.upsertEntry(row("/folder", "rf", remoteSize = 999, localSize = 999, hydrated = true, folder = true))
            db.upsertEntry(row("/gone", "r5", remoteSize = 7_777, localSize = 7_777, hydrated = true, status = EntryStatus.DELETED))

            val expected = db.getAllEntries()
            val got = db.statusAggregates()

            assertEquals(expected.count { !it.isHydrated && !it.isFolder }, got.sparseFiles)
            assertEquals(expected.filter { !it.isFolder }.sumOf { it.remoteSize }, got.remoteBytes)
            assertEquals(expected.filter { !it.isFolder && it.isHydrated && it.isPendingUpload }.sumOf { it.localSize ?: 0L }, got.pendingBytes)
            assertEquals(expected.filter { !it.isFolder && it.isHydrated && !it.isPendingUpload }.sumOf { it.localSize ?: 0L }, got.hydratedBytes)
            // The literal numbers, so a shared mistake in both computations cannot hide.
            assertEquals(StatusAggregates(sparseFiles = 2, remoteBytes = 730, pendingBytes = 5_000, hydratedBytes = 1_000), got)
        } finally {
            db.close()
        }
    }

    @Test
    fun `an empty table gives zeros`() {
        val db = StateDatabase(Files.createTempDirectory("status-agg").resolve("state.db"))
        try {
            db.initialize()
            assertEquals(StatusAggregates(0, 0, 0, 0), db.statusAggregates())
        } finally {
            db.close()
        }
    }
}
