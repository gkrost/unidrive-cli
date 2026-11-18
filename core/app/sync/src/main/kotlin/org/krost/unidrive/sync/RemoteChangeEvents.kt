package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.engine.FileEventTally
import org.krost.unidrive.engine.FileEvents
import org.krost.unidrive.sync.model.SyncEntry

private const val FILE_EVENT_REMOTE_INSPECT_LIMIT = 20_000

/**
 * gkrost/unidrive-cli#394: one `remote_change` event per file the cloud reported as new, changed or gone since the row
 * the engine holds. Bounded: at most [FILE_EVENT_REMOTE_INSPECT_LIMIT] items are compared with their rows per pass, and
 * [FileEventTally.REMOTE_CHANGE_LINES_PER_PASS] lines are written (the rest are counted for the summary).
 */
internal fun logRemoteChangeEvents(
    changes: Map<String, CloudItem>,
    rowLookup: (Collection<String>) -> Map<String, SyncEntry>,
    fileTally: FileEventTally,
) {
    // #742 finding 6: the rows of the inspected files come from one chunked lookup, not one query per file.
    val inspected = changes.entries.asSequence().filter { !it.value.isFolder }.take(FILE_EVENT_REMOTE_INSPECT_LIMIT).toList()
    if (inspected.isEmpty()) return
    val rows = rowLookup(inspected.map { it.key })
    for ((path, item) in inspected) {
        val row = rows[path]
        val state =
            when {
                item.deleted -> if (row == null) continue else "deleted"
                row == null -> "new"
                row.remoteId != item.id || row.remoteSize != item.size || row.remoteHash != item.hash -> "changed"
                else -> continue
            }
        if (fileTally.mayLogRemoteChange()) {
            FileEvents.event(FileEvents.Phase.REMOTE_CHANGE, path, bytes = item.size, tally = fileTally, extra = listOf("state" to state))
        } else {
            fileTally.record(FileEvents.Phase.REMOTE_CHANGE, item.size, null, null)
        }
    }
}
