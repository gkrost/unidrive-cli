package org.krost.unidrive.cli

import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #845 (#744-2), owner decision 2026-11-26 "read what it can": `status` against a state.db that only lacks an additive
 * column (here `changed_by`; additive columns do not bump the schema stamp) answers from it, and writes nothing.
 */
class StatusReadsWhatItCanTest {
    private lateinit var configDir: Path
    private lateinit var stateDb: Path
    private val originalOut = System.out
    private lateinit var out: ByteArrayOutputStream

    @BeforeTest
    fun setUp() {
        configDir = TestScratch.directory("ud-status-gap-cfg")
        val syncRoot = TestScratch.directory("ud-status-gap-local")
        val remoteRoot = TestScratch.directory("ud-status-gap-remote")
        Files.writeString(
            configDir.resolve("config.toml"),
            """
            |[providers.localtest]
            |type = "localfs"
            |sync_root = "${syncRoot.toString().replace('\\', '/')}"
            |root_path = "${remoteRoot.toString().replace('\\', '/')}"
            """.trimMargin(),
        )
        stateDb = configDir.resolve("localtest").resolve("state.db")
        Files.createDirectories(stateDb.parent)
        StateDatabase(stateDb).also { db ->
            db.initialize()
            db.upsertEntry(
                SyncEntry(
                    path = "/a.txt",
                    remoteId = "id-a",
                    remoteHash = "h",
                    remoteSize = 3,
                    remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
                    localMtime = null,
                    localSize = null,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = Instant.parse("2026-03-28T12:00:00Z"),
                ),
            )
            db.close()
        }
        DriverManager.getConnection("jdbc:sqlite:$stateDb").use { c ->
            c.createStatement().use { it.executeUpdate("ALTER TABLE sync_entries DROP COLUMN changed_by") }
        }
        out = ByteArrayOutputStream()
        System.setOut(PrintStream(out, true, Charsets.UTF_8))
    }

    @AfterTest
    fun tearDown() {
        System.setOut(originalOut)
    }

    private fun sha() = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(stateDb)).joinToString("") { "%02x".format(it) }

    @Test
    fun `status pending against a missing additive column answers and writes nothing`() {
        val before = sha()
        val exit = CommandLine(Main()).execute("-c", configDir.toString(), "-p", "localtest", "status", "--pending")
        val text = out.toString(Charsets.UTF_8)
        assertEquals(0, exit, text)
        assertFalse(text.contains("older unidrive"), text)
        assertTrue(text.contains("Download: 1 file(s)"), text)
        assertEquals(before, sha(), "state.db untouched")
    }

    @Test
    fun `status of one profile against a state db of an older schema does not crash`() {
        DriverManager.getConnection("jdbc:sqlite:$stateDb").use { c ->
            c.createStatement().use { it.executeUpdate("UPDATE sync_state SET value='3' WHERE key='schema_version'") }
        }
        val before = sha()
        val exit = CommandLine(Main()).execute("-c", configDir.toString(), "-p", "localtest", "status")
        assertEquals(0, exit, out.toString(Charsets.UTF_8))
        assertEquals(before, sha(), "state.db untouched")
    }

    @Test
    fun `status audit against a refused older schema prints the refusal and no gap numbers`() {
        DriverManager.getConnection("jdbc:sqlite:$stateDb").use { c ->
            c.createStatement().use { it.executeUpdate("UPDATE sync_state SET value='3' WHERE key='schema_version'") }
        }
        val originalErr = System.err
        val err = ByteArrayOutputStream()
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        val before = sha()
        val exit =
            try {
                CommandLine(Main()).apply { executionExceptionHandler = cliExecutionExceptionHandler }
                    .execute("-c", configDir.toString(), "-p", "localtest", "status", "--audit")
            } finally {
                System.setErr(originalErr)
            }
        val text = out.toString(Charsets.UTF_8)
        assertTrue(exit != 0, "a refused state.db is not an answer: $text")
        assertTrue(err.toString(Charsets.UTF_8).contains("older unidrive"), "the refusal is printed")
        assertFalse(text.contains("gap", ignoreCase = true) || text.contains("tracked", ignoreCase = true), "no audit numbers: $text")
        assertEquals(before, sha(), "state.db untouched")
    }
}
