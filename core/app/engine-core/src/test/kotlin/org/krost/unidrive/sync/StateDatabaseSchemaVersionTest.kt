package org.krost.unidrive.sync

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#269 (the schema part): eleven columns of `sync_entries` were added while the file stayed at schema 2,
 * so a build that knows schema 2 but not those columns opened a file a newer build wrote without complaint, and each of its
 * `INSERT OR REPLACE` writes put back only the columns it knew: `edit_base` (an unsynced cache edit), `remote_content_id`,
 * `upload_refused`, `name_block` and the rest went back to NULL. A file this build writes must be refused by every build
 * that knows schema 2 only, and a schema-2 file must still migrate forward with its rows.
 */
class StateDatabaseSchemaVersionTest {
    // The schema the eleven columns were added under: the newest a build before this change knows.
    private val schemaBeforeTheColumns = 2

    private fun stampedVersion(file: Path): Int? {
        val sql = "SELECT value FROM sync_state WHERE key='${StateDatabase.SCHEMA_VERSION_KEY}'"
        val c = DriverManager.getConnection("jdbc:sqlite:$file")
        try {
            val rs = c.createStatement().executeQuery(sql)
            return if (rs.next()) rs.getString(1).toIntOrNull() else null
        } finally {
            c.close()
        }
    }

    // The guard every build runs at open (StateDatabase.bootstrapSchema), for a build that knows [supported].
    private fun openedBy(
        supported: Int,
        recorded: Int,
    ) = check(recorded <= supported) { "state.db was written by a newer unidrive (schema $recorded, this build supports $supported)" }

    @Test
    fun `a file this build writes is refused by a build that knows schema 2 only`() {
        val file = Files.createTempDirectory("unidrive-schema").resolve("state.db")
        StateDatabase(file).also { it.initialize() }.close()

        val recorded = assertNotNull(stampedVersion(file))
        assertEquals(StateDatabase.SCHEMA_VERSION, recorded)
        assertTrue(recorded > schemaBeforeTheColumns, "a file with the eleven columns is stamped above schema $schemaBeforeTheColumns: $recorded")
        assertFailsWith<IllegalStateException> { openedBy(schemaBeforeTheColumns, recorded) }
    }

    @Test
    fun `a schema 2 file without the added columns migrates forward with its rows and is stamped with this schema`() {
        val file = Files.createTempDirectory("unidrive-schema").resolve("state.db")
        DriverManager.getConnection("jdbc:sqlite:$file").use { conn ->
            conn.createStatement().use { stmt ->
                // The shape of sync_entries at schema 2 before any of the eleven columns.
                stmt.executeUpdate(
                    """
                    CREATE TABLE sync_entries (
                        remote_id       TEXT PRIMARY KEY,
                        parent_uuid     TEXT,
                        path            TEXT NOT NULL,
                        remote_hash     TEXT,
                        remote_size     INTEGER NOT NULL DEFAULT 0,
                        remote_modified TEXT,
                        local_mtime     INTEGER,
                        local_size      INTEGER,
                        is_folder       INTEGER NOT NULL DEFAULT 0,
                        is_pinned       INTEGER NOT NULL DEFAULT 0,
                        is_hydrated     INTEGER NOT NULL DEFAULT 0,
                        last_synced     TEXT NOT NULL,
                        status          TEXT NOT NULL DEFAULT 'EXISTS'
                                        CHECK (status IN ('EXISTS','TRASHED','DELETED'))
                    )
                    """,
                )
                stmt.executeUpdate("CREATE VIEW alive_entries AS SELECT * FROM sync_entries WHERE status='EXISTS'")
                stmt.executeUpdate("CREATE TABLE sync_state (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                stmt.executeUpdate("INSERT INTO sync_state VALUES ('${StateDatabase.SCHEMA_VERSION_KEY}', '$schemaBeforeTheColumns')")
                stmt.executeUpdate("INSERT INTO sync_state VALUES ('delta_cursor', 'cursor-1')")
                stmt.executeUpdate(
                    "INSERT INTO sync_entries (remote_id, path, remote_hash, remote_size, is_hydrated, last_synced) " +
                        "VALUES ('u-1', '/Docs/report.pdf', 'h1', 7, 1, '2026-01-01T00:00:00Z')",
                )
            }
        }

        val upgraded = StateDatabase(file)
        upgraded.initialize()
        try {
            val row = assertNotNull(upgraded.getEntry("/Docs/report.pdf"), "the row survives the migration")
            assertEquals("u-1", row.remoteId)
            assertEquals(7L, row.remoteSize)
            assertTrue(row.isHydrated)
            assertNull(row.editBase, "an added column reads as its default")
            assertNull(row.remoteContentId)
            assertEquals("cursor-1", upgraded.getSyncState("delta_cursor"), "the sync state survives the migration")
            upgraded.upsertEntry(row.copy(editBase = "base-1", remoteContentId = "obj|7"))
            assertEquals("base-1", upgraded.getEntry("/Docs/report.pdf")?.editBase)
            assertEquals("obj|7", upgraded.getEntry("/Docs/report.pdf")?.remoteContentId)
        } finally {
            upgraded.close()
        }
        assertEquals(StateDatabase.SCHEMA_VERSION, stampedVersion(file), "the migrated file carries this schema")
        assertTrue(StateDatabase.SCHEMA_VERSION > schemaBeforeTheColumns)
    }

    @Test
    fun `a file of a newer schema is refused before anything is written, read-only too`() {
        val file = Files.createTempDirectory("unidrive-schema").resolve("state.db")
        StateDatabase(file).also { it.initialize() }.close()
        val newer = StateDatabase.SCHEMA_VERSION + 1
        DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            c.createStatement().use { it.executeUpdate("UPDATE sync_state SET value='$newer' WHERE key='${StateDatabase.SCHEMA_VERSION_KEY}'") }
        }

        val writable = assertFailsWith<IllegalStateException> { StateDatabase(file).initialize() }
        assertTrue("newer unidrive" in writable.message.orEmpty(), writable.message)
        assertFailsWith<IllegalStateException> { StateDatabase(file, readOnly = true).initialize() }
        assertEquals(newer, stampedVersion(file), "the refusal does not stamp the version down")
    }
}
