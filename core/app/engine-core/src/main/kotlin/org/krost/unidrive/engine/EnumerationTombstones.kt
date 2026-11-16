package org.krost.unidrive.engine

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.Logger

/**
 * Audit 745 finding 10: a tombstone removes only the row that tracks ITS item. Paired by path alone, the late tombstone of
 * the old id of a path an item replaced under a new id would flip the live row there to deleted and evict its cache copy.
 *
 * Both ids non-empty must be equal (exact, case-sensitive); a tombstone without an id pairs by path (some providers send
 * none), and so does a row without a remote id (null or empty: nothing to compare). Tombstones the engine synthesizes from a
 * row carry that row's id and pair with it.
 *
 * This mirrors the sync module's isTombstoneOfOtherItem (KindChange.kt), kept as its own copy because engine-core does not
 * depend on sync. The empty-row-id cell matches PR 776 (open, branch fix/tombstone-id-hardening) and DIFFERS from main's
 * isTombstoneOfOtherItem, which pairs a null row id by path but treats an empty one as another item. After 776 merges the
 * two should be unified: one public function in engine-core, with KindChange delegating to it.
 */
internal fun tombstoneBelongsToRow(
    item: CloudItem,
    row: SyncEntry,
): Boolean {
    val rowId = row.remoteId
    return rowId.isNullOrEmpty() || item.id.isEmpty() || item.id == rowId
}

/** What the row lookup of one tombstone found: [row] is null when no row is there; [skipped] when the rows there are another item's. */
internal class PairedRow(
    val row: SyncEntry?,
    val skipped: Boolean = false,
)

/**
 * The row the tombstone [item] keyed at [path] is about. The key is the item's REMOTE path, and [rowOfTombstone] takes the
 * first row it finds: by local path, then by effective remote path. When that first row is another item's (an alias
 * collision: a row whose local path is [path] tracks another item, while the tombstone's own row is only reachable by its
 * remote path), the other lookup is tried before the tombstone is skipped. No lookup by id: the item may have moved, and
 * its row at the new path is alive.
 */
internal fun pairedRow(
    db: StateDatabase,
    path: String,
    item: CloudItem,
    log: Logger? = null,
): PairedRow {
    val first = rowOfTombstone(db, path) ?: return PairedRow(null)
    if (tombstoneBelongsToRow(item, first)) return PairedRow(first)
    db.getEntryByRemotePath(path)?.takeIf { tombstoneBelongsToRow(item, it) }?.let { return PairedRow(it) }
    log?.debug("enumerate: skipping the tombstone of {} at {}: the row there tracks {}", item.id, path, first.remoteId)
    return PairedRow(null, skipped = true)
}

/**
 * The keys of the tombstones of [remoteChanges] a complete enumeration may reap. A tombstone no row belongs to stays in (it
 * reaps nothing but counts as missing, as ever). A tombstone of another item than the row it meets is left out: it says
 * nothing about that row, which is not missing, so it neither counts toward the bulk-disappearance threshold nor enters
 * the set carried to the next pass as corroboration. Rows are not kept: the reap fetches the row of the corroborated keys
 * again, so a mass disappearance holds no row per tombstone.
 */
internal fun reapableTombstones(
    db: StateDatabase,
    remoteChanges: Map<String, CloudItem>,
    log: Logger? = null,
): Set<String> =
    remoteChanges.entries
        .filter { it.value.deleted && !pairedRow(db, it.key, it.value, log).skipped }
        .mapTo(LinkedHashSet()) { it.key }
