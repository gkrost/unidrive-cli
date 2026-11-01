package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.RenameIntent
import org.krost.unidrive.sync.model.ReplaceIntent
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#311: the mount records a replace-rename before it parks the old destination and clears the record once the
 * replace is finished or undone. The record outlives the process, one per parking path, and a state.db written before the table
 * existed keeps its rows and records and gains the table when it is opened.
 */
class StateDatabaseReplaceIntentTest {
    private fun newFile(): Path = Files.createTempDirectory("ud-replace-intent-db").resolve("state.db")

    private fun open(path: Path = newFile()) = StateDatabase(path).also { it.initialize() }

    private val replace =
        ReplaceIntent(
            sourcePath = "/a.txt",
            destPath = "/b.txt",
            parkingPath = "/b.txt.unidrive-replaced",
            sourceRemoteId = "id-a",
            destRemoteId = "id-b",
        )

    @Test
    fun `a recorded replace is read back after a reopen until it is cleared`() {
        val file = newFile()
        val local = replace.copy(destPath = "/c.txt", parkingPath = "/c.txt.unidrive-replaced", sourceRemoteId = null, destRemoteId = null)
        val first = open(file)
        first.recordReplaceIntent(replace)
        first.recordReplaceIntent(local)
        first.close()

        val reopened = open(file)
        try {
            assertEquals(setOf(replace, local), reopened.replaceIntents().toSet())
            reopened.clearReplaceIntent(replace.parkingPath)
            assertEquals(listOf(local), reopened.replaceIntents())
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `a state db from before the table keeps its rows and gains the table when opened`() {
        val file = newFile()
        val old = open(file)
        old.upsertEntry(
            SyncEntry(
                path = "/b.txt",
                remoteId = "id-b",
                remoteHash = null,
                remoteSize = 3L,
                remoteModified = Instant.parse("2026-11-01T09:00:00Z"),
                localMtime = null,
                localSize = null,
                isFolder = false,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.parse("2026-11-01T09:00:00Z"),
            ),
        )
        old.recordRenameIntent(RenameIntent("/x.txt", "/y.txt", remoteId = "id-x", ghost = false))
        old.close()
        DriverManager.getConnection("jdbc:sqlite:$file").use { conn -> conn.createStatement().use { it.executeUpdate("DROP TABLE replace_intents") } }

        val db = open(file)
        try {
            assertEquals("id-b", db.getEntry("/b.txt")?.remoteId, "the rows of the older file are still there")
            assertEquals(listOf(RenameIntent("/x.txt", "/y.txt", "id-x", false)), db.renameIntents(), "and so are its rename records")
            assertTrue(db.replaceIntents().isEmpty(), "an older file holds no replace record")
            db.recordReplaceIntent(replace)
            assertEquals(listOf(replace), db.replaceIntents())
        } finally {
            db.close()
        }
    }
}
