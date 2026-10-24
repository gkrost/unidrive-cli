package org.krost.unidrive.engine

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.EntryStatus
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The absence sweep of a full listing (`RemoteGather.detectMissingAfterFullSync`) reads the table in steps: it needs the
 * rows the listing did not see, not every row as an object under one hold of the database monitor (#1027).
 */
class RemoteGatherSweepTest {
    private lateinit var tmp: Path
    private lateinit var db: StateDatabase

    private val old = Instant.parse("2026-03-01T00:00:00Z")

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("unidrive-sweep")
        db = StateDatabase(tmp.resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tmp.toFile().deleteRecursively()
    }

    private fun gather() =
        RemoteGather(
            provider = FakeViewProvider(kinds = emptySet()),
            db = db,
            guard =
                RemoteOperationGuard(
                    standingScope = emptyList(),
                    syncPaths = emptyList(),
                    excludePatterns = emptyList(),
                    maxConcurrentTransfers = 2,
                    matchesGlob = { _, _ -> false },
                ),
            options = RemoteGather.Options(providerId = "test"),
            listener = object : RemoteGather.Listener {},
            log = LoggerFactory.getLogger(RemoteGatherSweepTest::class.java),
        )

    private fun row(
        path: String,
        id: String?,
        remotePath: String? = null,
        status: EntryStatus = EntryStatus.EXISTS,
        lastSynced: Instant = old,
        folder: Boolean = false,
    ) = SyncEntry(
        path = path,
        remotePath = remotePath,
        remoteId = id,
        remoteHash = null,
        remoteSize = 1,
        remoteModified = null,
        localMtime = null,
        localSize = null,
        isFolder = folder,
        isPinned = false,
        isHydrated = id == null,
        lastSynced = lastSynced,
        status = status,
    )

    private fun live(
        id: String,
        path: String,
    ) = CloudItem(id = id, name = path.substringAfterLast('/'), path = path, size = 1, isFolder = false, modified = null, created = null, hash = null, mimeType = null)

    @Test
    fun `the sweep tombstones exactly the alive rows the listing did not see`() {
        val gatherStartedAt = Instant.parse("2026-06-01T00:00:00Z")
        db.upsertEntry(row("/seen.txt", "seen"))
        db.upsertEntry(row("/gone.txt", "gone"))
        db.upsertEntry(row("/pending.txt", null)) // never uploaded: no remote id, no verdict
        db.upsertEntry(row("/trashed.txt", "trashed", status = EntryStatus.TRASHED))
        db.upsertEntry(row("/L/aliased.txt", "aliased", remotePath = "/R/aliased.txt"))
        db.upsertEntry(row("/fresh.txt", "fresh", lastSynced = gatherStartedAt.plusSeconds(1))) // written after the listing began
        // Unreachable: the folders above a held row are held with it, however the listing reports them.
        db.upsertEntry(row("/D", "folder-d", folder = true))
        db.upsertEntry(row("/D/S", "folder-s", folder = true))
        db.upsertEntry(row("/D/S/e.txt", "held"))
        db.upsertEntry(row("/D/other.txt", "other"))
        val changes = linkedMapOf<String, CloudItem>("/seen.txt" to live("seen", "/seen.txt"))

        gather().detectMissingAfterFullSync(
            changes,
            gatherStartedAt = gatherStartedAt,
            unreachableIds = setOf("held"),
        )

        val tombstones = changes.filterValues { it.deleted }
        assertEquals(
            setOf("/gone.txt", "/R/aliased.txt", "/D/other.txt"),
            tombstones.keys,
            "the aliased row is keyed at its remote path; /D and /D/S are held by the unreachable row below them",
        )
        assertEquals("aliased", tombstones.getValue("/R/aliased.txt").id)
    }

    // A provider path with an empty, `.` or `..` segment or a control character is refused at the gather. Refused is not
    // deleted: the row of an item renamed to such a name keeps its file, and a tombstone at such a path reaps only the row
    // of its own id.
    @Test
    fun `an item whose path names another file is refused and the sweep holds its row`() {
        db.upsertEntry(row("/a/x.txt", "renamed"))
        db.upsertEntry(row("/victim.txt", "victim"))
        val gather = gather()

        for ((id, path) in listOf("renamed" to "/a/../victim.txt", "dot" to "/a/./b", "empty" to "/a//b", "newline" to "/a/b\nc")) {
            assertNull(gather.resolveItemPath(live(id, path)), "expected '$path' refused")
        }
        val changes = linkedMapOf<String, CloudItem>("/victim.txt" to live("victim", "/victim.txt"))
        gather.detectMissingAfterFullSync(changes, gatherStartedAt = Instant.parse("2026-06-01T00:00:00Z"))
        assertEquals(emptyList<CloudItem>(), changes.values.filter { it.deleted }, "the renamed item's row is held, not reaped")

        assertNull(gather.resolveItemPath(live("unknown", "/a/../victim.txt").copy(deleted = true)))
        assertEquals("/a/x.txt", gather.resolveItemPath(live("renamed", "/a/../victim.txt").copy(deleted = true))?.path)
        assertEquals("/a/x.txt", gather.resolveItemPath(live("renamed", "/a/x.txt"))?.path)
    }

    // 50,000 alive rows `/f/<i>` with remote id `id<i>`, straight into the table in one transaction.
    private fun seed(count: Int) =
        DriverManager.getConnection("jdbc:sqlite:${tmp.resolve("state.db")}").use { c ->
            c.createStatement().use {
                it.executeUpdate(
                    "WITH RECURSIVE n(i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM n WHERE i < ${count - 1}) " +
                        "INSERT INTO sync_entries (remote_id, path, remote_size, is_folder, last_synced, status) " +
                        "SELECT 'id' || i, '/f/' || i, 1, 0, '2026-03-01T00:00:00Z', 'EXISTS' FROM n",
                )
            }
        }

    @Test
    fun `the sweep reads a large table in steps and never as one list`() {
        val rows = 50_000
        seed(rows)
        val changes = LinkedHashMap<String, CloudItem>()
        for (i in 0 until 40_000) changes["/f/$i"] = live("id$i", "/f/$i") // 10,000 are missing
        db.holds.reset()

        gather().detectMissingAfterFullSync(changes, gatherStartedAt = Instant.parse("2026-06-01T00:00:00Z"))

        assertEquals(10_000, changes.values.count { it.deleted })
        val holds = db.holds.snapshot().associateBy { it.method }
        assertEquals(null, holds["getAllEntries"], "the table is not loaded as one list")
        assertEquals(
            rows / StateDatabase.ALIVE_SCAN_CHUNK_ROWS + 1,
            holds.getValue("scanAliveRows").calls.toInt(),
            "one hold of the monitor per step of ${StateDatabase.ALIVE_SCAN_CHUNK_ROWS} rows, the last one empty",
        )
    }

    @Test
    fun `scanAliveRows visits every alive row once, in steps, and no tombstone`() {
        for (i in 0 until 50) db.upsertEntry(row("/p/$i", "id$i"))
        db.upsertEntry(row("/p/trashed", "trashed", status = EntryStatus.TRASHED))
        db.upsertEntry(row("/p/pending", null))
        db.holds.reset()
        var gaps = 0
        db.aliveScanChunkGap = { gaps++ }

        val visited = mutableListOf<String>()
        db.scanAliveRows(chunkRows = 7) { visited += it.path }

        assertEquals(51, visited.size, "50 uploaded rows and the pending one; the trashed row is not alive")
        assertEquals(visited.toSet().size, visited.size, "each row once")
        assertEquals(8, db.holds.snapshot().single { it.method == "scanAliveRows" }.calls.toInt(), "7 full steps of 7 rows and the rest")
        assertEquals(7, gaps, "the monitor is free between the steps")
        assertTrue("/p/trashed" !in visited)
    }
}
