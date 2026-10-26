package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The staged scan lives in a file of its own: a listing written page by page, read back on resume and cleared at the
 * end no longer grows the main file or holds its monitor, and a page still commits with its marker.
 */
class StateDatabaseScanStagingTest {
    private lateinit var dir: Path
    private lateinit var dbFile: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("unidrive-scan-staging")
        dbFile = dir.resolve("state.db")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun opened(): StateDatabase = StateDatabase(dbFile).also { it.initialize() }

    @Test
    fun `a staged scan leaves the main file as it was`() {
        val db = opened()
        try {
            val pagesBefore = db.pragma("page_count")
            val scanId = db.beginScan(initialMarker = null)
            for (page in 0 until 20) db.persistScanPage(scanId, items(page * 500, 500), marker = "page-$page")
            assertEquals(10_000, db.loadStagedItems(scanId).size)

            db.completeScan(scanId)

            assertEquals(pagesBefore, db.pragma("page_count"), "the staging of 10,000 rows took no page of the main file")
            assertEquals("0", db.pragma("freelist_count"), "and left no free pages there")
            assertFalse(tableIn(dbFile, "scan_staging"), "the main file has no staging table")
            assertTrue(Files.exists(dir.resolve(StateDatabase.SCAN_STAGING_FILE_NAME)))
        } finally {
            db.close()
        }
    }

