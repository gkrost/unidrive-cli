package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #841 (#749 finding 8): the report reads give what the whole-table read ([StateDatabase.getAllEntries]) gave the reports,
 * and hold no more rows than the report shows.
 */
class StateDatabaseReportsTest {
    private lateinit var dir: Path
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        dir = TestScratch.directory("unidrive-reports")
        db = StateDatabase(dir.resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    private val t0 = Instant.parse("2026-03-28T12:00:00Z")

    private fun row(
        path: String,
        isFolder: Boolean = false,
        hydrated: Boolean = false,
        size: Long = 1,
        synced: Instant = t0,
    ) = SyncEntry(
        path = path,
        remotePath = null,
        remoteId = "id$path",
        remoteHash = null,
        remoteSize = size,
        remoteModified = t0,
        localMtime = null,
        localSize = null,
        isFolder = isFolder,
        isPinned = false,
        isHydrated = hydrated,
        lastSynced = synced,
    )

    @Test
    fun `file totals are the counts the whole-table read gave`() {
        db.upsertEntry(row("/dir", isFolder = true, size = 99))
        for (i in 0 until 7) db.upsertEntry(row("/f$i", hydrated = i % 3 == 0, size = i + 10L))

        val all = db.getAllEntries().filter { !it.isFolder }
        assertEquals(FileTotals(all.size, all.sumOf { it.remoteSize }, all.count { !it.isHydrated }), db.fileTotals())
        db.deleteEntry("/f1")
        assertEquals(6, db.fileTotals().files, "a deleted row counts no more")
    }

    @Test
    fun `the hydrated sample is the first hydrated files and at most the sample`() {
        db.upsertEntry(row("/dir", isFolder = true, hydrated = true))
        for (i in 0 until 50) db.upsertEntry(row("/f$i", hydrated = i % 2 == 0))
        val expected = db.getAllEntries().filter { !it.isFolder && it.isHydrated }

        assertEquals(expected.take(10), db.hydratedFiles(10), "the sample holds ten rows, not every row")
        assertEquals(expected, db.hydratedFiles(null), "no limit: every hydrated file")
    }

    @Test
    fun `the most recently synced rows are the newest first, at most the limit, ties in table order`() {
        // Nanosecond and whole-second stamps: their stored text does not sort as the instants do.
        val stamps = (0 until 300).map { t0.plusSeconds((it * 7919L) % 97).plusNanos(if (it % 5 == 0) 500_000_000 else 0) }
        stamps.forEachIndexed { i, at -> db.upsertEntry(row("/f$i", synced = at)) }

        val expected = db.getAllEntries().sortedByDescending { it.lastSynced }.take(20)
        assertEquals(expected.map { it.path }, db.mostRecentlySynced(20).map { it.path })
        assertEquals(3, db.mostRecentlySynced(3).size)
    }

    // One row more than a scan step, and "/f0" (the first row of the first step) re-synced between the steps: the replace moves it
    // to the end of the table, so the second step visits it again.
    private fun resyncFirstRowDuringTheScan(): Instant {
        // "/f0" is already the newest row, so its first copy and its replacement both rank at the top.
        for (i in 0..StateDatabase.ALIVE_SCAN_CHUNK_ROWS) db.upsertEntry(row("/f$i", synced = t0.plusSeconds(if (i == 0) 999_999L else i.toLong())))
        val resynced = t0.plusSeconds(1_000_000)
        var once = true
        db.aliveScanChunkGap = {
            if (once) {
                once = false
                db.upsertEntry(row("/f0", synced = resynced))
            }
        }
        return resynced
    }

    @Test
    fun `the most recently synced list names a row replaced during the scan once`() {
        val resynced = resyncFirstRowDuringTheScan()
        val top = db.mostRecentlySynced(3)
        assertEquals(top.map { it.path }.distinct(), top.map { it.path }, "no path twice: ${top.map { it.path }}")
        assertEquals("/f0", top.first().path)
        assertEquals(resynced, top.first().lastSynced, "the newest copy is kept")
    }

    @Test
    fun `a once-per-path scan visits a row replaced during the scan once`() {
        resyncFirstRowDuringTheScan()
        val visited = mutableListOf<String>()
        db.scanAliveRowsOncePerPath { visited += it.path }
        assertEquals(StateDatabase.ALIVE_SCAN_CHUNK_ROWS + 1, visited.size)
        assertEquals(visited.size, visited.toSet().size)
    }
}
