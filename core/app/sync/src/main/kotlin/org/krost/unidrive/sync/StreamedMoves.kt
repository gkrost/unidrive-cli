package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ChangeState
import org.krost.unidrive.sync.model.SyncAction
import org.krost.unidrive.sync.model.SyncEntry

/**
 * A tracked item the cloud renamed and edited is planned by its streamed slice as a move and a download of the new
 * content at the new name, as the buffered gather plans it. The move runs in Pass 1, after the gather: a download
 * streamed now would land first, and the move would find its destination taken. A download to a name a move of the
 * same slice fills, or to a name below a folder such a move fills (the folder's move carries its children), is left
 * for Pass 2, which runs after the moves.
 */
internal fun streamableNow(safeNow: List<SyncAction>): List<SyncAction> {
    val moveTargets = safeNow.mapNotNullTo(HashSet()) { (it as? SyncAction.MoveLocal)?.path }
    if (moveTargets.isEmpty()) return safeNow
    return safeNow.filterNot { it is SyncAction.DownloadContent && fillsOrBelow(it.path, moveTargets) }
}

private fun fillsOrBelow(
    path: String,
    targets: Set<String>,
): Boolean {
    if (path in targets) return true
    var parent = path.substringBeforeLast('/', "")
    while (parent.isNotEmpty()) {
        if (parent in targets) return true
        parent = parent.substringBeforeLast('/', "")
    }
    return false
}

/**
 * The old names a streamed move carries away with a local edit not yet uploaded, while the cloud changed the content
 * too. The slice planned a conflict at the new name for each, as the buffered gather does, so scan-end must not plan
 * the edit's upload at the old name as a local-only change: after the move there is no file there to upload.
 * Scan-end also keeps such a move's download as the content it fetches after the move, instead of pairing it again.
 */
internal fun movedAwayWithLocalEdit(
    streamed: List<SyncAction>,
    fullLocal: Map<String, ChangeState>,
    entryByRemoteId: Map<String, SyncEntry>,
    contentChanged: (CloudItem, SyncEntry) -> Boolean,
): Set<String> =
    streamed.mapNotNullTo(HashSet()) { action ->
        val move = action as? SyncAction.MoveLocal
        val entry = move?.let { entryByRemoteId[it.remoteItem.id] }?.takeIf { it.path == move.fromPath }
        move?.fromPath?.takeIf { entry != null && fullLocal[it] == ChangeState.MODIFIED && contentChanged(move.remoteItem, entry) }
    }
