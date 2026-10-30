package org.krost.unidrive

import java.nio.file.Path

interface CloudProvider {
    val id: String
    val displayName: String

    /**
     * Hands the provider's HTTP client the place to report its request attempts to (see
     * [org.krost.unidrive.http.RequestMetricsSink]); null detaches it. Only a daemon that records metrics calls this; a
     * provider without an HTTP client of its own (local files) ignores it. The sink never blocks and never throws.
     */
    fun attachRequestMetrics(sink: org.krost.unidrive.http.RequestMetricsSink?) {}

    /** Optional read-only stored-object audit surface; null when this provider cannot verify objects without downloading. */
    val remoteObjectAuditor: RemoteObjectAuditor? get() = null

    /**
     * UD-007: writable so the default [logout] implementation can flip it.
     * Providers that own external resources (token files, network sessions,
     * delegated auth services) override the getter to compute from their
     * own state — see OneDrive's `tokenManager.isAuthenticated` delegation
     * and Internxt's `authService.isAuthenticated`. The setter on those
     * delegated implementations becomes a no-op via the override; the four
     * boilerplate providers (WebDAV, S3, Rclone, LocalFs) keep a simple
     * `override var isAuthenticated: Boolean = false` so the default
     * [logout] body can reset it without per-provider override.
     * Because the setter may do nothing, a caller that has to sign an
     * instance out in memory calls [forgetCredentials], not the setter.
     */
    var isAuthenticated: Boolean

    /** Whether enough credentials/config exist to attempt [authenticate]. */
    val canAuthenticate: Boolean get() = false

    /**
     * Truthful declaration of what this adapter implements. Every adapter MUST
     * override this — there is no default. Optional methods whose capability is
     * not in this set return [CapabilityResult.Unsupported] by contract.
     *
     * See ADR-0005 / UD-301.
     */
    fun capabilities(): Set<Capability>

    suspend fun authenticate()

    /**
     * The stable id of the user the current credentials belong to (one identity call, no sign-in), or null when this
     * provider has no user (S3, SFTP, WebDAV, local folders) or the answer carries none. Read by
     * [AccountOwner.recordIfMissing] for a profile signed in before ids were kept. Personal data: never logged.
     */
    suspend fun currentAccountId(): String? = null

    /**
     * archive#1240: proves the credentials this profile has stored against the provider with ONE authenticated call (the
     * cheapest the provider has: the drive root, the usage figure, one key listing), and returns what the provider says
     * about the account. It never signs in: no browser, no console, no device code, whatever is (or is not) stored. An
     * instance that holds no credentials yet reads the stored ones in place, as [reloadCredentials] does.
     *
     * Throws [NotSignedInException] when nothing is stored, an [AuthenticationException] when the provider refused the
     * credentials (revoked, expired beyond a refresh, wrong account), an [java.io.IOException] or a
     * [TransientNetworkException] when the provider could not be asked, any other [ProviderException] when it answered
     * with something unusable, and [UnsupportedOperationException] when the provider has no such call (the default).
     * The refresh a provider does for an expired token is its own, under the same latch as every other request of the
     * instance: a caller that must not race a running daemon's refresh asks the daemon's instance (`auth.check`), or
     * holds the profile lock.
     */
    suspend fun checkCredentials(): CredentialProof = throw UnsupportedOperationException("$id has no credential check")

    /**
     * Picks up credentials that were stored for this profile after this instance authenticated: a running daemon calls
     * it once a sign-in through it (`auth.login`, `auth.complete`) has written new credentials, so the same provider
     * instance, and every transfer that goes through it from then on, uses them without a restart. The default runs
     * [authenticate] again. A provider whose [authenticate] would fall back to an interactive sign-in when nothing is
     * stored (a browser, a console prompt) overrides this to read the stored credentials only: a daemon has nobody to
     * answer a prompt.
     */
    suspend fun reloadCredentials() {
        authenticate()
    }

