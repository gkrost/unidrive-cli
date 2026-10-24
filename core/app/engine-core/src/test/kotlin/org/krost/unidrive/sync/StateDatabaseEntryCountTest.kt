package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.EntryStatus
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.util.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The enumeration asks for the number of alive rows twice per pass, and each ask was a `COUNT(*)` of the whole table
 * under the monitor: 128 ms on average on 388,000 rows, 22 s in 88 minutes of the daemon's life (archive#983). The number is
 * now kept in memory, in step with every write. The tests compare it with what the table holds, after every kind of
 * write, and pin the places where a number that is merely added to would go wrong: a rollback, a writer in another
 * connection, a bulk write. Nothing here reads a clock: that the table is not scanned is counted
 * ([StateDatabase.tableCounts]), not timed, and the tables are small.
 */
class StateDatabaseEntryCountTest {
    private lateinit var dir: Path
    private lateinit var dbFile: Path
    private lateinit var db: StateDatabase
    private val opened = mutableListOf<StateDatabase>()
    private var second: Connection? = null

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("unidrive-entry-count")
        dbFile = dir.resolve("state.db")
        db = open()
    }

    @AfterTest
    fun tearDown() {
        runCatching { second?.close() }
        opened.forEach { runCatching { it.close() } }
        dir.toFile().deleteRecursively()
    }

    private fun open(readOnly: Boolean = false): StateDatabase = StateDatabase(dbFile, readOnly = readOnly).also { it.initialize(); opened += it }

    /** What a second, independent connection counts in the file: committed alive rows. */
    private fun committed(): Int {
        val c = second ?: DriverManager.getConnection("jdbc:sqlite:$dbFile").also { second = it }
        return c.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM sync_entries WHERE status='EXISTS'").use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    /** The count of the table as the engine used to ask for it: every alive row, read by this handle (an open transaction included). */
    private fun rows(handle: StateDatabase = db) = handle.getAllEntries().size

    private fun assertCountMatches(
        what: String,
        handle: StateDatabase = db,
    ) = assertEquals(rows(handle), handle.getEntryCount(), what)

    private fun entry(
        path: String,
        id: String = "id-$path",
        status: EntryStatus = EntryStatus.EXISTS,
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
        status = status,
    )

    /** A file written through the mount that has not reached the cloud: no remote id, local bytes. */
    private fun pending(path: String) = entry(path).copy(remoteId = null, isHydrated = true, localMtime = 1, localSize = 1)

    /** Alive rows `<root>/f<i>` straight into the table, behind the handle's back, in one transaction. */
    private fun seed(
        root: String,
        count: Int,
    ) = DriverManager.getConnection("jdbc:sqlite:$dbFile").use { c ->
        c.createStatement().use {
            it.executeUpdate(
                "WITH RECURSIVE n(i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM n WHERE i < ${count - 1}) " +
                    "INSERT INTO sync_entries (remote_id, path, remote_size, is_folder, last_synced, status) " +
                    "SELECT '$root-' || i, '$root/f' || i, 1, 0, '1970-01-01T00:00:00Z', 'EXISTS' FROM n",
            )
        }
    }

    private fun onOtherThread(block: () -> Unit) {
        var failure: Throwable? = null
        val t =
            Thread {
                try {
                    block()
                } catch (e: Throwable) {
                    failure = e
                }
            }
        t.start()
        t.join(60_000)
        assertFalse(t.isAlive, "the call from the other thread must not wait for the open batch")
        failure?.let { throw it }
    }

    @Test
    fun `the count follows every kind of write`() {
        db.beginBatch() // one transaction for the whole walk: the handle sees its own uncommitted rows
        assertEquals(0, db.getEntryCount())

        db.upsertEntry(entry("/a"))
        db.upsertEntry(entry("/b"))
        assertCountMatches("two new rows")
        assertEquals(2, db.getEntryCount())

        db.upsertEntry(entry("/a").copy(remoteSize = 5))
        assertCountMatches("an upsert of an existing row replaces it")
        assertEquals(2, db.getEntryCount())

        db.upsertEntry(entry("/a", id = "someone-else"))
        assertCountMatches("a different remote id at an alive path evicts the row there")
        assertEquals(2, db.getEntryCount())

        db.markDeleted("/b")
        assertCountMatches("a tombstone is not alive")
        assertEquals(1, db.getEntryCount())

        db.upsertEntry(entry("/b"))
        assertCountMatches("an alive row over the tombstone of the same remote id")
        assertEquals(2, db.getEntryCount())

        db.insertFolder("/dir", "dir-id", Instant.EPOCH)
        db.insertFolder("/dir", "dir-id", Instant.EPOCH)
        assertCountMatches("a folder is inserted once")
        assertEquals(3, db.getEntryCount())

        db.upsertEntry(entry("/dir/x"))
        db.upsertEntry(entry("/dir/y"))
        assertEquals(2, db.markDescendantsDeleted("/dir"))
        assertCountMatches("the descendants of a folder are tombstoned in one statement")
        assertEquals(3, db.getEntryCount())

        assertTrue(db.setStatusTrashed("id-/b"))
        assertFalse(db.setStatusTrashed("id-/b"))
        assertCountMatches("trashed twice")
        assertEquals(2, db.getEntryCount())
        assertTrue(db.setStatusExists("id-/b"))
        assertFalse(db.setStatusExists("id-/b"))
        assertCountMatches("brought back twice")
        assertEquals(3, db.getEntryCount())

        db.deleteEntry("/not-here")
        db.deleteEntry("/a")
        db.deleteEntry("/a")
        assertCountMatches("a row deleted twice")
        assertEquals(2, db.getEntryCount())

        db.upsertEntry(pending("/p"))
        assertCountMatches("a pending upload")
        assertTrue(db.deleteLocalPendingRow("/p"))
        assertFalse(db.deleteLocalPendingRow("/p"))
        assertCountMatches("a pending upload dropped twice")

        db.upsertEntry(entry("/old/1"))
        db.upsertEntry(entry("/old/2"))
        db.upsertEntry(entry("/new/1"))
        db.upsertEntry(entry("/new/only-here"))
        db.upsertEntry(entry("/new"))
        db.renamePrefix("/old", "/new")
        assertCountMatches("a rename wipes the destination")
        assertEquals(4, db.getEntryCount(), "/b, /dir and the two moved rows: the three rows at the destination are gone")

        db.commitBatch()
        assertEquals(committed(), db.getEntryCount(), "committed")
    }

    @Test
    fun `a rename in steps over a destination in steps keeps the count`() {
        seed("/src", 1_300)
        seed("/dst", 1_100)
        assertEquals(2_400, db.getEntryCount(), "counted 0 at initialize; the rows were written behind its back")

        db.renamePrefix("/src", "/dst")

        assertCountMatches("steps that clear and steps that move")
        assertEquals(1_300, db.getEntryCount())
        assertEquals(1_300, committed())
    }

    @Test
    fun `resetKeepingPending leaves the rows it kept and resetAll none`() {
        db.batch {
            db.upsertEntry(entry("/a"))
            db.upsertEntry(entry("/b"))
            db.upsertEntry(pending("/p1"))
            db.upsertEntry(pending("/p2"))
            db.markDeleted("/a")
        }

        assertEquals(2, db.resetKeepingPending())
        assertCountMatches("only the pending uploads stay")
        assertEquals(2, db.getEntryCount())
        assertEquals(2, committed())

        db.resetAll()
        assertCountMatches("nothing stays")
        assertEquals(0, db.getEntryCount())

        db.upsertEntry(entry("/again"))
        assertEquals(1, db.getEntryCount())
        assertCountMatches("counting goes on after a reset")
    }

    @Test
    fun `a batch that fails leaves the count as it was`() {
        db.batch {
            db.upsertEntry(entry("/a"))
            db.upsertEntry(entry("/b"))
            db.upsertEntry(entry("/dir/x"))
        }

        assertFailsWith<IllegalStateException> {
            db.batch {
                db.upsertEntry(entry("/c"))
                db.upsertEntry(entry("/d"))
                db.deleteEntry("/a")
                db.markDescendantsDeleted("/dir")
                db.markDeleted("/b")
                assertEquals(2, db.getEntryCount(), "inside the batch the changes count")
                error("the batch fails after its writes")
            }
        }

        assertEquals(3, db.getEntryCount(), "the rolled back batch did not change the count")
        assertEquals(3, committed(), "and the file agrees")

        db.batch {
            db.upsertEntry(entry("/c"))
            db.deleteEntry("/a")
        }
        assertEquals(3, db.getEntryCount())
        assertEquals(3, committed(), "a batch that commits keeps its changes")
    }

    @Test
    fun `a rename that rolls back with its caller leaves the count as it was`() {
        db.batch {
            for (i in 0 until 1_100) db.upsertEntry(entry("/src/f$i"))
            db.upsertEntry(entry("/dst/stale"))
        }

        runCatching {
            db.batch {
                db.renamePrefix("/src", "/dst")
                error("the caller fails after the rename")
            }
        }

        assertEquals(1_101, db.getEntryCount())
        assertEquals(1_101, committed(), "the destination row is back")
    }

    @Test
    fun `a manual batch that is rolled back puts the count back`() {
        db.upsertEntry(entry("/a"))
        db.beginBatch()
        db.upsertEntry(entry("/b"))
        db.upsertEntry(entry("/c"))
        db.deleteEntry("/a")
        assertEquals(2, db.getEntryCount(), "the batch's own changes count while it is open")
        db.rollbackBatch()

        assertEquals(1, db.getEntryCount())
        assertEquals(1, committed(), "rolled back")

        db.beginBatch()
        db.upsertEntry(entry("/b"))
        db.commitBatch()
        assertEquals(2, db.getEntryCount())
        assertEquals(2, committed(), "committed")
    }

    @Test
    fun `when another thread's write closed the manual batch's transaction a rollback discards only what came after`() {
        db.upsertEntry(entry("/base"))
        db.beginBatch()
        db.upsertEntry(entry("/mine-before"))
        onOtherThread { db.upsertEntry(entry("/theirs")) } // commits the batch's transaction as it stands (archive#300)
        db.upsertEntry(entry("/mine-after")) // the owner's next write opens a new transaction
        db.rollbackBatch()

        assertEquals(3, db.getEntryCount(), "base, mine-before and theirs were committed; mine-after was rolled back")
        assertEquals(3, committed(), "the file agrees")
    }

    @Test
    fun `a database opened again counts what is in the file`() {
        db.batch {
            for (i in 0 until 25) db.upsertEntry(entry("/f$i"))
            db.markDeleted("/f3")
        }
        db.close()

        val again = open()

        assertEquals(24, again.getEntryCount())
        assertEquals(1, again.tableCounts, "counted once, at initialize")
        again.upsertEntry(entry("/more"))
        assertEquals(25, again.getEntryCount())
        assertCountMatches("and counted on", again)
        assertEquals(1, again.tableCounts)
    }

    @Test
    fun `a write by another connection is noticed`() {
        db.upsertEntry(entry("/a"))
        assertEquals(1, db.getEntryCount())

        val other = open()
        other.upsertEntry(entry("/b"))
        other.upsertEntry(entry("/c"))
        assertEquals(3, db.getEntryCount(), "a second StateDatabase on the file, like a CLI command")

        DriverManager.getConnection("jdbc:sqlite:$dbFile").use { c -> c.createStatement().use { it.executeUpdate("DELETE FROM sync_entries WHERE remote_id = 'id-/b'") } }
        assertEquals(2, db.getEntryCount(), "a plain SQL connection")

        // This handle writes before it asks again: the write is added to a number that was stale already.
        other.upsertEntry(entry("/d"))
        db.upsertEntry(entry("/e"))
        assertEquals(4, db.getEntryCount())
        assertEquals(4, committed(), "a foreign write and an own write between two asks")
        assertEquals(4, other.getEntryCount())
    }

    @Test
    fun `a read-only handle counts at its first ask and follows the writer`() {
        db.batch {
            db.upsertEntry(entry("/a"))
            db.upsertEntry(entry("/b"))
        }
        val reader = open(readOnly = true)
        assertEquals(0, reader.tableCounts, "a read-only handle does not count when it opens")

        assertEquals(2, reader.getEntryCount())
        db.upsertEntry(entry("/c"))
        assertEquals(3, reader.getEntryCount())
        db.deleteEntry("/a")
        assertEquals(2, reader.getEntryCount())
    }

    @Test
    fun `a snapshot copy counts its own rows`() {
        db.batch { for (i in 0 until 7) db.upsertEntry(entry("/f$i")) }
        val snapshot = StateDatabase.snapshotOf(dbFile, tempRoot = dir)
        try {
            assertEquals(7, snapshot.getEntryCount())
            snapshot.deleteEntry("/f0")
            assertEquals(6, snapshot.getEntryCount())
            assertEquals(7, db.getEntryCount(), "the copy is its own database")
        } finally {
            snapshot.close()
        }
    }

    @Test
    fun `asking does not scan the table`() {
        seed("/rows", 3_000)
        val big = open()
        assertEquals(1, big.tableCounts, "the one count, at initialize")

        // Rows are written and deleted between the asks, as an enumeration does.
        repeat(25) { i ->
            big.upsertEntry(entry("/extra/$i"))
            big.deleteEntry("/rows/f${i * 100 + 1}")
            big.renamePrefix("/extra", "/extra2")
            big.renamePrefix("/extra2", "/extra")
            assertEquals(3_000, big.getEntryCount())
        }
        assertEquals(1, big.tableCounts, "no ask and no write counted the table again")

        // A commit by another connection is the one thing that makes it count again, once.
        db.upsertEntry(entry("/foreign"))
        assertEquals(3_001, big.getEntryCount())
        assertEquals(2, big.tableCounts)
        assertEquals(3_001, big.getEntryCount())
        assertEquals(2, big.tableCounts, "and then not again")
    }

    @Test
    fun `the count agrees with the table after a long random mix of writes`() {
        val random = Random(20261018)

        fun path() = "/d${random.nextInt(4)}/f${random.nextInt(14)}"

        fun id() = "i${random.nextInt(22)}"

        fun attempt(write: () -> Unit) {
            // A write the table refuses (an alive row at the path of the tombstone that would come back) changes nothing.
            try {
                write()
            } catch (_: SQLException) {
            }
        }

        fun oneWrite() {
            when (random.nextInt(12)) {
                0, 1, 2 -> db.upsertEntry(entry(path(), id()))
                3 -> db.upsertEntry(entry(path(), id(), EntryStatus.DELETED))
                4 -> db.deleteEntry(path())
                5 -> db.setStatusTrashed(id())
                6 -> attempt { db.setStatusExists(id()) }
                7 -> db.markDescendantsDeleted("/d${random.nextInt(4)}")
                8 -> db.renamePrefix("/d${random.nextInt(4)}", "/d${random.nextInt(4)}")
                9 -> db.markDeleted(path())
                10 -> db.upsertEntry(pending(path()))
                else -> db.deleteLocalPendingRow(path())
            }
        }

        // Groups of writes in one transaction each, a fourth of them rolled back; the number is compared with the table
        // after every write (the handle sees its own open transaction) and with the file after every group.
        repeat(80) { group ->
            val rollBack = random.nextInt(4) == 0
            try {
                db.batch {
                    repeat(1 + random.nextInt(6)) { step ->
                        oneWrite()
                        assertCountMatches("group $group, write $step")
                    }
                    if (rollBack) error("rolled back")
                }
            } catch (_: IllegalStateException) {
                assertTrue(rollBack, "only the rolled back groups fail")
            }
            assertCountMatches("after group $group")
            assertEquals(committed(), db.getEntryCount(), "the file after group $group")
        }
        assertEquals(1, db.tableCounts, "no write of the mix counted the table")
    }
}
