package org.krost.unidrive.sync

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