    /**
     * Drops the credentials this instance holds in memory: a running daemon calls it on its own instance once a
     * sign-out (`auth.logout`) removed the stored ones, so the instance stops reporting itself [isAuthenticated] and
     * every later request fails as unauthenticated instead of going on with the cached token. A refresh still under
     * way when it is called stores nothing. Only the memory: the stored credentials are not touched, the provider is
     * not called and nothing is prompted for. [reloadCredentials] picks up a later sign-in. The default flips
     * [isAuthenticated]; a provider whose authenticated state is its in-memory token (its setter does nothing)
     * overrides this.
     */
    suspend fun forgetCredentials() {
        isAuthenticated = false
    }

    /**
     * Forget any cached credentials / authenticated state.
     *
     * UD-007: default implementation flips the in-memory flag. Providers that
     * own external resources (open API clients, token files, network
     * sessions) override to release them — typically calling `super.logout()`
     * is unnecessary because the override sets its own `isAuthenticated` via
     * its delegated state.
     */
    suspend fun logout() {
        isAuthenticated = false
    }

    /**
     * archive#1052: invalidates the stored token at the provider, so a stale copy of the credential files stops working.
     * [logout] only forgets it locally; a sign-out calls this first, then [logout]. Returns [TokenRevocation.REVOKED]
     * only after the provider confirmed the revocation. The default says the provider has no revoke call; a provider
     * that has one overrides this. Throws [AuthenticationException] when there is no valid credential to revoke, and
     * any other failure (the provider unreachable, the revocation refused) as itself: the caller still signs out locally.
     */
    suspend fun revokeToken(): TokenRevocation = TokenRevocation.UNSUPPORTED

    suspend fun listChildren(path: String): List<CloudItem>

    suspend fun getMetadata(path: String): CloudItem

    /**
     * Downloads [remotePath] to [destination] and returns the number of bytes written. A download that ends with fewer
     * bytes than the item has (a closed connection, a chunked response with no `Content-Length`) must throw, not return
     * the short count; the mount re-checks the count against the item's size and refuses a mismatch the provider does
     * not confirm (gkrost/unidrive-cli#80), but a provider that knows the size should fail first.
     */
    suspend fun download(
        remotePath: String,
        destination: Path,
    ): Long

    /** Download with optional SHA-256 of plaintext captured while the provider writes the staged file. */
    suspend fun downloadWithHash(
        remotePath: String,
        destination: Path,
        targetMtimeMillis: Long? = null,
    ): DownloadReceipt = DownloadReceipt(download(remotePath, destination))

    /** Download by known remote ID, skipping metadata lookup if the provider supports it. */
    suspend fun downloadById(
        remoteId: String,
        remotePath: String,
        destination: Path,
    ): Long = download(remotePath, destination)

    /** ID-addressed form of [downloadWithHash], preserving the existing fast path where supported. */
    suspend fun downloadByIdWithHash(
        remoteId: String,
        remotePath: String,
        destination: Path,
        targetMtimeMillis: Long? = null,
    ): DownloadReceipt = DownloadReceipt(downloadById(remoteId, remotePath, destination))

    /**
     * Upload a local file to [remotePath].
     *
     * UD-366: providers that support replace-in-place (e.g. Internxt's `PUT /files/{uuid}`)
     * use [existingRemoteId] when non-null to overwrite an existing remote entry instead of
     * creating a new one. The reconciler passes [SyncEntry.remoteId] for MODIFIED actions;
     * NEW uploads pass null. Providers without explicit replace semantics (path-based PUT
     * like OneDrive, or filesystem overwrite) ignore the parameter.
     *
     * `existingRemoteId` precedes `onProgress` in the signature so trailing-lambda call sites
     * `provider.upload(localPath, remotePath) { transferred, total -> … }` keep binding the
     * lambda to `onProgress` (Kotlin trailing-lambda convention — see memory
     * `feedback_kotlin_default_param_ordering`).
     *
     * archive#291: [ifMatchETag], when non-null, is the optimistic-concurrency token the engine
     * observed at plan time. Providers that support conditional writes (OneDrive's `If-Match`
     * on the content PUT / upload session) use it to detect a concurrent remote edit landing
     * between plan and apply — the write fails (Graph 412 Precondition Failed) instead of
     * blind-overwriting the other editor's change. It likewise precedes `onProgress` so
     * trailing-lambda call sites are unaffected. Providers without conditional-write semantics
     * (path-based or filesystem overwrite) ignore it; null means "no guard" (e.g. a NEW upload).
     */
    suspend fun upload(
        localPath: Path,
        remotePath: String,
        existingRemoteId: String? = null,
        ifMatchETag: String? = null,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): CloudItem

