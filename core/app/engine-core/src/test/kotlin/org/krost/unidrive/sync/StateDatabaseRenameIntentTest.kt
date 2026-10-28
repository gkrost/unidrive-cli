package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.RenameIntent
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * archive#1040 M4: the mount records a rename before its cloud move and clears the record once the row and its cache copy are at
 * the new path. The record outlives the process (a daemon that stopped half way finds it at its next start), one per old path,
 * and a state.db written before the table existed gains it when it is opened.
 */
class StateDatabaseRenameIntentTest {
    private fun open(path: Path = Files.createTempDirectory("ud-rename-intent-db").resolve("state.db")) = StateDatabase(path).also { it.initialize() }

    @Test
    fun `a recorded rename is read back after a reopen until it is cleared`() {
        val file = Files.createTempDirectory("ud-rename-intent-db").resolve("state.db")
        val first = open(file)
        first.recordRenameIntent(RenameIntent("/a.txt", "/b.txt", remoteId = "id-a", ghost = false))
        first.recordRenameIntent(RenameIntent("/g.txt", "/h.txt", remoteId = "ghost-id", ghost = true))
        first.close()

        val reopened = open(file)
        try {
            assertEquals(
                setOf(RenameIntent("/a.txt", "/b.txt", "id-a", false), RenameIntent("/g.txt", "/h.txt", "ghost-id", true)),
                reopened.renameIntents().toSet(),
            )
            reopened.clearRenameIntent("/a.txt")
            assertEquals(listOf(RenameIntent("/g.txt", "/h.txt", "ghost-id", true)), reopened.renameIntents())
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `a second record for the same old path replaces the first`() {
        val db = open()
        try {
            db.recordRenameIntent(RenameIntent("/a.txt", "/b.txt", remoteId = "id-a", ghost = false))
            db.recordRenameIntent(RenameIntent("/a.txt", "/c.txt", remoteId = "id-a", ghost = false))

            assertEquals(listOf(RenameIntent("/a.txt", "/c.txt", "id-a", false)), db.renameIntents())
        } finally {
            db.close()
        }
    }

    @Test
    fun `a state db from before the table gains it when opened`() {
        val file = Files.createTempDirectory("ud-rename-intent-db").resolve("state.db")
        open(file).close()
        DriverManager.getConnection("jdbc:sqlite:$file").use { conn -> conn.createStatement().use { it.executeUpdate("DROP TABLE rename_intents") } }

        val db = open(file)
        try {
            assertTrue(db.renameIntents().isEmpty())
            db.recordRenameIntent(RenameIntent("/a.txt", "/b.txt", remoteId = "id-a", ghost = false))
            assertEquals(1, db.renameIntents().size)
        } finally {
            db.close()
        }
    }
}
