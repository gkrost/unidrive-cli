package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncAction
import org.krost.unidrive.sync.model.SyncEntry

// Folder-move pairing for Reconciler.detectMoves (#743 finding 10): which folder deletes can be the source of a new
// folder, and which child actions a paired move replaces. Out of Reconciler.kt for the size ratchet (#534).

// The child actions a folder move [oldPrefix] -> [newPrefix] replaces, given the child names it carries ([movedNames]):
//  - a child delete under the old folder goes when the move carries the child (it is on disk in the new folder);
//    a hydrated file the user deleted during the move is not carried, so its delete is aimed at the new path, where
//    the move puts the cloud copy (moves apply before deletes);
//  - the uploads and folder creates of carried names under the new folder go; a genuinely new file keeps its upload,
//    and a conflict or anything else there stays planned (#743 finding 10);
//  - a download of a carried name at its old path goes: the move takes the row, and the file, along (UD-740 shape).
internal fun settleChildrenOfFolderMove(
    actions: MutableList<SyncAction>,
    oldPrefix: String,
    newPrefix: String,
    movedNames: Set<String>,
    entryByPath: Map<String, SyncEntry>,
    isAbsentLocally: (String) -> Boolean,
) {
    val iterator = actions.listIterator()
    while (iterator.hasNext()) {
        val action = iterator.next()
        when {
            action is SyncAction.DeleteRemote && action.path.startsWith(oldPrefix) -> {
                val newPath = newPrefix + action.path.removePrefix(oldPrefix)
                val deletedFile = entryByPath[action.path]?.let { !it.isFolder && it.isHydrated } == true
                if (deletedFile && isAbsentLocally(newPath)) {
                    iterator.set(SyncAction.DeleteRemote(newPath))
                } else {
                    iterator.remove()
                }
            }
            (action is SyncAction.Upload || action is SyncAction.CreateRemoteFolder) &&
                action.path.startsWith(newPrefix) && action.path.removePrefix(newPrefix) in movedNames -> iterator.remove()
            action is SyncAction.DownloadContent &&
                action.path.startsWith(oldPrefix) && action.path.removePrefix(oldPrefix) in movedNames -> iterator.remove()
        }
    }
}

internal class FolderMoveCandidate(val del: SyncAction.DeleteRemote, val score: Int, val movedNames: Set<String>)

// The folder deletes that can be the source of [create]: same basename, a folder row with a remote id, at least one
// child name the old folder held (a row under it) that the new folder uploads or creates, and the old folder gone
// from disk (#743 finding 10). Each carries its parent-segment overlap with [create] (UD-240k) and the shared names.
internal fun folderMoveCandidates(
    create: SyncAction.CreateRemoteFolder,
    deletes: List<SyncAction.DeleteRemote>,
    actions: List<SyncAction>,
    entryByPath: Map<String, SyncEntry>,
    matchedFolderDeletes: Set<String>,
    isAbsentLocally: (String) -> Boolean,
): List<FolderMoveCandidate> {
    val newName = create.path.substringAfterLast("/")
    val createParentSegs = Reconciler.parentSegments(create.path)
    val newPrefix = create.path + "/"
    val newNames =
        actions
            .filter { (it is SyncAction.Upload || it is SyncAction.CreateRemoteFolder) && it.path.startsWith(newPrefix) }
            .mapTo(HashSet()) { it.path.removePrefix(newPrefix) }
    return deletes
        .asSequence()
        .filter { it.path !in matchedFolderDeletes }
        .filter { it.path.substringAfterLast("/") == newName }
        .mapNotNull { d ->
            val e = entryByPath[d.path] ?: return@mapNotNull null
            if (!e.isFolder || e.remoteId == null) return@mapNotNull null
            val oldPrefix = d.path + "/"
            val shared = newNames.filterTo(HashSet()) { (oldPrefix + it) in entryByPath }
            if (shared.isEmpty() || !isAbsentLocally(d.path)) return@mapNotNull null
            FolderMoveCandidate(d, Reconciler.commonPrefixSegments(Reconciler.parentSegments(d.path), createParentSegs), shared)
        }.toList()
}