    /**
     * WB-3 (archive#87): discard any staged upload state the provider keeps for [localPath] — Internxt's
     * encrypted resume copy and its tombstone sidecar. The delete of a never-uploaded file calls
     * this so the user's deletion removes the only other copy of the content instead of leaving it
     * in the tombstone directory until the resume TTL expires. A provider without staged uploads
     * never needs to override this.
     */
    suspend fun discardStagedUpload(localPath: String) {}

    /**
     * Delete the remote item at [remotePath].
     *
     * archive#291: [ifMatchETag], when non-null, makes the delete conditional on the caller's view of
     * the item being current (OneDrive sends it as an `If-Match` header). A concurrent edit
     * between plan and apply flips the server-side eTag and the delete fails (412) instead of
     * destroying a version the engine never saw. Providers without conditional-delete semantics
     * ignore it; null preserves the legacy unconditional delete.
     */
    suspend fun delete(remotePath: String, ifMatchETag: String? = null)

    /**
     * archive#402: delete the item with [remoteId] instead of resolving [remotePath] by name.
     * Path-based resolution picks the first of same-named siblings, so a delete can
     * trash the wrong twin; callers that hold the row's real cloud id (non-`local:`)
     * route here. [remotePath] is carried for error messages and for providers
     * without an id-addressed delete — the default falls back to [delete].
     * Precedent: [downloadById] / [upload]'s `existingRemoteId`.
     */
    suspend fun deleteById(
        remoteId: String,
        remotePath: String,
    ) = delete(remotePath)

    /**
     * archive#1178: delete the item at [remotePath] FOR GOOD, bypassing the provider's trash where it has one (Internxt,
     * OneDrive, Dropbox). Never the default of any code path: only `rm --permanent` reaches it, behind its own
     * confirmation. Providers that declare [Capability.PermanentDelete] override this; the default throws
     * [UnsupportedCapabilityException] so an unsupported provider is a typed refusal, not a silent trash-delete.
     */
    suspend fun deletePermanently(remotePath: String): Unit =
        throw UnsupportedCapabilityException(Capability.PermanentDelete, "Provider cannot delete for good")

    /**
     * archive#1178: [deletePermanently] by the row's cloud id, as [deleteById] is to [delete]: a delete for good must not reach
     * the wrong one of two same-named siblings. The default falls back to the path.
     */
    suspend fun deletePermanentlyById(
        remoteId: String,
        remotePath: String,
    ) = deletePermanently(remotePath)

    suspend fun createFolder(path: String): CloudItem

    /**
     * UD-368: create multiple folders in a single round-trip when the underlying API
     * supports it (e.g. Internxt's polymorphic `POST /folders` bulk form, capped at 5
     * per call). Default implementation loops [createFolder] one at a time so providers
     * without bulk semantics keep working unchanged.
     *
     * Returns one [CloudItem] per requested path, in the same order. Implementations
     * that issue bulk calls are responsible for chunking against the server's per-call
     * cap and for handling partial-success / 409-conflict semantics per their backend.
     */
    suspend fun createFolders(paths: List<String>): List<CloudItem> = paths.map { createFolder(it) }

    suspend fun move(
        fromPath: String,
        toPath: String,
    ): CloudItem

    /**
     * archive#402: move the item with [remoteId] instead of resolving [fromPath] by name —
     * same rationale as [deleteById]. The default falls back to [move] so providers
     * without an id-addressed move keep working; the engine routes through here
     * whenever the source row carries a real cloud id.
     */
    suspend fun moveById(
        remoteId: String,
        fromPath: String,
        toPath: String,
    ): CloudItem = move(fromPath, toPath)

