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
}
