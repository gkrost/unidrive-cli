package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ChangeState
import org.krost.unidrive.sync.model.SyncAction
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("org.krost.unidrive.sync.TouchedFolderConflicts")

/**
 * #713: a folder deleted locally (usually the old name of a renamed folder) whose remote side reads as modified only
 * because its own metadata moved. A folder's modified time moves whenever a child is added or removed, so the uploads
 * of a first sync, or the engine's own moves out of the folder, leave its row behind the listing. resolveAction plans a
 * delete-vs-modify [SyncAction.Conflict] for it, and that conflict left the emptied old folder in the cloud.
 *
 * Such a conflict is settled as a [SyncAction.DeleteRemote] when nothing live is below the folder:
 *  - it is the same folder (a folder in the row and in the listing, with the row's remote id), and
 *  - no other action of the plan brings a live remote item down below it (a download, a conflict with a live remote
 *    side, a placeholder, a local move: [foldersWithLiveRemoteItemsBelow]), and
 *  - the listing shows no live item below it that the plan does not delete ([unplannedRemoteItemBelow]).
 *
 * A real remote change below the folder (a file someone else added there) therefore keeps the conflict, as before.
 * Runs before detectMoves, so a settled delete can still pair with the create of the new name into one folder move;
 * the folder-delete guards after it and the live check at apply stay in force.
 *
 * Returns the settled paths.
 */
internal fun settleTouchedFolderConflictsIn(
    actions: MutableList<SyncAction>,
    remoteChangesLocal: Map<String, CloudItem>,
    entryByPath: Map<String, SyncEntry>,
    skippedByScan: (path: String, item: CloudItem) -> Boolean,
): List<String> {
    val touched =
        actions.filterIsInstance<SyncAction.Conflict>().filter { conflict ->
            val entry = entryByPath[conflict.path]
            val item = conflict.remoteItem
            conflict.localState == ChangeState.DELETED && conflict.remoteState == ChangeState.MODIFIED &&
                entry != null && entry.isFolder && item != null && item.isFolder && !item.deleted && item.id == entry.remoteId
        }
    if (touched.isEmpty()) return emptyList()
    // The touched folders' own conflicts carry their live remote folder: they must not hold each other (an old
    // folder and its old subfolder are both touched by the same rename).
    val touchedPaths = touched.mapTo(HashSet()) { it.path }
    val holders = foldersWithLiveRemoteItemsBelow(actions.filterNot { it is SyncAction.Conflict && it.path in touchedPaths })
    val settled =
        touched.filter { conflict ->
            conflict.path !in holders && !unplannedRemoteItemBelow(conflict.path, remoteChangesLocal, entryByPath, skippedByScan)
        }
    if (settled.isEmpty()) return emptyList()
    val settledPaths = settled.mapTo(HashSet()) { it.path }
    val iterator = actions.listIterator()
    while (iterator.hasNext()) {
        val action = iterator.next()
        if (action is SyncAction.Conflict && action.path in settledPaths) iterator.set(SyncAction.DeleteRemote(action.path))
    }
    log.info("Deleting {} folder(s) removed locally whose only remote change is their own modified time: {}", settled.size, settledPaths.sorted())
    return settled.map { it.path }
}
