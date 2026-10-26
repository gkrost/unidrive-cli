package org.krost.unidrive.sync

import org.krost.unidrive.sync.ignore.IgnoreSettings
import org.krost.unidrive.sync.ignore.IgnoreTree
import org.krost.unidrive.sync.ignore.MemoryIgnoreSource
import org.krost.unidrive.sync.model.ChangeState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * archive#1049 X2: the providers stage a download as `<name>.unidrive-tmp` beside its destination (`.ud-<hex>.unidrive-tmp` for
 * a name too long to take the suffix, `stagingSiblingName`). An interrupted download (Ctrl-C of `sync --watch`, a kill)
 * leaves the partial file in the sync root, and the default excludes did not match it: the next scan reported it NEW and
 * uploaded the partial as a normal file.
 */
class StagingFilesNotUploadedTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-staging-root")
        db = StateDatabase(Files.createTempDirectory("unidrive-staging-db").resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        syncRoot.toFile().deleteRecursively()
    }

    @Test
    fun `a staging file left by an interrupted download is not reported as a new file`() {
        Files.writeString(syncRoot.resolve("movie.mkv.unidrive-tmp"), "partial")
        Files.writeString(syncRoot.resolve(".ud-0a1b2c3d.unidrive-tmp"), "partial of a very long name")
        Files.createDirectories(syncRoot.resolve("Videos"))
        Files.writeString(syncRoot.resolve("Videos/clip.mp4.unidrive-tmp"), "partial")
        Files.writeString(syncRoot.resolve("Videos/clip.mp4"), "whole")
        Files.writeString(syncRoot.resolve("real.txt"), "keep me")

        val changes = LocalScanner(syncRoot, db, SyncConfig.DEFAULT_EXCLUDE_PATTERNS).scan()

        assertEquals(ChangeState.NEW, changes["/real.txt"])
        assertEquals(ChangeState.NEW, changes["/Videos/clip.mp4"])
        assertNull(changes["/movie.mkv.unidrive-tmp"], "staging file must not be uploaded: $changes")
        assertNull(changes["/.ud-0a1b2c3d.unidrive-tmp"], "the short staging form must not be uploaded: $changes")
        assertNull(changes["/Videos/clip.mp4.unidrive-tmp"], "staging file in a folder must not be uploaded: $changes")
    }

    // The tracking engine stages a download as `.<name>.unidrive-download-<uuid>` beside the destination (TrackingEngine).
    @Test
    fun `a tracking engine staging file left by an interrupted download is not reported as a new file`() {
        val staging = ".x.txt.unidrive-download-0b9c1c5e-7f0e-4a58-9d57-3f1f2f9a6c11"
        Files.writeString(syncRoot.resolve(staging), "partial")
        Files.writeString(syncRoot.resolve("real.txt"), "keep me")

        val changes = LocalScanner(syncRoot, db, SyncConfig.DEFAULT_EXCLUDE_PATTERNS).scan()

        assertEquals(ChangeState.NEW, changes["/real.txt"])
        assertNull(changes["/$staging"], "staging file must not be uploaded: $changes")
    }

    @Test
    fun `the ignore rules hold staging files back by default, without touching the names around them`() {
        val tree = IgnoreTree(MemoryIgnoreSource(emptyMap()), IgnoreSettings()) { }

        assertTrue(tree.isIgnored("movie.mkv.unidrive-tmp"))
        assertTrue(tree.isIgnored("a/b/.ud-0a1b2c3d.unidrive-tmp"))
        assertEquals(false, tree.isIgnored("movie.mkv"))
        assertEquals(false, tree.isIgnored("unidrive-tmp"))
        assertEquals(false, tree.isIgnored("notes.unidrive-tmp.txt"))
    }
}