    /**
     * Walk the remote and return the change set since [cursor].
     *
     * UD-352: [onPageProgress], when supplied, fires periodically as pages
     * are accumulated so the engine can render a count-climbing heartbeat
     * during long delta walks (Internxt's 63 k-item profile sat silent for
     * minutes pre-fix). Providers paginate at very different granularities;
     * the contract is "fire often enough that a 10 s+ scan emits at least
     * once" — see [ScanHeartbeat] for the canonical math. Providers whose
     * remote-listing API is a single all-at-once call (rclone subprocess,
     * HiDrive's recursive endpoint) may legitimately leave this null — the
     * engine still emits a final-count tick once `delta()` returns. Default
     * argument keeps existing call sites and test stubs source-compatible.
     *
     * [scanContext], when supplied, switches the provider into resumable
     * mode: each successfully-fetched page is persisted via the context's
     * [ScanContext.persistPage] callback before the next is issued, and
     * [ScanContext.resumeMarker] (if non-null) tells the provider where
     * the previous interrupted scan left off. Providers without a resume
     * story may ignore it; the SPI default mirrors the existing
     * "accumulate in memory" behaviour. The engine clears the staged
     * inventory on a successful complete scan; partial scans leave it in
     * place for the next launch to resume from.
     */
    suspend fun delta(
        cursor: String?,
        onPageProgress: ((itemsSoFar: Int) -> Unit)? = null,
        scanContext: ScanContext? = null,
    ): DeltaPage

    /**
     * True when every [delta] call lists the WHOLE remote tree whatever cursor it is given and
     * reports deletions only by omission (localfs: a directory walk). The engine then runs the
     * absence sweep on every gather, as it does for a null cursor; without that, an item removed
     * remotely after the first sync would never be removed here, because no tombstone ever
     * arrives. Providers that return only changes since the cursor leave the default.
     */
    val deltaIsFullListing: Boolean get() = false

    /**
     * True when the drive can hold two live items with the same name in one folder (Internxt does). An item that an
     * incremental [delta] reports at a path the engine tracks for another item is then a duplicate, kept beside it under
     * a decorated local name, not a replacement of it. Providers whose names are unique per folder leave the default.
     */
    val allowsDuplicateNames: Boolean get() = false

    /**
     * Whether the drive tells names apart by case. The engine folds case for identity where this folds or the local file
     * system does: two names that differ only in case are one name then, and a second such name is refused instead of
     * landing on the first. The default is the safe side, folding: a drive that keeps case twins apart but declares
     * nothing only sees such twins refused, while a folding drive taken for a case-sensitive one would see one file
     * overwrite the other. Every provider in this repository declares its own.
     */
    val caseSensitivity: CaseSensitivity get() = CaseSensitivity.INSENSITIVE_PRESERVING

    /**
     * Delta that includes shared items. Providers that declare
     * [Capability.DeltaShared] must override. Default returns
     * [CapabilityResult.Unsupported] so callers fall back to [delta] explicitly.
     */
    suspend fun deltaWithShared(cursor: String?): CapabilityResult<DeltaPage> =
        CapabilityResult.Unsupported(Capability.DeltaShared, "Provider does not support delta-with-shared enumeration")

    /**
     * Fast first-sync bootstrap: return a cursor representing the remote
     * state *now* without enumerating existing items. The returned
     * [DeltaPage] has an empty or near-empty items list and a cursor that
     * subsequent [delta] calls can use to receive only future changes.
     *
     * Trade-off: items that exist on the remote before the bootstrap
     * timestamp are invisible to unidrive until they mutate. Callers
     * should surface that loudly.
     *
     * Providers that declare [Capability.FastBootstrap] must override.
     * OneDrive / Graph implements this via `?token=latest`. See UD-223.
     *
     * [scanContext] carries the same scan plumbing [delta] receives; a provider must honour
     * [ScanContext.readOnly] here too — a preview's bootstrap must leave no persistent side effect
     * (OneDrive: no `delta_last_seen` stamp).
     */
    suspend fun deltaFromLatest(scanContext: ScanContext? = null): CapabilityResult<DeltaPage> =
        CapabilityResult.Unsupported(Capability.FastBootstrap, "Provider does not support token=latest bootstrap")

    suspend fun quota(): QuotaInfo

    /**
     * UD-327: per-file size cap in bytes the target server enforces.
     *
     * Returns null when the provider has no documented or configured cap
     * (default for most providers — files larger than 4 GiB upload fine).
     * Returns a positive byte count when:
     *  - the user explicitly configured one in the profile TOML, OR
     *  - the provider has a documented hard cap (Synology DSM WebDAV defaults
     *    to 4 GiB unless the admin reconfigured nginx).
     *
     * Used by `CloudRelocator.preflightOversized` to surface oversized files
     * at planner-time, before a wasted PUT runs to its full timeout.
     */
    fun maxFileSizeBytes(): Long? = null

