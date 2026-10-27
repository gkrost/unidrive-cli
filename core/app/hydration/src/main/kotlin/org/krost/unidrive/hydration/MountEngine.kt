package org.krost.unidrive.hydration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.ContentProof
import org.krost.unidrive.FolderState
import org.krost.unidrive.NameRefusedException
import org.krost.unidrive.PermanentDownloadFailureException
import org.krost.unidrive.PermanentUploadFailureException
import org.krost.unidrive.RemoteContentRefusedDownloadException
import org.krost.unidrive.RemoteIncompleteDownloadException
import org.krost.unidrive.engine.AuditSink
import org.krost.unidrive.engine.CachePaths
import org.krost.unidrive.engine.EnumerationEntryPoint
import org.krost.unidrive.engine.MountHost
import org.krost.unidrive.engine.MountWiring
import org.krost.unidrive.engine.RemoteGather
import org.krost.unidrive.engine.RemoteOperationGuard
import org.krost.unidrive.engine.TransferPause
import org.krost.unidrive.engine.Transfers
import org.krost.unidrive.engine.ViewCache
import org.krost.unidrive.engine.ViewDirectory
import org.krost.unidrive.engine.ViewNamespaces
import org.krost.unidrive.sync.EnumerateResult
import org.krost.unidrive.sync.EnumerationStatus
import org.krost.unidrive.sync.EnumerationTracker
import org.krost.unidrive.sync.HashVerifier
import org.krost.unidrive.sync.PathNormalizer
import org.krost.unidrive.sync.RowHashProof
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.EntryStatus
import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.telemetry.TransferHandle
import org.slf4j.Logger
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * archive#560 U3: the mount front-end. The operations the hydration layer ([HydrationImpl]) and the daemon
 * run against the cloud and state.db for a mount: hydrate a path into the cache, upload a cache copy,
 * create, delete and rename remote items, and the remote enumeration. They used to live in the mirror
 * engine (`SyncEngine`, :app:sync) and moved here unchanged; this module no longer depends on :app:sync
 * (`checkModuleEdges`).
 *
 * archive#560 U6 (cutover): a mount profile touches no sync root. The bytes a mount write puts in the cache
 * are the bytes that upload ([uploadFromCache]); a read is served from the cache or downloaded, never
 * seeded from a local folder; a delete or rename leaves no local copy behind. A pending write keeps its
 * own source — the cache — so nothing outside the profile (the client's root move) can strand
 * it, and the 0-byte-placeholder shape of archive#524 cannot arise from the engine.
 *
 * Built over a [MountWiring] ([over]): the shared core of the engine that owns it, today `SyncEngine`
 * (the compatibility adapter until mount profiles get their own host, archive#560 U4/U6). One front-end per
 * wiring.
 *
 * Who owns what:
 *  - **Queues.** The upload queue (per-path serialisation, waiting depth, retries, replay) is
 *    [HydrationImpl]'s. The transfer budget (UD-263) is the [RemoteOperationGuard] of the wiring: one
 *    semaphore per engine, shared by the mirror's sync pass and the hydration uploads
 *    ([withTransferPermit]). This class keeps no queue.
 *  - **Scopes.** The standing scope, the per-run sync paths and the exclude patterns are fixed in the
 *    guard by the host at construction; this class only reads them ([isOutOfScope], [isExcludedPath]).
 *    A scope transition is the gather's (`RemoteGather.applyScopeTransition`).
 *  - **Transactions.** Each operation writes its rows itself after the provider call succeeded (one
 *    upsert, or `markDeleted`/`renamePrefix` for a subtree). The enumeration's batch commit and the
 *    cursor belong to `RemoteEnumeration`/`RemoteGather`. The database connection is the host's.
 *  - **Cancellation.** The caller's coroutine is the unit: every operation is a suspend function that
 *    rethrows [kotlinx.coroutines.CancellationException]; a cancelled hydration deletes its staging
 *    file, a cancelled upload is audited as failed and leaves the row pending. Background work runs
 *    elsewhere: the upload queue in [HydrationImpl]'s `recoveryUploadScope`, the poller in the
 *    daemon's serve scope.
 *  - **Pause (archive#778).** [pause] holds the daemon's pause. A cold [ensureHydrated] is refused while it is set
 *    ([TransferPausedException], answered `paused`), one already downloading is judged by the in-flight rule of
 *    [PauseGate.abortSlowTransfers]; a warm-cache serve needs no cloud and is unaffected. The uploads wait in
 *    [HydrationImpl]'s queue; the enumeration is gated by the daemon's handlers and poller. The downloads of
 *    cloud-only rule files ask the same gate (attached to the guard, archive#660).
 *  - **Shutdown order.** This class holds no thread, file or connection to close. The daemon cancels
 *    its serve scope (the poller, the IPC handlers, the hydration event fan-out), then closes the IPC
 *    server, the state database and the process lock, in that order (`DaemonRuntime.cleanup`). The
 *    upload queue's scope is not part of that: the daemon builds [HydrationImpl] with its default
 *    scope, so an upload still running then is not cancelled first; a row it could not settle stays
 *    pending for the replay at the next start (unchanged by archive#560 U3).
 */
class MountEngine private constructor(
    private val wiring: MountWiring,
) : EnumerationEntryPoint {
    private val provider: CloudProvider = wiring.provider
    private val db: StateDatabase = wiring.db
    private val guard: RemoteOperationGuard = wiring.guard
    private val options: MountWiring.Options = wiring.options

    // UD-113: the host's audit log of mutations, or null.
    private val auditLog: AuditSink? = wiring.auditLog

    private val gather: RemoteGather = wiring.gather
    private val transferBudget: Semaphore get() = guard.transferBudget

    /** The number of transfers this profile runs at once (the provider's `maxConcurrentTransfers`). */
    val transferBudgetSize: Int get() = guard.maxConcurrentTransfers
    private val uploadInFlight: (path: String) -> Boolean = wiring.uploadInFlight
    private val syncPaths: List<String> = options.syncPaths

    // The host's logger: the moved log lines keep their logger name.
    private val log: Logger = wiring.log

    /**
     * archive#658: epoch ms of the last provider round trip this engine actually made — a completed download
     * or upload, never a warm-cache serve or a local-only write. `daemon.status` reports it as
     * `provider_health.last_contact_ms`, so an outage keeps the last real contact.
     */
    @Volatile
    var lastProviderContactAtMs: Long? = null
        private set

    private fun recordProviderContact() {
        lastProviderContactAtMs = System.currentTimeMillis()
    }

    @Volatile
    private var transferAuthRejectedAtMs: Long? = null

    /**
     * archive#586: epoch ms of the latest time the provider refused this profile's credentials, or null: a transfer or an
     * enumeration that failed with an [AuthenticationException], which a provider raises only after its own token
     * refresh has failed. A reader compares it with the last provider contact: a rejection older than a success is
     * history. `daemon.status` turns a newer one into `credential_health.state = "expired"` without waiting for the
     * credential check to run again.
     */
    val authRejectedAtMs: Long?
        get() = listOfNotNull(transferAuthRejectedAtMs, wiring.enumeration.tracker.authRejectedAtMs).maxOrNull()

    private fun noteAuthRejection(e: Throwable) {
        if (e is AuthenticationException) transferAuthRejectedAtMs = System.currentTimeMillis()
    }

    private inline fun <T> watchingCredentials(block: () -> T): T {
        try {
            return block()
        } catch (e: AuthenticationException) {
            noteAuthRejection(e)
            throw e
        }
    }

    /**
     * archive#778: the daemon's pause. A download ([ensureHydrated]) starts only while it is clear, and the upload queue
     * ([HydrationImpl]) consults the same gate; `daemon.pause` and `daemon.resume` set it. One per front-end.
     */
    val pause: PauseGate = PauseGate()

    init {
        // archive#660: the downloads of cloud-only rule files ask the same pause, and a folder held back for it is probed
        // again once it ends.
        guard.attachPause(
            object : TransferPause {
                override val isPaused: Boolean get() = pause.isPaused

                override suspend fun track(
                    path: String,
                    block: suspend () -> Unit,
                ) = pause.track(path) { block() }
            },
        )
        pause.onResume(guard::pauseEnded)
    }

    /**
     * See [RemoteOperationGuard.isOutOfScope]. A path in a view is never out of scope: a view holds no row of its own
     * (the entries it lists were scoped when their rows were written), and a write there is refused as read-only,
     * which the scope check would otherwise pre-empt with `outside_scope`.
     */
    fun isOutOfScope(path: String): Boolean = guard.isOutOfScope(path) && !guard.views.contains(path)

    /** See [RemoteOperationGuard.isReadOnlyPath]. */
    fun isReadOnlyPath(path: String): Boolean = guard.isReadOnlyPath(path)

    /** The profile's local root, or null; see [MountWiring.localRoot]. */
    fun localRoot(): Path? = options.localRoot

    /** See [RemoteOperationGuard.keepsLocal]. */
    fun keepsLocal(path: String): Boolean = guard.keepsLocal(path)

    /** The views of the profile: which virtual folders exist and where (see [RemoteOperationGuard.views]). */
    val viewNamespaces: ViewNamespaces get() = guard.views

    @Volatile
    private var viewDirectory: ViewDirectory? = null

    /**
     * The views' listing and resolution, once the host has handed over the [cache] of the provider's references
     * ([attachViews]); null before that and for a profile without views. Late-bound like the hooks into the hydration
     * layer: the cache needs the daemon's scope and its event fan-out, which exist after the engine does.
     */
    val views: ViewDirectory? get() = viewDirectory

    /** Hands the mount the references of the views; see [views]. */
    fun attachViews(cache: ViewCache) {
        viewDirectory = ViewDirectory(guard, db, cache)
    }

    /** True when [path] is the root folder of the trash namespace itself. */
    fun isTrashRoot(path: String): Boolean = guard.trash.isRoot(path)

    /** See [RemoteOperationGuard.isExcludedPath]. */
    fun isExcludedPath(
        path: String,
        isDir: Boolean = false,
    ): Boolean = guard.isExcludedPath(path, isDir)

    /** See [RemoteOperationGuard.matchingExcludePattern]. */
    fun matchingExcludePattern(path: String): String? = guard.matchingExcludePattern(path)

    /** archive#660: a rule file of the ignore rules was handed over at [path]; see [RemoteOperationGuard.ignoreRuleFileWritten]. */
    fun ignoreRuleFileWritten(path: String) {
        if (guard.isIgnoreRuleFile(path)) guard.ignoreRuleFileWritten(path)
    }

    /** See [RemoteOperationGuard.withTransferPermit]. */
    suspend fun <T> withTransferPermit(block: suspend () -> T): T = guard.withTransferPermit(block)

    /**
     * The hydration-cache file of a logical [path] (the host's layout). Always inside the profile's cache folder:
     * a path that would resolve outside it throws [SecurityException] (`SyncEngine.resolveCachePath`).
     */
    fun resolveCachePath(path: String): Path = wiring.cachePathOf(path)

    // For the passes over rows: the cache file of [path], or null (logged once) when it does not resolve inside the cache.
    private fun rowCachePath(path: String): Path? = CachePaths.forRow(path, log, wiring.cachePathOf)

    /**
     * One-way remote→state.db refresh for view consumers (the FUSE mount). Reuses the remote
     * gather + state.db upsert, but NEVER scans sync_root, NEVER plans/executes a local→remote
     * delete, and NEVER evaluates the empty-sync_root / max_delete_* guards. Remote-observed
     * deletions flip state.db rows only on a COMPLETE enumeration. Single-flight across both
     * front-ends: an overlapping call returns `skipped = true`. See `RemoteEnumeration` (archive#560 U2b)
     * and docs/dev/specs/mount-view-refresh-design.md.
     */
    override suspend fun enumerateRemoteIntoState(reset: Boolean): EnumerateResult = wiring.enumeration.enumerate(reset)

    /**
     * What a client may be told about the enumeration right now. A running attempt is answered from
     * memory alone: state.db is held by the batch that saves the result, and a status request must
     * not wait for it. See `RemoteEnumeration.status`.
     */
    fun enumerationStatus(): EnumerationStatus = wiring.enumeration.status()

    /**
     * archive#580: an upload was refused because the provider's path walk found the folder [folderPath] (a remote path) gone.
     * When state.db tracks that folder with a remote id, the provider is asked about it by that id
     * ([CloudProvider.folderStateById]); only a confirmed [FolderState.TRASHED] or [FolderState.NOT_FOUND] tombstones its
     * subtree ([org.krost.unidrive.engine.GoneFolderTombstone]), keeping the rows of [keep] and every
     * row that may hold an unsent edit. A live folder (a trash undone in the lag window, archive#132), a provider that cannot
     * tell, and a lookup that fails (a 5xx, a degraded gateway archive#387, a timeout) change nothing. Returns how many rows it
     * marked deleted.
     */
    suspend fun tombstoneFolderIfGone(
        folderPath: String,
        keep: Set<String> = emptySet(),
    ): Int {
        val folder = (db.getEntryByRemotePath(folderPath) ?: db.getEntry(folderPath))?.takeIf { it.isFolder } ?: return 0
        val id = folder.remoteId ?: return 0
        val state =
            try {
                watchingCredentials { provider.folderStateById(id) }.also { recordProviderContact() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.info("#580: the state of {} by its id is unknown ({}); its rows stay", folderPath, e.message ?: e.javaClass.simpleName)
                return 0
            }
        if (state != FolderState.TRASHED && state != FolderState.NOT_FOUND) {
            log.info("#580: the provider reports {} as {} by its id; its rows stay", folderPath, state)
            return 0
        }
        return wiring.enumeration.goneFolders.tombstone(folder, keep)
    }

    /**
     * gkrost/unidrive-cli#134: the upload of [path] was refused because the provider's walk did not find the folder
     * [goneFolder] (a remote path above the file) in a listing. Before that stands as a refusal, the mount looks again:
     * the folder the walk missed is a folder this mount tracks (state.db has its row, with the id it had), so the
     * provider is asked for it by that id.
     *
     * - Trashed or not found: the folders from there down to the file's own folder are created again, each one that
     *   state.db has a row for (the local folder exists), and the rows take the new ids. A level without a row stops it:
     *   nothing local asks for that folder.
     * - Live: the walk and the id disagree (a listing that lags behind a create, archive#132), so the folder is looked up
     *   by path once more; only the same id can be retried.
     * - Unknown, or any failure of the lookup (a 5xx, a degraded gateway, a timeout): nothing is done.
     *
     * Returns true when the folders are there to be tried again; the caller sends the bytes once more, and only when
     * that is refused too does the refusal stand.
     */
    suspend fun recoverGoneParent(
        path: String,
        goneFolder: String,
    ): Boolean {
        val levels = parentLevelsFrom(path, goneFolder) ?: return false
        val first = levels.first()
        val firstId = first.row.remoteId ?: return false
        return try {
            val state = watchingCredentials { provider.folderStateById(firstId) }.also { recordProviderContact() }
            when (state) {
                FolderState.TRASHED, FolderState.NOT_FOUND -> recreateFolders(levels, state, path)
                FolderState.LIVE -> adoptLiveFolder(first, path)
                FolderState.UNKNOWN -> false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.info("#134: the folder {} for the upload of {} was not put back: {}", goneFolder, path, e.message ?: e.javaClass.simpleName)
            false
        }
    }

    // The folders of [levels] created again, topmost first, each row taking the id of its new folder. False when the provider
    // put one under another name (the rows below it would follow a name this mount does not know).
    private suspend fun recreateFolders(
        levels: List<ParentLevel>,
        was: FolderState,
        path: String,
    ): Boolean {
        val expected = levels.toMutableList()
        for (index in expected.indices) {
            val level = expected[index]
            if (!db.batch { expected.all(::folderStillTracked) }) return false
            val item = provider.createFolder(level.remotePath)
            recordProviderContact()
            if (item.nameOverridden || item.deleted) {
                log.info("#134: the folder {} did not come back under the requested name", level.remotePath)
                return false
            }
            if (!item.isFolder || item.id.isEmpty()) return false
            val updated = db.batch {
                if (!expected.all(::folderStillTracked) || idBelongsElsewhere(item.id, level.row.path)) return@batch null
                val current = db.getEntry(level.row.path) ?: return@batch null
                val row = current.copy(
                    remoteId = item.id,
                    remoteModified = item.modified ?: Instant.now(),
                    parentUuid = item.parentId ?: current.parentUuid,
                    lastErrorAt = null,
                    lastSynced = Instant.now(),
                )
                db.upsertEntry(row, keepMarks = true)
                row
            } ?: return false
            expected[index] = ParentLevel(level.remotePath, updated)
            log.info("#134: the folder {} was {} in the cloud and was created again for the upload of {}", level.remotePath, was, path)
        }
        return true
    }

    // The folder the walk missed is live by its id: retry only if the path resolves to that same folder, not a replacement.
    private suspend fun adoptLiveFolder(
        level: ParentLevel,
        path: String,
    ): Boolean {
        val live = watchingCredentials { provider.getMetadata(level.remotePath) }.also { recordProviderContact() }
        if (!live.isFolder || live.deleted || live.id != level.row.remoteId) return false
        if (!db.batch { folderStillTracked(level) }) return false
        log.info("#134: the folder {} is live in the cloud; the upload of {} is tried again", level.remotePath, path)
        return true
    }

    // The lookup and creation suspend; a rename, delete or enumeration may change a row in the meantime.
    // Recheck under the database transaction and preserve its current fields when updating its identity.
    private fun folderStillTracked(level: ParentLevel): Boolean =
        db.getEntry(level.row.path)?.let {
            it.isFolder && it.remoteId == level.row.remoteId && it.remotePath == level.row.remotePath
        } == true

    private fun idBelongsElsewhere(id: String, path: String): Boolean =
        db.recovery.getEntryByRemoteIdAnyStatus(id)?.let { it.path != path || it.status != EntryStatus.EXISTS } == true

    // A folder from [goneFolder] down to the folder of [path], with the remote path it has and the row that tracks it.
    private class ParentLevel(
        val remotePath: String,
        val row: SyncEntry,
    )

    // The tracked folders on the way from [goneFolder] (a remote path) to the parent of the file at [path], topmost first;
    // null when [goneFolder] is not above the file, or when one of them has no folder row (it does not exist locally).
    private fun parentLevelsFrom(
        path: String,
        goneFolder: String,
    ): List<ParentLevel>? {
        val remoteSegments = remotePathOf(path).split('/').filter { it.isNotEmpty() }
        val localSegments = path.split('/').filter { it.isNotEmpty() }
        val goneDepth = goneFolder.split('/').count { it.isNotEmpty() }
        if (remoteSegments.size != localSegments.size || goneDepth == 0 || goneDepth >= remoteSegments.size) return null
        if (remoteSegments.take(goneDepth) != goneFolder.split('/').filter { it.isNotEmpty() }) return null
        return (goneDepth until remoteSegments.size).map { depth ->
            val row =
                db.getEntry("/" + localSegments.take(depth).joinToString("/"))?.takeIf { it.isFolder && !it.remoteId.isNullOrEmpty() }
                    ?: return null
            ParentLevel("/" + remoteSegments.take(depth).joinToString("/"), row)
        }
    }

    /** The enumeration's tracker; the daemon's poller records its next attempt on it. */
    val enumerationTracker: EnumerationTracker get() = wiring.enumeration.tracker

    // archive#318: per-path serialization for ensureHydrated's warm-cache check + download.
    // Without it, a second open whose warm-cache size check fails re-downloads with
    // TRUNCATE_EXISTING into the cache file while a first handle is still reading it
    // (silent short/garbage reads on POSIX), and two concurrent cold opens
    // double-download the same file. Entries persist for the daemon session — the
    // same lifetime tradeoff as HydrationImpl.createMutexes, bounded by the number
    // of distinct paths ever hydrated.
    private val hydrateMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    // Cloud objects found truncated or refused for their content, remembered so a repeated read or pin is refused
    // without asking the provider (or downloading a refused object again).
    internal val remoteIncomplete = RemoteIncompleteMemory()

    private fun refuseIfRemembered(
        path: String,
        entry: SyncEntry,
    ) {
        val remembered = remoteIncomplete.recall(entry) ?: return
        log.debug("download of {} refused from memory: {}", path, remembered.message)
        throw remembered
    }

    // Runs a download; the provider's refusal of a truncated object or of a content that failed its integrity check is
    // recorded (WARN the first time per object and run, DEBUG after) and passed on unchanged.
    private suspend fun <T> rememberingRemoteIncomplete(
        path: String,
        entry: SyncEntry,
        download: suspend () -> T,
    ): T =
        try {
            download()
        } catch (e: RemoteIncompleteDownloadException) {
            throw remembered(path, entry, e)
        } catch (e: RemoteContentRefusedDownloadException) {
            throw remembered(path, entry, e)
        }

    private fun remembered(
        path: String,
        entry: SyncEntry,
        refusal: PermanentDownloadFailureException,
    ): PermanentDownloadFailureException {
        if (remoteIncomplete.remember(entry, refusal)) {
            log.warn("download of {} refused: {}", path, refusal.message)
        } else {
            log.debug("download of {} refused again: {}", path, refusal.message)
        }
        return refusal
    }

    /** How many cloud objects are remembered as truncated; `daemon.status.remote_incomplete.count` reports it. */
    fun remoteIncompleteCount(): Int = remoteIncomplete.count

    /**
     * Hydrate a single remote path into the local hydration cache. Idempotent —
     * if the path is already hydrated and the cache file exists, returns the
     * existing cache path without re-downloading. Throws on unrecoverable errors
     * (e.g. [PermanentDownloadFailureException] for a 404; IO errors; unknown
     * path).
     *
     * Cache layout: `<cacheRoot>/unidrive/hydration/<cacheKey>/<path>` where
     * `cacheRoot` is the host's cache root when set, otherwise `XDG_CACHE_HOME` or
     * `~/.cache`, and `cacheKey` is the per-account namespace (profile.name).
     *
     * Integrity failure throws (not warns) because FUSE-passthrough exposes the
     * cache directly to userspace reads — a silently accepted corrupt file would
     * be immediately visible to the user, unlike the mirror's `applyDownload`
     * local-placeholder path where a warning is recoverable on the next sync.
     *
     * archive#318: the whole check+download runs under a per-path mutex. Concurrent
     * opens of the same path must not double-download, and a re-download must
     * never truncate a cache file another handle is reading.
     *
     * archive#652: [history] is the transfer being recorded. It is started only when a cold download really begins (a warm
     * serve, and a refusal for a pause, never start it); the caller finishes it with the outcome.
     */
    suspend fun ensureHydrated(
        path: String,
        history: TransferHandle? = null,
    ): Path = ensureHydratedReporting(path, history).path

    /**
     * [ensureHydrated] that also says whether the provider was asked: [HydratedCache.fetched] is false for a warm
     * serve (the cache already held the bytes), true after a download. The caller needs the difference to tell the
     * daemon whether the provider proved reachable.
     */
    suspend fun ensureHydratedReporting(
        path: String,
        history: TransferHandle? = null,
    ): HydratedCache = hydrateMutexes.computeIfAbsent(path) { Mutex() }.withLock { ensureHydratedLocked(path, history) }

    /** The cache file of a hydrated path and whether making it ready took a download ([fetched]). */
    data class HydratedCache(val path: Path, val fetched: Boolean)

    /**
     * True when a read of [entry] needs no download: the row has no remote (the cache is the only copy), or it is
     * hydrated and the cache file is present with the size the remote reports, or the cache file holds an edit the
     * cloud has not seen ([SyncEntry.editBase]; archive#1070, M1 of archive#1040): that copy is the one of record, whatever its
     * size and whether the row says hydrated, and a download would replace the only copy of the edit.
     * [ensureHydrated] returns the cache in exactly this case.
     */
    fun servesFromLocalCopy(
        entry: SyncEntry,
        cachePath: Path = resolveCachePath(entry.path),
    ): Boolean {
        if (entry.editBase != null) return Files.exists(cachePath)
        if (editedBehindTheRow(entry, cachePath)) return true
        val warmCacheUsable =
            entry.remoteId == null ||
                runCatching { Files.size(cachePath) }.getOrDefault(-1L) == entry.remoteSize
        return entry.isHydrated && Files.exists(cachePath) && warmCacheUsable
    }

    /**
     * M1 of archive#1040: a cache copy of a hydrated cloud-backed file that is no longer the row's baseline (its mtime and size
     * are not the ones the row took from it) was written after the last sync, and no running daemon noted it: the edit of a
     * daemon that was stopped (or killed) leaves no [SyncEntry.editBase]. The startup replay finds it by this same
     * difference, so a read in front of the replay must not download over it. The size alone told nothing: the edit's size
     * differs from the row's remote size, which read as a cache copy to replace.
     */
    private fun editedBehindTheRow(
        entry: SyncEntry,
        cachePath: Path,
    ): Boolean {
        if (entry.isFolder || entry.remoteId == null || !entry.isHydrated) return false
        val baselineMtime = entry.localMtime ?: return false
        val baselineSize = entry.localSize ?: return false
        return runCatching { Files.getLastModifiedTime(cachePath).toMillis() != baselineMtime || Files.size(cachePath) != baselineSize }
            .getOrDefault(false)
    }

    private suspend fun ensureHydratedLocked(
        path: String,
        history: TransferHandle?,
    ): HydratedCache {
        val entry = db.getEntry(path)
            ?: throw IllegalArgumentException("Unknown remote path: $path")
        val cachePath = resolveCachePath(path)
        // The edit nothing noted (see [editedBehindTheRow]): noted now, from the version the row records, so that every
        // later read, dehydrate and replay treats it as the edit it is.
        if (entry.editBase == null && editedBehindTheRow(entry, cachePath)) {
            log.warn("{} was changed while the daemon was not watching it; its cache copy is kept as an unsynced edit", path)
            db.markEdited(path, entry.writeToken.orEmpty())
            return HydratedCache(cachePath, fetched = false)
        }
        // Trust the warm cache only if it is actually COMPLETE. The isHydrated flag
        // plus mere file-existence is not enough for a REMOTE-backed file: a stale
        // isHydrated over a 0-byte/truncated cache (crash mid-download, an
        // externally-cleared cache, a reset/enumeration window) would otherwise be
        // served as-is, silently yielding empty/short reads — which a file-manager
        // copy turns into a corrupt 0-byte destination. Re-download whenever the
        // cached size doesn't match the known remote size.
        //
        // Local-only rows (remoteId == null: created/edited through the mount, not
        // yet uploaded — remoteSize is 0 while the cache holds the just-written
        // bytes) have NO remote to compare against or re-download from; the cache is
        // the only copy, so always trust them on the warm path. archive#136: this
        // remoteId == null is NOT the UD-901 pending-upload predicate — it means "no
        // remote to compare against", and isHydrated here carries the warm-trust
        // meaning, so the split check is intentional.
        if (servesFromLocalCopy(entry, cachePath)) return HydratedCache(cachePath, fetched = false)
        // The cloud object was found truncated or refused and the row still describes that same version: refuse again
        // without asking the provider (see [RemoteIncompleteMemory]).
        refuseIfRemembered(path, entry)
        // Construct a minimal CloudItem so downloadByIdOrPath can route by id
        // (fast path) or fall back to path-based (when remoteId is null).
        val remoteItem = CloudItem(
            id = entry.remoteId ?: "",
            name = path.substringAfterLast("/"),
            path = path,
            size = entry.remoteSize,
            isFolder = false,
            modified = entry.remoteModified ?: Instant.now(),
            created = entry.remoteModified ?: Instant.now(),
            hash = entry.remoteHash,
            mimeType = null,
        )
        Files.createDirectories(cachePath.parent)
        // archive#318: download to a temp sibling and atomically swap it in, so a handle
        // already reading the old cache keeps its inode instead of having the file
        // truncated under it by TRUNCATE_EXISTING (silent short/garbage reads).
        val staged =
            cachePath.resolveSibling(
                // archive#529: a long cache name cannot take the full suffix — stage short when over the limit.
                org.krost.unidrive.io.stagingSiblingName(
                    cachePath.fileName.toString(),
                    ".hydrating-" + java.util.UUID.randomUUID(),
                ),
            )
        try {
            // archive#778: a download starts only while the daemon is not paused; one running when a pause begins is
            // judged on the bytes the staging file holds so far.
            val fetched =
                rememberingRemoteIncomplete(path, entry) {
                    pause.track(path) { transfer ->
                        transfer.begin()
                        // archive#652: a cold download is a transfer from here on (a warm serve and a refusal for a pause never get here).
                        history?.started()
                        transfer.measuredBy(entry.remoteSize) { runCatching { Files.size(staged) }.getOrDefault(0L) }
                        watchingCredentials { downloadByIdOrLiveItem(path, entry, remoteItem, staged) }
                    }
                }
            val downloadedSize = confirmedDownloadSize(path, entry, fetched)
            remoteIncomplete.forget(entry)
            if (options.verifyIntegrity) {
                // The bytes of an item read by its live id (archive#796) are checked against that item's hash, not the row's.
                val expectedHash = if (fetched.liveItem != null) fetched.liveItem.hash else entry.remoteHash
                val verified = HashVerifier.verify(staged, expectedHash, algorithm = provider.hashAlgorithm())
                if (!verified) {
                    // The surviving old cache may be corrupt-but-right-sized; removing
                    // it forces a clean re-download on the next open instead of
                    // serving it warm.
                    Files.deleteIfExists(cachePath)
                    throw IllegalStateException("Integrity check failed for hydration cache: $path")
                }
            }
            val rowNow = requireRowUnchanged(path, entry)
            // M1 of archive#1040: open_write_begin does not take this path's lock, so an edit may have begun while the
            // bytes came down. Its truncated copy is the start of that edit, the only copy of it: the finished download
            // is dropped (the staging file goes in the finally below), the mark stays, and the copy is what is read.
            if (rowNow.editBase != null && entry.editBase == null) {
                log.info("download of {} dropped: an edit began while it ran; the cache copy is kept", path)
                return HydratedCache(cachePath, fetched = true)
            }
            try {
                Files.move(staged, cachePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staged, cachePath, StandardCopyOption.REPLACE_EXISTING)
            }
            // Persist the freshly-downloaded size as remoteSize. The provider validated the
            // download against the authoritative remote length (throwing on a short read), so
            // this is the current truth. Without it a remote that changed size since the last
            // enumeration leaves remoteSize stale, and the openForRead size guard would EIO a
            // perfectly valid re-download.
            // archive#560 U6: the cache copy is the row's only local file (no sync root). Its stats
            // become the baseline: HydrationImpl.lastSynced() and the co-daemon's crash-recovery
            // scanner use this localMtime as their watermark, so an external cache write after
            // this point is a dirty overwrite the replay picks up.
            val current = db.getEntry(path) ?: entry
            val rebaselined = current.copy(
                isHydrated = true,
                remoteSize = downloadedSize,
                localMtime = Files.getLastModifiedTime(cachePath).toMillis(),
                localSize = Files.size(cachePath),
                lastSynced = Instant.now(),
                cacheBacked = true,
                // The cache copy is the cloud's now: a mark left by an edit whose copy is gone (see servesFromLocalCopy) ends.
                editBase = null,
            ).adopting(fetched.liveItem)
            // The row just adopted the cache copy's stats, so the hash must describe the cache
            // copy's bytes: keeping a hash recorded for the previous contents would pair stale
            // bytes with a fresh mtime/size and let a later touch be absorbed as unchanged.
            db.upsertEntry(
                withLocalHash(rebaselined, cachePath, rebaselined.localMtime!!, rebaselined.localSize!!),
            )
            recordProviderContact()
            // archive#660: a downloaded rule file is readable now; its folder is no longer held back.
            ignoreRuleFileWritten(path)
            return HydratedCache(cachePath, fetched = true)
        } finally {
            runCatching { Files.deleteIfExists(staged) }
        }
    }

    // M2 of archive#1040: unlink and rename do not take the path's hydrate lock, so the row may be gone or have moved while a
    // download ran. The bytes then belong to a path that is not this item's any more: moved into the cache and written
    // back, they would bring a deleted file back and put a renamed row back under its old path (the row write is an
    // upsert keyed by remote id). The read fails as a gone file instead.
    private fun requireRowUnchanged(
        path: String,
        entry: SyncEntry,
    ): SyncEntry {
        val rowNow = db.getEntry(path)
        if (rowNow == null || rowNow.remoteId != entry.remoteId) {
            throw PermanentDownloadFailureException("$path was deleted or moved while it was being downloaded")
        }
        return rowNow
    }

    /**
     * gkrost/unidrive-cli#80: the byte count of a finished download, or a failure when it is short. A provider whose
     * truncation guard needs a `Content-Length` can return fewer bytes than the item has without throwing (a chunked
     * response that closes early); taking that count as the row's new `remote_size` would switch off the size check
     * of the read and serve the truncated file as complete. A count below the size the row (or the live item
     * it was read from) holds is therefore accepted only when the provider confirms that size for the file now (a file
     * that shrank since the enumeration); a longer body is a file that grew and is taken as before.
     * A short body is a transient failure (EIO, read again), not a statement about the cloud object.
     */
    private suspend fun confirmedDownloadSize(
        path: String,
        entry: SyncEntry,
        fetched: Fetched,
    ): Long {
        val expected = fetched.liveItem?.size ?: entry.remoteSize
        if (expected <= 0 || fetched.bytes >= expected) return fetched.bytes
        val live =
            try {
                provider.getMetadata(remotePathOf(path, entry))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug("#80: cannot confirm the size of {} after a download of {} bytes: {}", path, fetched.bytes, e.javaClass.simpleName)
                null
            }
        val confirmed = live != null && live.size == fetched.bytes && !(live.isFolder || live.deleted)
        if (confirmed) return fetched.bytes
        throw java.io.IOException("download of $path ended after ${fetched.bytes} of $expected bytes")
    }

    /** What a cold download brought: the bytes, and the live item it was read from when the row's own id did not resolve. */
    private data class Fetched(val bytes: Long, val liveItem: CloudItem?)

    /**
     * archive#796: the row's download by its remote id, and when that fails as a gone item, once the file the
     * provider shows at the row's path. A listing that resolves by path (`ls --live`) and a read that resolves by the id the
     * row keeps can disagree: the row lists, the provider has a file of that name there, and the read used to answer
     * `not_found`. The file found at the path is read only when it is another id that no other row holds (see
     * [liveFileReplacing]); the row takes that id with the download ([adopting]). Every other case is the original failure.
     */
    private suspend fun downloadByIdOrLiveItem(
        path: String,
        entry: SyncEntry,
        item: CloudItem,
        staged: Path,
    ): Fetched {
        val remotePath = remotePathOf(path, entry)
        try {
            return Fetched(Transfers.downloadByIdOrPath(provider, item, remotePath, staged), liveItem = null)
        } catch (e: Exception) {
            if (item.id.isEmpty() || !DownloadFailures.isNotFound(e)) throw e
            val live = liveFileReplacing(path, remotePath, item.id) ?: throw e
            log.warn(
                "#796: {} is not found by the remote id its row keeps; reading the file the provider shows at its path, " +
                    "whose id the row takes",
                path,
            )
            val bytes = Transfers.downloadByIdOrPath(provider, item.copy(id = live.id, size = live.size), remotePath, staged)
            return Fetched(bytes, live)
        }
    }

    // The file at [remotePath] when it can stand in for a row whose id [staleId] is gone: a file (not a folder, not
    // deleted) under another id that no row of this profile holds; null otherwise, and when the provider cannot say. The
    // trash namespace is no drive path and is never asked for by path. The provider fails closed on two items of one name
    // (archive#402), so a twin is never picked.
    private suspend fun liveFileReplacing(
        path: String,
        remotePath: String,
        staleId: String,
    ): CloudItem? {
        if (isReadOnlyPath(path)) return null
        val live =
            try {
                provider.getMetadata(remotePath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug("#796: {} is not found by path either: {}", path, e.javaClass.simpleName)
                return null
            }
        if (live.isFolder || live.deleted) return null
        val anotherId = live.id.isNotEmpty() && live.id != staleId
        return live.takeIf { anotherId && db.getEntryByRemoteId(live.id) == null }
    }

    // The row with the cloud identity of [live], the item its bytes were read from; unchanged when there is none.
    private fun SyncEntry.adopting(live: CloudItem?): SyncEntry =
        if (live == null) {
            this
        } else {
            copy(remoteId = live.id, remoteHash = live.hash, remoteVersion = live.version, remoteModified = live.modified ?: remoteModified)
        }

    /**
     * The directory that holds this profile's hydration cache (every cache file lives below it, laid
     * out like the remote tree). archive#450: what the cache budget measures and evicts.
     */
    fun hydrationCacheDir(): Path = resolveCachePath("/")

    /**
     * archive#450: may the cache copy of [path] be deleted without losing anything? Answered from the bytes,
     * not from timestamps (enumeration refreshes a row's `last_synced`, so it cannot tell an edit
     * from a download). Never evictable ([CacheDisposition.PROTECTED]): no row, a folder, a row without a
     * remote (never uploaded: the cache is the only copy), a row marked failed, a copy the row's
     * baseline does not vouch for (modified since it was recorded), and a copy that differs from every
     * version the row knows. The caller still owes the open-handle and queued-upload checks, which
     * only the hydration layer can see.
     */
    fun cacheDisposition(path: String): CacheDisposition = dispositionOf(path, db.getEntry(path))

    /**
     * archive#728: [cacheDisposition] for a row the caller already loaded in a batch (`StateDatabase.getEntriesByPaths`).
     * The eviction pass asks about a whole LRU slice with ONE query instead of one per cache file; the
     * per-file checks (existence, size, mtime and, only for a copy no baseline vouches for, the content
     * hash) stay here and run with no database monitor held.
     *
     * [entry] is the row as the caller last read it and may be stale: this answers "is this copy worth
     * asking about" only. [evictCacheCopy] re-runs the same classification against the row and the bytes
     * it is about to delete, under the path's hydration lock, so nothing is ever deleted on stale data.
     */
    fun cacheDisposition(
        path: String,
        entry: SyncEntry?,
    ): CacheDisposition = dispositionOf(path, entry)

    private fun dispositionOf(
        path: String,
        entry: SyncEntry?,
    ): CacheDisposition {
        if (entry == null) return CacheDisposition.PROTECTED
        if (entry.isFolder || entry.remoteId == null || entry.lastErrorAt != null) {
            return CacheDisposition.PROTECTED
        }
        // archive#660: the ignore rules are read from the cache copies of their rule files first; an evicted rule file would
        // have to be downloaded again to read its rules.
        if (guard.isIgnoreRuleFile(path)) return CacheDisposition.PROTECTED
        val cache = rowCachePath(path) ?: return CacheDisposition.PROTECTED
        return try {
            if (!Files.isRegularFile(cache)) return CacheDisposition.PROTECTED
            val size = Files.size(cache)
            val mtime = Files.getLastModifiedTime(cache).toMillis()
            val cacheIsBaseline = entry.localMtime == mtime && entry.localSize == size
            if (entry.cacheBacked == true || (entry.cacheBacked == null && cacheIsBaseline)) {
                // The cache copy is the row's local file: clean only while it is exactly what was recorded.
                return if (cacheIsBaseline) CacheDisposition.DISPOSABLE else CacheDisposition.PROTECTED
            }
            if (cacheMatchesRecordedVersion(entry, cache, size)) CacheDisposition.DISPOSABLE else CacheDisposition.PROTECTED
        } catch (e: java.io.IOException) {
            CacheDisposition.PROTECTED
        }
    }

    /**
     * archive#1219: whether the plain file [local] holds exactly the bytes of the cloud copy of [path], proven WITHOUT downloading
     * the cloud copy. The proofs, cheapest first, each answering only when it really compared:
     *  1. the sizes: a row with a known size (> 0) that is not the file's size is a difference;
     *  2. the row's content hash, for a provider that has one (OneDrive's quickXor; [RowHashProof]);
     *  3. the SHA-256 the engine recorded when it wrote or sent these very bytes, for a provider with no hash of its own (Internxt):
     *     equal means the same, anything else decides nothing (the row may be behind);
     *  4. the provider's own proof from its stored object ([CloudProvider.contentMatchesLocal]; Internxt: the ciphertext shard hash).
     * [MatchesLocalResult.Unknown] is every other case, with a token: it is never evidence of a difference, and the caller falls back to a
     * download. Reads [local] and the provider's metadata, writes nothing, logs no hash. [local] must be a plain file; a cloud placeholder
     * would be recalled by the read.
     */
    suspend fun contentMatchesLocal(
        path: String,
        local: Path,
    ): MatchesLocalResult {
        // So that the daemon never reads a file relative to its own working directory.
        if (!local.isAbsolute) return MatchesLocalResult.Failed(HydrationError.Generic(INVALID_LOCAL_PATH))
        val entry = db.getEntry(path) ?: return MatchesLocalResult.Unknown("unknown_path")
        val remoteId = entry.remoteId
        if (entry.isFolder || remoteId.isNullOrEmpty() || entry.status != EntryStatus.EXISTS) return MatchesLocalResult.Unknown("no_cloud_copy")
        val localSize =
            try {
                withContext(Dispatchers.IO) { if (Files.isRegularFile(local)) Files.size(local) else null }
            } catch (_: java.io.IOException) {
                null
            } ?: return MatchesLocalResult.Failed(HydrationError.Generic(LOCAL_FILE_UNREADABLE))

        // A size of 0 on a row with an id can be the older "size unknown" shape: no evidence either way.
        if (entry.remoteSize > 0L && localSize != entry.remoteSize) return MatchesLocalResult.Differs(METHOD_SIZE)

        try {
            val algorithm = provider.hashAlgorithm()
            if (algorithm != null) {
                withContext(Dispatchers.IO) { RowHashProof.prove(local, entry.remoteHash, algorithm) }?.let { return it.asResult() }
            } else if (recordedBytesProof(entry, local)) {
                return MatchesLocalResult.Same(METHOD_RECORDED_SHA256)
            }
            // The proof from the stored object asks the provider's service; a paused daemon starts nothing.
            if (pause.isPaused) return MatchesLocalResult.Unknown("paused")
            return provider.contentMatchesLocal(remoteId, remotePathOf(path, entry), local)?.asResult() ?: MatchesLocalResult.Unknown("no_proof")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A read of the local file failed half way, or the provider's service did not answer or refused: no proof, no verdict.
            // The class name only: a message may carry a URL or an id.
            log.debug("matches_local of {} could not be decided: {}", path, e.javaClass.simpleName)
            return MatchesLocalResult.Unknown("proof_failed")
        }
    }

    // The row of a provider with no content hash records the SHA-256 of the bytes it last wrote or sent (archive#396). A hydrated, unedited row
    // whose baseline has the cloud's size and whose recorded hash is the file's is the cloud copy; a different hash decides nothing.
    private suspend fun recordedBytesProof(
        entry: SyncEntry,
        local: Path,
    ): Boolean {
        val recorded = entry.localHash
        if (recorded.isNullOrEmpty() || !entry.isHydrated) return false
        if (entry.editBase != null || entry.downloadQuarantined) return false
        if (entry.localSize == null || entry.localSize != entry.remoteSize) return false
        return withContext(Dispatchers.IO) { HashVerifier.computeSha256Hex(local) }.equals(recorded, ignoreCase = true)
    }

    private fun ContentProof.asResult(): MatchesLocalResult = if (same) MatchesLocalResult.Same(method) else MatchesLocalResult.Differs(method)

    // The bytes of [cache] are a version the row knows: the remote version (content-hash providers) or
    // the baseline bytes the row hashed when it recorded them (archive#396, hashless providers).
    private fun cacheMatchesRecordedVersion(
        entry: SyncEntry,
        cache: Path,
        size: Long,
    ): Boolean {
        val algorithm = provider.hashAlgorithm()
        return if (algorithm != null && !entry.remoteHash.isNullOrEmpty()) {
            size == entry.remoteSize && HashVerifier.verify(cache, entry.remoteHash, algorithm)
        } else {
            entry.localHash != null && HashVerifier.computeSha256Hex(cache) == entry.localHash
        }
    }

    /**
     * archive#450: delete the cache copy of [path] if [cacheDisposition] still allows it, under the same
     * per-path lock as [ensureHydrated] (no eviction in the middle of a hydration of the same path).
     * Returns the freed bytes, or null when the copy was protected or could not be removed. A row whose
     * local file WAS the cache copy is marked not hydrated (the local bytes are gone; the next open
     * refills them from the remote).
     * [onUnhydrated] is invoked for the first kind, so the hydration layer can publish the event.
     */
    suspend fun evictCacheCopy(
        path: String,
        onUnhydrated: (String) -> Unit = {},
    ): Long? =
        hydrateMutexes.computeIfAbsent(path) { Mutex() }.withLock {
            // archive#728: one row read decides both the classification and the baseline question below: this
            // is the re-check that counts, so it must not ask the database twice per evicted file.
            val entry = db.getEntry(path) ?: return@withLock null
            if (dispositionOf(path, entry) == CacheDisposition.PROTECTED) return@withLock null
            val cache = rowCachePath(path) ?: return@withLock null
            val size = runCatching { Files.size(cache) }.getOrNull() ?: return@withLock null
            val mtime = runCatching { Files.getLastModifiedTime(cache).toMillis() }.getOrNull() ?: return@withLock null
            // The same test cacheDisposition used: the cache copy is the row's local file.
            val cacheWasLocalFile =
                entry.cacheBacked == true || (entry.cacheBacked == null && entry.localMtime == mtime && entry.localSize == size)
            try {
                Files.delete(cache)
            } catch (e: java.io.IOException) {
                log.debug("#450: cannot evict the cache copy of {}: {}", path, e.message)
                return@withLock null
            }
            if (cacheWasLocalFile) {
                db.markUnhydrated(path)
                onUnhydrated(path)
            } else if (entry.cacheBacked == null) {
                db.upsertEntry(entry.copy(cacheBacked = false))
            }
            size
        }

    /**
     * The path the cloud knows [path] by: the row's own remote path when it has one (an entry the drive refused under
     * its local name and holds under a server name, or a locale alias), else that of the nearest folder above it that
     * has one, extended by the rest of the path, else [path] itself. Every operation that talks to the provider about
     * a path of this mount asks here first.
     */
    internal fun remotePathOf(
        path: String,
        entry: SyncEntry? = db.getEntry(path),
    ): String {
        entry?.remotePath?.let { return it }
        val nfc = PathNormalizer.nfc(path)
        val (ancestor, ancestorRemote) = db.remoteAncestor(nfc) ?: return path
        return ancestorRemote + nfc.substring(ancestor.length)
    }

    /** The nearest folder above [path] that the drive refused by name, so that nothing below it can exist there. */
    private fun blockedAncestorOf(path: String): String? = db.blockedAncestor(path)

    private fun requireNotBlockedByParent(path: String) {
        blockedAncestorOf(path)?.let { throw BlockedByParentException(it) }
    }

    private fun isNameBlocked(path: String): Boolean = db.nameBlock(path) != null || blockedAncestorOf(path) != null

    // The keep-local branch of [uploadFromCache]: an excluded or ignored path is never uploaded.
    private fun keepLocal(
        path: String,
        cachePath: Path,
    ): UploadOutcome {
        log.info("Skipping upload of excluded path (keep-local): {}", path)
        val inCloud = db.getEntry(path)
        if (inCloud?.remoteId != null) {
            // archive#660: the cloud holds an earlier version. The row keeps that version as its baseline, so the
            // edit stays owed to the cloud: it is uploaded (the start-up replay's dirty overwrites) once the path
            // is no longer excluded or ignored, and its cache copy, which is not the baseline, is never evicted.
            // Recording these bytes as the baseline would make them look like the cloud's and evictable.
            return UploadOutcome(sent = false, size = Files.size(cachePath), oldHash = null, newHash = null)
        }
        // Keep-local files are never uploaded, but the local watermark must still
        // advance. HydrationImpl.lastSynced() reports localMtime as the watermark,
        // and the co-daemon's crash-recovery scanner replays open_write for any
        // cache file whose mtime exceeds it — so without this, every daemon restart
        // re-replays excluded files forever. remoteId stays null (nothing uploaded).
        val mtime = Files.getLastModifiedTime(cachePath).toMillis()
        val size = Files.size(cachePath)
        db.upsertEntry(
            inCloud?.copy(
                localMtime = mtime,
                localSize = size,
                isHydrated = true,
                // archive#396: new bytes behind this mtime/size; a recorded hash of the old ones is stale.
                localHash = null,
                cacheBacked = true,
            ) ?: SyncEntry(
                path = path,
                remoteId = null,
                remoteHash = null,
                remoteSize = 0L,
                remoteModified = null,
                localMtime = mtime,
                localSize = size,
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.now(),
                cacheBacked = true,
            ),
        )
        return UploadOutcome(sent = false, size = size, oldHash = null, newHash = null)
    }

    suspend fun uploadFromCache(
        path: String,
        cachePath: Path,
        ifMatchETag: String? = null,
        // Byte progress of the transfer, (transferred, total). Optional: the
        // hydration upload path threads it into `uploading` events; callers
        // that don't need it leave it null (the sync-progress reporter below
        // always runs).
        onProgress: ((Long, Long) -> Unit)? = null,
    ): UploadOutcome {
        require(Files.exists(cachePath)) { "Cache path missing: $cachePath" }
        // A path in a view has no row and never gets one: bytes found in a cache copy there are neither sent nor recorded.
        if (guard.views.contains(path)) return UploadOutcome(sent = false, size = Files.size(cachePath), oldHash = null, newHash = null)
        // The trash namespace is read-only: whatever changed in a cache copy there is kept, and is never sent.
        if (isExcludedPath(path) || isReadOnlyPath(path)) return keepLocal(path, cachePath)
        val existingEntry = db.getEntry(path)
            // archive#319/#301: no row means the file was renamed away, unlinked, or reaped
            // (the remote vanished) while this upload sat queued. The old fallback
            // created a fresh row and uploaded anyway, which resurrected the old
            // remote path after a rename and let the reap race a queued edit.
            // Refuse: the caller's client gets a failed Completed event, the bytes
            // stay in the cache, and the recovery scanner re-targets them at the
            // row's current path.
            ?: throw IllegalStateException(
                "uploadFromCache: row for $path vanished while the upload was queued; refusing to recreate it",
            )
        // A folder above this file was refused by name and exists only here: nothing below it can reach the drive
        // until the folder does. Said without a request, so a subtree is not asked for file by file.
        requireNotBlockedByParent(path)
        val prevHash = existingEntry.remoteHash
        val existingRemoteId = existingEntry.remoteId
        // archive#115: if the existing row is a locale-aliased one, its content lives
        // at the canonical remote path; the FUSE write-back must upload there,
        // not at the alias `path`. Non-aliased rows have remotePath == null →
        // upload at `path`, byte-identical to pre-archive#115. The same holds for a row the
        // drive holds under a server name, or beneath a folder that is (remotePathOf).
        val remotePath = remotePathOf(path, existingEntry)
        val sizeForLog = Files.size(cachePath)
        val sent = statBeforeUpload(cachePath)
        // archive#583: bytes identical to the version the row records, and the cloud still holds that version: there is
        // nothing to send. The copy is adopted below exactly as an upload would rebaseline the row.
        val unchanged = unchangedCloudVersionOrNull(existingEntry, remotePath, cachePath, sizeForLog)
        if (unchanged != null) {
            log.info("uploadFromCache: {} has the bytes the cloud already holds ({} bytes); upload skipped", path, sizeForLog)
        }
        val result =
            unchanged
                ?: try {
                    provider.upload(cachePath, remotePath, existingRemoteId = existingRemoteId, ifMatchETag = ifMatchETag) { transferred, total ->
                        wiring.onTransferProgress(path, transferred, total)
                        onProgress?.invoke(transferred, total)
                    }
                } catch (e: Exception) {
                    noteAuthRejection(e)
                    auditLog?.emit(
                        action = "Upload",
                        path = path,
                        size = sizeForLog,
                        oldHash = prevHash,
                        result = "failed:${e.javaClass.simpleName}: ${e.message}",
                    )
                    throw e
                }
        // The cloud had a newer version and kept it: the bytes are a new item beside it, not this row's item.
        if (unchanged == null && result.keptBoth && existingRemoteId != null) {
            return settleKeptBoth(path, cachePath, remotePath, result, sent, prevHash)
        }
        // Where the item is now: a provider that had to place it under another name says so (nameOverridden), and
        // the row remembers that name from here on. Any other answer leaves the place it was asked for.
        val landedRemote = if (result.nameOverridden) result.path else remotePath
        val auditResult = if (unchanged != null) "skipped:unchanged" else "success"
        // archive#658: only a real transfer is a provider round trip; the skipped-unchanged path made no upload.
        if (unchanged == null) recordProviderContact()
        // Defer the absence sweep's deletion verdict while the delta feed catches up
        // to this just-written remote item (see markRecentlyUploaded). Keyed by the
        // REMOTE path so the absence sweep (remote namespace) matches. Nothing was written when the upload was skipped.
        if (unchanged == null) markRecentlyUploaded(landedRemote)
        // archive#337/#148: the row records the cache copy's stats as they were BEFORE
        // the transfer (same rule as applyUpload). A write landing mid-upload
        // keeps a newer mtime than the recorded watermark, so a later re-upload
        // of those bytes is never absorbed into this baseline.
        val afterUpload = statBeforeUpload(cachePath)
        val changedMidUpload = afterUpload != null && sent != null && afterUpload != sent
        if (changedMidUpload) {
            log.debug(
                "uploadFromCache: {} changed while it was being uploaded; keeping the pre-upload watermark",
                path,
            )
        }
        val mtime = sent?.first ?: afterUpload?.first ?: Files.getLastModifiedTime(cachePath).toMillis()
        val size = sent?.second ?: afterUpload?.second ?: Files.size(cachePath)
        val existing = db.getEntry(path)
        if (existing == null) {
            // archive#319: the row vanished while the bytes were in flight (a delete or
            // reap raced the upload). The remote copy is current; writing a fresh
            // row here resurrected a deleted path in state.db. Skip the row write —
            // the next enumeration decides between adopting the remote item and
            // re-reaping it.
            log.warn(
                "uploadFromCache: row for {} vanished mid-upload; remote copy is current, skipping row write",
                path,
            )
            auditLog?.emit(
                action = "Upload",
                path = path,
                size = size,
                oldHash = prevHash,
                newHash = result.hash,
                result = auditResult,
            )
            return UploadOutcome(
                sent = unchanged == null,
                size = size,
                oldHash = prevHash,
                newHash = result.hash,
                landedWithoutRow = LandedWithoutRow(result, landedRemote, sent),
            )
        }
        // archive#319: `existing` is non-null here (the vanished-row case returns above),
        // so the upload rebaselines the row that owns the bytes instead of
        // minting an ownerless one. archive#560 U6: the row's local file is the cache
        // copy, always — the bytes that were just sent are the bytes the cache
        // holds, and no local folder copy exists to converge with.
        val uploaded = existing.copy(
            remotePath = landedRemote.takeIf { it != path },
            remoteId = result.id,
            remoteHash = result.hash,
            // The write token of the version just written (every write moves OneDrive's eTag), so that the next edit's
            // base matches it. A skipped upload keeps the token the cloud still holds.
            remoteVersion = result.version,
            remoteSize = result.size,
            remoteModified = result.modified,
            localMtime = mtime,
            localSize = size,
            isHydrated = true,
            lastSynced = Instant.now(),
            lastErrorAt = existing.lastErrorAtAfterUpload(),
            cacheBacked = true,
            // archive#1070/#1069: what was sent is the cloud's now. A write that landed mid-upload is a new edit, which
            // started from the version just stored.
            editBase = if (changedMidUpload) (result.version ?: result.hash).orEmpty() else null,
        )
        // The row just adopted the cache copy's stats, and these are exactly the bytes the
        // write-back sent: hash the cache copy (guarded against a writer landing mid-hash)
        // so the touch shield covers mount-edited files too.
        db.upsertEntry(withSentHash(uploaded, cachePath, sent))
        auditLog?.emit(
            action = "Upload",
            path = path,
            size = size,
            oldHash = prevHash,
            newHash = result.hash,
            result = auditResult,
        )
        return UploadOutcome(sent = unchanged == null, size = size, oldHash = prevHash, newHash = result.hash)
    }

    /**
     * archive#583: the cloud item the cache copy is already identical to, or null when the copy has to be uploaded.
     * Skipping needs both: the bytes equal the version the row records ([cacheMatchesRecordedVersion]: the provider's
     * content hash, or the SHA-256 the engine recorded for hashless providers), AND the cloud still holds that version
     * (same item, size and modified time as the row; the provider's version token when both sides have one). Anything
     * else (a pending `local:` row, no recorded hash, a cloud copy that moved on, a lookup that fails) returns null and
     * the upload runs as before, so a changed cloud copy still meets the provider's write-token and conflict handling.
     */
    private suspend fun unchangedCloudVersionOrNull(
        entry: SyncEntry,
        remotePath: String,
        cachePath: Path,
        size: Long,
    ): CloudItem? {
        val remoteId = entry.remoteId ?: return null
        if (entry.isFolder || remoteId.startsWith("local:")) return null
        if (size != entry.remoteSize) return null
        if (!cacheMatchesRecordedVersion(entry, cachePath, size)) return null
        val current =
            try {
                provider.getMetadata(remotePath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug("uploadFromCache: cloud lookup for {} failed ({}); not skipping", remotePath, e.message)
                return null
            }
        if (current.isFolder || current.id != remoteId || current.size != entry.remoteSize) return null
        val recordedModified = entry.remoteModified
        if (recordedModified != null && current.modified != recordedModified) return null
        val recordedToken = entry.remoteHash
        if (!recordedToken.isNullOrEmpty() && !current.hash.isNullOrEmpty() && current.hash != recordedToken) return null
        // The provider's write token too, when both sides have one: a version the row does not know is not skipped.
        val recordedVersion = entry.remoteVersion
        if (recordedVersion != null && current.version != null && current.version != recordedVersion) return null
        return current
    }

    /**
     * The upload of a mount write based on [baseToken] (the client's base etag, which `hydration.list` handed out as
     * the row's [SyncEntry.writeToken]). The provider gets it as `ifMatchETag` when it is a token the provider takes
     * ([SyncEntry.ifMatchFor]): the row's write token of its own (OneDrive: the Graph eTag), or for a provider without
     * one the remoteHash, which is its token (Internxt). A content hash is never sent to a provider that reports
     * versions: the write is then unconditional, and the provider checks what it can itself (OneDrive fetches the
     * item's current eTag).
     */
    suspend fun uploadMountWriteFromCache(
        path: String,
        cachePath: Path,
        baseToken: String?,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): UploadOutcome =
        uploadFromCache(
            path = path,
            cachePath = cachePath,
            ifMatchETag = db.getEntry(path)?.ifMatchFor(baseToken, provider.reportsItemVersions),
            onProgress = onProgress,
        )

    /**
     * The provider kept both ([CloudItem.keptBoth]): the item at [remotePath] had a newer version than the edit's base,
     * and the edit's bytes landed as the new item [copy] beside it. The row at [path] stays the row of the cloud item,
     * which is untouched: it takes the item's current version where a lookup finds it and loses its cache copy, so the
     * next read downloads the cloud's version. The cache copy is the copy's now: it moves to the copy's path with a
     * row of its own (no download, and the edit is never sent again), unless that path is taken. Nothing is retried.
     */
    @Suppress("LongParameterList") // the facts uploadFromCache already holds; a record for them would only move the list
    private suspend fun settleKeptBoth(
        path: String,
        cachePath: Path,
        remotePath: String,
        copy: CloudItem,
        sent: Pair<Long, Long>?,
        prevHash: String?,
    ): UploadOutcome {
        recordProviderContact()
        markRecentlyUploaded(copy.path)
        val copyPath = path.substringBeforeLast('/') + "/" + copy.name
        log.warn(
            "uploadFromCache: {} changed in the cloud after the edit began; the cloud version is kept and the edit landed as '{}'",
            path,
            copyPath,
        )
        val size = sent?.second ?: Files.size(cachePath)
        val adopted = copyPath != path && db.getEntry(copyPath) == null && adoptKeptBothCopy(copyPath, cachePath, copy, sent)
        if (!adopted) Files.deleteIfExists(cachePath)
        db.getEntry(path)?.let { row ->
            val current = currentCloudItemOrNull(remotePath)?.takeIf { !it.isFolder && it.id == row.remoteId }
            db.upsertEntry(
                row.copy(
                    remoteHash = current?.hash ?: row.remoteHash,
                    // A version the row knows to be stale is no base for a write: unknown until a delivery brings it.
                    remoteVersion = current?.version,
                    remoteSize = current?.size ?: row.remoteSize,
                    remoteModified = current?.modified ?: row.remoteModified,
                    isHydrated = false,
                    localMtime = null,
                    localSize = null,
                    localHash = null,
                    cacheBacked = null,
                    editBase = null,
                ),
                keepMarks = true,
            )
        }
        auditLog?.emit(
            action = "Upload",
            path = path,
            size = size,
            oldHash = prevHash,
            newHash = copy.hash,
            result = "conflict:kept-both:$copyPath",
        )
        return UploadOutcome(sent = true, size = size, oldHash = prevHash, newHash = copy.hash, keptBothAs = copyPath, copyAdopted = adopted)
    }

    // The kept copy's row, from the cache copy that holds its bytes (moved to the copy's cache path). False when the
    // move fails; the copy then reaches the mount with the next delivery, like any other new cloud item.
    private fun adoptKeptBothCopy(
        copyPath: String,
        cachePath: Path,
        copy: CloudItem,
        sent: Pair<Long, Long>?,
    ): Boolean {
        val target = resolveCachePath(copyPath)
        try {
            Files.createDirectories(target.parent)
            Files.move(cachePath, target)
        } catch (e: java.io.IOException) {
            log.warn("uploadFromCache: the cache copy of the kept copy '{}' could not be moved there ({})", copyPath, e.message)
            return false
        }
        val mtime = sent?.first ?: Files.getLastModifiedTime(target).toMillis()
        val size = sent?.second ?: Files.size(target)
        val row =
            SyncEntry(
                path = copyPath,
                remotePath = copy.path.takeIf { it != copyPath },
                remoteId = copy.id,
                remoteHash = copy.hash,
                remoteVersion = copy.version,
                remoteSize = copy.size,
                remoteModified = copy.modified,
                localMtime = mtime,
                localSize = size,
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.now(),
                parentUuid = copy.parentId,
                cacheBacked = true,
            )
        db.upsertEntry(withSentHash(row, target, sent))
        return true
    }

    // The cloud item at [remotePath] now, or null when the lookup fails (the next delivery brings it).
    private suspend fun currentCloudItemOrNull(remotePath: String): CloudItem? =
        try {
            provider.getMetadata(remotePath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug("uploadFromCache: cloud lookup for {} failed ({})", remotePath, e.message)
            null
        }

    // A landed upload settles an earlier failed attempt: markUploadFailed stamps last_error_at
    // on the row, and an `existing.copy(...)` would otherwise carry that stamp into the
    // successful row for good (hydration.list then reports `error` for a file that is in the
    // cloud). A download quarantine shares the column and keeps its own stamp.
    private fun SyncEntry.lastErrorAtAfterUpload(): Instant? = if (downloadQuarantined) lastErrorAt else null

    /**
     * Create a folder on the remote provider and record it in state.db.
     * Used by the hydration SPI (HydrationImpl.mkdir) to back FUSE mkdir
     * requests. Separate code path from the legacy applyActions loop.
     *
     * Throws ProviderException on cloud-side failure. state.db is only
     * updated after the provider call succeeds.
     */
    suspend fun createRemoteFolder(path: String): CloudItem {
        requireNotBlockedByParent(path)
        val remote = remotePathOf(path)
        val item = provider.createFolder(remote)
        // A folder the provider had to place under another name keeps its local name here; the rows beneath it
        // (a folder refused earlier and created now has local-only children) follow it.
        val landed = if (item.nameOverridden) item.path else remote
        val existing = db.getEntry(path)
        if (existing == null) {
            db.insertFolder(path = path, remoteId = item.id, mtime = item.modified ?: Instant.now(), remotePath = landed.takeIf { it != path })
        } else if (existing.remoteId == null) {
            // The row of a folder that was refused by name and lived only here until now; the upsert also clears its block.
            db.upsertEntry(
                existing.copy(
                    remotePath = landed.takeIf { it != path },
                    remoteId = item.id,
                    remoteModified = item.modified ?: Instant.now(),
                    lastErrorAt = null,
                    lastSynced = Instant.now(),
                ),
            )
            if (landed != path) db.assignRemotePaths(path, landed)
        }
        return item
    }

    /**
     * The drive refuses the folder [path] by its name: it exists only here, so the client can go on filling it. Its
     * block ([reason], the conflicting [sibling] when known) is what the status reports, and everything beneath it is
     * blocked by it ([BLOCKED_BY_PARENT]) until [retryBlockedFolders] finds the drive willing.
     */
    fun recordBlockedFolder(
        path: String,
        reason: String,
        sibling: String?,
    ) {
        if (db.getEntry(path) == null) {
            db.upsertEntry(
                SyncEntry(
                    path = path,
                    remoteId = null,
                    remoteHash = null,
                    remoteSize = 0L,
                    remoteModified = null,
                    localMtime = null,
                    localSize = null,
                    isFolder = true,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = Instant.now(),
                ),
            )
        }
        if (reason == BLOCKED_BY_PARENT) db.markUploadFailed(path, Instant.now()) else db.markNameBlocked(path, reason, sibling)
    }

    /**
     * Asks the drive again for every folder that was refused by name, and then for the local-only folders beneath each
     * one it now accepts (their files follow through the upload replay). A refusal stays a block. Returns the number
     * of folders created.
     */
    suspend fun retryBlockedFolders(): Int {
        var created = 0
        for (blocked in db.blockedRows().filter { it.isFolder }) {
            if (!retryFolder(blocked.path)) continue
            created++
            val below = db.getEntriesByPrefix(blocked.path.trimEnd('/') + "/").filter { it.isFolder && it.remoteId == null }.sortedBy { it.path }
            for (folder in below) if (retryFolder(folder.path)) created++
        }
        return created
    }

    private suspend fun retryFolder(path: String): Boolean =
        try {
            createRemoteFolder(path)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: NameRefusedException) {
            db.markNameBlocked(path, e.reason, e.sibling)
            false
        } catch (_: BlockedByParentException) {
            false
        } catch (e: Exception) {
            log.warn("retry of the blocked folder {} failed: {}", path, e.message)
            false
        }

    /**
     * Delete a path on the remote provider and update state.db.
     * Handles both files and folders — provider distinguishes by
     * remoteId/path. Caller (HydrationImpl.unlink or .rmdir) is
     * responsible for type-checking.
     *
     * Idempotent on "remote already gone": if [provider.delete] throws a
     * [ProviderException] that [isAlreadyGone] recognises as a typed not-found
     * signal, the deletion is treated as already complete — the exception is
     * swallowed and markDeleted still runs, since the postcondition "path no
     * longer on cloud" is satisfied. Every other exception (auth, 5xx, network,
     * throttle) is re-thrown unchanged so real failures surface as EIO rather
     * than being silently eaten.
     *
     * The idempotency gate is type-gated, not free-text: only the two specific
     * provider-originated not-found shapes (path-resolution failure, direct
     * metadata miss) are recognised. A 5xx/proxy error whose body happens to
     * contain "404" or "not found" does NOT satisfy [isAlreadyGone] and will
     * re-throw as expected.
     *
     * state.db is only updated after the provider call succeeds (or is
     * determined to be a no-op because the remote is already gone).
     */
    suspend fun deleteRemote(
        path: String,
        permanent: Boolean = false,
    ) {
        // Read before the delete: the folder check below needs the row, and once the
        // row is tombstoned the alive view no longer answers for it.
        val entryBefore = db.getEntry(path)
        // A folder the drive refused by name, or one beneath such a folder, was never created there: the delete is
        // local, and a lookup by its name could only reach a similar sibling.
        val neverOnDrive = entryBefore != null && entryBefore.remoteId == null && entryBefore.isFolder && isNameBlocked(path)
        if (!neverOnDrive) {
            try {
                // archive#402/#403: a row with a cloud id is deleted by that id: the twin of a duplicate remote name shares its
                // remote path with the item that holds the name, and a lookup by path could reach either. archive#1178: the same
                // holds for a delete for good, where the wrong twin would be unrecoverable.
                val remoteId = entryBefore?.remoteId?.takeUnless { it.startsWith("local:") }
                val remotePath = remotePathOf(path, entryBefore)
                when {
                    permanent && remoteId != null -> provider.deletePermanentlyById(remoteId, remotePath)
                    permanent -> provider.deletePermanently(remotePath)
                    remoteId != null -> provider.deleteById(remoteId, remotePath)
                    else -> provider.delete(remotePath)
                }
            } catch (e: Exception) {
                if (isAlreadyGone(e)) {
                    // Remote is already gone; fall through to markDeleted below.
                } else {
                    throw e
                }
            }
        }
        db.markDeleted(path, mountDelete = true)
        // archive#87: a folder's rows below it are part of the same user delete — leave none of
        // them EXISTS, or the next fresh mount plans their re-download (the live 133k case).
        if (entryBefore?.isFolder == true) db.markDescendantsDeleted(path, mountDelete = true)
    }

    /**
     * WB-3 (archive#87): the delete of a never-uploaded file discards the provider's staged upload copy —
     * the encrypted ciphertext (and its resume sidecar) is the only other copy of the content, the
     * user deleted the file, so it goes with the row and the cache copy instead of sitting in the
     * tombstone directory until the resume TTL passes. The path is the logical one; the engine
     * resolves it to the local cache path the uploader staged, which is what the tombstone is keyed
     * by. A provider without staged uploads ignores this (the default is a no-op).
     */
    suspend fun discardStagedUpload(logicalPath: String) {
        provider.discardStagedUpload(resolveCachePath(logicalPath).toAbsolutePath().toString())
    }

    // archive#560 U2: the classification lives in :app:engine-core (RemoteErrors), shared with the mount.
    // See [org.krost.unidrive.engine.RemoteErrors.isAlreadyGone] for the two shapes it accepts.
    private fun isAlreadyGone(e: Throwable): Boolean = org.krost.unidrive.engine.RemoteErrors.isAlreadyGone(e)

    /**
     * Rename a remote item from [oldPath] to [newPath] and update state.db.
     * Used by the hydration SPI (HydrationImpl.rename) to back FUSE rename
     * requests. Pre-flight checks (source-exists, destination-doesn't-exist,
     * destination-parent-exists) live in HydrationImpl; this entry point
     * trusts its caller and performs the remote move plus the state.db
     * row update unconditionally.
     *
     * Throws ProviderException on cloud-side failure. state.db is only
     * updated after the provider call succeeds. For folders, the path
     * rewrite also moves all descendant rows under the new prefix
     * (db.renamePrefix), matching the rename's recursive semantics on
     * both OneDrive and Internxt.
     */
    suspend fun renameRemote(oldPath: String, newPath: String) {
        // The cloud knows both ends by their remote paths: the old one by the row's (or its folder's) server name, the
        // new one by the destination folder's. A moved item whose new name is an ordinary one drops its server name.
        val oldEntry = db.getEntry(oldPath)
        val oldRemote = remotePathOf(oldPath, oldEntry)
        val newRemote = remotePathOf(newPath, entry = null)
        // archive#402/#403: moved by its cloud id where the row has one (see deleteRemote).
        val remoteId = oldEntry?.remoteId?.takeUnless { it.startsWith("local:") }
        val moved = if (remoteId != null) provider.moveById(remoteId, oldRemote, newRemote) else provider.move(oldRemote, newRemote)
        db.renamePrefix(oldPath, newPath)
        db.rebaseRemotePaths(oldRemote, newRemote)
        if (newRemote != newPath) db.assignRemotePaths(newPath, newRemote)
        // A move gives the item a new write token (OneDrive's eTag moves with the name): the next edit is based on it.
        if (oldEntry?.remoteVersion != null && !moved.isFolder && moved.version != oldEntry.remoteVersion) {
            db.getEntry(newPath)?.let { db.upsertEntry(it.copy(remoteVersion = moved.version), keepMarks = true) }
        }
    }

    /**
     * archive#1217: the upload of a never-uploaded file landed [landed] while a rename moved its row to [newPath] (the rename's
     * ghost probe ran before the item existed, so the rename was local only, and the attempt created the item at the
     * old path). The item is moved to the new path by its id and the row there adopts it, exactly as a finished upload
     * rebaselines its row: no second upload, no item left at the old path. The row records the stats of the bytes
     * that were sent; a write that landed at the new path since stays a newer edit (based on the moved version).
     * Returns false, touching nothing, when no pending row lives at [newPath] any more (it was removed, or something
     * else gave it a cloud item): the item stays where it landed and the next enumeration decides about it.
     * Throws when the move fails; the item then stays at the old path and the row stays pending.
     */
    suspend fun adoptLandedAfterRename(
        landed: LandedWithoutRow,
        newPath: String,
    ): Boolean {
        val row = db.getEntry(newPath) ?: return false
        if (row.isFolder || row.remoteId?.startsWith("local:") == false) return false
        val newRemote = remotePathOf(newPath, row)
        val moved =
            if (landed.remotePath == newRemote) landed.item else provider.moveById(landed.item.id, landed.remotePath, newRemote)
        recordProviderContact()
        markRecentlyUploaded(newRemote)
        val cachePath = resolveCachePath(newPath)
        val now = statBeforeUpload(cachePath)
        val changedSince = now != null && landed.sent != null && now != landed.sent
        val mtime = landed.sent?.first ?: now?.first ?: row.localMtime
        val size = landed.sent?.second ?: now?.second ?: row.localSize
        val adopted = row.copy(
            remotePath = newRemote.takeIf { it != newPath },
            remoteId = moved.id,
            remoteHash = moved.hash ?: landed.item.hash,
            // The move gave the item a new write token: the next edit is based on it.
            remoteVersion = moved.version,
            remoteSize = moved.size,
            remoteModified = moved.modified,
            localMtime = mtime,
            localSize = size,
            isHydrated = true,
            lastSynced = Instant.now(),
            lastErrorAt = row.lastErrorAtAfterUpload(),
            cacheBacked = true,
            editBase = if (changedSince) (moved.version ?: moved.hash).orEmpty() else null,
        )
        db.upsertEntry(withSentHash(adopted, cachePath, landed.sent))
        log.info("upload of {} landed at {} while a rename moved it; moved the item to {} by its id", newPath, landed.remotePath, newRemote)
        return true
    }

    // The remote item at [path], or null only when the provider proves it ABSENT.
    // The mount's rename/unlink use this to tell a genuinely-never-uploaded local:
    // row from a "ghost" — a local: row whose content actually landed on the cloud
    // (an upload whose response was lost) and must therefore be moved/deleted
    // remotely, not handled locally. A genuine not-found maps to null; transient
    // failures (auth expiry, throttling, 5xx, network) are PROPAGATED, never read
    // as absence — otherwise a ghost probed during a blip would fall to the
    // local-only path and silently skip the cloud move/delete (orphan/duplicate).
    // RemoteErrors.isNotFound is the one rule: the typed not-found messages, an HTTP 404 on the exception (OneDrive, S3,
    // Internxt) and a FileNotFoundException (the local-directory provider).
    suspend fun remoteItemOrNull(path: String): CloudItem? =
        try {
            // An entry the drive refused by name cannot be there under that name, and a lookup by it could only
            // reach a similar sibling: nothing to probe.
            if (isNameBlocked(path)) null else provider.getMetadata(remotePathOf(path))
        } catch (e: Exception) {
            if (org.krost.unidrive.engine.RemoteErrors.isNotFound(e)) {
                null
            } else {
                throw e
            }
        }

    private fun statBeforeUpload(local: Path): Pair<Long, Long>? = Transfers.statBeforeUpload(local)

    // archive#337/#148: hash only the bytes that were sent, see Transfers.withSentHash.
    private fun withSentHash(
        entry: SyncEntry,
        local: Path,
        sent: Pair<Long, Long>?,
    ): SyncEntry = Transfers.withSentHash(entry, local, sent, provider.hashAlgorithm(), log)

    private fun isTracked(remotePath: String): Boolean = guard.isTracked(remotePath)

    // The OS file-name rules (org.krost.unidrive.engine.LocalNames); the hydration layer refuses a new name with them.
    internal fun localNameIssue(path: String): String? = org.krost.unidrive.engine.localNameIssue(path)

    // Defer the absence sweep's deletion verdict for a path this process just wrote (the gather's state).
    private fun markRecentlyUploaded(path: String) = gather.markRecentlyUploaded(path)

    // The bytes are hashed as the host's transfers do (archive#396), see Transfers.withLocalHash.
    private fun withLocalHash(
        entry: SyncEntry,
        local: Path,
        mtime: Long,
        size: Long,
    ): SyncEntry = Transfers.withLocalHash(entry, local, mtime, size, provider.hashAlgorithm(), log)

    companion object {
        /** The mount front-end of [host]: one per host, built on the first call. */
        fun over(host: MountHost): MountEngine = host.mountWiring.frontEnd { MountEngine(it) }

        // The wire tokens of `hydration.matches_local` (archive#1219). [METHOD_SIZE] and the provider's own methods are shared
        // with the client; the unreadable token is the `error` of a request that cannot be answered.
        const val INVALID_LOCAL_PATH = "invalid_local_path"
        const val LOCAL_FILE_UNREADABLE = "local_file_unreadable"
        const val METHOD_SIZE = "size"
        const val METHOD_RECORDED_SHA256 = "recorded-sha256"
    }
}

/**
 * archive#450: what [MountEngine.cacheDisposition] says about a hydration-cache copy.
 * [PROTECTED] must never be deleted; [DISPOSABLE] holds only bytes the row already knows (the remote
 * version or the recorded baseline). (archive#560 U6 retired REDUNDANT — the sync-root copy it compared
 * against is gone.)
 */
enum class CacheDisposition { PROTECTED, DISPOSABLE }

/**
 * What [MountEngine.uploadFromCache] did, for the transfer history: [sent] is false when no byte left the
 * machine (the cloud already held these bytes, or the path is keep-local); [size] is the cache copy's size,
 * [oldHash] the version the row recorded before and [newHash] the one the cloud reports now. A value the
 * upload did not learn is null.
 */
data class UploadOutcome(
    val sent: Boolean,
    val size: Long?,
    val oldHash: String?,
    val newHash: String?,
    // The cloud had a newer version and the provider kept both: the bytes landed as a new item, named here by its
    // path in the mount. The row the upload was for is the cloud version's now, without a cache copy.
    val keptBothAs: String? = null,
    // With [keptBothAs]: the cache copy moved there, with a row of its own (else the next delivery brings the copy).
    val copyAdopted: Boolean = false,
    // archive#1217: the row vanished while the bytes were in flight (a rename, a delete or a reap raced the upload), so no row
    // records the item that landed. Only the caller knows whether a rename moved the row, see
    // [MountEngine.adoptLandedAfterRename].
    val landedWithoutRow: LandedWithoutRow? = null,
)

/**
 * archive#1217: an item an upload created after its row was gone: the [item] as the provider answered, the [remotePath] it
 * landed at, and the cache copy's (mtime, size) as they were sent ([sent], null when they could not be read).
 */
data class LandedWithoutRow(
    val item: CloudItem,
    val remotePath: String,
    val sent: Pair<Long, Long>?,
)
/**
 * A folder above the path was refused by name ([NameRefusedException]) and exists only in this mount, so the drive cannot
 * take anything below it. Raised without a request to the provider; permanent, so the upload queue does not retry it.
 */
class BlockedByParentException(
    val parent: String,
) : PermanentUploadFailureException("blocked by the folder $parent, which the drive refuses by name")

/** The reason of an entry that waits for a blocked folder above it; the reason of the folder itself is [NameRefusedException.SIMILAR_NAME]. */
const val BLOCKED_BY_PARENT = "blocked_by_parent"
