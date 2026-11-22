package org.krost.unidrive.sync.ignore

import org.krost.unidrive.sync.FakeCloudProvider
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#775, as the mount engine wires it: a venv's own `.gitignore` (`*`) was written through the mount and never
 * uploaded (it ignores itself), so the hydration cache held its only copy. With that cache wiped, the row is still there:
 * the venv is held back instead of every file in it being queued for upload.
 */
class MountLostRuleFileTest {
    private val cacheRoot = TestScratch.directory("unidrive-775-cache")
    private lateinit var db: StateDatabase
    private lateinit var engine: SyncEngine

    @BeforeTest
    fun setUp() {
        db = StateDatabase(TestScratch.directory("unidrive-775-db").resolve("state.db"))
        db.initialize()
        engine =
            SyncEngine(
                provider = FakeCloudProvider(),
                db = db,
                syncRoot = TestScratch.directory("unidrive-775-root"),
                cacheRoot = cacheRoot,
                mountIgnore = UploadIgnore.settingsFor(honorGitignore = true, accountRules = emptyList()),
            )
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private val cacheDir get() = SyncEngine.hydrationCacheRoot(cacheRoot, "")

    // What the keep-local branch of the mount's upload writes for a file the rules keep here: no remote id, bytes here.
    private fun keptLocalRow(path: String) =
        SyncEntry(
            path = path, remoteId = null, remoteHash = null, remoteSize = 0L, remoteModified = null,
            localMtime = 1_767_225_600_000L, localSize = 2L, isFolder = false, isPinned = false, isHydrated = true,
            lastSynced = Instant.now(),
        )

    @Test
    fun `a wiped cache copy of a local-only rule file holds its folder back`() {
        db.upsertEntry(keptLocalRow("/venv/.gitignore"))
        val ignore = assertNotNull(engine.uploadIgnore)

        assertTrue(ignore.isIgnored("/venv/Lib/site-packages/x.py", false), "nothing below the venv is uploaded")
        assertEquals(CacheIgnoreSource.LOST, ignore.heldBack().single().reason)
        // Keep-local itself, so the start-up drop of pending rows whose file is gone (#86) leaves the evidence alone.
        assertTrue(ignore.isIgnored("/venv/.gitignore", false), "the lost rule file is held back with its folder")
    }

    @Test
    fun `the rule file's cache copy applies its rules, and nothing is held back`() {
        db.upsertEntry(keptLocalRow("/venv/.gitignore"))
        Files.createDirectories(cacheDir.resolve("venv"))
        Files.writeString(cacheDir.resolve("venv/.gitignore"), "*\n")
        val ignore = assertNotNull(engine.uploadIgnore)

        assertTrue(ignore.isIgnored("/venv/Lib/site-packages/x.py", false), "ignored by the rule itself")
        assertTrue(ignore.heldBack().isEmpty())
    }

    @Test
    fun `a rule file deleted through the mount, its row gone too, holds nothing back`() {
        val ignore = assertNotNull(engine.uploadIgnore)

        assertFalse(ignore.isIgnored("/venv/Lib/site-packages/x.py", false))
        assertTrue(ignore.heldBack().isEmpty())
    }

    @Test
    fun `a never-uploaded name without bytes is not a lost rule file`() {
        db.upsertEntry(keptLocalRow("/venv/.gitignore").copy(isHydrated = false))
        val ignore = assertNotNull(engine.uploadIgnore)

        assertFalse(ignore.isIgnored("/venv/Lib/site-packages/x.py", false))
    }

    private fun restart() =
        SyncEngine(
            provider = FakeCloudProvider(),
            db = db,
            syncRoot = TestScratch.directory("unidrive-775-root"),
            cacheRoot = cacheRoot,
            mountIgnore = UploadIgnore.settingsFor(honorGitignore = true, accountRules = emptyList()),
        )

    private val keptDir get() = SyncEngine.ignoreRuleFilesRoot(cacheRoot, "").resolve("local")

    private fun keptCopies(): List<String> =
        if (Files.isDirectory(keptDir)) Files.list(keptDir).use { s -> s.map { it.fileName.toString() }.toList() } else emptyList()

    @Test
    fun `a local-only rule file read once keeps its rules in force after the cache is wiped and the engine restarts`() {
        db.upsertEntry(keptLocalRow("/venv/.gitignore"))
        Files.createDirectories(cacheDir.resolve("venv"))
        Files.writeString(cacheDir.resolve("venv/.gitignore"), "*.py\n")
        assertTrue(assertNotNull(engine.uploadIgnore).isIgnored("/venv/Lib/x.py", false), "the rules in force, from the cache copy")
        assertEquals(2, keptCopies().size, "a copy and its stamp are kept outside the cache")

        cacheDir.toFile().deleteRecursively()
        val ignore = assertNotNull(restart().uploadIgnore)

        assertTrue(ignore.isIgnored("/venv/Lib/x.py", false), "the kept copy's rules apply")
        assertFalse(ignore.isIgnored("/venv/notes.txt", false), "and only them: nothing is held back")
        assertTrue(ignore.heldBack().isEmpty())
        assertEquals(IgnoreLayer.GITIGNORE, ignore.explain("/venv/Lib/x.py").layer)
    }

    @Test
    fun `a changed cache copy replaces the kept copy`() {
        db.upsertEntry(keptLocalRow("/venv/.gitignore"))
        Files.createDirectories(cacheDir.resolve("venv"))
        val rule = cacheDir.resolve("venv/.gitignore")
        Files.writeString(rule, "*.py\n")
        val first = assertNotNull(engine.uploadIgnore)
        assertTrue(first.isIgnored("/venv/x.py", false))
        Files.writeString(rule, "*.txt\n")
        Files.setLastModifiedTime(rule, java.nio.file.attribute.FileTime.fromMillis(1_800_000_000_000L))
        first.ruleFileWritten("/venv/.gitignore")
        assertTrue(first.isIgnored("/venv/x.txt", false), "the new rules from the cache copy")

        cacheDir.toFile().deleteRecursively()
        val ignore = assertNotNull(restart().uploadIgnore)

        assertTrue(ignore.isIgnored("/venv/x.txt", false), "the kept copy is the new one")
        assertFalse(ignore.isIgnored("/venv/x.py", false))
    }

    @Test
    fun `the kept copy goes with the rule file's row`() {
        db.upsertEntry(keptLocalRow("/venv/.gitignore"))
        Files.createDirectories(cacheDir.resolve("venv"))
        Files.writeString(cacheDir.resolve("venv/.gitignore"), "*\n")
        assertTrue(assertNotNull(engine.uploadIgnore).isIgnored("/venv/x.py", false))
        assertTrue(keptCopies().isNotEmpty())

        // Deleted through the mount: the cache copy and the row go.
        cacheDir.toFile().deleteRecursively()
        db.deleteEntry("/venv/.gitignore")
        val ignore = assertNotNull(restart().uploadIgnore)

        assertFalse(ignore.isIgnored("/venv/x.py", false), "no rules once the file is gone")
        assertTrue(keptCopies().isEmpty(), "and no copy left to bring them back")
    }

    @Test
    fun `a rule file over the cap is not kept, and a wipe holds its folder back`() {
        db.upsertEntry(keptLocalRow("/venv/.gitignore"))
        Files.createDirectories(cacheDir.resolve("venv"))
        Files.writeString(cacheDir.resolve("venv/.gitignore"), "*\n" + "#".repeat(CloudRuleFiles.DEFAULT_MAX_BYTES.toInt()))
        assertNotNull(engine.uploadIgnore).isIgnored("/venv/x.py", false)
        assertTrue(keptCopies().isEmpty())

        cacheDir.toFile().deleteRecursively()
        val ignore = assertNotNull(restart().uploadIgnore)

        assertTrue(ignore.isIgnored("/venv/x.py", false))
        assertEquals(CacheIgnoreSource.LOST, ignore.heldBack().single().reason)
    }

    @Test
    fun `a read-only store reads the kept copy and writes none`() {
        db.upsertEntry(keptLocalRow("/venv/.gitignore"))
        Files.createDirectories(cacheDir.resolve("venv"))
        Files.writeString(cacheDir.resolve("venv/.gitignore"), "*\n")
        val lost: (String) -> Boolean = { it == "/venv/.gitignore" }
        val oneShot = CacheIgnoreSource(cacheDir, lost = lost, kept = LocalRuleFiles(keptDir, writable = false)) { false }
        assertTrue(UploadIgnore(oneShot, IgnoreSettings(honorGitignore = true)).isIgnored("/venv/x.py", false))
        assertTrue(keptCopies().isEmpty(), "a one-shot command writes nothing")

        assertNotNull(engine.uploadIgnore).isIgnored("/venv/x.py", false) // the daemon keeps the copy
        cacheDir.toFile().deleteRecursively()
        val after = CacheIgnoreSource(cacheDir, lost = lost, kept = LocalRuleFiles(keptDir, writable = false)) { false }
        val ignore = UploadIgnore(after, IgnoreSettings(honorGitignore = true))

        assertTrue(ignore.isIgnored("/venv/x.py", false))
        assertTrue(ignore.heldBack().isEmpty(), "read from the daemon's copy")
        assertEquals(2, keptCopies().size, "and left in place")
    }
}
