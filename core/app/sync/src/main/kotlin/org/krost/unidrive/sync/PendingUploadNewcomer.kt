package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ChangeState
import org.krost.unidrive.sync.model.SyncEntry

// #743 finding 1: a local file with a pending-upload row (scanned, never uploaded) whose path the remote later
// answered with a file another device created. Against the pending row the remote side classifies as NEW (no id, no
// hash recorded), but the local file scans as UNCHANGED (the bytes match the row, so the scanner reports nothing) or
// MODIFIED (an edit before the newcomer landed). The pair used to fall outside the both-new branch: UNCHANGED planned
// a bare DownloadContent that overwrote the local bytes with no conflict copy, and MODIFIED planned nothing, so the
// recovery loop retried the row with a blind Upload that had no write guard. Both overwrite data. The pending pair is
// the both-new situation — the local bytes were never synced, so the cloud side sees them as new — and takes the
// both-new branch: adopt on identical bytes, else keep both.
internal fun isBothNewPlan(
    localState: ChangeState,
    remoteState: ChangeState,
    entry: SyncEntry?,
    remoteItem: CloudItem?,
): Boolean =
    (localState == ChangeState.NEW && remoteState == ChangeState.NEW) ||
        (
            remoteState == ChangeState.NEW &&
                remoteItem != null && !remoteItem.isFolder &&
                entry != null && entry.isPendingUpload &&
                (localState == ChangeState.UNCHANGED || localState == ChangeState.MODIFIED)
        )

// The both-new Conflict carries the local state the apply keeps aside (applyKeepBoth moves the bytes to a
// conflict-local copy for NEW and MODIFIED only). A pending upload's bytes have no synced baseline, so carrying NEW
// keeps them on the copy path; it also makes the conflict a candidate for a remote rename onto the local file
// (isBothNewFileConflict), the same treatment a both-new pair gets.
internal fun bothNewLocalState(
    localState: ChangeState,
    entry: SyncEntry?,
): ChangeState = if (entry?.isPendingUpload == true && localState == ChangeState.UNCHANGED) ChangeState.NEW else localState
