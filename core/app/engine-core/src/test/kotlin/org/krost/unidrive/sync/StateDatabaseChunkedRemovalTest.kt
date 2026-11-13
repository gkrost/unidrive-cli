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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * windows#454 items 9 and 10: the removal writers that used to be one unbounded transaction per pass — the scope
 * narrowing (a DELETE per row over the whole table) — are chunked now ([StateDatabase.deleteOutOfScope],
 * [StateDatabase.markDeletedChunked]). These pin what they remove and count, and that each chunk commits.
 */
class StateDatabaseChunkedRemovalTest {
    private lateinit var dbFile: Path
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        dbFile = Files.createTempDirectory("unidrive-chunked-removal").resolve("state.db")
        db = StateDatabase(dbFile)
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun entry(path: String, remotePath: String? = null) =
        SyncEntry(
            path = path,
            remotePath = remotePath,
            remoteId = "id-$path",
            remoteHash = "hash-$path",
            remoteSize = 100,
            remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
            localMtime = 1711627200000,
            localSize = 100,
            isFolder = false,
            isPinned = false,
            isHydrated = false,
            lastSynced = Instant.now(),
        )

    /** Rows a second, independent connection sees: committed ones only. */
    private fun committedPaths(): Set<String> =
        DriverManager.getConnection("jdbc:sqlite:$dbFile").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT path FROM sync_entries WHERE status='EXISTS'").use { rs ->
                    buildSet { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }

    @Test
    fun `deleteOutOfScope removes exactly the rows outside the scope, wildcards and aliases included`() {
        db.upsertEntry(entry("/in/a.txt"))
        db.upsertEntry(entry("/in/sub/b.txt"))
        db.upsertEntry(entry("/out/c.txt"))
        db.upsertEntry(entry("/in_100%d.txt")) // a name with LIKE wildcards: literal, never a pattern
        db.upsertEntry(entry("/tracked", remotePath = "/in/aliased.txt")) // the effective remote path decides

        val deleted = db.deleteOutOfScope(listOf("/in"), setOf("/"))

        assertEquals(2, deleted)
        assertNotNull(db.getEntry("/in/a.txt"))
        assertNotNull(db.getEntry("/in/sub/b.txt"))
        assertNotNull(db.getEntry("/tracked"), "the aliased row's effective remote path is in scope")
        assertNull(db.getEntry("/out/c.txt"))
    }

    @Test
    fun `deleteOutOfScope with the whole drive tracked removes nothing`() {
        db.upsertEntry(entry("/a.txt"))
        assertEquals(0, db.deleteOutOfScope(emptyList(), setOf()))
        assertNotNull(db.getEntry("/a.txt"))
    }

    @Test
    fun `each chunk commits without a manual batch open`() {
        for (n in 0 until 5) db.upsertEntry(entry("/r$n.txt"))

        val deleted = db.markDeletedChunked((0 until 5).map { "/r$it.txt" }, chunkSize = 1)

        assertEquals(5, deleted.size)
        assertEquals(emptySet(), committedPaths(), "no chunk waits for an outside commit: all are durable at once")
    }

    @Test
    fun `markDeletedChunked flips the rows and keeps the alive count`() {
        for (n in 0 until 7) db.upsertEntry(entry("/r$n.txt"))
        assertEquals(7, db.getEntryCount())

        val deleted = db.markDeletedChunked((0 until 7).map { "/r$it.txt" }, chunkSize = 3)

        assertEquals(7, deleted.size)
        assertEquals(0, db.getEntryCount(), "the alive count follows the flips")
        for (n in 0 until 7) assertNull(db.getEntry("/r$n.txt"))
    }
}
