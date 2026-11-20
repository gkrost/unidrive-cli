package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncAction

/**
 * archive#424: a remote folder rename reports the folder AND its descendants at their new paths, so the move detection
 * plans a MoveLocal for each. The folder's move already carries every descendant (on disk, and in state.db through
 * renamePrefix), so a descendant move whose destination is just its old path re-rooted under the folder's new path has
 * nothing left to do. Run anyway, it found its source gone and recreated an empty placeholder over the file the folder
 * move had put there. Such moves are dropped here. A descendant that ALSO got a new name inside the moved folder keeps
 * its move, with its source re-rooted under the folder's new path: that is where the file is once the folder move (which
 * sorts first, its destination being shallower) has run.
 *
 * gkrost/unidrive-cli#743 finding 7, the tail: every MoveLocal used to be compared with every folder move, three times:
 * O(moves × folder moves), about 10⁸ comparisons for a renamed tree of 10k listed subfolders. The folder moves are
 * indexed by their old path, and each move walks the ancestors of its own source: O(depth) per move. [onProbe] counts
 * the ancestors and folder moves looked at (the cost the tests pin). The verdicts are the pairwise ones.
 */
internal fun dropMovesCarriedByFolderMoves(
    actions: MutableList<SyncAction>,
    onProbe: () -> Unit = {},
) {
    val folderMoves = actions.filterIsInstance<SyncAction.MoveLocal>().filter { it.remoteItem.isFolder }
    if (folderMoves.isEmpty()) return
    // In plan order within a bucket: of folder moves with one old path, the first that fits wins, as maxByOrNull chose.
    val byFrom = folderMoves.groupBy { it.fromPath }
    actions.removeAll { action ->
        action is SyncAction.MoveLocal &&
            ancestorsOf(action.fromPath, onProbe).any { from ->
                byFrom[from].orEmpty().any { folder ->
                    onProbe()
                    folder !== action && action.path == folder.path + action.fromPath.removePrefix(folder.fromPath)
                }
            }
    }
    for (i in actions.indices) {
        val move = actions[i] as? SyncAction.MoveLocal ?: continue
        // The deepest folder move that both contains the source and keeps the destination inside its new path.
        // Re-rooting is exact only when no other folder move sits between it and the source: one deeper than the
        // carrier that contains the source, whatever its destination.
        var between = false
        var carrier: SyncAction.MoveLocal? = null
        for (from in ancestorsOf(move.fromPath, onProbe)) {
            val bucket = byFrom[from].orEmpty()
            carrier =
                bucket.firstOrNull { f ->
                    onProbe()
                    f !== move && move.path.startsWith(f.path + "/")
                }
            if (carrier != null) break
            if (bucket.any { it !== move }) between = true
        }
        if (carrier == null || between) continue
        actions[i] = move.copy(fromPath = carrier.path + move.fromPath.removePrefix(carrier.fromPath))
    }
}

// Every p with path.startsWith("$p/"), deepest first (the cut at each '/', down to "" for a leading one).
private fun ancestorsOf(
    path: String,
    onProbe: () -> Unit,
): Sequence<String> =
    sequence {
        var cut = path.lastIndexOf('/')
        while (cut >= 0) {
            onProbe()
            yield(path.substring(0, cut))
            cut = path.lastIndexOf('/', cut - 1)
        }
    }
