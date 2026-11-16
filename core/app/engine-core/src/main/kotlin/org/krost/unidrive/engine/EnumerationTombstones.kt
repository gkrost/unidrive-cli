package org.krost.unidrive.engine

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry

// A tombstone removes only the row that tracks ITS item (audit 745 finding 10). Paired by path alone, the late tombstone of
// the old id of a path an item replaced under a new id would flip the live row there to deleted and evict its cache copy.
// Both ids non-empty must be equal; a tombstone without an id pairs by path (some providers send none), and so does a row
// without a remote id (nothing to compare). Tombstones the engine synthesizes from a row carry that row's id and pair with it.
//
// This mirrors the sync module's isTombstoneOfOtherItem (KindChange.kt), kept as its own copy because engine-core does not
// depend on sync. Unify the two once the open changes to that function and to RemoteGather have merged.
internal fun tombstoneBelongsToRow(
    item: CloudItem,
    row: SyncEntry,
): Boolean {
    val rowId = row.remoteId
    return rowId.isNullOrEmpty() || item.id.isEmpty() || item.id == rowId
}

/**
 * The tombstones of [remoteChanges] a complete enumeration may reap, by key, each with the alive row it is about (null when
 * no row belongs to it: such a key reaps nothing but still counts as missing, as ever). A tombstone whose item is not the one
 * its row tracks is left out altogether, so it neither counts toward the bulk-disappearance threshold nor enters the set
 * carried to the next pass as corroboration: it says nothing about the row, and the row is not missing.
 */
internal fun reapableTombstones(
    db: StateDatabase,
    remoteChanges: Map<String, CloudItem>,
): Map<String, SyncEntry?> {
    val result = LinkedHashMap<String, SyncEntry?>()
    for ((path, item) in remoteChanges) {
        if (!item.deleted) continue
        val row = rowOfTombstone(db, path)
        if (row != null && !tombstoneBelongsToRow(item, row)) continue
        result[path] = row
    }
    return result
}
