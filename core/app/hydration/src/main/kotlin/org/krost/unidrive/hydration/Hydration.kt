package org.krost.unidrive.hydration

import kotlinx.coroutines.flow.Flow
import java.nio.file.Path

/**
 * Thin verb-based SPI between the engine and platform-tier consumers
 * (Phase 2 FUSE co-daemon; future Phase 3 Dolphin extension; eventual
 * Windows / Android tiers). All paths are remote-namespace paths
 * (cloud-side), not local FS paths. The cache-path returned from
 * open_* is a local FS path inside ~/.cache/unidrive/hydration/.
 *
 * Handle IDs are caller-supplied opaque strings. The implementation
 * tracks them per IPC connection (see HydrationImpl) so a co-daemon
 * crash cleanly releases its open-set without explicit close calls.
 */
interface Hydration {
    suspend fun openForRead(connectionId: String, handleId: String, path: String): OpenResult

    /**
     * Register a write handle for [path] and start a background upload of
     * [cachePath] (see [HydrationImpl.openForWrite]). [cachePath] must lie
     * inside the profile's hydration cache folder (the path the open and
     * create verbs hand out); anything else is refused with
     * [HydrationError.InvalidPath] before any state changes. [baseEtag] is the
     * optimistic-concurrency token the client observed via `hydration.list`
     * (`etag` field) when it last read the file; when it no longer matches the
     * row's token the write is refused with [HydrationError.Conflict] BEFORE
     * any upload starts — the remote is untouched and the client keeps both
     * copies. Null (or a row with no recorded token) skips the guard: a
     * never-uploaded row has no remote version to lose.
     */
    suspend fun openForWrite(
        connectionId: String,
        handleId: String,
        path: String,
        cachePath: Path,
        baseEtag: String? = null,
    ): OpenResult
    suspend fun closeHandle(connectionId: String, handleId: String)

    /**
     * True when [connectionId] holds [handleId] open on [path]. A retried `open_write` that has not yet been
     * told the id the daemon issued uses this to return that id instead of registering another. Default is
     * false: an engine that does not track handles has nothing to reuse.
     */
    fun hasRegisteredWriteHandle(connectionId: String, handleId: String, path: String): Boolean = false

    /**
     * Cancel the queued or running upload(s) for [path] — what a client calls
     * when the user deleted the file or moved it out while an upload is still
     * in flight. Every in-flight submission for the path (waiting for its
     * per-path turn, waiting for the transfer budget, mid-transfer, or in a
     * retry backoff) is aborted and reports a Completed carrying the
     * `cancelled` token, so no client handle is left waiting. Returns true
     * when at least one submission was aborted, false when nothing was in
     * flight (the caller's goal — "no upload happens" — is met either way).
     *
     * Cancellation is cooperative: a transfer already mid-flight is cut at
     * the provider's next suspension point, which for HTTP providers aborts
     * the request; a provider that commits a partial object before its next
     * suspension point can still leave a remote item behind. Cancel is for
     * aborting in-flight work — the caller is expected to follow with the
     * row-level verb (unlink / rename) for the path itself.
     */
    suspend fun cancelUpload(path: String): Boolean

    suspend fun hydrate(path: String): HydrateResult
    suspend fun dehydrate(path: String): DehydrateResult
    suspend fun lastSynced(path: String): LastSyncedResult
    suspend fun list(prefix: String): ListResult

    /**
     * Whether the plain file [localPath] holds exactly the bytes of the cloud copy of [path], proven WITHOUT downloading that copy
     * (`hydration.matches_local`, archive#1219). The caller reads the answer as: [MatchesLocalResult.Same] (a real comparison
     * found them equal: adopt the file, upload nothing), [MatchesLocalResult.Differs] (a real comparison found them different), or
     * [MatchesLocalResult.Unknown] (no proof is possible here: the caller falls back to a download and a byte compare).
     * [MatchesLocalResult.Failed] is for a request that cannot be answered at all (the local file cannot be read).
     * The default is "unknown" for a front-end without a cloud behind it.
     */
    suspend fun matchesLocal(
        path: String,
        localPath: java.nio.file.Path,
    ): MatchesLocalResult = MatchesLocalResult.Unknown("not_supported")

    /**
     * The client does not upload the file [path] because a file of its folder, [sibling] (its name as the client spells it), is the same
     * name after NFC (`hydration.refuse_name`, gkrost/unidrive-cli#24). The refusal is kept as a row blocked by name
     * ([HydrationError.NAME_COLLIDES_TOKEN]), so the status and `hydration.list` show the file is not in the cloud and why. Idempotent.
     * The default refuses the request for a front-end without state.
     */
    suspend fun refuseName(
        path: String,
        sibling: String?,
    ): RefuseNameResult = RefuseNameResult.Failed(HydrationError.Generic("not_supported"))

    /**
     * The clash of [path] is over (one of the two files was renamed or deleted): its refusal goes. A row that was only the refusal is
     * removed; a row that holds bytes keeps them and waits for its upload again. True when there was a refusal to clear.
     */
    suspend fun clearNameRefusal(path: String): Boolean = false

