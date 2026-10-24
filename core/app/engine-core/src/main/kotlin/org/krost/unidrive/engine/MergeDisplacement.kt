package org.krost.unidrive.engine

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.ConflictCopyNames
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import java.time.Instant

/**
 * The rows that sit on the paths the items of a merge are about to take (#1029).
 *
 * A delta can carry several renames at once: a swap through a temporary name, a chain (`/b` goes to `/c`, `/a` to `/b`),
 * a create on a path a rename has just left. The merge puts an item's row where the item now lives, and the row there
 * under another remote id is replaced by the write. When that row belongs to an item the same delivery moves on, it
 * must not be replaced: it is moved to its own new path first (the row keeps its baseline, the move is reported like a
 * rename), so that each item meets its own row afterwards, whatever order the paths come in and whichever merge chunk
 * they land in. A swap has no free path to go through: the merged item's own row waits under a parking name for the
 * moment.
 *
 * Only files move here. A folder's rename is [RemoteGather.moveRenamedFolders], which takes the subtree along.
 */
internal class MergeDisplacement(
    private val db: StateDatabase,
    // The whole delivery of the enumeration (a merge chunk is a part of it): where each live item is going.
    private val delivery: Map<String, CloudItem>,
    private val localPathOf: (remotePath: String) -> String,
    private val moved: MutableList<RemoteGather.RemoteMerge.Move>,
    private val changed: MutableSet<String>,
    // The mount's merge (RemoteGather.updateRemoteEntries' keepPendingUploads): see [movePendingUploadAside].
    private val keepPendingUploads: Boolean = false,
    private val now: () -> Instant = Instant::now,
) {
    // The path every live item of the delivery is delivered at, by remote id. Built when the first conflict needs it.
    private val pathOfId: Map<String, String> by lazy {
        val byId = HashMap<String, String>()
        for ((path, item) in delivery) if (!item.deleted) byId[item.id] = path
        byId
    }

    /**
     * Clears the path [item] is delivered at ([path], a remote path) of the row that sits there under another remote id,
     * when the same delivery moves that row's item away: [known] is that row when the caller has read it already.
     * Returns whether the row left.
     */
    fun makeRoomFor(
        path: String,
        item: CloudItem,
        known: SyncEntry? = null,
    ): Boolean {
        val occupant = (known ?: db.getEntryByRemotePath(path))?.takeIf { holdsName(it, besidesId = item.id) } ?: return false
        return moveAway(occupant, own = db.getEntryByRemoteId(item.id), visiting = hashSetOf(item.id))
    }

    /**
     * The mount's merge only: a renamed item is about to take [localPath], and the row there is a pending upload (a file
     * written through the mount whose upload has not landed, so the cloud name was free). The upsert would evict that row
     * and leave its cache copy, the only copy of the unsent bytes, without a row. It moves aside first, to a keep-both
     * name beside it ([ConflictCopyNames], as the mirror's keep-both names the user's copy), reported as a move so the
     * enumeration carries the cache copy along and the view shows the file under its new name. The row keeps its bytes
     * and its baseline, and its upload sends them under that name. Returns the new path, or null when nothing moved.
     *
     * The mirror keeps its merge: the bytes are a file in its sync root, and its reconciler keeps both.
     */
    fun movePendingUploadAside(localPath: String): String? {
        if (!keepPendingUploads) return null
        val row = db.getEntry(localPath)?.takeIf { it.isPendingUpload && !it.isFolder } ?: return null
        val dir = localPath.substringBeforeLast('/', missingDelimiterValue = "")
        val leaf = localPath.substringAfterLast('/')
        val at = now()
        for (n in 1..PARK_ATTEMPTS) {
            val candidate = "$dir/${ConflictCopyNames.localCopyName(leaf, at, n)}"
            if (db.getEntry(candidate) != null || db.getEntryByRemotePath(candidate) != null) continue
            relocate(row, to = candidate, remotePath = null)
            return candidate
        }
        return null
    }

    // An alive file row of another remote id. A twin's row (#403) is no holder of the name.
    private fun holdsName(
        row: SyncEntry,
        besidesId: String,
    ): Boolean = row.remoteId != null && row.remoteId != besidesId && !row.isFolder && DuplicateNames.keyOf(row.path, row.remotePath) == null

    private fun moveAway(
        row: SyncEntry,
        own: SyncEntry?,
        visiting: MutableSet<String>,
    ): Boolean {
        val id = row.remoteId ?: return false
        // Not delivered, or delivered gone: nothing says where it goes, the write replaces it as it always did.
        val target = pathOfId[id] ?: return false
        val targetLocal = localPathOf(target)
        if (targetLocal == row.path || !visiting.add(id)) return false
        val blocker = db.getEntryByRemotePath(target)?.takeIf { holdsName(it, besidesId = id) }
        if (blocker != null) {
            // The row the merge is about to move sits on the path this one goes to: they swap, or a chain closes on it.
            if (blocker.remoteId == own?.remoteId) park(blocker) else moveAway(blocker, own, visiting)
        }
        relocate(row, to = targetLocal, remotePath = target.takeIf { it != targetLocal })
        return true
    }

    private fun park(row: SyncEntry) {
        var n = 0
        while (n < PARK_ATTEMPTS) {
            val candidate = if (n == 0) "${row.path}$PARK_SUFFIX" else "${row.path}$PARK_SUFFIX.$n"
            n++
            if (db.getEntry(candidate) != null) continue
            relocate(row, to = candidate, remotePath = row.remotePath)
            return
        }
    }

    private fun relocate(
        row: SyncEntry,
        to: String,
        remotePath: String?,
    ) {
        db.upsertEntry(row.copy(path = to, remotePath = remotePath))
        moved += RemoteGather.RemoteMerge.Move(from = row.path, to = to)
        changed += row.path
        changed += to
    }

    private companion object {
        const val PARK_SUFFIX = ".unidrive-rename"
        const val PARK_ATTEMPTS = 100
    }
}