    @Test
    fun `the staging work does not hold the database monitor`() {
        val db = opened()
        try {
            val seen = mutableListOf<Any?>()
            val scanId = db.beginScan(initialMarker = null)
            db.persistScanPage(scanId, items(0, 3), marker = "m")
            // This thread holds the monitor (as a merge chunk does) while another one persists, loads and clears.
            synchronized(db) {
                val worker =
                    Thread {
                        db.persistScanPage(scanId, items(3, 3), marker = "m2")
                        seen += db.loadStagedItems(scanId).size
                        seen += db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_MARKER)
                        db.completeScan(scanId)
                        seen += db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_ID)
                    }
                worker.start()
                worker.join(TimeUnit.SECONDS.toMillis(10))
                assertFalse(worker.isAlive, "the staging calls finished while another thread held the monitor")
            }
            assertEquals(listOf<Any?>(6, "m2", null), seen)
        } finally {
            db.close()
        }
    }

    @Test
    fun `an older file with a staged scan in the main file opens without it, and the scan starts again`() {
        DriverManager.getConnection("jdbc:sqlite:$dbFile").use { c ->
            c.createStatement().use { st ->
                st.executeUpdate("CREATE TABLE sync_state (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                st.executeUpdate(
                    "INSERT INTO sync_state VALUES ('${StateDatabase.SCHEMA_VERSION_KEY}', '${StateDatabase.SCHEMA_VERSION}')",
                )
                st.executeUpdate("INSERT INTO sync_state VALUES ('delta_cursor', 'cursor-1')")
                st.executeUpdate("INSERT INTO sync_state VALUES ('${StateDatabase.SCAN_IN_PROGRESS_ID}', 'old-scan')")
                st.executeUpdate("INSERT INTO sync_state VALUES ('${StateDatabase.SCAN_IN_PROGRESS_MARKER}', 'old-marker')")
                st.executeUpdate(
                    "INSERT INTO sync_state VALUES ('${StateDatabase.SCAN_IN_PROGRESS_STARTED_AT}', '${Instant.now()}')",
                )
                st.executeUpdate(
                    "CREATE TABLE scan_staging (scan_id TEXT NOT NULL, remote_id TEXT NOT NULL, parent_uuid TEXT, " +
                        "plain_name TEXT NOT NULL, is_folder INTEGER NOT NULL DEFAULT 0, size INTEGER NOT NULL DEFAULT 0, " +
                        "modified TEXT, remote_hash TEXT, reconciled INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (scan_id, remote_id))",
                )
                st.executeUpdate("CREATE INDEX idx_scan_staging_scan_id ON scan_staging(scan_id)")
                st.executeUpdate("INSERT INTO scan_staging (scan_id, remote_id, plain_name) VALUES ('old-scan', 'u1', 'a.txt')")
            }
        }

        val db = opened()
        try {
            assertFalse(tableIn(dbFile, "scan_staging"), "the old table is dropped")
            assertNull(db.getActiveScan(Duration.ofHours(6)), "the scan in flight starts again")
            assertEquals(0, db.loadStagedItems("old-scan").size)
            for (key in StateDatabase.SCAN_CHECKPOINT_KEYS) assertNull(db.getSyncState(key), "$key is gone")
            assertNull(mainSyncState(dbFile, StateDatabase.SCAN_IN_PROGRESS_ID), "and gone from the main file")
            assertEquals("cursor-1", db.getSyncState("delta_cursor"), "the rest of sync_state stays")
        } finally {
            db.close()
        }
        // Idempotent: the next open has nothing to drop.
        opened().close()
    }

    @Test
    fun `a checkpoint older than the last full scan is no active scan`() {
        // The cursor and its stamp commit in the main file, the checkpoint in the staging file: a crash between the
        // stamp and the clear of the checkpoint leaves this pair, and resuming it would page past the new cursor.
        val db = opened()
        try {
            val scanId = db.beginScan(initialMarker = "m0")
            db.persistScanPage(scanId, items(0, 3), marker = "m1")
            db.setSyncState("last_full_scan", Instant.now().plusSeconds(1).toString())

            assertNull(db.getActiveScan(Duration.ofHours(6)))
            assertNull(db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_ID))
            assertEquals(0, db.loadStagedItems(scanId).size)
        } finally {
            db.close()
        }
    }

    @Test
    fun `a checkpoint past the threshold of its start stays active while its pages keep landing`() {
        // A full walk of a large account can outlive the threshold while paging steadily; measured from the
        // start it was discarded on every restart past the threshold and paged from item 0 again.
        val db = opened()
        try {
            val scanId = db.beginScan(initialMarker = null)
            db.setSyncState(StateDatabase.SCAN_IN_PROGRESS_STARTED_AT, Instant.now().minus(Duration.ofDays(1)).toString())
            db.persistScanPage(scanId, items(0, 3), marker = "m1")
            assertNotNull(db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_LAST_PROGRESS_AT), "the page stamped the checkpoint's progress")

            val active = assertNotNull(db.getActiveScan(Duration.ofHours(6)), "progress inside the threshold keeps the scan resumable")
            assertEquals("m1", active.marker)
        } finally {
            db.close()
        }
    }

    @Test
    fun `a checkpoint whose last page is past the threshold is no active scan`() {
        val db = opened()
        try {
            val scanId = db.beginScan(initialMarker = null)
            db.persistScanPage(scanId, items(0, 3), marker = "m1")
            val past = Instant.now().minus(Duration.ofDays(1)).toString()
            db.setSyncState(StateDatabase.SCAN_IN_PROGRESS_STARTED_AT, past)
            db.setSyncState(StateDatabase.SCAN_IN_PROGRESS_LAST_PROGRESS_AT, past)

            assertNull(db.getActiveScan(Duration.ofHours(6)))
            assertNull(db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_ID))
            assertEquals(0, db.loadStagedItems(scanId).size, "a stale scan's rows are discarded")
        } finally {
            db.close()
        }
    }

    @Test
    fun `a scan killed between pages resumes with exactly the pages it committed`() {
        val java = ProcessHandle.current().info().command().orElse("java")
        val child =
            ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), StagingCrashChild::class.java.name, dbFile.toString(), "3")
                .redirectErrorStream(true)
                .start()
        val output = child.inputStream.bufferedReader().readText()
        assertTrue(child.waitFor(60, TimeUnit.SECONDS), "the child ended")
        assertEquals(StagingCrashChild.HALT_STATUS, child.exitValue(), "the child halted after its pages: $output")

        val db = opened()
        try {
            val active = assertNotNull(db.getActiveScan(Duration.ofHours(6)), "the checkpoint survives the halt")
            assertEquals("page-2", active.marker, "the marker of the last committed page")
            assertEquals(30, db.loadStagedItems(active.scanId).size, "the rows of the three committed pages")
        } finally {
            db.close()
        }
    }

    @Test
    fun `a read-only handle finds no scan where there is no staging file, and creates none`() {
        opened().close()
        val staging = dir.resolve(StateDatabase.SCAN_STAGING_FILE_NAME)
        Files.deleteIfExists(staging)

        val ro = StateDatabase(dbFile, readOnly = true).also { it.initialize() }
        try {
            assertNull(ro.getActiveScan(Duration.ofHours(6)))
            assertNull(ro.getSyncState(StateDatabase.SCAN_IN_PROGRESS_ID))
            assertTrue(ro.loadStagedItems("any").isEmpty())
        } finally {
            ro.close()
        }
        assertFalse(Files.exists(staging))
    }

    @Test
    fun `a snapshot carries the staged scan, and its close removes the copy`() {
        val db = opened()
        val scanId = db.beginScan(initialMarker = null)
        db.persistScanPage(scanId, items(0, 4), marker = "m1")
        db.close()

        val snap = StateDatabase.snapshotOf(dbFile, tempRoot = dir.resolve("tmp"))
        val copyDir = snap.file.parent
        try {
            assertEquals(scanId, snap.getActiveScan(Duration.ofHours(6))?.scanId)
            assertEquals(4, snap.loadStagedItems(scanId).size)
            snap.completeScan(scanId)
        } finally {
            snap.close()
        }
        assertFalse(Files.exists(copyDir), "the snapshot folder is gone, staging copy included")
        val again = opened()
        try {
            assertEquals(4, again.loadStagedItems(scanId).size, "the preview cleared its copy, not the real staging")
        } finally {
            again.close()
        }
    }

    @Test
    fun `an in-memory database stages in memory`() {
        val mem = StateDatabase(dbFile, inMemory = true).also { it.initialize() }
        try {
            val scanId = mem.beginScan(initialMarker = null)
            mem.persistScanPage(scanId, items(0, 2), marker = "m")
            assertEquals(2, mem.loadStagedItems(scanId).size)
        } finally {
            mem.close()
        }
        assertFalse(Files.exists(dir.resolve(StateDatabase.SCAN_STAGING_FILE_NAME)))
    }

    private fun tableIn(
        file: Path,
        name: String,
    ): Boolean =
        DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?").use { st ->
                st.setString(1, name)
                st.executeQuery().use { it.next() }
            }
        }

    private fun mainSyncState(
        file: Path,
        key: String,
    ): String? =
        DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            c.prepareStatement("SELECT value FROM sync_state WHERE key=?").use { st ->
                st.setString(1, key)
                st.executeQuery().use { rs -> rs.takeIf { it.next() }?.getString(1) }
            }
        }

    companion object {
        fun items(
            from: Int,
            count: Int,
        ): List<CloudItem> =
            (from until from + count).map { i ->
                CloudItem(
                    id = "uuid-%06d".format(i),
                    name = "f$i",
                    path = "/f$i",
                    size = 1,
                    isFolder = false,
                    modified = Instant.parse("2026-03-28T12:00:00Z"),
                    created = null,
                    hash = null,
                    mimeType = null,
                    parentId = "parent-uuid",
                )
            }
    }
}

/** Stages pages of ten rows and halts the JVM after the last one: no close, no shutdown hook. */
object StagingCrashChild {
    const val HALT_STATUS = 77

    @JvmStatic
    fun main(args: Array<String>) {
        val db = StateDatabase(Path.of(args[0]))
        db.initialize()
        val scanId = db.beginScan(initialMarker = null)
        repeat(args[1].toInt()) { page -> db.persistScanPage(scanId, StateDatabaseScanStagingTest.items(page * 10, 10), "page-$page") }
        Runtime.getRuntime().halt(HALT_STATUS)
    }
}
