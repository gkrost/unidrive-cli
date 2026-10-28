package org.krost.unidrive.sync.model

import java.time.Instant

data class SyncEntry(
    val path: String,
    // archive#115: canonical REMOTE path for a row whose local [path] is an XDG
    // locale alias (e.g. path=/Bilder/x.jpg, remotePath=/Pictures/x.jpg).
    // When null the remote path EQUALS [path] — the universal, non-aliased
    // case, so a null here is byte-identical to pre-archive#115 behaviour. The
    // effective remote path of any row is therefore `remotePath ?: path`.
    // [path] always stays the REAL local sync_root-relative path so
    // LocalScanner (which keys/scans by the on-disk name) keeps finding the
    // row; the reconciler's remote-delta match uses the effective remote
    // path so an uploaded-to-canonical row is found by its canonical delta
    // key without re-uploading or planning a spurious local move.
    val remotePath: String? = null,
    val remoteId: String?,
    val remoteHash: String?,
    val remoteSize: Long,
    val remoteModified: Instant?,
    val localMtime: Long?,
    val localSize: Long?,
    val isFolder: Boolean,
    val isPinned: Boolean,
    val isHydrated: Boolean,
    val lastSynced: Instant,
    // Parent's provider UUID. Internxt populates from folderUuid/parentUuid so
    // alive children of a folder are reachable via the (parent_uuid, status)
    // composite index. Null = drive root, OR provider doesn't track parent
    // identity (OneDrive in v1), OR row is a pending-upload placeholder whose
    // cloud parent isn't known yet.
    val parentUuid: String? = null,
    val status: EntryStatus = EntryStatus.EXISTS,
    // Permanent-failure quarantine flag. True when a download against this
    // row's remote_id returned a stable 404 ("object is gone, retry won't
    // recover it"). The recovery loops in Reconciler skip quarantined rows
    // so the engine doesn't burn cycles retrying the same dead identifier;
    // the next delta event that re-reports the same remote_id clears the
    // flag (handled in updateRemoteEntries).
    val downloadQuarantined: Boolean = false,
    // Timestamp of the last permanent failure that set [downloadQuarantined].
    // Recorded for operator audit; not currently used as input to any
    // policy decision.
    val lastErrorAt: Instant? = null,
    // archive#396: SHA-256 (lowercase hex) of the local file's bytes as the engine last wrote
    // (download) or sent (upload) them. Recorded only for providers with no remote content
    // hash (hashAlgorithm() == null), where it is the only way LocalScanner can tell a
    // touched-but-unchanged file (mtime bumped by a shell handler, indexer, antivirus...)
    // from a real edit. Null = unknown, which keeps the plain mtime+size behaviour.
    val localHash: String? = null,
    // archive#449: which file the local baseline (localMtime, localSize, localHash) describes. Written by the
    // mount (MountEngine); the mirror engine never sets it.
    // true  = the hydration cache copy: the bytes were written or downloaded into the cache.
    // false = not the cache copy: the mount's cache sweep found a cache copy that is not the baseline
    //         (MountEngine.evictCacheCopy), or a legacy hybrid row whose baseline was the sync-root file.
    // null  = unknown: rows written before the column, and every row the mirror engine writes. The
    //         mount's cache sweep treats the cache copy as the baseline only while its mtime and size
    //         match the row (MountEngine.cacheDisposition).
    // Only meaningful while [isHydrated]; the database stores null for a row without local bytes.
    // The mirror's Reconciler does not read it (archive#680): in a mirror profile a missing sync-root file is
    // a local delete whatever this column holds.
    val cacheBacked: Boolean? = null,
    // archive#1070/#1069: set while the mount's cache copy of this cloud-backed file holds an edit the cloud has not seen:
    // the cloud version token the edit started from (the row's [writeToken] then; "" when it had none). The row's
    // own token moves with every poll, so only this says which version the bytes derive from. Written when the
    // copy is first made dirty (open_write_begin, open_write); the start-up replay sends the edit with this token,
    // and a read, dehydrate or reap treats the cache copy as the copy of record while it is set. Cleared by the
    // upload that lands and by a download that replaces the copy. Null = no unsynced edit is known (every row the
    // mirror engine writes); the mount's older mtime/size baseline test still catches an edit of a hydrated row.
    val editBase: String? = null,
    // The provider's write token of the version the row records ([org.krost.unidrive.CloudItem.version]; OneDrive:
    // the Graph eTag), kept apart from [remoteHash], which stays the content hash for change detection. Written by
    // a delivery and by an upload that lands. Null = the provider has none (its remoteHash is the token: Internxt),
    // or none is known yet (every row from before the column, until its item is delivered or uploaded again).
    val remoteVersion: String? = null,
    // gkrost/unidrive-cli#164: the provider's name for the bytes of the version the row records
    // ([org.krost.unidrive.CloudItem.contentId]; Internxt: the stored object and the size), for a provider whose [remoteHash] is no
    // content hash. Written with [remoteHash] wherever the row takes an item's version. Null = the provider names none, or none is
    // known yet (every row from before the column, until its item is delivered, read or uploaded again).
    val remoteContentId: String? = null,
) {
    /**
     * The token a write of this row's file is based on, the one rule for the whole engine: the provider's own
     * [remoteVersion] when the row has one, else [remoteHash]. `hydration.list` hands it out as `etag`, the edit
     * mark ([editBase]) records it, and the base-token guard compares with it. A computed property, like
     * [isPendingUpload].
     */
    val writeToken: String?
        get() = remoteVersion ?: remoteHash

    /**
     * True when a write based on [base] starts from the version this row records: [base] is the row's [writeToken],
     * or (for a row that has a [remoteVersion]) its [remoteHash], the token handed out before the row had a version.
     * The content hash only matches while the content is the same; it says nothing about a metadata change.
     */
    fun isBasedOn(base: String): Boolean = base == writeToken || (remoteVersion != null && base == remoteHash)

    /**
     * The token a conditional write based on [base] may hand to the provider as `ifMatchETag`, or null for an
     * unconditional write (the provider then checks what it can itself). The row's [remoteVersion] passes; a
     * [remoteHash] passes only for a provider without versions of its own ([providerReportsVersions] false), whose
     * remoteHash is its token. Null when [base] is null.
     */
    fun ifMatchFor(
        base: String?,
        providerReportsVersions: Boolean,
    ): String? =
        when {
            base == null -> null
            remoteVersion != null && base == remoteVersion -> base
            providerReportsVersions -> null
            else -> base
        }

    // UD-901 / archive#136: the pending-upload predicate, assembled in ONE place. A pending
    // upload is a file whose only copy is the local bytes and which has never
    // reached the cloud. Consumers used to re-assemble the predicate from halves
    // (`remoteId == null` here, `isHydrated` there), and rows in the gap — a sparse
    // partial-download row (remoteId == null, isHydrated == false) has no real bytes
    // to upload — were counted or re-uploaded by whichever half the consumer checked.
    // Where the local bytes are follows from the profile's mode (archive#560): a mirror
    // profile's pending rows are LocalScanner's (bytes in the sync root, uploaded by
    // the next sync), a mount profile's are the mount's (bytes in the hydration cache,
    // replayed by the daemon). A legacy profile converted to a mount can still hold
    // scanner rows whose bytes were quarantined; the replay skips a row without a cache
    // copy (HydrationImpl.replayable).
    // Deliberately a computed property: derived from two stored columns, so it takes
    // no part in equals/copy.
    val isPendingUpload: Boolean
        get() = remoteId == null && isHydrated
}

/**
 * Lifecycle state of a [SyncEntry]. EXISTS rows are the only ones the sync
 * loop reads (via the alive-only view); TRASHED and DELETED are tombstones
 * preserved for recovery and for the un-trash-on-scan branch.
 */
enum class EntryStatus { EXISTS, TRASHED, DELETED }
