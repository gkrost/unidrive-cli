package org.krost.unidrive.engine

import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncScope

/**
 * The state.db side of a change of the tracked scope. The gather applies it at the start of every run
 * ([RemoteGather.applyScopeTransition]); `unidrive scope` applies it when the scope is edited, so the
 * next run starts from the new scope. Narrowing drops the rows outside the new scope without planning any
 * delete: the local files stay and the remote is untouched. Widening clears the delta cursor so the next
 * gather enumerates the newly in-scope subtrees; rows outside the old scope were never tracked, so an
 * incremental delta could not find them.
 */
object ScopeTransition {
    const val TRACKED_SCOPE_KEY = "tracked_scope"

    /** What [apply] changed: the rows it stopped tracking and whether the next gather re-enumerates. */
    data class Result(
        val untracked: Int,
        val widened: Boolean,
    )

    /** The scope state.db tracks now; empty means the whole drive. */
    fun trackedScope(db: StateDatabase): List<String> =
        db.getSyncState(TRACKED_SCOPE_KEY).orEmpty().split("\t").filter { it.isNotEmpty() }

    /** Reshapes the tracked set of [db] to [trackScope] (normalised; empty = whole drive); null when it already is. */
    fun apply(
        db: StateDatabase,
        trackScope: List<String>,
    ): Result? {
        val prior = trackedScope(db)
        if (prior == trackScope) return null
        val narrowed =
            if (prior.isEmpty()) {
                trackScope.isNotEmpty()
            } else {
                trackScope.isNotEmpty() && prior.any { !SyncScope.contains(it, trackScope) }
            }
        val widened =
            trackScope.isEmpty() || (prior.isNotEmpty() && trackScope.any { !SyncScope.contains(it, prior) })
        val ancestors = SyncScope.ancestors(trackScope)
        var untracked = 0
        db.batch {
            if (narrowed) {
                for (entry in db.getAllEntries()) {
                    val remotePath = entry.remotePath ?: entry.path
                    if (SyncScope.contains(remotePath, trackScope) || remotePath in ancestors) continue
                    db.deleteEntry(entry.path)
                    untracked++
                }
            }
            if (widened) {
                db.setSyncState("delta_cursor", "")
                db.getSyncState(StateDatabase.SCAN_IN_PROGRESS_ID)?.let { db.completeScan(it) }
            }
            db.setSyncState(TRACKED_SCOPE_KEY, trackScope.joinToString("\t"))
        }
        return Result(untracked, widened)
    }
}