    /**
     * Check if a remote item still exists by ID. Default is [Unsupported] —
     * callers that *require* existence verification must check
     * [capabilities] first. This replaces the previous dangerous default of
     * always returning `true`.
     */
    suspend fun verifyItemExists(remoteId: String): CapabilityResult<Boolean> =
        CapabilityResult.Unsupported(Capability.VerifyItem, "Provider cannot verify item existence by ID")

    /**
     * What the provider says about the folder [remoteId] right now, asked by its id (archive#580): [FolderState.LIVE],
     * [FolderState.TRASHED], or [FolderState.NOT_FOUND] from a call that succeeded and found no such folder. The
     * engine asks after an upload whose path walk proved a tracked folder gone, and tombstones that folder's rows only
     * on [FolderState.TRASHED] or [FolderState.NOT_FOUND]. A provider that cannot tell answers [FolderState.UNKNOWN]
     * (the default). A call that failed (unreachable, a 5xx, a timeout) throws: it proves nothing, and the engine
     * changes nothing on it.
     */
    suspend fun folderStateById(remoteId: String): FolderState = FolderState.UNKNOWN

    /** Generate a shareable link for a file/folder. */
    suspend fun share(
        path: String,
        expiryHours: Int = 24,
        password: String? = null,
    ): CapabilityResult<String> = CapabilityResult.Unsupported(Capability.Share, "Provider does not support generating share links")

    /** List active share links for a file/folder. */
    suspend fun listShares(path: String): CapabilityResult<List<ShareInfo>> =
        CapabilityResult.Unsupported(Capability.ListShares, "Provider does not support listing share links")

    /** Revoke a share link by permission ID. */
    suspend fun revokeShare(
        path: String,
        shareId: String,
    ): CapabilityResult<Unit> = CapabilityResult.Unsupported(Capability.RevokeShare, "Provider does not support revoking share links")

    /**
     * The ordered references of the view [kind], at most [limit] of them, best first (see [ViewKind], [ViewRef]).
     * Providers that declare [Capability.View] for [kind] must override. Default is [CapabilityResult.Unsupported],
     * which is also what a provider answers for a kind it has but the server does not serve (a gateway without the
     * route): the engine then shows the view as unavailable instead of failing. A failure to reach the server throws,
     * like every other listing. A provider that cut the list (at [limit] or at a cap of its own) answers a [ViewList] with
     * `truncated` set, so the engine can say so; a plain list is one it did not cut.
     */
    suspend fun listView(
        kind: ViewKind,
        limit: Int,
    ): CapabilityResult<List<ViewRef>> = CapabilityResult.Unsupported(Capability.View(kind), "Provider does not support the $kind view")

    /**
     * Register a coarse-grained wake-signal callback. Providers that have a
     * server-pushed change feed (Internxt's socket.io `NOTIFICATIONS_URL`)
     * invoke [callback] once per observed remote mutation. A provider that
     * cannot tell its own mutations apart from another installation's on the
     * same account (Internxt: every unidrive sends the same client name) also
     * reports its own echoes. The callback is a "something changed,
     * walk the delta" hint — not authoritative payload — so the receiver
     * is expected to debounce and then trigger the normal [delta] walk.
     *
     * Default implementation is a no-op; providers without a push channel
     * inherit it.
     *
     * Called at most once per provider lifetime; the second call REPLACES
     * the first. Pass `{}` to detach. Implementations may invoke the
     * callback on any thread.
     */
    fun onRemoteChangeHint(callback: () -> Unit) { /* default: no-op */ }

    /**
     * The state of the push channel behind [onRemoteChangeHint], for `daemon.status` `notifications`. Null (the
     * default) means the provider reports none: the daemon shows `unsupported`. A provider that has a channel answers
     * a reading even while the channel is not open (`off`). Never carries the channel's endpoint: it is a credential.
     */
    fun remoteChangeChannelStatus(): RemoteChangeChannelStatus? = null