    /**
     * One page of the read-only view [kind] (its wire name, `recents`, ...) for a front-end's own list: the provider's
     * references from [offset] on, at most [limit], as the real rows (`view.page`, ipc-write-protocol.md section 4.2).
     * [ViewPageResult.UnknownView] when the profile shows no such view (a layer without views answers that for every kind).
     */
    suspend fun viewPage(
        kind: String,
        offset: Int,
        limit: Int,
    ): ViewPageResult = ViewPageResult.UnknownView

    suspend fun mkdir(path: String): MkdirResult

    /** [permanent] (archive#1178): delete for good, bypassing the provider's trash (`rm --permanent`); the provider must declare [org.krost.unidrive.Capability.PermanentDelete]. */
    suspend fun unlink(
        path: String,
        permanent: Boolean = false,
    ): UnlinkResult

    /** [permanent] (archive#1178): delete the tree for good, bypassing the provider's trash (`rm --permanent -r`). */
    suspend fun rmdir(
        path: String,
        recursive: Boolean = false,
        permanent: Boolean = false,
    ): RmdirResult
    suspend fun create(connectionId: String, handleId: String, path: String): CreateResult
    suspend fun openWriteBegin(connectionId: String, path: String, handleId: String? = null): OpenResult
    /**
     * Rename [oldPath] to [newPath]. With [replace] (POSIX rename(2)
     * overwrite-if-exists semantics) an existing FILE destination is deleted
     * first — through the same delete path `unlink` takes, so whatever
     * trash/undo the provider offers applies to it — and the source then takes
     * its place. A folder destination is never replaced. Without [replace] a
     * destination that exists fails with [RenameResult.NewPathExists] (the
     * historic contract; editors that safe-save handle the refusal
     * gracefully). The delete-then-move sequence is not a single atomic
     * remote op (no provider offers atomic replace): a crash between the two
     * leaves the destination tombstoned — recoverable — and the source intact.
     */
    suspend fun rename(oldPath: String, newPath: String, replace: Boolean = false): RenameResult

    val events: Flow<HydrationEvent>

    /** Called by IpcServer when an IPC connection closes. Clears that connection's open-set. */
    fun onConnectionClosed(connectionId: String)
}

sealed class OpenResult {
    data class Ok(
        val cachePath: Path,
        // True when the path matches the profile's exclude_patterns: the write
        // is accepted but the content is deliberately never uploaded (the
        // keep-local rule). Only serialized on the wire when true.
        val excluded: Boolean = false,
    ) : OpenResult()
    data class Failed(val error: HydrationError) : OpenResult()
}

sealed class HydrateResult {
    data object Ok : HydrateResult()
    data class Failed(val error: HydrationError) : HydrateResult()
}

sealed class DehydrateResult {
    data object Ok : DehydrateResult()
    data object Busy : DehydrateResult()
    data class Failed(val error: HydrationError) : DehydrateResult()
}

sealed class LastSyncedResult {
    data class Ok(val mtimeEpochMillis: Long) : LastSyncedResult()
    data class Unknown(val reason: String) : LastSyncedResult()
}

/**
 * The answer of `hydration.matches_local`. [Same] and [Differs] come only from a real comparison ([method] says which: `size`,
 * `quickxor`, `sha256`, `recorded-sha256`, `internxt-shard-hash`, `empty`; never a hash value). [Unknown] is "no proof possible"
 * with a short [reason] token and is never evidence of a difference.
 */
sealed class MatchesLocalResult {
    data class Same(val method: String) : MatchesLocalResult()

    data class Differs(val method: String) : MatchesLocalResult()

    data class Unknown(val reason: String) : MatchesLocalResult()

    data class Failed(val error: HydrationError) : MatchesLocalResult()
}

sealed class ViewPageResult {
    /**
     * The [entries] of the page (the real rows, in the provider's order), how many references had no row to show
     * ([skipped]), where the next page starts ([nextOffset], null when the list ends here), and the [state] (`ok`, `stale`,
     * `unavailable`) and fetch time ([asOfMs]) of the list the page was cut from.
     */
    data class Ok(
        val kind: String,
        val offset: Int,
        val entries: List<ListResult.Entry>,
        val skipped: Int,
        val nextOffset: Int?,
        val state: String,
        val asOfMs: Long?,
    ) : ViewPageResult()

    /** The profile shows no view of that kind. Wire token `unknown_view`. */
    data object UnknownView : ViewPageResult()

    data class Failed(val error: HydrationError) : ViewPageResult()
}

sealed class ListResult {
    data class Ok(val entries: List<Entry>) : ListResult()
    data class Failed(val error: HydrationError) : ListResult()

