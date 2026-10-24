package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * A [StateDatabase.batch] that ends in a throw leaves no row behind, whatever it throws. JDBC commits the open
 * transaction when auto-commit comes back on, so a rollback that only an Exception triggers commits half of the
 * transaction when an Error (out of memory, stack overflow) escapes the block.
 */
class StateDatabaseBatchErrorTest {
    private lateinit var dbFile: Path
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        dbFile = Files.createTempDirectory("unidrive-batch-error").resolve("state.db")
        db = StateDatabase(dbFile)
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dbFile.parent.toFile().deleteRecursively()
    }

    private fun entry(path: String) =
        SyncEntry(
            path = path,
            remoteId = "id-$path",
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

    /** The alive rows a second, independent connection sees: committed ones only. */
    private fun committedRows(): Int =
        DriverManager.getConnection("jdbc:sqlite:$dbFile").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM sync_entries WHERE status='EXISTS'").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    @Test
    fun `an Error out of a batch rolls back what the block wrote`() {
        assertFailsWith<OutOfMemoryError> {
            db.batch {
                db.upsertEntry(entry("/a"))
                throw OutOfMemoryError("test")
            }
        }

        assertNull(db.getEntry("/a"))
        assertEquals(0, committedRows())
        assertEquals(0, db.getEntryCount())
        db.upsertEntry(entry("/b")) // the connection is back in auto-commit and usable
        assertEquals(1, committedRows())
    }

    @Test
    fun `a StackOverflowError out of a batch rolls back as well`() {
        assertFailsWith<StackOverflowError> {
            db.batch {
                db.upsertEntry(entry("/a"))
                db.upsertEntry(entry("/b"))
                throw StackOverflowError()
            }
        }

        assertEquals(0, committedRows())
    }

    @Test
    fun `an Exception out of a batch still rolls back`() {
        assertFailsWith<IllegalStateException> {
            db.batch {
                db.upsertEntry(entry("/a"))
                error("test")
            }
        }

        assertEquals(0, committedRows())
        assertEquals(0, db.getEntryCount())
    }

    @Test
    fun `a manual batch that is rolled back leaves no row`() {
        db.beginBatch()
        db.upsertEntry(entry("/a"))
        db.rollbackBatch()

        assertEquals(0, committedRows())
        assertEquals(0, db.getEntryCount())
    }

    @Test
    fun `the count after a rollback is not the number counted before another connection committed`() {
        db.upsertEntry(entry("/a"))
        assertEquals(1, db.getEntryCount())

        assertFailsWith<IllegalStateException> {
            db.batch {
                // Another connection commits, and the count inside the batch notices (data_version moved) and counts again.
                DriverManager.getConnection("jdbc:sqlite:$dbFile").use { c ->
                    c.createStatement().use {
                        it.executeUpdate(
                            "INSERT INTO sync_entries (remote_id, path, remote_size, is_folder, last_synced, status) " +
                                "VALUES ('id-/foreign', '/foreign', 1, 0, '1970-01-01T00:00:00Z', 'EXISTS')",
                        )
                    }
                }
                assertEquals(2, db.getEntryCount())
                db.upsertEntry(entry("/b"))
                error("test")
            }
        }

        assertEquals(2, committedRows())
        assertEquals(2, db.getEntryCount(), "the foreign row is in the table: the count restored from before it is stale")
    }
}
