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
