package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.EntryStatus
import org.krost.unidrive.sync.model.SyncAction
import org.krost.unidrive.sync.model.SyncEntry

// #451: the folder DeleteRemotes of [actions] whose live content below them the plan does not delete: a path
// [skippedByScan] excludes or ignores, or one without an alive tracked row (an EXISTS row with a remote id). A
// provider's folder delete takes its whole subtree with it, so such a folder must not go: the tracked children
// keep their own file-level deletes and the engine's empty-directory reaper removes the folder once its live
// listing shows nothing surviving below it.
internal fun folderDeletesHoldingUnplannedRemoteItems(
    actions: List<SyncAction>,
    remoteChangesLocal: Map<String, CloudItem>,
    entryByPath: Map<String, SyncEntry>,
    skippedByScan: (path: String, item: CloudItem) -> Boolean,
): List<SyncAction.DeleteRemote> {
    val folderDeletes = actions.filterIsInstance<SyncAction.DeleteRemote>().filter { entryByPath[it.path]?.isFolder == true }
    if (folderDeletes.isEmpty()) return emptyList()
    val held = foldersHoldingUnplannedRemoteItems(folderDeletes.mapTo(HashSet()) { it.path }, remoteChangesLocal, entryByPath, skippedByScan)
    return folderDeletes.filter { it.path in held }
}

/**
 * The [folders] that have a live item of [remoteChangesLocal] somewhere below them that gets no delete of its own.
 * #743 finding 6: one walk of the listing for all folders, each item looking up its ancestors, instead of one walk
 * per folder (O(folders × items) for a deleted tree against a full listing).
 */
internal fun foldersHoldingUnplannedRemoteItems(
    folders: Set<String>,
    remoteChangesLocal: Map<String, CloudItem>,
    entryByPath: Map<String, SyncEntry>,
    skippedByScan: (path: String, item: CloudItem) -> Boolean,
): Set<String> {
    val held = HashSet<String>()
    if (folders.isEmpty()) return held
    for ((path, item) in remoteChangesLocal) {
        if (item.deleted) continue
        val candidates = ancestorsAmong(path, folders).filterNot { it in held }
        if (candidates.isNotEmpty() && unplannedRemoteItem(path, item, entryByPath, skippedByScan)) {
            held += candidates
            if (held.size == folders.size) break
        }
    }
    return held
}

// The proper ancestors of [path] that are in [folders].
private fun ancestorsAmong(
    path: String,
    folders: Set<String>,
): List<String> {
    val found = ArrayList<String>(1)
    var parent = path.substringBeforeLast('/', "")
    while (parent.isNotEmpty()) {
        if (parent in folders) found += parent
        parent = parent.substringBeforeLast('/', "")
    }
    return found
}

// True when [path] (a live item under a folder the plan deletes remotely) gets no delete of its own: the scan
// skips it ([skippedByScan]), or no alive row with a remote id tracks it.
private fun unplannedRemoteItem(
    path: String,
    item: CloudItem,
    entryByPath: Map<String, SyncEntry>,
    skippedByScan: (path: String, item: CloudItem) -> Boolean,
): Boolean {
    val entry = entryByPath[path]
    return skippedByScan(path, item) || entry == null || entry.remoteId == null || entry.status != EntryStatus.EXISTS
}
