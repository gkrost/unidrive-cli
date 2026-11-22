package org.krost.unidrive.cli

import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.ignore.CloudRuleFiles
import org.krost.unidrive.sync.ignore.LocalRuleFiles
import org.krost.unidrive.sync.ignore.RuleFileProbe
import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#775, the CLI half: a venv's own `.gitignore` (`*`) was written through the mount and never uploaded (it
 * ignores itself), then the hydration cache was wiped. The CLI's pending views judge the files below the venv as the daemon
 * does: kept local, not owed to the cloud.
 */
class PendingLostRuleFileTest {
    private lateinit var dir: Path
    private val out = ByteArrayOutputStream()
    private val originalOut = System.out
    private val originalErr = System.err

    @BeforeTest
    fun setUp() {
        dir = TestScratch.directory("pending-lost-rule-file")
        ProfileRemoveHooks.cacheRoot = { dir.resolve("cache") }
        Files.writeString(
            dir.resolve("config.toml"),
            "[general]\ndefault_profile = \"lo\"\n\n[providers.lo]\ntype = \"localfs\"\nmode = \"mount\"\n" +
                "root_path = \"/x\"\nhonor_gitignore = true\n",
        )
        val db = StateDatabase(Files.createDirectories(dir.resolve("lo")).resolve("state.db"))
        try {
            db.initialize()
            listOf("/venv/.gitignore", "/venv/Lib/site.py", "/src/main.py").forEach { db.upsertEntry(keptLocalRow(it)) }
        } finally {
            db.close()
        }
    }

    @AfterTest
    fun tearDown() {
        System.setOut(originalOut)
        System.setErr(originalErr)
        ProfileRemoveHooks.cacheRoot = { SyncEngine.defaultHydrationCacheRoot() }
        dir.toFile().deleteRecursively()
    }

    // What the mount writes for a file written through it and not uploaded yet: no remote id, bytes here.
    private fun keptLocalRow(path: String) =
        SyncEntry(
            path = path, remoteId = null, remoteHash = null, remoteSize = 0L, remoteModified = null,
            localMtime = 1_767_225_600_000L, localSize = 2L, isFolder = false, isPinned = false, isHydrated = true,
            lastSynced = Instant.now(),
        )

    private fun statusPending(): String {
        System.setOut(PrintStream(out, true, Charsets.UTF_8))
        System.setErr(PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8))
        val exit = CommandLine(Main()).execute("-c", dir.toString(), "-p", "lo", "status", "--pending")
        System.setOut(originalOut)
        System.setErr(originalErr)
        assertEquals(0, exit)
        return out.toString(Charsets.UTF_8)
    }

    @Test
    fun `status pending does not list the files a lost local-only rule file kept local`() {
        val text = statusPending()

        assertTrue(text.contains("Upload:   1 file(s)"), "only /src/main.py is owed to the cloud: $text")
    }

    @Test
    fun `the pending gate keeps the files below a lost local-only rule file local`() {
        val split = assertNotNull(PendingGate.split(dir.resolve("lo/state.db"), dir, "lo"))

        assertEquals(listOf("/src/main.py"), split.waiting.map { it.path })
        assertEquals(setOf("/venv/.gitignore", "/venv/Lib/site.py"), split.keptLocal.map { it.path }.toSet())
    }

    @Test
    fun `the rules of the copy the daemon kept apply, without holding the folder back`() {
        // `*.py` does not name the rule file itself: held back, it would be kept local too; read, it awaits its upload.
        val kept = LocalRuleFiles.under(SyncEngine.ignoreRuleFilesRoot(dir.resolve("cache"), "lo"), CloudRuleFiles.DEFAULT_MAX_BYTES)
        kept.keep("/venv/.gitignore", RuleFileProbe.Present(5L, 1L)) { "*.py\n".toByteArray() }

        val split = assertNotNull(PendingGate.split(dir.resolve("lo/state.db"), dir, "lo"))

        assertEquals(setOf("/venv/.gitignore", "/src/main.py"), split.waiting.map { it.path }.toSet())
        assertEquals(listOf("/venv/Lib/site.py"), split.keptLocal.map { it.path })
        assertTrue(statusPending().contains("Upload:   2 file(s)"))
    }

    @Test
    fun `a cache folder without the rule file's copy does not make its row gone`() {
        Files.createDirectories(SyncEngine.hydrationCacheRoot(dir.resolve("cache"), "lo"))

        val split = assertNotNull(PendingGate.split(dir.resolve("lo/state.db"), dir, "lo"))

        assertEquals(listOf("/venv/.gitignore"), split.keptLocal.map { it.path }, "kept as the record that keeps the folder held")
        assertEquals(setOf("/venv/Lib/site.py", "/src/main.py"), split.gone.map { it.path }.toSet())
    }
}
