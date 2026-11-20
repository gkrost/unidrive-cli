package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.*
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

// #743 finding 10: a DeleteRemote(old folder) + CreateRemoteFolder(new folder) pair becomes a MoveRemote only with
// evidence that the new folder is the old one: a child name both sides share, and the old folder gone from disk. The
// move takes out only the actions it replaces (the child deletes under the old folder, the uploads and folder creates
// of the shared names under the new one); anything else under the new folder stays planned.
class FolderMovePairingTest {
    private lateinit var db: StateDatabase
    private lateinit var syncRoot: Path
    private lateinit var reconciler: Reconciler

    @BeforeTest
    fun setUp() {
        syncRoot = TestScratch.directory("unidrive-folder-move-pairing")
        db = StateDatabase(TestScratch.directory("unidrive-folder-move-pairing-db").resolve("state.db"))
        db.initialize()
        reconciler = Reconciler(db, syncRoot, ConflictPolicy.KEEP_BOTH)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun row(path: String, isFolder: Boolean) =
        SyncEntry(
            path = path,
            remoteId = "id-$path",
            remoteHash = if (isFolder) null else "abc",
            remoteSize = if (isFolder) 0 else 100,
            remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
            localMtime = 1711627200000,
            localSize = if (isFolder) 0 else 100,
            isFolder = isFolder,
            isPinned = false,
            isHydrated = true,
            lastSynced = Instant.now(),
        )

    private fun newLocalFile(path: String) {
        val p = syncRoot.resolve(path.removePrefix("/"))
        Files.createDirectories(p.parent)
        Files.writeString(p, "bytes of $path")
    }

    private fun remoteFile(path: String) =
        CloudItem(
            id = "remote-$path",
            name = path.substringAfterLast("/"),
            path = path,
            size = 7,
            isFolder = false,
            modified = Instant.parse("2026-03-28T12:00:00Z"),
            created = Instant.parse("2026-03-28T10:00:00Z"),
            hash = "remote-hash",
            mimeType = "image/jpeg",
        )

    @Test
    fun `folder_with_no_child_name_in_common_with_the_deleted_folder_is_not_its_move`() {
        // The user deletes /a/Photos and creates an unrelated /b/Photos: the same basename, nothing else.
        db.upsertEntry(row("/a/Photos", isFolder = true))
        db.upsertEntry(row("/a/Photos/old.jpg", isFolder = false))
        newLocalFile("/b/Photos/new.jpg")
        val local =
            mapOf(
                "/a/Photos" to ChangeState.DELETED,
                "/a/Photos/old.jpg" to ChangeState.DELETED,
                "/b/Photos" to ChangeState.NEW,
                "/b/Photos/new.jpg" to ChangeState.NEW,
            )
        val actions = reconciler.reconcile(emptyMap(), local)
        assertTrue(actions.none { it is SyncAction.MoveRemote }, "no shared child name, no move: $actions")
        assertTrue(actions.any { it is SyncAction.DeleteRemote && it.path == "/a/Photos/old.jpg" }, "the delete stays: $actions")
        assertTrue(actions.any { it is SyncAction.Upload && it.path == "/b/Photos/new.jpg" }, "the upload stays: $actions")
    }

    @Test
    fun `folder_still_on_disk_is_not_the_source_of_a_move`() {
        // The scan reported /a/Photos deleted, but the folder is back on disk by the time the plan is made.
        db.upsertEntry(row("/a/Photos", isFolder = true))
        db.upsertEntry(row("/a/Photos/x.jpg", isFolder = false))
        Files.createDirectories(syncRoot.resolve("a/Photos"))
        newLocalFile("/b/Photos/x.jpg")
        val local =
            mapOf(
                "/a/Photos" to ChangeState.DELETED,
                "/a/Photos/x.jpg" to ChangeState.DELETED,
                "/b/Photos" to ChangeState.NEW,
                "/b/Photos/x.jpg" to ChangeState.NEW,
            )
        val actions = reconciler.reconcile(emptyMap(), local)
        assertTrue(actions.none { it is SyncAction.MoveRemote }, "an old folder still present is not moved away: $actions")
    }

    @Test
    fun `folder_move_keeps_the_actions_it_does_not_replace`() {
        // A real move of /a/Photos to /b/Photos (x.jpg moved along), while the cloud also gained /b/Photos/y.jpg and the
        // user placed a different y.jpg there: that conflict is not part of the move and must stay planned.
        db.upsertEntry(row("/a/Photos", isFolder = true))
        db.upsertEntry(row("/a/Photos/x.jpg", isFolder = false))
        db.upsertEntry(row("/a/Photos/y.jpg", isFolder = false))
        newLocalFile("/b/Photos/x.jpg")
        newLocalFile("/b/Photos/y.jpg")
        val local =
            mapOf(
                "/a/Photos" to ChangeState.DELETED,
                "/a/Photos/x.jpg" to ChangeState.DELETED,
                "/a/Photos/y.jpg" to ChangeState.DELETED,
                "/b/Photos" to ChangeState.NEW,
                "/b/Photos/x.jpg" to ChangeState.NEW,
                "/b/Photos/y.jpg" to ChangeState.NEW,
            )
        val remote = mapOf("/b/Photos/y.jpg" to remoteFile("/b/Photos/y.jpg"))
        val actions = reconciler.reconcile(remote, local)
        val move = actions.filterIsInstance<SyncAction.MoveRemote>().singleOrNull { it.path == "/b/Photos" }
        assertNotNull(move, "x.jpg ties the folders together: $actions")
        assertEquals("/a/Photos", move.fromPath)
        assertTrue(actions.none { it is SyncAction.Upload && it.path == "/b/Photos/x.jpg" }, "the move carries x.jpg: $actions")
        val atY = actions.filter { it.path == "/b/Photos/y.jpg" }
        assertTrue(atY.isNotEmpty() && atY.none { it is SyncAction.Upload }, "the conflict at y.jpg stays planned: $actions")
    }

    @Test
    fun `folder_move_with_a_shared_child_name_still_pairs`() {
        // Regression guard: the evidence rule must not stop a real cross-parent move.
        db.upsertEntry(row("/a/docs", isFolder = true))
        db.upsertEntry(row("/a/docs/readme.txt", isFolder = false))
        newLocalFile("/b/docs/readme.txt")
        newLocalFile("/b/docs/brand-new.txt")
        val local =
            mapOf(
                "/a/docs" to ChangeState.DELETED,
                "/a/docs/readme.txt" to ChangeState.DELETED,
                "/b/docs" to ChangeState.NEW,
                "/b/docs/readme.txt" to ChangeState.NEW,
                "/b/docs/brand-new.txt" to ChangeState.NEW,
            )
        val actions = reconciler.reconcile(emptyMap(), local)
        val move = assertIs<SyncAction.MoveRemote>(actions.single { it is SyncAction.MoveRemote }, "$actions")
        assertEquals("/a/docs", move.fromPath)
        assertEquals("/b/docs", move.path)
        assertTrue(actions.none { it.path == "/b/docs/readme.txt" || it.path.startsWith("/a/docs") }, "$actions")
        assertTrue(actions.any { it is SyncAction.Upload && it.path == "/b/docs/brand-new.txt" }, "a genuinely new file uploads: $actions")
    }

    @Test
    fun `subfolder_carried_by_a_parent_move_is_not_paired_with_another_deleted_folder_of_its_name`() {
        // /a/X moves to /b/X and carries /b/X/S along. /b/X/S also holds a new file g, and an unrelated deleted /c/S held a g:
        // looking for /b/X/S's source after the parent move would pair it with /c/S, a second move into a moved subtree.
        db.upsertEntry(row("/a/X", isFolder = true))
        db.upsertEntry(row("/a/X/S", isFolder = true))
        db.upsertEntry(row("/a/X/S/f", isFolder = false))
        db.upsertEntry(row("/c/S", isFolder = true))
        db.upsertEntry(row("/c/S/g", isFolder = false))
        newLocalFile("/b/X/S/f")
        newLocalFile("/b/X/S/g")
        val local =
            mapOf(
                "/a/X" to ChangeState.DELETED,
                "/a/X/S" to ChangeState.DELETED,
                "/a/X/S/f" to ChangeState.DELETED,
                "/c/S" to ChangeState.DELETED,
                "/c/S/g" to ChangeState.DELETED,
                "/b/X" to ChangeState.NEW,
                "/b/X/S" to ChangeState.NEW,
                "/b/X/S/f" to ChangeState.NEW,
                "/b/X/S/g" to ChangeState.NEW,
            )
        val moves = reconciler.reconcile(emptyMap(), local).filterIsInstance<SyncAction.MoveRemote>()
        assertEquals(listOf("/a/X" to "/b/X"), moves.map { it.fromPath to it.path }, "only the parent move: $moves")
    }
}
