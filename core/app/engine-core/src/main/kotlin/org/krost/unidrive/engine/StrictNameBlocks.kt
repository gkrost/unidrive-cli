package org.krost.unidrive.engine

import org.krost.unidrive.CloudProvider
import org.krost.unidrive.NameRefusedException
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.aliveNameBlockRowsAfter

/**
 * gkrost/unidrive-cli#29: brings the `name_incompatible` blocks of the state in line with the provider's strict-name mode when a
 * daemon starts. The merge reconciles a row only when its item is delivered, and a poll delivers what changed: a mode turned on
 * (or off) between two runs would leave every settled row as it was until something touched it, and the status would say nothing
 * blocked while the mode refused names. A block of another kind (a too-similar sibling) is never touched.
 *
 * #845 (#744 finding 6): the table is read in steps of [CHUNK_ROWS] rows and each step's changes are one transaction, so memory
 * holds one step and a run commits once per step with a change, not once per changed row.
 */
object StrictNameBlocks {
    internal const val CHUNK_ROWS = 1_000

    /** Returns how many rows were marked or cleared; zero for a provider without a strict-name mode. */
    fun reconcileAll(
        db: StateDatabase,
        provider: CloudProvider,
    ): Int = reconcileAll(db, provider, CHUNK_ROWS)

    internal fun reconcileAll(
        db: StateDatabase,
        provider: CloudProvider,
        chunkRows: Int,
    ): Int {
        if (provider.strictNameMode == null) return 0
        var changed = 0
        var after = Long.MIN_VALUE
        while (true) {
            val rows = db.aliveNameBlockRowsAfter(after, chunkRows)
            if (rows.isEmpty()) return changed
            after = rows.last().rowid
            val writes = mutableListOf<() -> Boolean>()
            for (row in rows) {
                val reason = row.nameBlock?.substringBefore('|')
                val ours = reason == null || reason.startsWith(NameRefusedException.NAME_INCOMPATIBLE)
                if (!ours) continue
                val issue = provider.existingNameIssue(row.remotePath)
                when {
                    issue == null && reason != null -> writes += { db.clearNameBlock(row.path) }
                    issue != null && issue != reason -> writes += { db.markNameBlocked(row.path, issue, null) }
                }
            }
            if (writes.isNotEmpty()) changed += db.batch { writes.count { it() } }
            if (rows.size < chunkRows) return changed
        }
    }
}
