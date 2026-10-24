package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A folder rename through the mount rewrote every row of the subtree inside one locked transaction: 134,877 rows
 * kept the database monitor for 15 s on a copy of the real table, and every status request, listing and enumeration
 * merge waited behind it. The rewrite now goes in steps of [StateDatabase.RENAME_CHUNK_ROWS] rows with the monitor free
 * in between. These tests pin the shape that matters, with the steps made visible by [StateDatabase.renameChunkGap]
 * instead of by the clock (none of them times anything): that the monitor is free between two steps, what a reader sees
 * there, and that a small rename is still one transaction.
 */
class StateDatabaseRenameChunkTest {
    private lateinit var dir: Path
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("unidrive-rename-chunk")
        db = StateDatabase(dir.resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    /** Alive rows `<root>/d<i % 40>/f<i>` for i in [0, count), straight into the table: tens of thousands of upserts would take a minute. */
    private fun seed(
        root: String,
        count: Int,
        idPrefix: String,
        folderRow: Boolean = true,
    ) {
        DriverManager.getConnection("jdbc:sqlite:${dir.resolve("state.db")}").use { c ->
            c.autoCommit = false
            c.prepareStatement(
                "INSERT INTO sync_entries (remote_id, path, remote_size, is_folder, last_synced, status) VALUES (?, ?, 1, ?, ?, 'EXISTS')",
            ).use { insert ->
                fun add(
                    id: String,
                    path: String,
                    folder: Boolean,
                ) {
                    insert.setString(1, id)
                    insert.setString(2, path)
                    insert.setInt(3, if (folder) 1 else 0)
                    insert.setString(4, Instant.EPOCH.toString())
                    insert.executeUpdate()
                }
                if (folderRow) add("$idPrefix-root", root, true)
                for (i in 0 until count) add("$idPrefix-$i", "$root/d${i % 40}/f$i", false)
            }
            c.commit()
        }
    }

    private fun entry(
        path: String,
        id: String,
    ) = SyncEntry(
        path = path,
        remoteId = id,
        remoteHash = null,
        remoteSize = 1,
        remoteModified = null,
        localMtime = null,
        localSize = null,
        isFolder = false,
        isPinned = false,
        isHydrated = false,
        lastSynced = Instant.EPOCH,
    )

    private fun below(prefix: String) = db.getEntriesByPrefix(prefix).map { it.path.removePrefix(prefix) }.toSet()

    /** Proves from another thread that the monitor can be taken now: a thread that blocks on it never finishes. */
    private fun assertMonitorFree(what: String) {
        val reader = thread(name = "rename-chunk-reader") { db.getEntry("/nothing") }
        reader.join(TimeUnit.SECONDS.toMillis(60))
        if (reader.isAlive) fail("the monitor was held $what")
    }

    @Test
    fun `a rename of 3,500 rows is four locked sections and the monitor is free between them`() {
        seed("/big", 3_500, "big")
        seed("/other", 500, "other")
        var gaps = 0
        db.renameChunkGap = {
            gaps++
            assertMonitorFree("between two steps of the rename (gap $gaps)")
        }
        db.holds.reset()

        db.renamePrefix("/big", "/huge")

        // 3,500 rows in steps of 1,000 rows: three steps that move a full chunk, a fourth for the rest and the folder row.
        // The steps are counted, not timed: how long a step takes depends on the machine (0.1 s on a development machine,
        // 24 s on a CI runner's disk), and a clock in a test only turns that into red builds. What the fix is, is that one
        // locked section never covers the whole subtree, and the monitor is free between them.
        assertEquals(3, gaps, "the monitor is given back after each full step")
        assertEquals(4L, db.holds.snapshot().single { it.method == "renamePrefix" }.calls, "one locked section per step")
        assertEquals(emptySet(), below("/big/"))
        assertEquals(3_500, db.getEntriesByPrefix("/huge/").size)
        assertNull(db.getEntry("/big"))
        assertNotNull(db.getEntry("/huge"))
        assertEquals(500, db.getEntriesByPrefix("/other/").size, "rows outside both prefixes stay")
        assertEquals(3_500 + 1 + 500 + 1, db.getEntryCount())
    }

    @Test
    fun `between two steps every row is under the old prefix or the new one, never both and never neither`() {
        seed("/src", 2_500, "s")
        val all = (0 until 2_500).map { "d${it % 40}/f$it" }.toSet()
        val seen = mutableListOf<Pair<Int, Int>>()
        db.renameChunkGap = {
            val stillOld = below("/src/")
            val alreadyNew = below("/dst/")
            assertEquals(emptySet(), stillOld intersect alreadyNew, "a row under both prefixes")
            assertEquals(all, stillOld union alreadyNew, "a row under neither prefix")
            assertEquals(2_500 + 1, db.getEntryCount(), "the number of alive rows never changes while rows move")
            // The folder row moves last: until the end it is where it was, and the new folder has no row yet.
            assertNotNull(db.getEntry("/src"), "the old folder row is still listed")
            assertNull(db.getEntry("/dst"), "the new folder row appears with the last step")
            seen += stillOld.size to alreadyNew.size
        }

        db.renamePrefix("/src", "/dst")

        assertEquals(listOf(1_500 to 1_000, 500 to 2_000), seen, "the subtree is half-moved between steps, a chunk at a time")
        assertEquals(emptySet(), below("/src/"))
        assertEquals(all, below("/dst/"))
        assertNull(db.getEntry("/src"))
        assertNotNull(db.getEntry("/dst"))
    }

    @Test
    fun `a subtree smaller than one step is one transaction and never gives the monitor back`() {
        seed("/small", 998, "m") // the folder row and 998 rows: 999 writes in a step of 1,000
        var gaps = 0
        db.renameChunkGap = { gaps++ }
        db.holds.reset()

        db.renamePrefix("/small", "/renamed")

        assertEquals(0, gaps)
        assertEquals(1L, db.holds.snapshot().single { it.method == "renamePrefix" }.calls)
        assertEquals(998, db.getEntriesByPrefix("/renamed/").size)
        assertNotNull(db.getEntry("/renamed"))
    }

    @Test
    fun `a rename that rolls back with its caller moves nothing`() {
        seed("/src", 1_500, "s")
        runCatching {
            db.batch {
                db.renamePrefix("/src", "/dst")
                error("the caller fails after the rename")
            }
        }

        assertEquals(1_500, db.getEntriesByPrefix("/src/").size, "the steps were part of the caller's transaction")
        assertEquals(emptySet(), below("/dst/"))
        assertNotNull(db.getEntry("/src"))
    }

    @Test
    fun `rows at and below the destination are removed in steps before anything moves`() {
        seed("/old", 1_100, "o")
        seed("/new", 2_100, "n") // what LocalScanner wrote at the destination: pending rows, and the folder row
        var cleared = 0
        db.renameChunkGap = {
            // Nothing has moved while the destination is still being cleared.
            if (below("/old/").size == 1_100) cleared++
        }

        db.renamePrefix("/old", "/new")

        assertEquals(2, cleared, "2,100 destination rows: two full clearing steps, then the third moves the first rows")
        // Only the moved rows are at the destination: the 1,000 destination rows that had no counterpart are gone.
        assertEquals((0 until 1_100).map { "d${it % 40}/f$it" }.toSet(), below("/new/"))
        assertEquals(emptySet(), below("/old/"))
        assertEquals(1_100 + 1, db.getEntryCount(), "the destination's own rows are gone, only the moved ones remain")
        assertEquals("o-root", db.getAllEntries().single { it.path == "/new" }.remoteId, "the folder row is the moved one")
    }

    @Test
    fun `a row written at a destination path between steps does not stop the rename, the moved row wins`() {
        seed("/src", 2_200, "s")
        var written = false
        db.renameChunkGap = {
            if (!written) {
                written = true
                // A different row (another remote id) where a row that has not moved yet is going: the last one in path order.
                val last = below("/src/").max()
                db.upsertEntry(entry("/dst/$last", "intruder"))
                db.upsertEntry(entry("/dst", "intruder-folder"))
            }
        }

        db.renamePrefix("/src", "/dst")

        assertTrue(written, "the rename gave the monitor back and the intruder got in")
        assertEquals(emptySet(), below("/src/"))
        assertEquals(2_200, db.getEntriesByPrefix("/dst/").size)
        assertNull(db.getEntryByRemoteId("intruder"), "the row in the way is gone")
        assertNull(db.getEntryByRemoteId("intruder-folder"))
        assertEquals("s-root", db.getEntry("/dst")?.remoteId)
        assertEquals(2_200 + 1, db.getEntryCount())
    }

    @Test
    fun `a folder renamed into itself still ends, as one step`() {
        db.upsertEntry(entry("/a/x", "x"))
        db.upsertEntry(entry("/a/sub/y", "y"))
        db.upsertEntry(entry("/a", "a").copy(isFolder = true))
        var gaps = 0
        db.renameChunkGap = { gaps++ }

        val done = thread { db.renamePrefix("/a", "/a/b") }
        done.join(TimeUnit.SECONDS.toMillis(60))

        assertTrue(!done.isAlive, "the rename must not loop on rows that match the old prefix again")
        assertEquals(0, gaps)
        assertEquals(listOf("/a/b", "/a/b/sub/y", "/a/b/x"), db.getAllEntries().map { it.path }.sorted())
    }
}
