package org.krost.unidrive.engine

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tombstone of a folder stands for everything below it (RemoteGather.expandFolderTombstones). It is matched to the
 * tracked folder by id, so that the tombstone of another item that once held the path does not reap the rows of the
 * folder now there. A provider whose deleted entry names no item (Dropbox: the API gives the path alone, and the engine
 * pairs an id-less tombstone with the row by path, gkrost/unidrive-cli#743) sends an EMPTY id: its folder tombstone is a
 * delete of the path and must expand like the tombstone of the folder's own id.
 *
 * Scope: these tests build the tombstone by hand with isFolder=true. The expansion only looks at folder tombstones, and a
 * real Dropbox deleted entry carries no kind (isFolder=false), so it does not reach this rule: the rows below a deleted
 * Dropbox folder still wait for a full listing, as before. The rule matters for a provider that sends id-less folder
 * tombstones with isFolder=true.
 */
class RemoteGatherFolderTombstoneIdTest {
    private lateinit var tmp: Path
    private lateinit var db: StateDatabase

    private val old = Instant.parse("2026-03-01T00:00:00Z")

    @BeforeTest
    fun setUp() {
        tmp = TestScratch.directory("unidrive-folder-tomb")
        db = StateDatabase(tmp.resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tmp.toFile().deleteRecursively()
    }

    private fun gather() =
        RemoteGather(
            provider = FakeViewProvider(kinds = emptySet()),
            db = db,
            guard =
                RemoteOperationGuard(
                    standingScope = emptyList(),
                    syncPaths = emptyList(),
                    excludePatterns = emptyList(),
                    maxConcurrentTransfers = 2,
                    matchesGlob = { _, _ -> false },
                ),
            options = RemoteGather.Options(providerId = "test"),
            listener = object : RemoteGather.Listener {},
            log = LoggerFactory.getLogger(RemoteGatherFolderTombstoneIdTest::class.java),
        )

    private fun row(
        path: String,
        id: String,
        folder: Boolean = false,
    ) = SyncEntry(
        path = path,
        remoteId = id,
        remoteHash = null,
        remoteSize = 1,
        remoteModified = null,
        localMtime = null,
        localSize = null,
        isFolder = folder,
        isPinned = false,
        isHydrated = false,
        lastSynced = old,
    )

    private fun folderTombstone(id: String) =
        CloudItem(id = id, name = "D", path = "/D", size = 0, isFolder = true, modified = null, created = null, hash = null, mimeType = null, deleted = true)

    private fun tracked() {
        db.upsertEntry(row("/D", "folder-d", folder = true))
        db.upsertEntry(row("/D/a.txt", "file-a"))
        db.upsertEntry(row("/D/S", "folder-s", folder = true))
        db.upsertEntry(row("/D/S/b.txt", "file-b"))
    }

    private fun expand(tombstone: CloudItem): Map<String, CloudItem> {
        val changes = linkedMapOf("/D" to tombstone)
        gather().expandFolderTombstones(changes)
        return changes
    }

    @Test
    fun `a folder tombstone without an id expands to the rows below the tracked folder`() {
        tracked()
        val changes = expand(folderTombstone(""))
        assertEquals(
            setOf("/D/a.txt", "/D/S", "/D/S/b.txt"),
            changes.keys - "/D",
            "the rows below a folder deleted by path are tombstoned, as for the folder's own id: $changes",
        )
        assertTrue(changes.filterKeys { it != "/D" }.values.all { it.deleted }, "each is a tombstone of the row's own id: $changes")
    }

    @Test
    fun `guard - a folder tombstone of the tracked folder's own id expands to the rows below it`() {
        tracked()
        val changes = expand(folderTombstone("folder-d"))
        assertEquals(setOf("/D/a.txt", "/D/S", "/D/S/b.txt"), changes.keys - "/D")
        assertEquals(mapOf("/D/a.txt" to "file-a", "/D/S" to "folder-s", "/D/S/b.txt" to "file-b"), (changes - "/D").mapValues { it.value.id })
    }

    @Test
    fun `guard - a folder tombstone of another item than the tracked folder expands nothing`() {
        tracked()
        val changes = expand(folderTombstone("folder-other"))
        assertEquals(setOf("/D"), changes.keys, "the tombstone of an item that once held the path does not reap the folder now there: $changes")
    }

    @Test
    fun `guard - a folder tombstone without an id expands nothing when no folder is tracked at the path`() {
        db.upsertEntry(row("/D", "file-d")) // a file row at the path: the tombstone is no folder's
        db.upsertEntry(row("/D/a.txt", "file-a"))
        val changes = expand(folderTombstone(""))
        assertEquals(setOf("/D"), changes.keys, "no tracked folder, nothing below it to reap: $changes")
    }

    @Test
    fun `a folder tombstone without an id leaves rows the delta delivers alive elsewhere to the merge`() {
        tracked()
        val moved = CloudItem(id = "file-a", name = "a.txt", path = "/E/a.txt", size = 1, isFolder = false, modified = null, created = null, hash = null, mimeType = null)
        val changes = linkedMapOf("/D" to folderTombstone(""), "/E/a.txt" to moved)
        gather().expandFolderTombstones(changes)
        assertTrue("/D/a.txt" !in changes, "an item moved out of the folder is no tombstone: $changes")
        assertTrue("/D/S/b.txt" in changes, "the rest of the subtree is: $changes")
    }
}
