package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ChangeState
import org.krost.unidrive.sync.model.SyncAction
import org.krost.unidrive.sync.model.SyncEntry

// gkrost/unidrive-cli#723: the scan reports NEW for a path whose tracked row is of the other kind (a file replaced by a
// folder, a folder by a file) and keeps the row. The create [action] the reconciler resolved for it carries no remote id;
// the old item goes through a delete of its own first, which the provider's name check needs before the new one is made.
internal fun withDeleteOfOtherKind(
    action: SyncAction,
    localState: ChangeState,
    remoteState: ChangeState,
    entry: SyncEntry?,
    isDirectoryOnDisk: () -> Boolean,
): List<SyncAction> {
    val tracked = entry?.takeIf { localState == ChangeState.NEW && remoteState == ChangeState.UNCHANGED && it.remoteId != null }
    return if (tracked != null && tracked.isFolder != isDirectoryOnDisk()) listOf(SyncAction.DeleteRemote(action.path), action) else listOf(action)
}

// A tombstone paired with the row at its path by path alone says nothing about that row when the ids differ: the row
// tracks another item (the winner of a name a twin shares, kept under its own id), and the tombstone is the twin's. The
// gather re-keys a twin's tombstone to the twin's decorated key at scan-end, where it meets its own row. An id-less
// tombstone (some providers send none; Dropbox always) is paired by path, as ever, and so is a row with no usable remote id
// (null or empty: nothing to compare the tombstone's id against). Applies in both modes (the streaming resolvePaths and
// the buffered reconcile): the tombstone of the old id of an item the provider replaced under a new id is no delete of
// the replacement either. Tombstones the engine synthesizes from a row carry that row's id and still pair with it.
internal fun isTombstoneOfOtherItem(
    item: CloudItem,
    entry: SyncEntry?,
): Boolean = item.deleted && item.id.isNotEmpty() && entry?.remoteId?.let { it.isNotEmpty() && it != item.id } == true
