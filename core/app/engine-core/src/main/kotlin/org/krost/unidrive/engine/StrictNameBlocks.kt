package org.krost.unidrive.engine

import org.krost.unidrive.CloudProvider
import org.krost.unidrive.NameRefusedException
import org.krost.unidrive.sync.StateDatabase

/**
 * gkrost/unidrive-cli#29: brings the `name_incompatible` blocks of the state in line with the provider's strict-name mode when a
 * daemon starts. The merge reconciles a row only when its item is delivered, and a poll delivers what changed: a mode turned on
 * (or off) between two runs would leave every settled row as it was until something touched it, and the status would say nothing
 * blocked while the mode refused names. A block of another kind (a too-similar sibling) is never touched.
 */
object StrictNameBlocks {
    /** Returns how many rows were marked or cleared; zero for a provider without a strict-name mode. */
    fun reconcileAll(
        db: StateDatabase,
        provider: CloudProvider,
    ): Int {
        if (provider.strictNameMode == null) return 0
        var changed = 0
        for ((path, remotePath, block) in db.aliveNameBlockRows()) {
            val reason = block?.substringBefore('|')
            val ours = reason == null || reason.startsWith(NameRefusedException.NAME_INCOMPATIBLE)
            if (!ours) continue
            val issue = provider.existingNameIssue(remotePath)
            when {
                issue == null && reason != null -> if (db.clearNameBlock(path)) changed++
                issue != null && issue != reason -> if (db.markNameBlocked(path, issue, null)) changed++
            }
        }
        return changed
    }
}
