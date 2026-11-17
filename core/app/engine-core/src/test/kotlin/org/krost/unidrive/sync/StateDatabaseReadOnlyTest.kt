package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.SQLException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import org.krost.unidrive.testing.TestScratch

/**
 * Commands that only report (`status`, `sweep` without --rehydrate) open state.db read-only: opening it
 * must not stamp the schema, rebuild an old-format file, create directories, or write anything else.
 */
class StateDatabaseReadOnlyTest {
    private fun sha256(p: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)).joinToString("") { "%02x".format(it) }

    private fun seededDb(): Path {
        val path = TestScratch.directory("ud-400-db").resolve("state.db")
        StateDatabase(path).also {
            it.initialize()
            it.upsertEntry(
                SyncEntry(
                    path = "/a.txt",
                    remoteId = "id-a",
                    remoteHash = "h",
                    remoteSize = 3,
                    remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
                    localMtime = 1,
                    localSize = 3,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = Instant.parse("2026-03-28T12:00:00Z"),
                ),
            )
            it.setSyncState("last_full_scan", "yesterday")
            it.close()
        }
        return path
    }

    @Test
    fun `a read-only open reads the data and leaves the file byte-identical`() {
        val path = seededDb()
        val before = sha256(path)

        val db = StateDatabase(path, readOnly = true)
        db.initialize()
        assertEquals(listOf("/a.txt"), db.getAllEntries().map { it.path })
        assertEquals("yesterday", db.getSyncState("last_full_scan"))
        db.close()

        assertEquals(before, sha256(path), "reading must not write a single byte")
    }

    @Test
    fun `a normal open of an up-to-date file leaves it byte-identical`() {
        // The premise of the read-only open, updated: the schema stamp used to make every normal
        // open write, so a report command needed mode=ro to keep its hands off state.db. Only a
        // fresh or out-of-date file is stamped now, so an up-to-date file must not change a byte.
        val path = seededDb()
        val before = sha256(path)

        StateDatabase(path).also {
            it.initialize()
            it.close()
        }

        assertEquals(before, sha256(path), "a normal open must not rewrite an up-to-date file")
    }

    @Test
    fun `a read-only database refuses writes`() {
        val path = seededDb()
        val db = StateDatabase(path, readOnly = true)
        db.initialize()
        try {
            assertFailsWith<SQLException> { db.setSyncState("x", "y") }
        } finally {
            db.close()
        }
    }

    @Test
    fun `a read-only open does not create a missing file or its directory`() {
        val dir = TestScratch.directory("ud-400-missing").resolve("nested")
        val path = dir.resolve("state.db")

        val db = StateDatabase(path, readOnly = true)
        assertFailsWith<IllegalStateException> { db.initialize() }

        assertFalse(Files.exists(path))
        assertFalse(Files.exists(dir))
    }

    @Test
    fun `a read-only open refuses an old-format file instead of rebuilding it`() {
        // A pre-redesign file: sync_state without a schema_version row, and a sync_entries table that
        // a normal open would DROP.
        val path = TestScratch.directory("ud-400-old").resolve("state.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            c.createStatement().use {
                it.executeUpdate("CREATE TABLE sync_state (key TEXT PRIMARY KEY, value TEXT)")
                it.executeUpdate("CREATE TABLE sync_entries (path TEXT PRIMARY KEY)")
                it.executeUpdate("INSERT INTO sync_entries VALUES ('/kept')")
            }
        }
        val before = sha256(path)

        val ex = assertFailsWith<IllegalStateException> { StateDatabase(path, readOnly = true).initialize() }

        assertNotNull(ex.message)
        assertEquals(before, sha256(path), "the old-format file must be left alone")
    }
}
