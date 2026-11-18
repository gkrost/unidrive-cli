package org.krost.unidrive.cli

import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #768 (a): the daemon's engine has a conflict log, so the loser of a last-writer-wins conflict is always backed up. It
 * writes where `unidrive sync` and `unidrive conflicts` keep it: the journal in the profile's config folder, the backups
 * under the profile's data folder.
 */
class DaemonConflictLogTest {
    @Test
    fun `the daemon's conflict log journals in the profile folder and backs the loser up under the data folder`() {
        val configDir = TestScratch.directory("ud-768-config")
        val dataRoot = TestScratch.directory("ud-768-data")
        val loser = TestScratch.directory("ud-768-src").resolve("doc.txt")
        Files.writeString(loser, "losing bytes")

        val entry = daemonConflictLog("work", configDir, dataRoot.resolve("work")).record("/doc.txt", "MODIFIED", "MODIFIED", "LAST_WRITER_WINS", loser)

        assertTrue(Files.readString(configDir.resolve("conflicts.jsonl")).contains("\"/doc.txt\""))
        val backup = dataRoot.resolve("work").resolve("conflict-backups").resolve(entry.backupFile!!)
        assertEquals("losing bytes", Files.readString(backup))
    }
}
