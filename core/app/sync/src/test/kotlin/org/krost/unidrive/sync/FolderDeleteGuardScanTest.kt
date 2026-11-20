package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ChangeState
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncAction
import org.krost.unidrive.sync.model.SyncEntry
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #743 finding 6: the folder-delete guards (#451 and the touched-folder settle of #713) asked, per folder, whether any
 * live item of the listing below it gets no delete, and each question scanned the whole listing: O(folders × items).
 * They now read the listing once for all folders. Both the cost and the verdicts are pinned here.
 */
class FolderDeleteGuardScanTest {
    private val stamp = Instant.parse("2026-11-20T10:00:00Z")

    private fun item(
        path: String,
        isFolder: Boolean = false,
    ) = CloudItem(
        id = "id-$path",
        name = path.substringAfterLast('/'),
        path = path,
        size = if (isFolder) 0 else 1,
        isFolder = isFolder,
        modified = stamp,
        created = stamp,
        hash = null,
        mimeType = null,
    )

    private fun row(
        path: String,
        isFolder: Boolean = false,
    ) = SyncEntry(
        path = path,
        remoteId = "id-$path",
        remoteHash = null,
        remoteSize = 0,
        remoteModified = stamp.minusSeconds(60),
        localMtime = null,
        localSize = null,
        isFolder = isFolder,
        isPinned = false,
        isHydrated = true,
        lastSynced = stamp,
    )

    // A listing that counts how often a guard walks its entries.
    private class CountingListing(
        private val base: Map<String, CloudItem>,
    ) : Map<String, CloudItem> by base {
        var walks = 0

        override val entries: Set<Map.Entry<String, CloudItem>>
            get() = base.entries.also { walks++ }
    }

    private val folders = (1..50).map { "/f$it" }

    private fun listing(): CountingListing =
        CountingListing(
            buildMap {
                for (folder in folders) {
                    put(folder, item(folder, isFolder = true))
                    put("$folder/tracked.txt", item("$folder/tracked.txt"))
                }
            },
        )

    private fun rows(): Map<String, SyncEntry> =
        buildMap {
            for (folder in folders) {
                put(folder, row(folder, isFolder = true))
                put("$folder/tracked.txt", row("$folder/tracked.txt"))
            }
        }

    @Test
    fun `the folder-delete guard walks the listing once for all folder deletes`() {
        val remote = listing()

        folderDeletesHoldingUnplannedRemoteItems(folders.map { SyncAction.DeleteRemote(it) }, remote, rows()) { _, _ -> false }

        assertEquals(1, remote.walks)
    }

    @Test
    fun `the touched-folder settle walks the listing once for all touched folders`() {
        val remote = listing()
        val actions: MutableList<SyncAction> =
            folders.mapTo(mutableListOf()) {
                SyncAction.Conflict(it, ChangeState.DELETED, ChangeState.MODIFIED, item(it, isFolder = true), ConflictPolicy.KEEP_BOTH)
            }

        settleTouchedFolderConflictsIn(actions, remote, rows()) { _, _ -> false }

        assertEquals(1, remote.walks)
    }

    @Test
    fun `a folder delete is held exactly when an unplanned live item is somewhere below it`() {
        val remote =
            mapOf(
                "/a/b/untracked.txt" to item("/a/b/untracked.txt"),
                "/a/tracked.txt" to item("/a/tracked.txt"),
                "/c2/untracked.txt" to item("/c2/untracked.txt"),
                "/d/skipped.txt" to item("/d/skipped.txt"),
                "/e/gone.txt" to item("/e/gone.txt").copy(deleted = true),
            )
        val entries =
            listOf("/a", "/a/b", "/c", "/d", "/e").associateWith { row(it, isFolder = true) } +
                mapOf("/a/tracked.txt" to row("/a/tracked.txt"), "/d/skipped.txt" to row("/d/skipped.txt"))
        val deletes = listOf("/a", "/a/b", "/c", "/d", "/e").map { SyncAction.DeleteRemote(it) }

        val held = folderDeletesHoldingUnplannedRemoteItems(deletes, remote, entries) { path, _ -> path == "/d/skipped.txt" }

        assertEquals(listOf("/a", "/a/b", "/d"), held.map { it.path })
    }

    @Test
    fun `a touched folder is settled only when no unplanned live item is below it`() {
        val remote =
            mapOf(
                "/a" to item("/a", isFolder = true),
                "/a/untracked.txt" to item("/a/untracked.txt"),
                "/b" to item("/b", isFolder = true),
                "/b2/untracked.txt" to item("/b2/untracked.txt"),
            )
        val entries = listOf("/a", "/b").associateWith { row(it, isFolder = true) }
        val actions: MutableList<SyncAction> =
            listOf("/a", "/b").mapTo(mutableListOf()) {
                SyncAction.Conflict(it, ChangeState.DELETED, ChangeState.MODIFIED, remote.getValue(it), ConflictPolicy.KEEP_BOTH)
            }

        val settled = settleTouchedFolderConflictsIn(actions, remote, entries) { _, _ -> false }

        assertEquals(listOf("/b"), settled)
    }
}