    fun close() {}

    /**
     * Algorithm this provider uses for `remoteHash` strings. Returning
     * null means the provider has no verifiable hash; callers MUST
     * treat that as "skip verification" rather than "verification
     * passed". Default null.
     */
    fun hashAlgorithm(): HashAlgorithm? = null

    /**
     * Whether [local] holds exactly the bytes of the cloud copy [remoteId] (at [remotePath]), proven WITHOUT downloading that copy,
     * for a provider whose rows carry no content hash of the plaintext ([hashAlgorithm] null; Internxt encrypts end to end, so the
     * network cannot hold one). The answer is a [ContentProof] only when a real comparison was made: same, or different. Null means
     * "this provider cannot tell" (the default, and every case the provider cannot prove: a stored shape it does not know, a
     * malformed record) and is never evidence of a difference; the caller falls back to a download and a byte compare.
     *
     * Reads [local] and the provider's metadata, writes nothing, and never logs or returns a hash value (archive#1219).
     * Throws what a metadata request throws (network, authentication); a caller treats that as "cannot tell" too.
     */
    suspend fun contentMatchesLocal(
        remoteId: String,
        remotePath: String,
        local: Path,
    ): ContentProof? = null

    /**
     * True when the provider reports a write token of its own in [CloudItem.version] and takes only that token as
     * `ifMatchETag` ([upload], [delete]): its content hash ([CloudItem.hash]) is never one, and handing it over would
     * refuse every conditional write (OneDrive compares `If-Match` with the Graph eTag). False (the default): the
     * provider has no separate token, and the engine's token for an item is its `remoteHash` (Internxt's version
     * token, or the content hash a provider checks itself).
     */
    val reportsItemVersions: Boolean get() = false

    /**
     * gkrost/unidrive-cli#202: true when the provider's listings name a file's bytes ([CloudItem.contentId]; Internxt, and OneDrive
     * since gkrost/unidrive-cli#233). A mount then fills the content id of rows written before it kept one, from one listing of their folders, once
     * per profile. False (the default): the provider names none, and nothing is listed for it.
     */
    val reportsContentIds: Boolean get() = false

    /**
     * Provider-specific status fields to render in `unidrive status`
     * after the shared fields (quota, tracked files, etc.). Empty
     * list (the default) means this provider contributes no extras.
     */
    fun statusFields(): List<StatusField> = emptyList()

    /**
     * archive#578: the effective parallelism of this provider's multipart uploads, or null
     * when the provider does not upload in parts. daemon.status reports it
     * (additive, read-only) so a client can show the setting it configured.
     */
    fun multipartUploadConcurrency(): Int? = null

    /**
     * archive#578: a runtime override of [multipartUploadConcurrency] (the daemon's
     * --upload-concurrency flag wins over the profile key). A provider that does
     * not upload in parts ignores it. Returns true when the override was applied.
     */
    fun overrideUploadConcurrency(
        concurrency: Int,
    ): Boolean = false

    /**
     * Optional warning surfaced when relocating large data INTO this
     * provider. `planSize` is the total byte count being moved.
     * Returning null (the default) means "no provider-specific
     * warning". Used by `relocate` to flag known transport ceilings
     * (e.g. WebDAV's nginx-mod_dav throughput cliff).
     */
    fun transportWarning(planSize: Long): String? = null
}

/** What [CloudProvider.revokeToken] did at the provider. */
enum class TokenRevocation {
    /** The provider confirmed that the token (and the refresh token behind it) no longer works. */
    REVOKED,

    /** The provider has no call that revokes a token; the sign-out removed the local credentials only. */
    UNSUPPORTED,
}

data class ShareInfo(
    val id: String,
    val url: String,
    val type: String,
    val scope: String,
    val hasPassword: Boolean = false,
    val expiration: String? = null,
)

/** What [CloudProvider.folderStateById] found for a folder. */
enum class FolderState {
    /** The folder exists and is not in the trash. */
    LIVE,

    /** The folder itself is in the provider's trash. */
    TRASHED,

    /** The provider answered, and has no folder with this id. */
    NOT_FOUND,

    /** The provider cannot tell (the default for a provider without a lookup by id). */
    UNKNOWN,
}
