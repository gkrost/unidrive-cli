package org.krost.unidrive.cli

import org.krost.unidrive.io.OwnerOnly
import org.krost.unidrive.io.grantProblem
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
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
 * `sync --reset` and `refresh --reset` clear what a profile knows about its files (#100): they ask first unless `--yes`
 * is given, refuse when nobody can be asked, and copy state.db aside before anything is cleared. The refusals run the
 * real command through Main against a localfs profile in a temp folder and stop before a sync or a daemon starts; the
 * copy is checked on [StateReset.backup] itself, since a reset that goes ahead runs a whole sync.
 */
class ResetConfirmationTest {
    private lateinit var dir: Path
    private lateinit var dbPath: Path
    private val defaultConsole = StateReset.console

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("reset-confirmation")
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
        dbPath = Files.createDirectories(dir.resolve("test")).resolve("state.db")
    }

    @AfterTest
    fun tearDown() {
        StateReset.console = defaultConsole
        dir.toFile().deleteRecursively()
    }

    private fun entry(path: String) =
        SyncEntry(
            path = path,
            remoteId = "r$path",
            remoteHash = null,
            remoteSize = 1,
            remoteModified = null,
            localMtime = null,
            localSize = null,
            isFolder = false,
            isPinned = false,
            isHydrated = false,
            lastSynced = Instant.now(),
        )

    private fun seedState() {
        StateDatabase(dbPath).also { db ->
            db.initialize()
            try {
                db.upsertEntry(entry("/a.txt"))
            } finally {
                db.close()
            }
        }
    }

    private fun stateStillHoldsTheRow() {
        val db = StateDatabase(dbPath, readOnly = true)
        db.initialize()
        try {
            assertNotNull(db.getEntry("/a.txt"), "a refused reset must not have cleared the state")
        } finally {
            db.close()
        }
    }

    private fun backupsIn(folder: Path): List<String> =
        Files.list(folder).use { s -> s.map { it.fileName.toString() }.filter { it.startsWith("state-before-reset-") }.toList() }

    private class Run(val exit: Int, val out: String, val err: String)

    private fun run(vararg args: String): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        val exit =
            try {
                System.setOut(PrintStream(out, true, Charsets.UTF_8))
                System.setErr(PrintStream(err, true, Charsets.UTF_8))
                CommandLine(Main()).execute("-c", dir.toString(), "-p", "test", *args)
            } finally {
                System.setOut(originalOut)
                System.setErr(originalErr)
            }
        return Run(exit, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    // The test JVM has no terminal, so nobody can be asked: the reset is refused, not taken as a yes.
    @Test
    fun `sync --reset without a terminal and without --yes is refused and clears nothing`() {
        seedState()

        val result = run("sync", "--reset")

        assertEquals(1, result.exit, result.err)
        assertTrue("--yes" in result.err, "the refusal names the way out for a script: ${result.err}")
        assertTrue("Nothing was reset" in result.err, result.err)
        stateStillHoldsTheRow()
        assertEquals(emptyList(), backupsIn(dbPath.parent), "nothing to copy aside when nothing is reset")
    }

    @Test
    fun `sync --reset declined at the prompt clears nothing`() {
        seedState()
        val questions = mutableListOf<String>()
        StateReset.console = {
            { question ->
                questions += question
                false
            }
        }

        val result = run("sync", "--reset")

        assertEquals(1, result.exit, result.err)
        assertEquals(1, questions.size, "one question: $questions")
        assertTrue("'test'" in questions.single(), "the question names the profile: ${questions.single()}")
        assertTrue("copied aside" in questions.single(), "the question says the state is kept: ${questions.single()}")
        assertTrue("Cancelled" in result.err, result.err)
        stateStillHoldsTheRow()
        assertEquals(emptyList(), backupsIn(dbPath.parent))
    }

    // Refused before DaemonAutospawn: a refused reset must not start a daemon either.
    @Test
    fun `refresh --reset without a terminal and without --yes is refused before a daemon is started`() {
        seedState()

        val result = run("refresh", "--reset")

        assertEquals(1, result.exit, result.err)
        assertTrue("--yes" in result.err, result.err)
        assertFalse("starting it" in result.err, "no daemon may be started for a refused reset: ${result.err}")
        stateStillHoldsTheRow()
        assertEquals(emptyList(), backupsIn(dbPath.parent))
    }

    @Test
    fun `refresh --reset declined at the prompt is refused before a daemon is started`() {
        seedState()
        StateReset.console = { { false } }

        val result = run("refresh", "--reset")

        assertEquals(1, result.exit, result.err)
        assertTrue("Cancelled" in result.err, result.err)
        assertFalse("starting it" in result.err, result.err)
        assertEquals(emptyList(), backupsIn(dbPath.parent))
    }

    @Test
    fun `--yes skips the question`() {
        StateReset.console = { error("--yes must not ask") }

        assertNull(StateReset.refusal("sync", "test", yes = true))
    }

    @Test
    fun `a yes at the prompt lets the reset go ahead`() {
        StateReset.console = { { true } }

        assertNull(StateReset.refusal("refresh", "test", yes = false))
    }

    // The copy goes through SQLite, not the file system: a row only the WAL holds (the writer is still open, nothing is
    // checkpointed) is in it, and it is one file that opens on its own.
    @Test
    fun `the backup next to state db holds what only the WAL held`() {
        val writer = StateDatabase(dbPath)
        writer.initialize()
        try {
            writer.upsertEntry(entry("/in-wal.txt"))
            assertTrue(Files.exists(dbPath.resolveSibling("state.db-wal")), "precondition: the writer is in WAL mode")

            val backup = StateReset.backup(dbPath, Instant.parse("2026-10-28T10:15:00Z"))

            assertEquals(dbPath.resolveSibling("state-before-reset-20261028T101500Z.db"), backup)
            val copy = StateDatabase(backup!!, readOnly = true)
            copy.initialize()
            try {
                assertNotNull(copy.getEntry("/in-wal.txt"), "the copy holds the row the WAL held")
            } finally {
                copy.close()
            }
        } finally {
            writer.close()
        }
    }

    @Test
    fun `two resets within the same second keep both copies`() {
        seedState()
        val now = Instant.parse("2026-10-28T10:15:00Z")

        val first = StateReset.backup(dbPath, now)
        val second = StateReset.backup(dbPath, now)

        assertEquals("state-before-reset-20261028T101500Z.db", first!!.fileName.toString())
        assertEquals("state-before-reset-20261028T101500Z-2.db", second!!.fileName.toString())
    }

    @Test
    fun `a state db that cannot be copied throws and leaves no partial copy`() {
        Files.writeString(dbPath, "not a database")

        assertFailsWith<IOException> { StateReset.backup(dbPath, Instant.now()) }
        assertEquals(emptyList(), backupsIn(dbPath.parent))
    }

    // --yes goes past the question to the copy; a copy that fails stops the run before anything is cleared (and before
    // the sync proper starts).
    @Test
    fun `sync --reset --yes whose copy fails resets nothing`() {
        Files.writeString(dbPath, "not a database")
        StateReset.console = { error("--yes must not ask") }

        val result = run("sync", "--reset", "--yes")

        assertEquals(1, result.exit, result.err)
        assertTrue("could not be copied aside" in result.err, result.err)
        assertTrue("nothing was reset" in result.err, result.err)
        assertEquals("not a database", Files.readString(dbPath), "state.db is left as it was")
        assertEquals(emptyList(), backupsIn(dbPath.parent))
    }

    @Test
    fun `a profile without state db has nothing to copy`() {
        assertNull(StateReset.backup(dbPath, Instant.now()))
        assertEquals(emptyList(), backupsIn(dbPath.parent))
    }

    // The copy lists every path of the drive, like state.db itself.
    @Test
    fun `the backup is the current user's only`() {
        seedState()

        val backup = StateReset.backup(dbPath, Instant.now())!!

        assertNull(OwnerOnly.grantProblem(backup), "the copy of state.db must be owner-only")
    }

    @Test
    fun `sync and refresh help say --reset asks first, name --yes and the copy`() {
        for (name in listOf("sync", "refresh")) {
            val out = StringWriter()
            CommandLine(Main()).subcommands[name]!!.usage(PrintWriter(out), CommandLine.Help.Ansi.OFF)
            val help = out.toString()
            assertTrue("--yes" in help, "$name help names --yes:\n$help")
            assertTrue("state-before-reset-" in help, "$name help names the copy:\n$help")
        }
    }
}
