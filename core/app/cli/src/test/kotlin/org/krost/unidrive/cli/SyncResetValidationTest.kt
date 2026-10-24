package org.krost.unidrive.cli

import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** `sync --reset` clears the state; an option it will refuse anyway must be refused first (archive#1085). */
class SyncResetValidationTest {
    @Test
    fun `a bad streaming-reconciliation value is refused before --reset clears the state`() {
        val dir = Files.createTempDirectory("sync-reset-validation")
        try {
            val syncRoot = Files.createDirectories(dir.resolve("local"))
            val remote = Files.createDirectories(dir.resolve("remote"))
            Files.writeString(
                dir.resolve("config.toml"),
                """
                [providers.test]
                type = "localfs"
                mode = "mirror"
                sync_root = "${syncRoot.toString().replace('\\', '/')}"
                root_path = "${remote.toString().replace('\\', '/')}"
                """.trimIndent() + "\n",
            )
            val dbPath = Files.createDirectories(dir.resolve("test")).resolve("state.db")
            StateDatabase(dbPath).also { db ->
                db.initialize()
                try {
                    db.upsertEntry(
                        SyncEntry(
                            path = "/a.txt",
                            remoteId = "r-a",
                            remoteHash = null,
                            remoteSize = 1,
                            remoteModified = null,
                            localMtime = null,
                            localSize = null,
                            isFolder = false,
                            isPinned = false,
                            isHydrated = false,
                            lastSynced = Instant.now(),
                        ),
                    )
                } finally {
                    db.close()
                }
            }

            val err = ByteArrayOutputStream()
            val originalErr = System.err
            val exit =
                try {
                    System.setErr(PrintStream(err, true, Charsets.UTF_8))
                    CommandLine(Main()).execute("-c", dir.toString(), "-p", "test", "sync", "--reset", "--streaming-reconciliation=bogus")
                } finally {
                    System.setErr(originalErr)
                }

            assertEquals(2, exit, err.toString(Charsets.UTF_8))
            assertTrue("--streaming-reconciliation must be one of" in err.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
            val db = StateDatabase(dbPath)
            db.initialize()
            try {
                assertNotNull(db.getEntry("/a.txt"), "the refused run must not have cleared the state")
            } finally {
                db.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
