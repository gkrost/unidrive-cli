package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#845 (item #744-3): a row that never reached the cloud is told by the `pending_upload` column, not by
 * the spelling of its key. An S3 object key may start with `local:`; before schema 5 such a row read as a pending upload.
 *
 * One test per invariant. If `an S3 key starting with local is an ordinary remote row` is removed or loosened, the key
 * namespace silently collides with the pending namespace again; if the two migration tests are, a file written before
 * schema 5 can lose its pending uploads or be left half migrated.
 */
class PendingUploadColumnTest {
    private lateinit var dir: Path
    private lateinit var file: Path

    @BeforeTest
    fun setUp() {
        dir = TestScratch.directory("unidrive-pending-column")
        file = dir.resolve("state.db")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun row(
        path: String,
        remoteId: String?,
    ) = SyncEntry(
        path = path,
        remoteId = remoteId,
        remoteHash = null,
        remoteSize = 3,
        remoteModified = null,
        localMtime = 1L,
        localSize = 3L,
        isFolder = false,
        isPinned = false,
        isHydrated = true,
        lastSynced = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private fun <T> raw(body: (java.sql.Connection) -> T): T = DriverManager.getConnection("jdbc:sqlite:$file").use(body)

    private fun version(): String? =
        raw { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT value FROM sync_state WHERE key='schema_version'").use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    private fun columns(): Set<String> =
        raw { c ->
            c.createStatement().use { s ->
                s.executeQuery("PRAGMA table_info(sync_entries)").use { rs -> buildSet { while (rs.next()) add(rs.getString("name")) } }
            }
        }

    /** A file as schema 4 wrote it: one pending row under a minted key, one cloud row, one cloud row whose key starts with `local:`. */
    private fun writeSchema4File(): String {
        StateDatabase(file).also { it.initialize() }.run {
            upsertEntry(row("/pending.txt", null))
            upsertEntry(row("/synced.txt", "cloud-1"))
            upsertEntry(row("/report.txt", "local:report.txt"))
            close()
        }
        val mintedKey =
            raw { c ->
                c.createStatement().use { s ->
                    s.executeQuery("SELECT remote_id FROM sync_entries WHERE path='/pending.txt'").use { rs ->
                        rs.next()
                        rs.getString(1)
                    }
                }
            }
        val hasColumn = "pending_upload" in columns() // false on a build from before schema 5: the file is then schema 4 already
        raw { c ->
            c.createStatement().use { s ->
                s.executeUpdate("DROP VIEW alive_entries")
                s.executeUpdate("DROP INDEX IF EXISTS idx_sync_entries_pending_upload")
                if (hasColumn) s.executeUpdate("ALTER TABLE sync_entries DROP COLUMN pending_upload")
                s.executeUpdate("CREATE VIEW alive_entries AS SELECT * FROM sync_entries WHERE status='EXISTS'")
                s.executeUpdate("UPDATE sync_state SET value='4' WHERE key='schema_version'")
            }
        }
        assertFalse("pending_upload" in columns(), "the fixture is a schema 4 file")
        assertEquals("4", version())
        return mintedKey
    }

    @Test
    fun `an S3 key starting with local is an ordinary remote row, not a pending upload`() {
        val db = StateDatabase(file).also { it.initialize() }
        db.use {
            it.upsertEntry(row("/report.txt", "local:report.txt"))

            val read = assertNotNull(it.getEntry("/report.txt"))
            assertEquals("local:report.txt", read.remoteId, "the key is the remote id, as for any other S3 key")
            assertFalse(read.isPendingUpload)
            assertEquals(emptyList(), it.pendingUploadPaths())
            assertEquals(emptyList(), it.pendingUploadRows().map { r -> r.path })
            assertNotNull(it.getEntryByRemoteId("local:report.txt"))
            assertEquals(true, it.setStatusTrashed("local:report.txt"), "a cloud key is trashed like any other")
        }
    }

    @Test
    fun `a database with local-prefixed pending rows migrates to the pending column on first open`() {
        val minted = writeSchema4File()

        val db = StateDatabase(file).also { it.initialize() }
        db.use {
            assertEquals(StateDatabase.SCHEMA_VERSION.toString(), version())
            assertTrue("pending_upload" in columns())
            assertEquals(listOf("/pending.txt"), it.pendingUploadPaths(), "the minted key became a pending row")
            assertNull(it.getEntry("/pending.txt")?.remoteId)
            assertEquals(minted, it.pendingRowDetails().single().remoteId, "the key stays the row's primary key")
            assertEquals("cloud-1", it.getEntry("/synced.txt")?.remoteId)
            assertEquals("local:report.txt", it.getEntry("/report.txt")?.remoteId, "a key that only starts with local: is a cloud key")
        }
    }

    @Test
    fun `a crash mid-migration leaves the old schema intact and the next open completes it`() {
        writeSchema4File()
        // The last statement of the migration, the version stamp, fails: everything before it must be undone.
        raw { c ->
            c.createStatement().use { s ->
                s.executeUpdate(
                    "CREATE TRIGGER fail_stamp BEFORE INSERT ON sync_state WHEN NEW.key='schema_version' " +
                        "BEGIN SELECT RAISE(ABORT, 'simulated crash'); END",
                )
            }
        }

        assertFailsWith<Exception> { StateDatabase(file).initialize() }

        assertFalse("pending_upload" in columns(), "the column went with the rolled back transaction")
        assertEquals("4", version(), "the old stamp is untouched")
        raw { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT COUNT(*) FROM sync_entries WHERE remote_id LIKE 'local:%'").use { rs ->
                    rs.next()
                    assertEquals(2, rs.getInt(1), "the rows are as they were")
                }
                s.executeUpdate("DROP TRIGGER fail_stamp")
            }
        }

        val db = StateDatabase(file).also { it.initialize() }
        db.use {
            assertEquals(StateDatabase.SCHEMA_VERSION.toString(), version())
            assertEquals(listOf("/pending.txt"), it.pendingUploadPaths())
            assertEquals("local:report.txt", it.getEntry("/report.txt")?.remoteId)
        }
    }

    @Test
    fun `a pending upload created after the migration is pending by the column and lands normally`() {
        writeSchema4File()
        val db = StateDatabase(file).also { it.initialize() }
        db.use {
            it.upsertEntry(row("/new.txt", null))
            assertEquals(listOf("/new.txt", "/pending.txt"), it.pendingUploadPaths())
            assertTrue(assertNotNull(it.getEntry("/new.txt")).isPendingUpload)
            assertEquals(1, raw { c -> c.createStatement().use { s -> s.executeQuery("SELECT pending_upload FROM sync_entries WHERE path='/new.txt'").use { rs -> rs.next(); rs.getInt(1) } } })

            // The upload lands: the same path takes a cloud id, the pending row is replaced, not duplicated.
            it.upsertEntry(row("/new.txt", "cloud-2"))
            assertEquals(listOf("/pending.txt"), it.pendingUploadPaths())
            val landed = assertNotNull(it.getEntry("/new.txt"))
            assertEquals("cloud-2", landed.remoteId)
            assertFalse(landed.isPendingUpload)
            assertEquals(1, it.getAllEntries().count { e -> e.path == "/new.txt" })
        }
        Files.exists(file)
    }

    @Test
    fun `a freshly created state db has the same tables, columns and indexes as a migrated one`() {
        fun names(s: java.sql.Statement, sql: String): List<String> =
            s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }

        fun shape(f: Path): List<String> =
            DriverManager.getConnection("jdbc:sqlite:$f").use { c ->
                c.createStatement().use { s ->
                    val tables = names(s, "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
                    val columns =
                        tables.flatMap { t ->
                            names(s, "SELECT '$t.' || name || ' ' || type || ' ' || \"notnull\" || ' ' || ifnull(dflt_value,'') FROM pragma_table_info('$t') ORDER BY name")
                        }
                    tables + columns +
                        names(s, "SELECT 'index ' || name || ' ' || ifnull(sql,'') FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%' ORDER BY name")
                }
            }
        val fresh = dir.resolve("fresh.db")
        StateDatabase(fresh).also { it.initialize() }.close()
        writeSchema4File()
        StateDatabase(file).also { it.initialize() }.close()

        assertEquals(shape(fresh), shape(file))
        assertTrue(shape(fresh).any { it.equals("sync_entries.pending_upload integer 1 0", ignoreCase = true) }, "the column is NOT NULL DEFAULT 0 on a fresh file")
        assertTrue(shape(fresh).any { it.startsWith("index idx_sync_entries_pending_upload") })
    }

    private inline fun <T> StateDatabase.use(body: (StateDatabase) -> T): T =
        try {
            body(this)
        } finally {
            close()
        }
}
