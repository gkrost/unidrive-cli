package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The hot calls of [StateDatabase] (the lookups, the upsert, the sync_state pair, the staging insert) reuse prepared
 * statements instead of preparing their SQL on every call: preparing was most of the cost of a merge row. A reused
 * statement must not carry anything from its last use, and must not keep a result open: an open cursor holds the
 * read lock and stops the commit of every other connection (the CLI reads the same file while the daemon writes).
 */
class StateDatabaseStatementCacheTest {
    private lateinit var dir: Path
    private lateinit var dbFile: Path
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("unidrive-stmt-cache")
        dbFile = dir.resolve("state.db")
        db = StateDatabase(dbFile)
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    private val stamp = Instant.parse("2026-03-28T12:00:00Z")

    private fun row(
        path: String,
        id: String? = "id-$path",
        remotePath: String? = null,
        hash: String? = null,
        localMtime: Long? = null,
    ) = SyncEntry(
        path = path,
        remotePath = remotePath,
        remoteId = id,
        remoteHash = hash,
        remoteSize = 7,
        remoteModified = stamp,
        localMtime = localMtime,
        localSize = localMtime,
        isFolder = false,
        isPinned = false,
        isHydrated = false,
        lastSynced = stamp,
    )

    @Test
    fun `one statement answers each call for its own parameters`() {
        db.batch {
            for (i in 0 until 20) db.upsertEntry(row("/f$i", hash = "h$i"))
        }

        for (i in listOf(3, 17, 3, 0, 19, 17)) {
            assertEquals("h$i", db.getEntry("/f$i")?.remoteHash)
            assertEquals("/f$i", db.getEntryByRemoteId("id-/f$i")?.path)
            assertEquals("/f$i", db.getEntryByRemotePath("/f$i")?.path)
        }
        assertNull(db.getEntry("/absent"), "a miss after hits is a miss")
        assertNull(db.getEntryByRemoteId("id-/absent"))
        assertNull(db.getEntryByRemotePath("/absent"))
        db.setSyncState("k", "one")
        db.setSyncState("k", "two")
        assertEquals("two", db.getSyncState("k"))
        assertNull(db.getSyncState("other"), "a key that was never written")
    }

    @Test
    fun `nothing of one upsert reaches the next`() {
        db.upsertEntry(row("/a", hash = "hash-a", localMtime = 5, remotePath = "/Remote/a"))
        db.upsertEntry(row("/b"))

        val b = assertNotNull(db.getEntry("/b"))
        assertNull(b.remoteHash, "the optional columns of the first row did not stay bound")
        assertNull(b.localMtime)
        assertNull(b.localSize)
        assertNull(b.remotePath)
        assertEquals("hash-a", db.getEntry("/a")?.remoteHash)
    }

    @Test
    fun `an upsert that replaces the row at its path still evicts it`() {
        db.upsertEntry(row("/p", id = "first"))
        db.upsertEntry(row("/p", id = "second"))

        assertNull(db.getEntryByRemoteId("first"), "the collision probe found the old row and the delete removed it")
        assertEquals("second", db.getEntry("/p")?.remoteId)
    }

    @Test
    fun `after a rollback the statements answer as before`() {
        db.upsertEntry(row("/kept"))
        assertFailsWith<IllegalStateException> {
            db.batch {
                db.upsertEntry(row("/rolled-back"))
                db.setSyncState("k", "in the batch")
                assertNotNull(db.getEntry("/rolled-back"))
                error("abort the batch")
            }
        }

        assertNull(db.getEntry("/rolled-back"))
        assertNull(db.getSyncState("k"))
        assertEquals("/kept", db.getEntry("/kept")?.path)
        db.upsertEntry(row("/after"))
        assertEquals("/after", db.getEntry("/after")?.path)
    }

    @Test
    fun `statements are prepared again after close and initialize`() {
        db.upsertEntry(row("/x"))
        db.getEntry("/x")
        assertTrue(db.cachedStatementCount() > 0, "the hot calls left their statements in the cache")

        db.close()
        assertEquals(0, db.cachedStatementCount(), "closing the database closes them")
        db.initialize()

        assertEquals("/x", db.getEntry("/x")?.path)
        db.setSyncState("k", "v")
        assertEquals("v", db.getSyncState("k"))
    }

