package org.krost.unidrive.engine

import org.krost.unidrive.sync.PathNormalizer
import org.krost.unidrive.sync.model.EntryStatus
import org.krost.unidrive.sync.model.SyncEntry

/**
 * gkrost/unidrive-cli#745 finding 7: the merge ([RemoteGather.updateRemoteEntries]) wrote every delivered row on every
 * pass. A full-listing provider (localfs, S3) re-delivers its whole tree each poll, so each poll held a write
 * transaction per item, which every mount read waited behind. A delivery that leaves its row exactly as it is stored
 * has nothing to write, and the write is skipped.
 *
 * True only when the upsert would store what is already there: the merge found the row in place (no rename) and alive,
 * the merged row equals it, and the upsert would not normalise it on the way in (it stores paths in NFC and drops the
 * local hash and the cache-backed mark of a row without local bytes). The marks the upsert carries over (pending_local,
 * upload_refused, name_block) are the stored row's own either way. A row stored any other way is written, as before.
 */
internal fun mergeLeavesRowAsStored(
    existing: SyncEntry?,
    merged: SyncEntry,
    renamedFrom: String?,
): Boolean =
    existing != null &&
        renamedFrom == null &&
        merged == existing &&
        existing.status == EntryStatus.EXISTS &&
        PathNormalizer.nfc(existing.path) == existing.path &&
        (existing.remotePath == null || PathNormalizer.nfc(existing.remotePath) == existing.remotePath) &&
        (existing.isHydrated || (existing.localHash == null && existing.cacheBacked == null))

/**
 * gkrost/unidrive-cli#849: the row the merge writes for a delivery, with the stamp it earns. A delivery is a change the cloud
 * told the engine of (`remote`, now) only when it brings the row something it does not hold: a new row, or a merged row that
 * differs from the stored one. Two deliveries bring nothing and keep the stamp the row has (none, for a row from before the
 * columns, never a wrong one): one that leaves the row as stored but is still written (a full-listing provider re-lists its
 * tree every poll; the upsert normalises a path or a local hash on the way in), and the echo of the engine's own change
 * ([echoesOwnChange]).
 */
internal fun stampedByDelivery(
    existing: SyncEntry?,
    merged: SyncEntry,
    renamedFrom: String?,
): SyncEntry =
    if (existing != null && (merged == existing || echoesOwnChange(existing, merged, renamedFrom))) merged else merged.stampedRemote()

/**
 * The delivery of the version the engine itself just wrote: the row's last change is `local`, and the delivered item names
 * the same bytes (id, hash, token, size, stored object) at the same place. Only its time may differ, as a provider rounds
 * or restamps it (Internxt), and that is no change of the file. A provider that names neither a hash nor a token cannot
 * vouch for the bytes, so only a folder (which has none) is judged by its id.
 */
private fun echoesOwnChange(
    existing: SyncEntry,
    merged: SyncEntry,
    renamedFrom: String?,
): Boolean =
    existing.changedBy == "local" &&
        renamedFrom == null &&
        existing.status == EntryStatus.EXISTS &&
        merged.remoteId == existing.remoteId &&
        merged.isFolder == existing.isFolder &&
        merged.remoteHash == existing.remoteHash &&
        merged.remoteVersion == existing.remoteVersion &&
        merged.remoteSize == existing.remoteSize &&
        merged.remoteContentId == existing.remoteContentId &&
        (merged.isFolder || merged.remoteHash != null || merged.remoteVersion != null)
