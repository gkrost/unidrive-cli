package org.krost.unidrive.tracking

import java.nio.file.Files
import java.nio.file.Path
import org.krost.unidrive.sync.ConflictLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A hashless provider that is incremental (Internxt: a cursor, tombstones, moves, folder tombstones). Each test syncs
 * once from a full listing, then resumes from the saved cursor with a hand-built delta page, and asserts the disk and
 * the tracking set. A hashless row must follow the same rules an etag row has.
 */
class TrackingHashlessIncrementalDeleteTest : HashlessEngineTestBase() {
    private fun exists(name: String) = Files.exists(syncRoot.resolve(name))

    private fun write(
        name: String,
        text: String,
    ) {
        val file = syncRoot.resolve(name)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    /** Pass 1 from a full listing: every file in [files] is downloaded and tracked, cursor "c1" saved. */
    private fun firstPass(tracking: TrackingSet) {
        provider.cursorValue = "c1"
        engine(tracking).syncOnce()
        provider.incrementalAware = true
        provider.cursorValue = "c2"
    }

    private fun idOf(
        tracking: TrackingSet,
        path: String,
    ) = assertNotNull(tracking.lookup(path)?.remoteFileId, "precondition: $path is tracked with a remote id")

    @Test
    fun `a tombstone by id trashes an unchanged hashless file`() {
        provider.files["/a.txt"] = "v1".toByteArray()
        provider.files["/keep.txt"] = "k1".toByteArray()
        val tracking = open()
        try {
            firstPass(tracking)
            provider.incrementalItems = listOf(provider.tombstone(idOf(tracking, "/a.txt")))
            provider.files.remove("/a.txt")

            engine(tracking).syncOnce()

            assertTrue(!exists("a.txt"), "the tombstoned unchanged file is trashed")
            assertNull(tracking.lookup("/a.txt"))
            assertTrue(exists("keep.txt"))
            assertNotNull(tracking.lookup("/keep.txt"))
            assertEquals("c2", tracking.loadDeltaCursor(), "the cursor advances after the plan applied")
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `a tombstone by id keeps a locally edited hashless file and holds the cursor`() {
        provider.files["/a.txt"] = "v1".toByteArray()
        val tracking = open()
        try {
            firstPass(tracking)
            write("a.txt", "edited locally, longer")
            provider.incrementalItems = listOf(provider.tombstone(idOf(tracking, "/a.txt")))
            provider.files.remove("/a.txt")

            engine(tracking).syncOnce()

            assertEquals("edited locally, longer", Files.readString(syncRoot.resolve("a.txt")))
            assertNotNull(tracking.lookup("/a.txt"), "the row stays so the collision is retried")
            assertTrue(provider.files.isEmpty(), "no upload against the dead remote id")
            assertEquals("c1", tracking.loadDeltaCursor())
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `a folder tombstone trashes unchanged files below it and keeps edited ones`() {
        provider.files["/dir/same.txt"] = "s1".toByteArray()
        provider.files["/dir/sub/deep.txt"] = "d1".toByteArray()
        provider.files["/dir/edited.txt"] = "e1".toByteArray()
        provider.files["/other.txt"] = "o1".toByteArray()
        val tracking = open()
        try {
            firstPass(tracking)
            write("dir/edited.txt", "edited locally, longer")
            provider.incrementalItems = listOf(provider.tombstone("folder-id", "/dir", isFolder = true))
            provider.files.keys.removeAll { it.startsWith("/dir/") }

            engine(tracking).syncOnce()

            assertTrue(!exists("dir/same.txt") && !exists("dir/sub/deep.txt"), "unchanged files below the folder are trashed")
            assertEquals("edited locally, longer", Files.readString(syncRoot.resolve("dir/edited.txt")), "the edit is kept")
            assertTrue(exists("other.txt"), "a file outside the folder is untouched")
            assertNotNull(tracking.lookup("/dir/edited.txt"))
            assertNotNull(tracking.lookup("/other.txt"))
            assertNull(tracking.lookup("/dir/same.txt"))
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `a remote move trashes the unchanged old path and downloads the new one`() {
        provider.files["/old.txt"] = "v1".toByteArray()
        val tracking = open()
        try {
            firstPass(tracking)
            val id = idOf(tracking, "/old.txt")
            val bytes = provider.files.remove("/old.txt")!!
            provider.files["/new.txt"] = bytes
            provider.incrementalItems = listOf(provider.liveItem("/new.txt", bytes, id))

            engine(tracking).syncOnce()

            assertTrue(!exists("old.txt"), "the old path is deleted")
            assertEquals("v1", Files.readString(syncRoot.resolve("new.txt")), "the new path is downloaded")
            assertNull(tracking.lookup("/old.txt"))
            assertNotNull(tracking.lookup("/new.txt"))
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `a remote move preserves a local edit at the old path`() {
        provider.files["/old.txt"] = "v1".toByteArray()
        val tracking = open()
        try {
            firstPass(tracking)
            val id = idOf(tracking, "/old.txt")
            write("old.txt", "edited locally, longer")
            val bytes = provider.files.remove("/old.txt")!!
            provider.files["/new.txt"] = bytes
            provider.incrementalItems = listOf(provider.liveItem("/new.txt", bytes, id))

            engine(tracking).syncOnce()

            assertEquals("edited locally, longer", Files.readString(syncRoot.resolve("old.txt")), "the edit survives")
            assertEquals("v1", Files.readString(syncRoot.resolve("new.txt")))
            assertEquals("c1", tracking.loadDeltaCursor(), "the unresolved collision holds the cursor")
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `a stale tombstone next to a live item with a new id never deletes the live file`() {
        provider.files["/a.txt"] = "v1".toByteArray()
        val tracking = open()
        try {
            firstPass(tracking)
            val oldId = idOf(tracking, "/a.txt")
            // The path was re-created under a new id with the same size: the live item comes first, the old id's
            // tombstone after it.
            val bytes = "v2".toByteArray()
            provider.files["/a.txt"] = bytes
            provider.incrementalItems = listOf(provider.liveItem("/a.txt", bytes, "new-id"), provider.tombstone(oldId, "/a.txt"))

            engine(tracking).syncOnce()

            assertEquals("v1", Files.readString(syncRoot.resolve("a.txt")), "the live local file survives")
            assertNotNull(tracking.lookup("/a.txt"))
            assertTrue(provider.files.containsKey("/a.txt"))
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `an incremental pass that mentions nothing changes nothing`() {
        provider.files["/a.txt"] = "v1".toByteArray()
        val tracking = open()
        try {
            firstPass(tracking)
            provider.incrementalItems = emptyList()
            provider.files.remove("/a.txt") // gone from the listing, but the delta says nothing

            val report = engine(tracking).syncOnce()

            assertTrue(report.plan.none { it is ReconcileAction.PropagateRemoteDelete })
            assertTrue(exists("a.txt"))
            assertNotNull(tracking.lookup("/a.txt"))
        } finally {
            tracking.close()
        }
    }

    // ---- an unresolved collision is journalled once, not once per pass ----

    private fun backupCount(dir: Path) = if (Files.isDirectory(dir)) Files.list(dir).use { it.count() } else 0L

    @Test
    fun `a held collision is journalled and backed up once over many passes`() {
        provider.deltaIsFullListing = true
        provider.files["/a.txt"] = "v1".toByteArray()
        val backups = workDir.resolve("conflict-backups")
        val journal = ConflictLog(workDir.resolve("conflicts.jsonl"), backups)
        val tracking = open()
        try {
            provider.cursorValue = "c1"
            engine(tracking, conflictLog = journal).syncOnce()
            write("a.txt", "edited locally, longer")
            provider.files.remove("/a.txt")
            provider.cursorValue = "c2"

            repeat(4) { engine(tracking, conflictLog = journal).syncOnce() }

            assertEquals(1, journal.recent(50).size, "one journal entry for the unchanged conflict")
            assertEquals(1L, backupCount(backups), "one backup copy")
            assertEquals("edited locally, longer", Files.readString(syncRoot.resolve("a.txt")))
            assertEquals("c1", tracking.loadDeltaCursor(), "the cursor stays held")

            write("a.txt", "edited again, even longer than before")
            engine(tracking, conflictLog = journal).syncOnce()
            assertEquals(2, journal.recent(50).size, "a further edit is a new conflict and is backed up")
            assertEquals(2L, backupCount(backups))
        } finally {
            tracking.close()
        }
    }
}
