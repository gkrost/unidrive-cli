package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncAction
import java.util.IdentityHashMap

/**
 * Reconciler.detectRemoteRenames: a remote folder rename carries what lies below its old path, so the placeholders and
 * downloads planned there go. gkrost/unidrive-cli#743 finding 7: they used to be removed by one scan of the whole plan
 * per renamed folder, O(renamed folders × plan). The old paths are collected here and the plan is scanned once, each
 * action walking its own ancestors.
 *
 * The verdict is the per-rename scan's: that scan removed what the plan held at that moment, so an action the rename loop
 * appends later ([appended]) is dropped only by a folder that left after it was appended.
 */
internal class CarriedRenameChildren {
    // Old folder path -> the latest step it left at (steps count from 1).
    private val leftAt = HashMap<String, Int>()
    private var steps = 0
    private val bornAt = IdentityHashMap<SyncAction, Int>()

    /** The folder at [oldPath] moved away: what is planned below it is carried. */
    fun folderLeft(oldPath: String) {
        leftAt[oldPath] = ++steps
    }

    /** [action] was appended to the plan now: the folders that left before it do not carry it. */
    fun appended(action: SyncAction) {
        bornAt[action] = steps
    }

    /** Takes the carried placeholders and downloads out of [actions], in one pass. */
    fun dropFrom(actions: MutableList<SyncAction>) {
        if (leftAt.isEmpty()) return
        actions.removeAll { action ->
            (action is SyncAction.CreatePlaceholder || action is SyncAction.DownloadContent) && carried(action)
        }
    }

    private fun carried(action: SyncAction): Boolean {
        val born = bornAt[action] ?: 0
        var cut = action.path.lastIndexOf('/')
        while (cut > 0) {
            val above = action.path.substring(0, cut)
            if ((leftAt[above] ?: 0) > born) return true
            cut = above.lastIndexOf('/')
        }
        // The empty old path is a prefix of every absolute path ("/" + rest).
        return cut == 0 && (leftAt[""] ?: 0) > born
    }
}