    @Test
    fun `a finished call leaves no cursor open for another connection`() {
        db.upsertEntry(row("/seen"))
        db.setSyncState("k", "v")
        // Every call that reads one row and returns: each would keep its read lock if its result stayed open.
        db.getEntry("/seen")
        db.getEntryByRemoteId("id-/seen")
        db.getEntryByRemotePath("/seen")
        db.getSyncState("k")
        db.upsertEntry(row("/seen", id = "another"))

        DriverManager.getConnection("jdbc:sqlite:$dbFile").use { other ->
            other.createStatement().use { it.execute("PRAGMA busy_timeout=0") }
            other.createStatement().use { it.executeUpdate("INSERT INTO pin_rules (pattern, pinned) VALUES ('/other', 1)") }
        }
        assertTrue(db.getPinRules().any { it.first == "/other" })
    }

    @Test
    fun `a call that fails leaves the cache usable`() {
        db.setSyncState("k", "before")
        val cachedBefore = db.cachedStatementCount()
        db.pragma("busy_timeout=0") // the lock below must fail the write at once, not after the timeout
        DriverManager.getConnection("jdbc:sqlite:$dbFile").use { other ->
            other.createStatement().use { it.execute("BEGIN IMMEDIATE") }
            try {
                assertFailsWith<SQLException> { db.setSyncState("k", "while locked") }
            } finally {
                other.createStatement().use { it.execute("ROLLBACK") }
            }
        }

        assertEquals(cachedBefore - 1, db.cachedStatementCount(), "the statement that failed was dropped, not kept in doubt")
        db.setSyncState("k", "after")
        assertEquals("after", db.getSyncState("k"))
    }

    @Test
    fun `the staging insert reuses its statement and keeps each page apart`() {
        fun item(
            id: String,
            hash: String?,
        ) = org.krost.unidrive.CloudItem(
            id = id,
            name = "n-$id",
            path = "/n-$id",
            size = 1,
            isFolder = false,
            modified = stamp,
            created = null,
            hash = hash,
            mimeType = null,
            parentId = null,
        )
        val scan = db.beginScan("m0")
        db.persistScanPage(scan, listOf(item("a", "ha"), item("b", null)), "m1")
        db.persistScanPage(scan, listOf(item("c", "hc")), "m2")

        val staged = db.loadStagedItems(scan).associateBy { it.id }
        assertEquals(setOf("a", "b", "c"), staged.keys)
        assertEquals("ha", staged.getValue("a").hash)
        assertNull(staged.getValue("b").hash, "the hash of the row before did not stay bound")
        assertEquals("hc", staged.getValue("c").hash)
    }

    @Test
    fun `the connection has the busy timeout and the page cache of the writer`() {
        assertEquals("5000", db.pragma("busy_timeout"))
        assertEquals("-65536", db.pragma("cache_size"))
    }

    // ---- StatementCache on its own ------------------------------------------------------------------------------

    @Test
    fun `the cache keeps at most its limit and closes the one given back longest ago`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            val cache = StatementCache(limit = 3)
            val statements = (1..5).map { conn.prepareStatement("SELECT $it") }
            statements.forEachIndexed { i, s -> cache.give("SELECT ${i + 1}", s) }

            assertEquals(3, cache.size)
            assertTrue(statements[0].isClosed && statements[1].isClosed, "the two oldest were closed")
            assertFalse(statements[2].isClosed || statements[3].isClosed || statements[4].isClosed)
            assertNull(cache.take("SELECT 1"))
            assertSame(statements[4], cache.take("SELECT 5"))
            assertEquals(2, cache.size, "a statement that is taken out is not in the cache")

            cache.clear()
            assertEquals(0, cache.size)
            assertTrue(statements[2].isClosed && statements[3].isClosed)
        }
    }

    @Test
    fun `two uses of one text that overlap each have a statement and the cache keeps the last`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            val cache = StatementCache(limit = 3)
            val first = conn.prepareStatement("SELECT 1")
            val second = conn.prepareStatement("SELECT 1")

            cache.give("SELECT 1", first)
            cache.give("SELECT 1", second)

            assertEquals(1, cache.size)
            assertTrue(first.isClosed, "the older one is closed, not leaked")
            assertSame(second, cache.take("SELECT 1"))
        }
    }
}