    data class Entry(
        val path: String,
        val size: Long,
        val mtimeEpochMillis: Long,
        val isHydrated: Boolean,
        val isFolder: Boolean,
        // Remote modified time straight from the provider, unlike [mtimeEpochMillis]
        // which is the local watermark (enumeration time for cloud-only rows,
        // download time for hydrated ones). Null for rows the provider never
        // reported a modified time for (never-uploaded rows).
        val remoteModifiedEpochMillis: Long? = null,
        // Provider identity of the row; null while the upload is still pending
        // (a row created/edited through the mount that has not landed cloud-side
        // yet). Lets a mirroring client recognise a remote rename (old path reaped,
        // new path upserted) as the same item.
        val remoteId: String? = null,
        // Provider change-detection token (content hash where the provider offers
        // one). Null when the provider exposes none — NOT a conditional-write
        // etag; see the open_write base_etag docs.
        val etag: String? = null,
        // True when the row holds local bytes whose upload has not completed —
        // created/edited through the mount, not yet on the cloud.
        val pendingUpload: Boolean = false,
        // True when the last write-back upload attempt against this row failed
        // (state.db last_error_at stamped; `unidrive doctor` surfaces the same gap).
        val hasError: Boolean = false,
        // True when the path matches the profile's exclude_patterns: the row is
        // served from local content that is deliberately never uploaded. Only
        // serialized on the wire when true.
        val excluded: Boolean = false,
        // Why the drive refuses this entry by its name (`similar_name`), or that it waits for a folder above it that
        // it refuses (`blocked_by_parent`). Null for every other row; only serialized on the wire when set.
        val blocked: String? = null,
        // True for the trash namespace's root folder and everything below it: items the provider keeps in its trash, shown
        // apart from the live tree. They can be read, hydrated and copied out; every write is refused with `read_only`
        // (see HydrationError.ReadOnly), so a client refuses the action before it asks. Only serialized on the wire when true.
        val readOnly: Boolean = false,
        // True for the root folder of the trash namespace itself (the row with the stable remote id `unidrive:trash`),
        // for a client that labels it and gives it an icon of its own. Only serialized on the wire when true.
        val trashRoot: Boolean = false,
        // The wire name of the view this entry belongs to (`recents`, ...): the root folder of a read-only view and every entry
        // listed in one. Such an entry is `readOnly` too; its `remoteId` is the view's synthetic key, never the real item's id.
        // Null for every other row; only serialized on the wire when set.
        val view: String? = null,
        // For an entry in a view: the logical path of the real item it stands for (reads of the entry are resolved to it).
        // Null for the view's root folder and for every other row; only serialized on the wire when set.
        val viewTarget: String? = null,
    )
}

sealed class MkdirResult {
    data object Ok : MkdirResult()
    data class Failed(val error: HydrationError) : MkdirResult()
    data object ParentNotFound : MkdirResult()
}

sealed class UnlinkResult {
    data object Ok : UnlinkResult()
    data class Failed(val error: HydrationError) : UnlinkResult()
    data object PathIsFolder : UnlinkResult()

    /**
     * WB-3 (archive#87): the file's own upload is queued or in flight, so the delete cannot be answered
     * yet — deleting the row and the cache copy under a running upload would orphan its cloud copy
     * (the upload lands after the row is gone). The client cancels the upload
     * ([Hydration.cancelUpload]) and retries, or retries after the upload's completed event. Wire
     * token `busy` — the same one dehydrate has answered since archive#301.
     */
    data object Busy : UnlinkResult()
}

sealed class RmdirResult {
    data object Ok : RmdirResult()
    data class Failed(val error: HydrationError) : RmdirResult()
    data object PathIsFile : RmdirResult()
    data object NotEmpty : RmdirResult()

    /**
     * WB-3 (archive#87): an upload is queued or in flight for a file below the folder. The delete would
     * tombstone that file's row and evict its cache copy under the upload. Wire token `busy`, as
     * for [UnlinkResult.Busy]; the client retries after the upload's completed event.
     */
    data object Busy : RmdirResult()
}

sealed class CreateResult {
    data class Ok(
        val cachePath: Path,
        val handleId: String,
        // True when the path matches the profile's exclude_patterns: the row
        // and cache file are created (the file exists locally), but the
        // content is deliberately never uploaded (keep-local). Only serialized
        // on the wire when true.
        val excluded: Boolean = false,
    ) : CreateResult()
    data class Failed(val error: HydrationError) : CreateResult()
    data object ParentNotFound : CreateResult()
    data object PathExists : CreateResult()
}

/** The answer of `hydration.refuse_name` (gkrost/unidrive-cli#24). */
sealed class RefuseNameResult {
    data object Ok : RefuseNameResult()

    /** The cloud has the name already: its row is the cloud item's and is not marked (a block would make its delete and rename local-only). */
    data object InCloud : RefuseNameResult()

    data object PathIsFolder : RefuseNameResult()

    data object ParentNotFound : RefuseNameResult()

    data class Failed(val error: HydrationError) : RefuseNameResult()
}

sealed class RenameResult {
    data object Ok : RenameResult()
    data class Failed(val error: HydrationError) : RenameResult()
    data object OldPathNotFound : RenameResult()
    data object NewParentNotFound : RenameResult()
    data object NewPathExists : RenameResult()
}
