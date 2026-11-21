package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.EntryStatus
import org.krost.unidrive.sync.model.RenameIntent
import org.krost.unidrive.sync.model.ReplaceIntent
import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#863: a tombstone (`DELETED` row) is kept for a retention, then purged with the compaction; the rows a
 * pending operation still names are never purged, and a reset gives the space of the rows it drops back. Real database
 * files in a scratch folder (never a profile's own state.db), no fakes.
 */
class StateDatabaseTombstoneRetentionTest {
    private lateinit var dir: Path
    private lateinit var dbFile: Path
    private lateinit var db: StateDatabase

    private val now: Instant = Instant.parse("2026-11-21T12:00:00Z")
    private val old: Instant = now.minus(Duration.ofDays(45))
    private val recent: Instant = now.minus(Duration.ofDays(3))

    @BeforeTest
    fun setUp() {
        dir = TestScratch.directory("unidrive-tombstone-retention")
        dbFile = dir.resolve("state.db")
        db = StateDatabase(dbFile).also { it.initialize() }
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    private fun row(
        path: String,
        remoteId: String? = "id-$path",
        status: EntryStatus = EntryStatus.EXISTS,
        lastSynced: Instant = old,
        hydrated: Boolean = false,
    ) = SyncEntry(
        path = path,
        remoteId = remoteId,
        remoteHash = "h",
        remoteSize = 1,
        remoteModified = old,
        localMtime = 1,
        localSize = 1,
        isFolder = false,
        isPinned = false,
        isHydrated = hydrated,
        lastSynced = lastSynced,
        status = status,
    )

    private fun tombstone(
        path: String,
        lastSynced: Instant = old,
        remoteId: String? = "id-$path",
    ) = db.upsertEntry(row(path, remoteId, EntryStatus.DELETED, lastSynced))

    private fun paths(): List<String> = db.recovery.allEntriesAnyStatus().map { it.path }.sorted()

    private fun sql(statement: String) {
        DriverManager.getConnection("jdbc:sqlite:$dbFile").use { c -> c.createStatement().use { it.executeUpdate(statement) } }
    }

    @Test
    fun `tombstones older than the cutoff go, newer ones and alive rows stay`() {
        db.upsertEntry(row("/alive.txt"))
        tombstone("/old-a.txt")
        tombstone("/old-b.txt")
        tombstone("/new-a.txt", lastSynced = recent)

        val removed = db.purgeExpiredTombstones(30, now)

        assertEquals(2, removed)
        assertEquals(listOf("/alive.txt", "/new-a.txt"), paths())
        assertEquals(1, db.tombstoneCount())
    }

    @Test
    fun `a retention of 0 keeps every tombstone`() {
        tombstone("/old.txt")

        assertEquals(0, db.purgeExpiredTombstones(0, now))
        assertEquals(listOf("/old.txt"), paths())
    }

    @Test
    fun `trashed rows are not tombstones of this purge`() {
        db.upsertEntry(row("/trashed.txt", status = EntryStatus.TRASHED))

        assertEquals(0, db.purgeExpiredTombstones(30, now))
        assertEquals(listOf("/trashed.txt"), paths())
    }

    @Test
    fun `a tombstone a rename intent names is kept, by its cloud id or by either path`() {
        tombstone("/by-id.txt", remoteId = "rid-1")
        tombstone("/old-end.txt")
        tombstone("/new-end.txt")
        tombstone("/free.txt")
        db.recordRenameIntent(RenameIntent("/x/gone-from.txt", "/x/gone-to.txt", "rid-1", ghost = false))
        db.recordRenameIntent(RenameIntent("/old-end.txt", "/elsewhere.txt", null, ghost = true))
        db.recordRenameIntent(RenameIntent("/elsewhere-2.txt", "/new-end.txt", null, ghost = false))

        assertEquals(1, db.purgeExpiredTombstones(30, now))

        assertEquals(listOf("/by-id.txt", "/new-end.txt", "/old-end.txt"), paths())
    }

    @Test
    fun `a tombstone a replace intent names is kept, by either cloud id or any of its paths`() {
        tombstone("/src.txt", remoteId = "src-id")
        tombstone("/dest.txt", remoteId = "dest-id")
        tombstone("/parked.txt")
        tombstone("/free.txt")
        db.recordReplaceIntent(ReplaceIntent("/other-src.txt", "/other-dest.txt", "/parked.txt", "src-id", "dest-id"))

        assertEquals(1, db.purgeExpiredTombstones(30, now))

        assertEquals(listOf("/dest.txt", "/parked.txt", "/src.txt"), paths())
    }

    @Test
    fun `a tombstone at the path of an unfinished upload handle is kept`() {
        tombstone("/handled.txt")
        tombstone("/free.txt")
        db.recordUploadHandle("/handled.txt", "handle-1")

        assertEquals(1, db.purgeExpiredTombstones(30, now))

        assertEquals(listOf("/handled.txt"), paths())
    }

    @Test
    fun `a tombstone that still carries a pending upload, an unsynced edit or a pending local change is kept`() {
        tombstone("/pending-upload.txt", remoteId = "local:abc")
        sql("UPDATE sync_entries SET is_hydrated = 1 WHERE path = '/pending-upload.txt'")
        tombstone("/edited.txt")
        sql("UPDATE sync_entries SET edit_base = 'v1' WHERE path = '/edited.txt'")
        tombstone("/pending-local.txt")
        sql("UPDATE sync_entries SET pending_local = 'delete' WHERE path = '/pending-local.txt'")
        tombstone("/free.txt")

        assertEquals(1, db.purgeExpiredTombstones(30, now))

        assertEquals(listOf("/edited.txt", "/pending-local.txt", "/pending-upload.txt"), paths())
    }

    @Test
    fun `a delete made through the mount inside the lagging-listing window is kept whatever its age`() {
        tombstone("/mount-deleted.txt")
        sql("UPDATE sync_entries SET mount_deleted_at = '${now.minusSeconds(600)}' WHERE path = '/mount-deleted.txt'")
        tombstone("/free.txt")

        assertEquals(1, db.purgeExpiredTombstones(30, now))

        assertEquals(listOf("/mount-deleted.txt"), paths())
    }

    @Test
    fun `a purge is chunked and counts every row it removed`() {
        db.batch { for (i in 0 until 12_345) tombstone("/bulk/f$i.txt") }
        db.upsertEntry(row("/keep.txt"))

        assertEquals(12_345, db.purgeExpiredTombstones(30, now))

        assertEquals(listOf("/keep.txt"), paths())
    }

    @Test
    fun `a purge touches no local file and no other table`() {
        val local = dir.resolve("sync-root-file.txt")
        Files.writeString(local, "mine")
        db.upsertEntry(row("/alive.txt"))
        db.addPinRule("*.txt", true)
        db.setSyncState("delta_cursor", "c-1")
        tombstone("/old.txt")

        db.purgeExpiredTombstones(30, now)

        assertEquals("mine", Files.readString(local))
        assertEquals(listOf("*.txt" to true), db.getPinRules())
        assertEquals("c-1", db.getSyncState("delta_cursor"))
        assertTrue(Files.exists(local))
    }

    @Test
    fun `the file shrinks after a purge, although it was compacted once before`() {
        db.batch { for (i in 0 until 6_000) tombstone("/bulk/f$i.txt") }
        db.upsertEntry(row("/keep.txt"))
        db.setSyncState(StateDatabase.COMPACTED_KEY, "2026-11-01T00:00:00Z")
        db.pragma("wal_checkpoint(TRUNCATE)")
        val before = Files.size(dbFile)
        assertFalse(db.compactOnce(), "precondition: a file that was compacted once is not rewritten again")

        assertEquals(6_000, db.purgeExpiredTombstones(30, now))
        assertTrue(db.compactOnce(), "the purge freed the pages, so the file may be rewritten once more")

        assertTrue(Files.size(dbFile) < before / 4, "${Files.size(dbFile)} < ${before / 4}")
        assertEquals(listOf("/keep.txt"), paths())
        assertNotNull(db.getSyncState(StateDatabase.COMPACTED_KEY))
    }

    @Test
    fun `a purge that removes nothing leaves the compaction record alone`() {
        tombstone("/new.txt", lastSynced = recent)
        db.setSyncState(StateDatabase.COMPACTED_KEY, "2026-11-01T00:00:00Z")

        assertEquals(0, db.purgeExpiredTombstones(30, now))

        assertEquals("2026-11-01T00:00:00Z", db.getSyncState(StateDatabase.COMPACTED_KEY))
    }

    @Test
    fun `a read-only handle purges nothing`() {
        tombstone("/old.txt")
        db.close()
        val readOnly = StateDatabase(dbFile, readOnly = true).also { it.initialize() }
        try {
            assertEquals(0, readOnly.purgeExpiredTombstones(30, now))
        } finally {
            readOnly.close()
        }
        db = StateDatabase(dbFile).also { it.initialize() }
        assertEquals(listOf("/old.txt"), paths())
    }

    @Test
    fun `the start-up maintenance purges, then compacts, and reports what it did`() {
        db.batch { for (i in 0 until 6_000) tombstone("/bulk/f$i.txt") }
        db.upsertEntry(row("/keep.txt"))
        db.pragma("wal_checkpoint(TRUNCATE)")
        val before = Files.size(dbFile)

        val outcome = db.maintainAtStart(30)

        assertEquals(6_000, outcome.purgedTombstones)
        assertTrue(outcome.compacted)
        assertTrue(Files.size(dbFile) < before / 4)
        assertNull(db.recovery.getEntryByRemoteIdAnyStatus("id-/bulk/f1.txt"))
    }

    @Test
    fun `a completed scan gives its staging file back`() {
        val scanId = db.beginScan(null)
        val items =
            (0 until 20_000).map { i ->
                CloudItem(
                    id = "c-$i", name = "n$i.txt", path = "/n$i.txt", size = 1, isFolder = false, modified = old, created = old,
                    hash = "h".repeat(40), mimeType = null,
                )
            }
        db.persistScanPage(scanId, items, "m-1")
        val staging = dir.resolve("scan-staging.db")
        val full = stagingBytes()
        assertTrue(full > 100_000, "precondition: the staged rows fill the file ($full)")

        db.completeScan(scanId)

        assertTrue(stagingBytes() < full / 4, "${stagingBytes()} < ${full / 4} (file $staging)")
    }

    private fun stagingBytes(): Long =
        StateDatabase.stagingFileNames().sumOf { name -> dir.resolve(name).let { if (Files.exists(it)) Files.size(it) else 0L } }
}
