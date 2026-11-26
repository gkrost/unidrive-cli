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
            assertEquals("/pending.txt", it.pendingRowDetails().single().path)
            assertEquals(minted, storedKey("/pending.txt"), "the key stays the row's primary key")
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

    private fun storedKey(path: String): String? =
        raw { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT remote_id FROM sync_entries WHERE path='$path'").use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    private fun fresh(body: (StateDatabase) -> Unit) = StateDatabase(file).also { it.initialize() }.use(body)

    @Test
    fun `a minted pending key finds no row by remote id, and a local-prefixed cloud key does`() =
        fresh { db ->
            db.upsertEntry(row("/pending.txt", null))
            db.upsertEntry(row("/report.txt", "local:report.txt"))
            val minted = assertNotNull(storedKey("/pending.txt"))

            assertNull(db.getEntryByRemoteId(minted))
            assertNull(db.recovery.getEntryByRemoteIdAnyStatus(minted))
            assertEquals(emptyMap(), db.getEntriesByRemoteIds(listOf(minted)))

            assertEquals("/report.txt", db.getEntryByRemoteId("local:report.txt")?.path)
            assertEquals("/report.txt", db.recovery.getEntryByRemoteIdAnyStatus("local:report.txt")?.path)
            assertEquals(setOf("local:report.txt"), db.getEntriesByRemoteIds(listOf(minted, "local:report.txt")).keys)
        }

    @Test
    fun `the remote path rows and the recently deleted ids never carry a minted key`() =
        fresh { db ->
            db.upsertEntry(row("/pending.txt", null).copy(remotePath = "/Server/pending.txt"))
            db.upsertEntry(row("/cloud.txt", "c1").copy(remotePath = "/Server/cloud.txt"))
            assertEquals(listOf("c1"), db.rowsWithRemotePath().map { it.third })

            raw { c ->
                c.createStatement().use { s ->
                    s.executeUpdate("UPDATE sync_entries SET status='DELETED', mount_deleted_at='2999-01-01T00:00:00Z'")
                }
            }
            assertEquals(setOf("c1"), db.recovery.recentlyDeletedRemoteIds(Instant.parse("2000-01-01T00:00:00Z")))
        }

    @Test
    fun `deleting a pending row or a folder of them leaves no tombstone and tombstones the cloud rows`() =
        fresh { db ->
            db.upsertEntry(row("/gone.txt", null))
            db.markDeleted("/gone.txt", mountDelete = true)
            assertNull(db.statusOf("/gone.txt"), "a row that never reached the cloud has nothing to tombstone")

            db.upsertEntry(row("/dir/p.txt", null))
            db.upsertEntry(row("/dir/c.txt", "cloud-3"))
            assertEquals(2, db.markDescendantsDeleted("/dir", mountDelete = true))
            assertNull(db.statusOf("/dir/p.txt"))
            assertEquals(org.krost.unidrive.sync.model.EntryStatus.DELETED, db.statusOf("/dir/c.txt"))
            assertEquals(0, db.pendingUploadPaths().size)
            assertEquals(0, db.getAllEntries().size, "the alive count follows")
            assertEquals(
                0,
                raw { c -> c.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM sync_entries WHERE pending_upload=1").use { rs -> rs.next(); rs.getInt(1) } } },
                "no unpurgeable tombstone",
            )
        }

    @Test
    fun `a kept row below a deleted folder keeps its key and its marks`() =
        fresh { db ->
            db.upsertEntry(row("/dir/keep.txt", null))
            db.upsertEntry(row("/dir/other.txt", "cloud-4"))
            db.markNeverSubmitted("/dir/keep.txt")
            db.markUploadRefused("/dir/keep.txt", "100|200|x")
            raw { c -> c.createStatement().use { s -> s.executeUpdate("UPDATE sync_entries SET name_block='similar|x' WHERE path='/dir/keep.txt'") } }
            val before = storedKey("/dir/keep.txt")

            db.markDescendantsDeleted("/dir", mountDelete = true, keep = listOf("/dir/keep.txt"))

            assertEquals(before, storedKey("/dir/keep.txt"), "no second key is minted")
            assertEquals(1, raw { c -> c.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM sync_entries WHERE path='/dir/keep.txt'").use { rs -> rs.next(); rs.getInt(1) } } })
            assertEquals(true, db.neverSubmitted("/dir/keep.txt"))
            assertEquals("100|200|x", db.uploadRefusal("/dir/keep.txt"))
            assertNotNull(db.nameBlock("/dir/keep.txt"))
            assertEquals(listOf("/dir/keep.txt"), db.pendingUploadPaths())
            assertEquals(org.krost.unidrive.sync.model.EntryStatus.DELETED, db.statusOf("/dir/other.txt"))
        }

    @Test
    fun `a guarded flip never takes a pending row and does take a local-prefixed cloud row`() =
        fresh { db ->
            db.upsertEntry(row("/p.txt", null))
            db.upsertEntry(row("/report.txt", "local:report.txt"))
            val rows = mapOf("/p.txt" to assertNotNull(db.getEntry("/p.txt")), "/report.txt" to assertNotNull(db.getEntry("/report.txt")))

            assertEquals(listOf("/report.txt"), db.markDeletedChunked(listOf("/p.txt", "/report.txt"), expectedRows = rows))
            assertNotNull(db.getEntry("/p.txt"))
        }

    @Test
    fun `a schema 4 read of the pending column reads a local-prefixed cloud key as 0 and a minted key as 1`() {
        writeSchema4File()
        val read =
            raw { c ->
                c.createStatement().use { s ->
                    s.executeQuery("SELECT path, ${PendingUploadKeys.SCHEMA4_FALLBACK_SQL} FROM sync_entries ORDER BY path").use { rs ->
                        buildMap { while (rs.next()) put(rs.getString(1), rs.getInt(2)) }
                    }
                }
            }
        assertEquals(mapOf("/pending.txt" to 1, "/report.txt" to 0, "/synced.txt" to 0), read)
    }

    @Test
    fun `the schema version is stamped once by a migration and not at all by the next open`() {
        writeSchema4File()
        raw { c ->
            c.createStatement().use { s ->
                s.executeUpdate("CREATE TABLE stamp_log (n INTEGER PRIMARY KEY AUTOINCREMENT)")
                s.executeUpdate(
                    "CREATE TRIGGER log_stamp AFTER INSERT ON sync_state WHEN NEW.key='schema_version' BEGIN INSERT INTO stamp_log (n) VALUES (NULL); END",
                )
            }
        }
        fun stamps() = raw { c -> c.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM stamp_log").use { rs -> rs.next(); rs.getInt(1) } } }

        StateDatabase(file).also { it.initialize() }.close()
        assertEquals(1, stamps(), "the migration stamps, and nothing stamps again")
        StateDatabase(file).also { it.initialize() }.close()
        assertEquals(1, stamps(), "an open of a current file writes no stamp")
    }

    @Test
    fun `a cloud id equal to a pending row's minted key is refused and the pending row stays`() =
        fresh { db ->
            db.upsertEntry(row("/a/new.txt", null))
            val minted = assertNotNull(storedKey("/a/new.txt"))

            db.upsertEntry(row("/other.txt", minted))

            assertEquals(listOf("/a/new.txt"), db.pendingUploadPaths(), "the pending row is not overwritten")
            assertNull(db.getEntry("/other.txt"), "the colliding cloud item is not tracked under that key")
            assertEquals(minted, storedKey("/a/new.txt"))
        }

    @Test
    fun `quarantine and content id writes by remote id never touch a pending row`() =
        fresh { db ->
            db.upsertEntry(row("/p.txt", null))
            val minted = assertNotNull(storedKey("/p.txt"))
            raw { c -> c.createStatement().use { s -> s.executeUpdate("UPDATE sync_entries SET last_error_at='2026-01-01T00:00:00Z', download_quarantined=1 WHERE path='/p.txt'") } }

            assertFalse(db.clearDownloadQuarantine(minted))
            assertFalse(db.setDownloadQuarantine(minted, Instant.parse("2026-02-02T00:00:00Z")))
            assertFalse(db.fillContentId(row("/p.txt", minted), "obj|3"))

            val state = raw { c -> c.createStatement().use { s -> s.executeQuery("SELECT last_error_at, remote_content_id FROM sync_entries WHERE path='/p.txt'").use { rs -> rs.next(); rs.getString(1) to rs.getString(2) } } }
            assertEquals("2026-01-01T00:00:00Z" to null, state, "the failed-upload stamp survives and no content id is written")
        }

    @Test
    fun `the re-key warning never calls the minted key of a pending upload an incoming remote id`() {
        val minted = "local:6f1c0d1e-0000-4000-8000-000000000001"
        val pendingEvictsCloud = rekeyWarning("/a.txt", null, "cloud-1")
        assertFalse("incoming remote_id" in pendingEvictsCloud, pendingEvictsCloud)
        assertFalse(minted in pendingEvictsCloud)
        assertTrue("pending upload" in pendingEvictsCloud, pendingEvictsCloud)
        assertTrue("incoming remote_id=cloud-2" in rekeyWarning("/a.txt", "cloud-2", "cloud-1"))
    }

    private inline fun <T> StateDatabase.use(body: (StateDatabase) -> T): T =
        try {
            body(this)
        } finally {
            close()
        }
}
