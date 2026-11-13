package org.krost.unidrive.cli

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.krost.unidrive.AccountOwner
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.CredentialHealth
import org.krost.unidrive.authenticateAndLog
import org.krost.unidrive.engine.BackupsNamespace
import org.krost.unidrive.engine.ReadOnlyTrashProvider
import org.krost.unidrive.engine.StrictNameBlocks
import org.krost.unidrive.engine.TrashNamespace
import org.krost.unidrive.engine.ViewCache
import org.krost.unidrive.engine.ViewNamespaces
import org.krost.unidrive.engine.ViewSpec
import org.krost.unidrive.engine.throttleObservingSink
import org.krost.unidrive.hydration.HydrationEvent
import org.krost.unidrive.hydration.HydrationImpl
import org.krost.unidrive.hydration.HydrationIpcHandler
import org.krost.unidrive.hydration.MountEngine
import org.krost.unidrive.hydration.StateDbNameSpellingStore
import org.krost.unidrive.hydration.provesProviderReachable
import org.krost.unidrive.hydration.serialiseHydrationEvent
import org.krost.unidrive.io.Os
import org.krost.unidrive.io.ndjsonString
import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcServer
import org.krost.unidrive.sync.ProcessLock
import org.krost.unidrive.sync.ProfileMode
import org.krost.unidrive.sync.PendingUploads
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.StateDbCorruptException
import org.krost.unidrive.sync.StateDbRecovery
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.telemetry.TransferHistory
import org.krost.unidrive.sync.ignore.IgnoreSettings
import org.krost.unidrive.sync.ignore.UploadIgnore
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Per-profile daemon lifecycle, separated from picocli wiring for testability.
 *
 * Per spec docs/dev/specs/unidrive-daemon-design.md §3.3: the picocli class
 * `DaemonRunCommand` is a thin shell that reads its dependencies off
 * `parent: Main` and constructs this runtime. Tests construct DaemonRuntime
 * directly with their own paths + provider factory — no JVM spawn required.
 *
 * Lifecycle (spec §3.2):
 *   1. Acquire ProcessLock(DAEMON). On contention: render holder info, exit 1.
 *   2. Warn on stale FUSE mounts (best-effort). Never abort.
 *   3. Open StateDatabase. On failure: release lock (in cleanup), rethrow.
 *   4. Call provider.authenticateAndLog(). On failure: release lock, rethrow.
 *      NO socket is bound until this succeeds.
 *   5. Register handlers, then bind IpcServer LAST — a client that can
 *      connect is guaranteed a reply for every documented verb; binding
 *      earlier accepted requests into the unregistered window and hung
 *      their senders. On bind failure: release lock, rethrow.
 *   6. SERVE until close() is called.
 *   7. On close: graceful shutdown bounded by SHUTDOWN_DEADLINE_MS (spec I7): the IPC server closes, the
 *      uploads that are transferring get up to HydrationImpl.UPLOAD_DRAIN_MS to finish (gkrost/unidrive-cli#72),
 *      the rest is cancelled, then state.db, the socket and the lock are released.
 *
 * Phase 2 scope: hydration.* verbs + sync.subscribe wired. refresh.run and
 * daemon.status come in Phase 3.
 */
// The constructor lists the daemon's seams one by one (tests build it with named arguments); its baseline entry
// quoted the whole parameter list, so it is suppressed here instead.
@Suppress("LongParameterList")
class DaemonRuntime(
    private val profileName: String,
    private val lockFile: Path,
    private val dbPath: Path,
    private val syncRoot: Path,
    private val socketPath: Path,
    private val providerFactory: () -> CloudProvider,
    private val syncPaths: List<String> = emptyList(),
    private val excludePatterns: List<String> = emptyList(),
    // > 0 enables the in-process auto-poll (mount-view-refresh-design.md §5):
    // one periodic enumerate on serveScope, serialised by the sync.enumerate
    // in-flight guard. 0 = off. `daemon run` passes the profile's daemon_poll_seconds
    // (default 60 s, archive#463) or --poll-interval; 0 stays the default here so tests that
    // construct a runtime do not poll.
    private val pollIntervalMs: Long = 0,
    // archive#891: the health line's interval (one INFO line with the heap, GC, threads, enumeration and upload
    // queue, see DaemonHealthLine). `daemon run` passes 60 s; 0 = off, so tests that construct a runtime
    // log nothing.
    private val healthIntervalMs: Long = 0,
    // archive#450: hydration cache budget in bytes (profile key hydration_cache_max_bytes); 0 = unlimited,
    // below 0 = no content cache (#404). `daemon run` passes the config's resolution; a profile without the key
    // gets the config default (disabled). Tests that build a runtime without it get [HydrationImpl.DEFAULT_CACHE_MAX_BYTES].
    private val hydrationCacheMaxBytes: Long = HydrationImpl.DEFAULT_CACHE_MAX_BYTES,
    // archive#603 (U4): the profile's hosting mode, resolved and gated by `daemon run` before this runtime is
    // constructed. A mount daemon serves the hydration verbs, sync.enumerate and the poller, and routes
    // refresh.run to the always-enumerate path; a mirror daemon refuses the mount verbs (wrong_mode) and
    // keeps refresh.run's legacy reconcile. Reported in daemon.status (mode, capabilities) and the banner.
    private val profileMode: ProfileMode,
    // The profile's config folder (it holds credentials.json and the lock): the IPC token files are
    // written here at every start, before the socket listens (docs/dev/specs/ipc-authentication.md).
    private val ipcTokenDir: Path = lockFile.parent,
    // archive#655: TTL of the quota snapshot in daemon.status (`quota`), in ms; 0 = the quota field
    // renders from the cache only and never refreshes. `daemon run` passes the profile's
    // quota_refresh_minutes (default 15 min).
    private val quotaRefreshMs: Long = 15 * 60_000L,
    // archive#655: whether the provider's quota() reports the account's storage plan (ProviderMetadata
    // hasQuota). False — e.g. localfs — keeps the `quota` field ABSENT from daemon.status.
    private val providerHasQuota: Boolean = true,
    // archive#586: the offline credential check behind `credential_health` in daemon.status (`daemon run` passes the provider
    // factory's), re-run at most once per TTL and never inside a status request. Null = no check can run: the field then
    // reports `unknown`.
    private val credentialHealthCheck: (() -> CredentialHealth)? = null,
    private val credentialHealthTtlMs: Long = 60_000L,
    // archive#660: the profile's ignore rules (honor_gitignore, ignore_rules). A mount daemon applies them to its uploads, a
    // mirror daemon to its scan and plan, and both report them (daemon.status `ignore`, ignore.explain); null = off, the
    // built-in excludes stay plain patterns.
    private val ignoreSettings: IgnoreSettings? = UploadIgnore.settingsFor(honorGitignore = false, accountRules = emptyList()),
    // The profile's trash_folder_name: the name of the read-only virtual folder that shows the provider's trash. Null =
    // the default, "Papierkorb".
    private val trashFolderName: String? = null,
    // The read-only views of the profile (Recents, ...): the virtual folders that list items living elsewhere in the drive.
    // Only a mount daemon shows them; none (the default) leaves every reply as it was.
    private val views: List<ViewSpec> = emptyList(),
    // Whether the profile shows the account's backups in a read-only folder of their own (BackupsNamespace), and that folder's
    // name (null = the default, "Backups"). Only a mount daemon whose provider declares Capability.Backups shows them; off
    // (the default) leaves every listing and reply as it was.
    private val showBackups: Boolean = false,
    private val backupsFolderName: String? = null,
    // The backups root stays while nothing is in it when the profile says `show_backups = true` (not `auto`).
    private val backupsRootAlways: Boolean = false,
    // How long the references of a view are answered as they are before the next listing starts a refresh (a seam for tests).
    private val viewTtlMs: Long = ViewCache.DEFAULT_TTL_MS,
    // How a start that loses the profile lock ends the process (a seam for tests, which cannot exit).
    private val exitProcess: (Int) -> Unit = { code -> System.exit(code) },
    // archive#678: where the startup replay and the cache sweep run their blocking per-record walks.
    // Dispatchers.IO in production; a seam for tests, which pin that a stop does not wait for them.
    private val startupIoDispatcher: CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
    private val afterProfileLockAcquired: () -> Unit = {},
    // archive#578: the --upload-concurrency flag's override of the profile's upload_concurrency
    // key; null leaves the key in force. Applied to the provider right after construction.
    private val uploadConcurrencyOverride: Int? = null,
    // gkrost/unidrive-cli#72: how long a stop waits for the uploads that are transferring before it cancels them
    // (HydrationImpl.UPLOAD_DRAIN_MS); a seam for tests, which need no ten-second waits.
    private val uploadDrainMs: Long = HydrationImpl.UPLOAD_DRAIN_MS,
) {
    private val log = LoggerFactory.getLogger(DaemonRuntime::class.java)

    /**
     * archive#652: how the transfer history file is opened; a seam for tests, which need an open to fail on demand (and to
     * keep hold of the history to see it closed). Default retention (90 days or 200 MB) applies until the config keys
     * exist.
     */
    internal var openHistory: (profile: String, file: Path) -> TransferHistory =
        { profile, file -> TransferHistory.open(profile, file) }

    /**
     * archive#813: the profile the sign-in verbs (`auth.begin`, `auth.complete`, `auth.login`) sign in, with its non-secret
     * config. `daemon run` sets it before [start]; null (a runtime a test builds without it) answers them
     * `sign_in_unavailable`.
     */
    internal var signInTarget: ProfileTarget? = null

    /** archive#813: when a sign-in through this daemon last loaded new credentials; a credential rejection before it is history. */
    @Volatile
    private var signedInAtMs: Long? = null

    private var lock: ProcessLock? = null
    private var db: StateDatabase? = null

    /** gkrost/unidrive-cli#581: set when a mount-mode start found state.db corrupt and moved it aside; reported by daemon.status. */
    @Volatile private var stateRecovery: StateDbRecovery? = null

    /**
     * Opens state.db. A corrupt file (gkrost/unidrive-cli#581) ends the start with [StateDbCorruptException] — except in
     * mount mode, where the owner decided the daemon recovers by itself: the damaged file is kept as
     * `state.db.corrupt-<time>`, a fresh one is opened, the engine rebuilds from the cloud listing, and the status says
     * so (including that local changes recorded only in the state are lost). Other modes only report.
     */
    private fun openStateDatabase(): StateDatabase {
        try {
            return StateDatabase(dbPath).also { it.initialize() }
        } catch (e: StateDbCorruptException) {
            if (profileMode != ProfileMode.MOUNT) throw e
            val recovery =
                try {
                    StateDbRecovery.moveAside(dbPath)
                } catch (moveFailure: java.io.IOException) {
                    // Cannot recover: report the corruption as a non-mount mode does, with the reason it could not be moved.
                    e.addSuppressed(moveFailure)
                    log.info("daemon: could not move the corrupt state.db at {} aside: {}", dbPath, moveFailure.message)
                    throw e
                }
            log.warn("daemon: {}", recovery.message)
            System.err.println("WARNING: unidrive daemon: ${recovery.message}")
            stateRecovery = recovery
            return StateDatabase(dbPath).also { it.initialize() }
        }
    }

    // archive#655: the daemon's quota snapshot for daemon.status (`quota`). It reads the cache and persists
    // through this runtime's database — the one the lifecycle opens and cleanup closes — and shares the
    // provider factory.
    private val quotaMonitor = QuotaSnapshotMonitor(quotaRefreshMs = quotaRefreshMs, provider = { servingProvider }, db = { db })

    private var ipcServer: IpcServer? = null
    private val closeSignal = CompletableDeferred<Unit>()

    // Counted down once cleanup() has run. Only armed after the lock is held: a start() that lost
    // the lock race (or never ran) has nothing to wait for, and a shutdown hook must not stall the
    // System.exit that path triggers.
    private val cleanupDone = java.util.concurrent.CountDownLatch(1)

    @Volatile private var lifecycleActive = false
    @Volatile private var startedAtMs: Long = 0

    // The provider this daemon serves with, once it is built: the quota refresh uses it rather than building a second
    // instance with a token state of its own (gkrost/unidrive-cli#442).
    @Volatile private var servingProvider: CloudProvider? = null

    /**
     * Runs the lifecycle until [close]. gkrost/unidrive-cli#225: it runs in [logContext], so the serve scope and every
     * coroutine under it (the IPC server's threads, the poller and the enumeration, the backfill, the HTTP calls made from
     * them) logs with this daemon's profile and build on whichever thread it resumes.
     */
    suspend fun start() = withContext(logContext(profileName)) { lifecycle() }

    private suspend fun lifecycle() {
        Files.createDirectories(lockFile.parent)
        val acquiredLock = ProcessLock(lockFile)
        if (!acquiredLock.tryLock(ProcessLock.Mode.DAEMON)) {
            renderLockContentionAndExit(acquiredLock)
            return
        }
        lock = acquiredLock
        lifecycleActive = true

        try {
            afterProfileLockAcquired()

            // 2. Stale-mount warn (spec §3.3) — best-effort, never aborts.
            val staleMounts = StaleMountDetector.detectStaleFuseUnidriveMounts()
            if (staleMounts.isNotEmpty()) {
                System.err.println(
                    "WARNING: detected ${staleMounts.size} stale unidrive FUSE mount(s) " +
                        "(likely from a kill -9'd `unidrive mount` parent or prior daemon): " +
                        staleMounts.joinToString(),
                )
                // Emit one ready-to-run command per detected mount (real path,
                // not a `<path>` placeholder) so the operator can copy-paste directly.
                System.err.println("These mounts no longer serve data. Clean up with:")
                staleMounts.forEach { System.err.println("  fusermount3 -u $it") }
            }

            db = openStateDatabase()
            // Before anything is served: the one rewrite of a fragmented file holds it alone (seconds on a large drive).
            runCatching { db!!.compactOnce() }.onFailure { log.warn("compacting state.db at start failed", it) }

            // archive#655: seed the quota snapshot from the sync_state cache (the same tuple
            // `quota` persists), so the very first status already shows the last known
            // values flagged stale instead of nulls.
            quotaMonitor.seedFromCache(db!!)

            // 4. Authenticate. NO socket is bound until this succeeds.
            // The trash namespace is read-only: this wrapper is the last line of that rule, whichever part of the
            // engine starts a write (the same namespace is handed to the engine, whose guard and planner ask it too).
            val trash = TrashNamespace(trashFolderName)
            // The views are a mount front-end feature: a mirror profile has no folder for them. Their roots yield to the live
            // top-level items state.db holds. A view the provider does not declare (Capability.View) is not shown at all: an
            // empty, unavailable folder would stand on every mount of a provider that never has it.
            val cloud = providerFactory()
            val viewNamespaces =
                ViewNamespaces(
                    specs = if (profileMode == ProfileMode.MOUNT) viewsTheProviderHas(cloud) else emptyList(),
                    topLevelNames = { db?.listDirectChildren("")?.map { it.path.removePrefix("/") } ?: emptyList() },
                )
            val backups =
                BackupsNamespace(backupsFolderName, enabled = showBackups && providerShowsBackups(cloud), rootAlways = backupsRootAlways)
            val provider = ReadOnlyTrashProvider(cloud, trash, viewNamespaces, backups)
            servingProvider = provider
            // archive#578: the flag wins over the profile key, same shape as --poll-interval.
            uploadConcurrencyOverride?.let { n ->
                if (!provider.overrideUploadConcurrency(n)) {
                    System.err.println(
                        "unidrive daemon: --upload-concurrency ignored — provider '${provider.id}' does not upload in parts",
                    )
                }
            }
            try {
                provider.authenticateAndLog()
            } catch (e: Exception) {
                System.err.println(
                    "unidrive daemon: authentication failed for profile " +
                        "'$profileName': ${e.message}",
                )
                throw e
            }

            // New IPC tokens for this start, written before the socket exists (only the lock holder
            // gets here, so a refused second start never rotates a running daemon's tokens). A refusal
            // has printed its one line; start() rethrows it and `daemon run` exits 78.
            val ipcAuth = IpcAuth.issueOrReport(ipcTokenDir, profileName, BuildInfo.versionString())

            Files.createDirectories(socketPath.parent)
            val server = IpcServer(socketPath, auth = ipcAuth)
            ipcServer = server

            // Use supervisorScope-with-explicit-cancel so the SERVE block returns
            // promptly on close(). A plain coroutineScope would wait for the
            // hydration.events.collect launch (infinite flow) and any handler
            // children spawned by IpcServer/HydrationIpcHandler to complete —
            // they don't. Solution: launch the long-lived children under a
            // dedicated SupervisorJob that we cancel before the scope exits.
            // The scope inherits the daemon's logContext from start() (gkrost/unidrive-cli#225).
            val serveJob = kotlinx.coroutines.SupervisorJob()
            val serveScope = serveScopeOf(kotlin.coroutines.coroutineContext, serveJob)
            // A profile signed in before ids were kept has no account_id (AccountOwner): one identity call with the
            // credentials this start just authenticated with records it. Off the start's path and bounded: a failed or slow
            // call logs one line, the daemon serves either way and the next start tries again.
            serveScope.launch { AccountOwner.recordIfMissing(ipcTokenDir, cloud) }
            // The engine's two hooks into the hydration layer (see HydrationLink). Declared outside the
            // try below: its finally joins the hydration uploads through it.
            val hydrationLink = HydrationLink()
            // archive#652: opened with the mount's hydration layer, closed (flushed) after its uploads have been joined.
            var transferHistory: TransferHistory? = null
            var historyRpc: HistoryRpcHandler? = null
            // Coroutine-debug probes (archive#613 ask 1): with UNIDRIVE_COROUTINE_DEBUG=1 the
            // daemon captures suspension stacks at start and dumps every live coroutine when a
            // coroutine-dump.trigger file appears in the profile folder — a hung upload becomes one
            // stack trace instead of a silent wait. Off by default; see CoroutineDebug.
            if (CoroutineDebug.enabled()) {
                CoroutineDebug.install()
                CoroutineDebug.watchForDumpRequests(ipcTokenDir, serveScope)
            }
            try {
                // cacheKey = profileName keeps the daemon's hydration cache
                // subtree per-account and consistent with MountCommand's
                // co-daemon --cache root (also profile.name), so the FUSE
                // backing store, eviction, and crash-recovery scanner all
                // address the same files.
                // The engine is built first and the hydration layer over it (MountEngine.over(engine)), so
                // the engine's hooks into that layer go through hydrationLink, bound right after the layer
                // is built. archive#301: the enumerate-reap asks whether an upload of a path is queued or in flight
                // before it evicts that path's cache file. viewInvalidationSink fans the event to all
                // subscribers without going through the HydrationImpl flow, which is intentional: the
                // invalidation event originates outside the per-path hydration lifecycle.
                val engine = SyncEngine(
                    provider, db!!, syncRoot = syncRoot, cacheKey = profileName, syncPaths = syncPaths,
                    // gkrost/unidrive-cli#392: the provider TYPE the engine looks its audited limits up by (transfer cap,
                    // download budget). Left blank before, every daemon ran at the fallback of 4 whatever the provider.
                    providerId = cloud.id,
                    standingScope = syncPaths, excludePatterns = excludePatterns, trashNamespace = trash, viewNamespaces = viewNamespaces,
                    backupsNamespace = backups,
                    mountIgnore = ignoreSettings.takeIf { profileMode == ProfileMode.MOUNT },
                    mirrorIgnore = ignoreSettings.takeIf { profileMode != ProfileMode.MOUNT },
                    uploadInFlight = hydrationLink::hasUploadSlot,
                    viewInvalidationSink = { changedPaths, full, moved ->
                        // A pass changed rows: the top level may hold a folder with the name of a view's root now.
                        viewNamespaces.invalidate()
                        val cap = HydrationEvent.VIEW_INVALIDATED_PATH_CAP
                        val event = if (full || changedPaths.size > cap) {
                            HydrationEvent.ViewInvalidated(paths = emptyList(), full = true)
                        } else {
                            // archive#595: the renames ride along as an additive hint; a consumer
                            // that ignores them still sees both ends in `paths`.
                            HydrationEvent.ViewInvalidated(
                                paths = changedPaths.toList(),
                                moved = moved.map { HydrationEvent.ViewInvalidated.Moved(it.from, it.to) },
                            )
                        }
                        hydrationLink.dispatchEvent(event)
                    },
                )
                // archive#560 U3: the mount operations (hydration, uploads, remote folder/delete/rename and
                // the enumeration) run on MountEngine, over this engine's shared core (guard, gather,
                // enumeration: one of each per daemon). The engine itself stays for the refresh.run
                // fallback (RefreshRpcHandler, U4). See MountEngine for who owns what.
                val mount = MountEngine.over(engine)
                // The references of the views, refreshed on the serve scope; a change goes to the subscribers as a
                // view.invalidated for the view's root. Held so the poller can keep them fresh (afterTick below) and the
                // start fill below can fetch them once without a caller (gkrost/unidrive-cli#631).
                val viewCache: ViewCache? =
                    if (!viewNamespaces.isEmpty()) {
                        ViewCache(
                            scope = serveScope,
                            fetch = provider::listView,
                            limitOf = { kind -> viewNamespaces.spec(kind)?.limit ?: 1 },
                            pageCapOf = { kind -> viewNamespaces.spec(kind)?.pageCap ?: 1 },
                            ttlMs = viewTtlMs,
                            onChanged = { kind ->
                                viewNamespaces.rootPath(kind)?.let { root ->
                                    hydrationLink.dispatchEvent(HydrationEvent.ViewInvalidated(paths = listOf(root)))
                                }
                            },
                        ).also { mount.attachViews(it) }
                    } else {
                        null
                    }
                // archive#778: the pause (daemon.pause / daemon.resume); the uploads and downloads of the mount consult it
                // through the front-end, the enumeration and the refresh through the handlers below.
                val pause = mount.pause
                val wrongModeReply = """{"ok":false,"error":"wrong_mode"}"""
                val mountMode = profileMode == ProfileMode.MOUNT
                // archive#652: a mount profile records its transfers; a failure to open the history is logged once and the daemon
                // runs without it. Mirror mode records none (its transfers are the sync pass's).
                transferHistory = if (mountMode) openTransferHistory() else null
                // gkrost/unidrive-cli#29: the strict-name blocks follow the provider's mode at every start, not only when an item is next delivered.
                db?.let { d ->
                    try {
                        val changed = StrictNameBlocks.reconcileAll(d, provider)
                        if (changed > 0) log.info("Strict-name mode: {} row(s) marked or cleared at start", changed)
                    } catch (e: java.sql.SQLException) {
                        log.warn("Strict-name blocks could not be reconciled at start: {}", e.message)
                    }
                }
                // archive#560 U6 (cutover): the hydration runtime exists only on a mount profile. A mirror
                // daemon constructs no hydration layer and keeps no cache — its local bytes live in
                // the sync root its reconcile owns, and every hydration verb gets wrong_mode below.
                // Upload and replay jobs outlive IPC handlers but not this daemon: hydrationUploadScope
                // keeps their scope separate so shutdown can cancel/join it without cancelling the serve scope.
                val hydration: HydrationImpl? =
                    if (mountMode) {
                        HydrationImpl(
                            mount,
                            db!!,
                            recoveryUploadScope = hydrationUploadScope(profileName),
                            cacheMaxBytes = hydrationCacheMaxBytes,
                            history = transferHistory,
                        )
                    } else {
                        null
                    }
                if (hydration != null) {
                    log.info(hydrationLimitsLine(mount.transferBudgetSize, HydrationImpl.DEFAULT_UPLOAD_QUEUE_DEPTH, HydrationImpl.CACHE_ACCESS_GRACE_MS))
                }
                val hydrationIpc: HydrationIpcHandler? =
                    // gkrost/unidrive-cli#24: the name_clash guard's first spellings persist in state.db, so a restart keeps them.
                    hydration?.let { h ->
                        HydrationIpcHandler(h, spellingsStore = db?.let { StateDbNameSpellingStore(it) }).also { hydrationLink.bind(h, it) }
                    }

                // The construction order from here on is explicit: every handler and the poller exist
                // before anything is launched on serveScope, so the launches below (the cache sweep, the
                // event collector, the upload replay, the poller) find what they signal already built.

                // sync.enumerate handler (mount-view-refresh-design.md §4.1): one-way
                // remote→state.db refresh for mount view consumers. Constructed before the
                // hydration verbs so the subscribe-triggered enumerate (below) can reuse its
                // shared in-flight guard.
                val enumerateHandler = EnumerateRpcHandler(OffLoopEnumeration(mount), serveScope, server::emit, paused = { pause.isPaused })

                // refresh.run handler (spec §4.2). Pass serveScope so refresh
                // jobs are cancelled when the daemon shuts down. Pass db so
                // the F9 `reset` parameter can clear it (keeping the rows that
                // still await upload) before re-enumeration.
                // archive#603 (U4): a mount profile's refresh.run ALWAYS takes the one-way enumerate path —
                // with or without a connected client. The enumerate is remote→state.db only: a reset
                // clears the delta cursor (the enumeration state), pending local intent stays, the last
                // good rows survive a failed or incomplete gather, and nothing is published as a
                // deletion before an authoritative completion. A mirror profile keeps the legacy
                // reconcile (its mounted flag stays false; no hydration subscriber exists there).
                val refreshHandler =
                    RefreshRpcHandler(
                        server,
                        engine,
                        db!!,
                        serveScope,
                        mountClientConnected = { profileMode == ProfileMode.MOUNT },
                        // A reset keeps the rows of uploads still under way (same hook the enumeration's reap uses).
                        uploadInFlight = { path -> hydration?.hasUploadSlot(path) ?: false },
                        paused = { pause.isPaused },
                    )

                // The remote poll (archive#463), mount-only; built here, started below (see there). The backoff
                // starts escalated when the previous run ended in enumerate failures (archive#517 R3).
                val poller: EnumeratePoller? =
                    if (mountMode) {
                        EnumeratePoller(
                            enumerateHandler,
                            pollIntervalMs,
                            serveScope,
                            onNextAttempt = mount.enumerationTracker::nextAttemptAt,
                            consecutiveFailuresAtStart =
                                db!!.getSyncState(SyncEngine.ENUMERATE_FAILURE_STREAK_KEY)?.toIntOrNull() ?: 0,
                            isBusy = { refreshHandler.isInFlight() },
                            awaitResumed = { pause.awaitResumed() },
                            afterTick = {
                                // gkrost/unidrive-cli#631: a list younger than its TTL is answered as it is, one past it is
                                // refreshed behind the answer — a view stays fresh even where nothing ever lists it.
                                viewCache?.let { cc -> for (kind in viewNamespaces.kinds()) cc.get(kind) }
                            },
                        )
                    } else {
                        null
                    }

                if (hydration != null && hydrationIpc != null) {
                    // archive#450: what a stopped daemon left in the hydration cache (staging temp files, the
                    // copies of synced files read through the mount) is trimmed to the budget at start.
                    serveScope.launch {
                        // archive#678: blocking walk; off the event loop so it cannot delay a stop.
                        withContext(startupIoDispatcher) {
                            try {
                                hydration.sweepCache()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                log.warn("hydration cache sweep at start failed", e)
                            }
                        }
                    }

                    // archive#463: a download or upload that succeeded is a provider round trip that worked, so it cuts
                    // the poller's back-off short. A read served from the warm cache touched no network and does
                    // not (provesProviderReachable). The poller polls at most once per plain interval anyway.
                    serveScope.launch {
                        hydration.events.collect {
                            hydrationIpc.dispatchEvent(it)
                            if (it.provesProviderReachable()) poller?.providerReachable()
                        }
                    }
                }

                // Reactive remote-change detection: when the FUSE co-daemon issues
                // hydration.subscribe on mount, run ONE guarded enumerate after the reply.
                // The enumerate pulls remote changes into state.db and, when anything changed,
                // the engine's viewInvalidationSink pushes view.invalidated back over this same
                // subscribe stream — so a freshly-mounted view reflects remote renames/deletes
                // without a manual `refresh`, without waiting for the next poll (archive#463). Serialised through
                // the same in-flight guard as sync.enumerate/the poller, so a concurrent
                // enumeration is skipped rather than overlapped.
                // archive#603 (U4): the mount verbs exist only on a mount profile. A mirror daemon answers every
                // hydration verb and sync.enumerate with wrong_mode — the client's command gate refuses
                // before this, but nothing here may serve mount semantics on a mirror profile either.
                // The mount wiring below (handlers, subscribe-hook, upload replay, poller)
                // is mount-only; a mirror daemon keeps sync.subscribe, refresh.run (legacy reconcile)
                // and daemon.status.
                for (verb in HydrationIpcHandler.VERBS) {
                    if (!mountMode) {
                        server.registerHandler(verb) { _, _ -> wrongModeReply }
                        continue
                    }
                    // The hydration layer is built above whenever mountMode holds.
                    val ipc = requireNotNull(hydrationIpc) { "mount mode without a hydration layer" }
                    server.registerHandler(verb) { connId, json ->
                        val reply = ipc.handle(connectionId = connId, jsonRequest = json)
                        // A READ-scope subscribe (an observer such as a status UI) never starts the enumerate:
                        // observing must not scan the remote. archive#657
                        if (verb == "hydration.subscribe" && isOkReplyLine(reply)) {
                            server.scheduleAfterReply(connId) {
                                // gkrost/unidrive-cli#437: the reply line is out — a replayed subscriber may start
                                // streaming, so the reply stays the connection's first line and the events follow it.
                                ipc.onSubscribeReplyWritten(connId)
                                if (subscribeEnumerates(server.authEnabled, server.scopeOf(connId))) {
                                    // Detached, not awaited: the post-reply hook runs on the connection's
                                    // reader coroutine, and the enumeration a fresh mount triggers can run for
                                    // hours — awaiting it here would leave the socket unread for the whole
                                    // pass (EOF unseen, close listeners delayed, the connection still counted
                                    // against the client cap). The in-flight guard still serialises it with
                                    // the sync.enumerate verb and the poller.
                                    serveScope.launch {
                                        try {
                                            enumerateHandler.runGuarded(reset = false)
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            log.warn("enumerate-on-subscribe failed", e)
                                        }
                                    }
                                }
                            }
                        }
                        reply
                    }
                }
                if (!mountMode) {
                    server.registerHandler("sync.enumerate") { _, _ -> wrongModeReply }
                }
                if (mountMode) {
                    server.registerConnectionCloseListener { connId ->
                        hydration?.onConnectionClosed(connId)
                    }
                    // A mount client's connections are never closed for being idle: its open handles and
                    // its event subscription live on them, and refresh.run routing counts them.
                    server.registerIdleExemptVerbs(HydrationIpcHandler.VERBS)
                    hydrationIpc?.start(serveScope, server::writeToConnection)
                    server.registerConnectionCloseListener { connId ->
                        hydrationIpc?.onSubscriberDisconnect(connId)
                    }
                }

                // Engine-side upload recovery: re-enqueue rows written through the
                // mount whose upload never landed (still queued or failed when the
                // daemon last stopped). Runs before any client connects, so a
                // restart alone drains the backlog; the uploads go through the
                // same per-path serialization and daemon-wide transfer budget as
                // client-submitted ones. The co-daemon's recovery-<n> scanner
                // stays as the client-side complement for cache files the row
                // scan cannot see.
                //
                // archive#603 (U4): mount-only. A mirror daemon serves no hydration uploads to replay.
                if (mountMode) {
                    // The hydration layer is built above whenever mountMode holds.
                    val h = requireNotNull(hydration) { "mount mode without a hydration layer" }
                    serveScope.launch {
                        // archive#678: the replay walks every record with blocking file-system work and no
                        // suspension point; on the event loop it starved closeSignal.await(), so a
                        // stop waited for the whole backlog. Off the loop, and with the walk checking
                        // cancellation between records, a stop lands within one record.
                        withContext(startupIoDispatcher) {
                            try {
                                val n = h.replayPendingUploads()
                                if (n > 0) log.info("replayed {} pending upload(s) from state.db", n)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                log.warn("pending-upload replay failed", e)
                            }
                        }
                    }
                }

                // sync.subscribe — the same wiring as SyncCommand's (gkrost/unidrive-cli#442).
                server.registerSyncSubscribeVerb()

                // history.list / history.subscribe (archive#653): read verbs over the transfer history. A mirror daemon, or a mount
                // daemon whose history did not open, answers history_unavailable (never an empty list).
                historyRpc =
                    HistoryRpcHandler(
                        transferHistory,
                        if (mountMode) HistoryRpcHandler.OPEN_FAILED else HistoryRpcHandler.MIRROR,
                    ).also { it.register(server, serveScope) }

                // refresh.run verb (spec §4.2); the handler is constructed above.
                server.registerHandler("refresh.run") { connId, json ->
                    refreshHandler.handle(connId, json)
                }

                // share.create verb (§4.13, #615): one share link for one path, from the daemon's live provider. Both
                // modes — it needs no mount. A provider without Capability.Share answers `unsupported` and
                // daemon.status does not list the share.create capability for it (the client's visibility gate).
                val shareCapable = Capability.Share in provider.capabilities()
                val shareHandler = ShareRpcHandler(provider)
                server.registerHandler("share.create") { _, json -> shareHandler.handle(json) }

                // sync.enumerate verb (mount-view-refresh-design.md §4.1), mount-only (archive#603: a mirror
                // daemon registered its wrong_mode refusal above). The handler is constructed above
                // (shared with the subscribe-triggered enumerate); here we expose it as an explicit verb
                // whose terminal events fan out to sync.subscribe listeners via server.emit.
                if (mountMode) {
                    server.registerHandler("sync.enumerate") { connId, json ->
                        enumerateHandler.handle(connId, json)
                    }
                }

                // Remote-change discovery (mount-view-refresh-design.md §5, archive#463), on by default in
                // `daemon run`: the notification channel is not reliable, and a mount profile has no
                // other way to learn what changed in the cloud. The enumerate path is cheap on a
                // no-change incremental delta. The first poll runs at start (catch-up after a stop or
                // an outage). Shares the sync.enumerate in-flight guard and skips while a refresh runs,
                // so it never overlaps either. Launched on serveScope so it cancels with the daemon at
                // shutdown. The backoff starts escalated when the previous run ended in enumerate
                // failures (archive#517 R3): a restart into a known-bad remote doesn't re-run the doomed
                // cycle at full cadence. Mount-only (archive#603): a mirror profile's discovery is `sync`'s
                // reconcile, not the mount-view poll (the poller is constructed above, null on a mirror).
                poller?.start()
                // gkrost/unidrive-cli#631: a view's list is fetched only when a caller asks (the cache runs nothing on a
                // timer), and a mount that serves its directories from the filesystem never asks — the view stayed empty
                // for ever. The first fill runs here, once, without waiting for anyone, through the directory so the join
                // and the counts a status reports come with it; the poller's afterTick keeps the lists fresh from then on.
                // list() answers a failed fill at once and the next tick retries, so a provider that is slow to
                // authenticate costs nothing here but a logged warning.
                val viewDirectory = mount.views
                if (viewDirectory != null) {
                    serveScope.launch {
                        for (kind in viewNamespaces.kinds()) viewDirectory.list(kind)
                    }
                }
                // archive#111: a provider with a push channel (Internxt's notifications, Dropbox's longpoll) wakes the poll when
                // something changed in the cloud. Mount-only and only while the poll is on; a mirror daemon registers nothing.
                if (poller != null && pollIntervalMs > 0) wakePollerOnRemoteChangeHint(provider, poller)
                // gkrost/unidrive-cli#202: the rows from before the content-id column learn their ids, once per profile, in the
                // background (a provider that names no content returns at once). Mount-only: only the mount's lists carry the id.
                if (mountMode) {
                    serveScope.launch {
                        withContext(startupIoDispatcher) {
                            try {
                                mount.backfillContentIds()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                log.warn("content-id backfill failed", e)
                            }
                        }
                    }
                }

                // archive#891: one health line per interval (heap, GC, threads, enumeration, upload queue) so the
                // memory a long scan needs is visible while it happens. Cancelled with the serve scope.
                if (healthIntervalMs > 0) {
                    DaemonHealthLine(
                        intervalMs = healthIntervalMs,
                        scope = serveScope,
                        enumeration = {
                            mount.enumerationTracker.snapshot().let { it.state.name.lowercase() to it.items }
                        },
                        uploadQueueDepth = { hydration?.uploadHealthSnapshot()?.pending },
                    ).start()
                }

                // credential_health (archive#586): checked off the request path, so the first status can already carry an answer.
                val credentialHealth = CredentialHealthMonitor(credentialHealthCheck, credentialHealthTtlMs, serveScope)
                credentialHealth.refreshAsync()

                // mode + capabilities (archive#603 U4): what this daemon serves, so a client can check the
                // hosting contract before it sends a verb the profile refuses (additive, read-only). Fixed for the run, so
                // rendered once here, not at every status request (gkrost/unidrive-cli#442).
                val modeJson = ndjsonString(profileMode.wireName)
                val capabilitiesJson = daemonCapabilitiesJson(profileMode, hydration != null, engine.uploadIgnore != null, viewNamespaces, shareCapable)

                // daemon.status verb (spec §4.3). protocol_version is the
                // additive cross-repo handshake field (IPC_PROTOCOL_VERSION):
                // co-clients warn/refuse on mismatch instead of failing on a
                // missing field mid-operation. provider/authenticated are the
                // account/auth state a status UI needs; the SPI carries no account
                // identity, so the provider type + name is what we can report
                // truthfully, and `authenticated` only says that credentials are
                // loaded (not that they are valid). enumeration is the progress of
                // the remote enumeration (additive, object always present). Before a
                // connection authenticates, IpcAuth answers daemon.status itself with
                // the minimal reply; this handler serves authenticated connections.
                server.registerHandler("daemon.status") { _, _ ->
                    val uptimeMs = System.currentTimeMillis() - startedAtMs
                    val clientCount = server.clientCount
                    val refreshInFlight = refreshHandler.isInFlight()
                    val refreshJobId = refreshHandler.inFlightJobId()
                    val jobIdJson = if (refreshJobId != null) "\"$refreshJobId\"" else "null"
                    // sync_paths: the effective scope (sync_path entries the daemon was started
                    // with; empty = whole drive). Additive field, read-only over IPC.
                    val syncPathsJson = syncPaths.joinToString(",", "[", "]") { ndjsonString(it) }
                    val providerJson = ndjsonString(provider.id)
                    val providerNameJson = ndjsonString(provider.displayName)
                    val enumerationStatus = mount.enumerationStatus()
                    val enumerationJson = enumerationStatus.toJson().toString()
                    val engineVersionJson = ndjsonString(BuildInfo.versionString())
                    // engine_build_time_ms: when the build was made (epoch ms UTC; the commit time of a reproducible build); null when unknown.
                    val engineBuildTimeJson = BuildInfoReport.buildTimeMs()?.toString() ?: "null"
                    // uploads / cache / provider_health (archive#658): the engine's own account of whether everything
                    // is uploaded, how much the hydration cache holds and when the provider last answered.
                    // uploads and cache exist only where a hydration layer does (a mount profile): a mirror
                    // daemon leaves them out, and so does an upload queue that has never been read, because a
                    // front-end must never read an unknown as "nothing pending". Neither read blocks this reply
                    // behind state.db or the cache directory: the previous reading is served (uploadHealthSnapshot),
                    // and the cache walk runs off this request (bytes null until the first).
                    hydration?.requestCacheMeasurement()
                    val uploadsJson = hydration?.uploadHealthSnapshot()?.toJson()?.toString()
                    val cacheJson = hydration?.cacheHealth()?.toJson()?.toString()
                    val lastContactMs = lastProviderContactMs(enumerationStatus.lastSuccessAtMs, mount.lastProviderContactAtMs)
                    // strict_names (issue 29): present only for a provider with a strict-name mode; the count reads the rows
                    // (the same ones `uploads.blocked` lists), so a busy state.db can hold the reply briefly, like any reader.
                    // skipped (gkrost/unidrive-cli#405): the listing entries the provider leaves out — OneDrive's
                    // Personal Vault stub and its package entries. Present only while the run has skipped some.
                    val skippedJson =
                        (cloud as? org.krost.unidrive.EntrySkipReporting)?.skippedEntries()?.let { skipped ->
                            if (skipped.vault == 0L && skipped.packageEntries == 0L) null
                            else skippedEntriesJson(skipped.vault, skipped.packageEntries)
                        }
                    val providerHealth =
                        providerHealthJson(
                            lastContactMs,
                            provider.strictNameMode?.let { on -> strictNamesStatusJson(on, mount.strictNameBlockedCount()) },
                            skippedJson,
                        )
                    // requests (gkrost/unidrive-cli#74): the provider's own account of its HTTP calls, so a stalled
                    // transfer can be diagnosed without reading the log: per endpoint class how many calls are in
                    // flight and how old the oldest is, and per throttle budget the free slots. Present only for a
                    // provider whose HTTP layer can account for its calls; absent otherwise, like quota.
                    val requestsJson =
                        (cloud as? org.krost.unidrive.RequestStatusReporting)?.requestStatus()?.let { status ->
                            val inFlightJson =
                                status.inFlight.joinToString(",", "[", "]") { open ->
                                    """{"class":${ndjsonString(open.endpointClass)},"count":${open.count},"oldest_ms":${open.oldestMs}}"""
                                }
                            val slotsJson =
                                status.freeSlots.entries.sortedBy { it.key }.joinToString(",") { "${ndjsonString(it.key)}:${it.value}" }
                            // total (gkrost/unidrive-cli#460): attempts per endpoint class since the daemon started.
                            val totalJson =
                                status.totals.entries.joinToString(",") { "${ndjsonString(it.key)}:${it.value}" }
                            """{"in_flight":$inFlightJson,"free_slots":{$slotsJson},"total":{$totalJson}}"""
                        }
                    // credential_health (archive#586): the last offline check, or `expired` at once when the provider rejected the
                    // credentials after the check (a transfer or an enumeration failed authentication and nothing worked since).
                    val credentialReading =
                        credentialHealth.reading()
                            .withRejection(rejectionAfterSignIn(mount.authRejectedAtMs, signedInAtMs), lastContactMs)
                    val credentialHealthJson = credentialReading.toJson(System.currentTimeMillis())
                    // auth (unidrive-cli#387): the same reading as a sign-in question, with the profile folder the check looked in.
                    val authJson = authStatusOf(credentialReading.state, lockFile.parent).toJson().toString()
                    // quota (archive#655): the account's storage plan snapshot. Absent entirely for
                    // providers without an account quota (ProviderMetadata hasQuota=false —
                    // localfs probes machine storage). A client-connected status request is
                    // also the refresh trigger: a fetch runs single-flight when the TTL has
                    // expired; no client asking means no provider call. Failure keeps the
                    // last value with stale=true and the error named; unknowns are null —
                    // never zero, never a division by a missing total.
                    if (providerHasQuota) quotaMonitor.maybeTriggerQuotaRefresh(clientCount, serveScope, paused = pause.isPaused)
                    val quotaJson =
                        if (!providerHasQuota) {
                            null
                        } else {
                            val snap = quotaMonitor.quotaSnapshot ?: QuotaSnapshot()
                            val errorJson = snap.error?.let { ndjsonString(it) } ?: "null"
                            """{"used_bytes":${snap.usedBytes ?: "null"},""" +
                                """"total_bytes":${snap.totalBytes ?: "null"},""" +
                                """"fetched_at_ms":${snap.fetchedAtMs ?: "null"},"stale":${snap.stale},"error":$errorJson}"""
                        }
                    // poll_interval_ms (archive#463): the effective interval of the remote poll (0 = off). The
                    // next attempt after a failure is enumeration.next_attempt_at_ms (additive, read-only).
                    // engine_version (archive#554): the build a co-client is talking to — behaviour fixes do
                    // not move IPC_PROTOCOL_VERSION, so this is the age signal a client gates its
                    // engine minimum on (additive, read-only). archive#574: a dev build carries the commit id
                    // as semver build metadata (VERSION+COMMIT[.dirty]), so the release part orders.
                    // gkrost/unidrive-cli#442: the members in the order the IPC contract corpus pins; JsonMembers writes the
                    // separators, and a null value leaves its member out.
                    JsonMembers()
                        .put("ok", "true")
                        .put("protocol_version", IPC_PROTOCOL_VERSION.toString())
                        .put("contract_level", IPC_CONTRACT_LEVEL.toString())
                        .put("engine_version", engineVersionJson)
                        .put("engine_build_time_ms", engineBuildTimeJson)
                        .put("mode", modeJson)
                        .put("capabilities", capabilitiesJson)
                        .put("uptime_ms", uptimeMs.toString())
                        .put("clients_connected", clientCount.toString())
                        .put("refresh_in_flight", refreshInFlight.toString())
                        .put("refresh_job_id", jobIdJson)
                        .put("sync_paths", syncPathsJson)
                        .put("provider", providerJson)
                        .put("provider_name", providerNameJson)
                        .put("authenticated", provider.isAuthenticated.toString())
                        .put("enumeration", enumerationJson)
                        .put("poll_interval_ms", pollIntervalMs.toString())
                        .put("quota", quotaJson)
                        // uploads and cache: a mount profile's hydration layer only; uploads also only once the queue was read.
                        .put("uploads", uploadsJson)
                        .put("cache", cacheJson)
                        .put("provider_health", providerHealth.toString())
                        .put("requests", requestsJson)
                        .put("credential_health", credentialHealthJson.toString())
                        // state_recovery (gkrost/unidrive-cli#581): present only when this start found state.db corrupt and moved it aside.
                        .put("state_recovery", stateRecovery?.let { stateRecoveryJson(it).toString() })
                        .put("auth", authJson)
                        // notifications (archive#111): the provider's remote-change push channel; `unsupported` where it has none.
                        .put("notifications", notificationsStatusJson(provider.remoteChangeChannelStatus()).toString())
                        // ignore (archive#660): present where the ignore rules apply; held_back counts the folders
                        // whose uploads wait for a rule file that cannot be read.
                        .put("ignore", engine.uploadIgnore?.let { ignoreHealthJson(it.heldBack()).toString() })
                        // trash: present only while something is in the provider's trash. The read-only folder that shows it,
                        // by the path it has now, the name it has on disk (suffixed when a live top-level item holds the
                        // configured name) and the name the profile asks for (trash_folder_name).
                        .put("trash", trash.rootPath()?.let { trashStatusJson(it, trash.configuredName).toString() })
                        // backups: present only while the backups folder exists (the profile shows them and the account has a
                        // backup device), as trash, with the number of devices listed in it.
                        .put(
                            "backups",
                            backups.rootPath()?.let { root ->
                                val devices = db?.listDirectChildren(root)?.count { it.isFolder } ?: 0
                                backupsStatusJson(root, backups.configuredName, devices).toString()
                            },
                        )
                        // views: present only while the profile shows a read-only view (Recents, ...), one object per view; the
                        // state of each is the last answer the provider gave (nothing here asks it).
                        .put("views", hydration?.viewStatuses()?.takeIf { it.isNotEmpty() }?.let { viewsStatusJson(it).toString() })
                        // remote_incomplete: present only while a truncated cloud object is remembered (count only, no names).
                        .put("remote_incomplete", mount.remoteIncompleteCount().takeIf { it > 0 }?.let { remoteIncompleteJson(it).toString() })
                        // paused (archive#778): present only while the daemon is paused; uploads and quota above keep reporting.
                        .put("paused", pause.current?.toJson()?.toString())
                        // archive#578: the effective part parallelism of the provider's multipart uploads
                        // (null when it does not upload in parts). Additive, read-only.
                        .put("upload_concurrency", provider.multipartUploadConcurrency()?.let { kotlinx.serialization.json.JsonPrimitive(it).toString() } ?: "null")
                        // gkrost/unidrive-cli#392: how many hydration downloads a client should run at once (the download
                        // budget's small-file width, the transfer cap for a while after a throttle). Additive, read-only.
                        .put("transfer_budget", mount.downloadBudget.recommended.toString())
                        // ipc (gkrost/unidrive-cli#460): the requests each verb's handler served since the daemon started, so a
                        // client's change of verbs can be checked on a live daemon. Additive, read-only, always present.
                        .put("ipc", ipcStatusJson(server.requestCounts()).toString())
                        .render()
                }

                // ignore.explain (archive#660): a read verb; the daemon's own rules answer it, so it says what the upload path
                // decides (the mount's, or the mirror's scan and plan).
                val ignoreHandler = engine.uploadIgnore?.let { IgnoreRpcHandler(it, mount::matchingExcludePattern) }
                server.registerHandler("ignore.explain") { _, json -> ignoreHandler?.explain(json) ?: wrongModeReply }

                // daemon.pause / daemon.resume (archive#778): the daemon holds its pause in memory only. A pause stops every
                // upload, cloud download and cloud enumeration from starting (local writes stay queued, reported as
                // waiting); a transfer already running is judged by the in-flight rule. The change goes to the
                // subscribers of both streams: sync.subscribe (every mode) and hydration.subscribe (mount).
                // A pause with until_ms resumes by itself on serveScope (the timer dies with the daemon, like the pause).
                val pauseHandler =
                    PauseRpcHandler(pause, serveScope) { event ->
                        runCatching { server.emit(serialiseHydrationEvent(event)) }
                        hydrationIpc?.dispatchEvent(event)
                    }
                server.registerHandler("daemon.pause") { _, json -> pauseHandler.pause(json) }
                server.registerHandler("daemon.resume") { _, _ -> pauseHandler.resume() }

                // auth.logout (§4.10): sign the account out while the daemon keeps running — the daemon is the
                // profile-lock holder, so the credential removal is its job (the CLI hands it over over IPC).
                // It refuses while uploads are pending (without credentials they cannot finish) and never
                // touches state.db. Both modes: a mirror daemon is signed out like a mount one.
                // One lock for both sign-out and sign-in: the IPC handlers run in parallel, and the sign-out's
                // forget + credential-file deletion must not run through a sign-in that stored credentials and
                // reloaded them (that would leave credentials in memory with no file on disk, both saying success).
                val authOneAtATime = Mutex()
                val logoutHandler =
                    AuthLogoutRpcHandler(
                        profileDir = ipcTokenDir,
                        db = db!!,
                        providerFactory = providerFactory,
                        // archive#1186: not the isAuthenticated setter, which does nothing on OneDrive, Internxt and Dropbox.
                        forgetCredentials = { provider.forgetCredentials() },
                        // #86: the refusal counts the rows `uploads.pending` counts, not the ones the keep-local rules hold back.
                        pendingRows =
                            hydration?.let { h -> { h.pendingUploadSplit() } }
                                // A mirror daemon: the exclude patterns and the standing scope keep rows local, as in the gate of a stopped profile.
                                ?: PendingGate.keepsLocalRule(excludePatterns, syncPaths).let { keepsLocal ->
                                    { PendingUploads.split(db!!.pendingUploadRows(), keepsLocal, null) }
                                },
                        oneAtATime = authOneAtATime,
                    )
                server.registerHandler(AuthLogoutRpcHandler.VERB) { _, json -> logoutHandler.handle(json) }

                // auth.begin / auth.complete / auth.login (§4.11, archive#813): sign the account in again while the daemon keeps
                // running. The daemon runs the CLI's own sign-in step, which stores the credentials once the provider accepted
                // them; then its own provider instance reloads them in place (no restart, the mount stays), the credential
                // check runs again and an authentication rejection from before the sign-in no longer reads as `expired`.
                val signInHandler =
                    AuthLoginRpcHandler(
                        signInTarget,
                        onSignedIn = {
                            provider.reloadCredentials()
                            signedInAtMs = System.currentTimeMillis()
                            credentialHealth.refreshNow()
                        },
                        oneAtATime = authOneAtATime,
                    )
                server.registerHandler(AuthLoginRpcHandler.BEGIN) { _, json -> signInHandler.begin(json) }
                server.registerHandler(AuthLoginRpcHandler.COMPLETE) { _, json -> signInHandler.complete(json) }
                server.registerHandler(AuthLoginRpcHandler.LOGIN) { _, json -> signInHandler.login(json) }

                // auth.check (§4.12, archive#1240): the credential check goes through the daemon's own provider instance, the one that
                // holds and refreshes the token, so a CLI check never races that refresh.
                val checkHandler = AuthCheckRpcHandler(profileName, provider)
                server.registerHandler(AuthCheckRpcHandler.VERB) { _, json -> checkHandler.check(json) }

                // metrics.query (archive#654): the per-minute roll-ups of the provider's request attempts. A mount profile with an open
                // history records them; a mirror daemon (no history) answers "not recording", never zeros.
                val requestMetrics = transferHistory?.requestMetrics()
                // gkrost/unidrive-cli#392: the same attempts tell the download budget about the provider's throttles (a 429 or a
                // Retry-After), so the small-file width narrows to the transfer cap until the provider has been quiet a while.
                // Attached on every daemon, recording or not: the budget needs no history.
                provider.attachRequestMetrics(throttleObservingSink(requestMetrics, mount.downloadBudget::noteThrottle))
                val metricsHandler = MetricsRpcHandler(requestMetrics)
                server.registerHandler("metrics.query") { _, json -> metricsHandler.query(json) }

                // daemon.shutdown verb: graceful stop over IPC, signal-free and identical on every
                // OS (Windows has no SIGTERM; Process.destroy() there is TerminateProcess, which
                // skips the shutdown path entirely). Acks first, then runs the same path as a
                // clean exit: the serve scope is cancelled, then cleanup() closes the IPC server,
                // the state database and the process lock. The ack means "accepted"; completion
                // is observable as the connection closing and, finally, the process exiting.
                server.registerHandler("daemon.shutdown") { connId, _ ->
                    server.scheduleAfterReply(connId) { close() }
                    """{"ok":true}"""
                }

                // Bind LAST, after every handler above is registered. The socket
                // file appearing is what clients poll for before sending their
                // first verb — starting the server any earlier opens a window in
                // which a request is accepted and then silently dropped ("no
                // handler"), hanging the client that sent it. With the bind here,
                // a connect that succeeds is guaranteed a reply for every
                // documented verb; authentication, engine construction and handler
                // registration all complete before the socket exists.
                server.start(serveScope)
                startedAtMs = System.currentTimeMillis()

                System.err.println(
                    "daemon ready, pid ${ProcessHandle.current().pid()}, socket $socketPath",
                )

                closeSignal.await()
                log.info("daemon: shutting down")
            } finally {
                // Stop accepting hydration verbs and cancel in-flight request handlers first. Upload
                // workers have their own scope, so join them explicitly before cleanup closes state.db.
                server.close()
                ipcServer = null
                serveJob.cancelAndJoin()
                try {
                    hydrationLink.hydration?.shutdownUploads(uploadDrainMs)
                } finally {
                    // archive#652: after the uploads are joined (their last rows are queued by then), before state.db closes.
                    historyRpc?.close()
                    closeTransferHistory(transferHistory)
                }
            }
        } catch (e: StateDbCorruptException) {
            // The command prints the one line (stderr is also where WARN and up are logged, so no WARN here); the
            // trace stays in the log at debug (gkrost/unidrive-cli#581).
            log.info("daemon: state.db at {} is corrupt; stopping", e.dbPath)
            log.debug("daemon: state.db corrupt", e)
            throw e
        } catch (e: IpcAuth.StartupRefused) {
            // An IPC startup refusal is already reported as its one line.
            throw e
        } catch (e: Exception) {
            log.error("daemon: lifecycle error", e)
            throw e
        } finally {
            cleanup()
            cleanupDone.countDown()
        }
    }

    fun close() {
        closeSignal.complete(Unit)
    }

    /**
     * Request shutdown and block until [start]'s cleanup (IpcServer close, StateDatabase close,
     * lock release) has finished, bounded by [timeoutMs] (spec I7). For the JVM shutdown hook:
     * the JVM halts as soon as every hook returns, so a hook that only called [close] raced the
     * main thread's cleanup and could leave a stale `.lock.pid` and socket behind.
     *
     * Returns true when cleanup completed (or there was nothing to clean up), false on timeout.
     */
    fun shutdownAndWait(timeoutMs: Long = SHUTDOWN_DEADLINE_MS): Boolean {
        close()
        if (!lifecycleActive) return true
        return try {
            cleanupDone.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun renderLockContentionAndExit(lock: ProcessLock) {
        val lines = lockContentionLines(lock.readLiveHolderInfo(), profileName, ProcessLock.Mode.DAEMON, Os.isWindows())
        for (line in lines) System.err.println(line)
        exitProcess(1)
    }

    /**
     * archive#652: opens this mount profile's transfer history, or returns null after one WARN line: the history is never a
     * reason to refuse to serve, so every failure to open it (a file that cannot be restricted to the owner, a damaged
     * or newer database, a full disk) leaves the daemon running without one.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun openTransferHistory(): TransferHistory? =
        try {
            openHistory(profileName, dbPath.resolveSibling(HISTORY_FILE_NAME))
        } catch (e: Exception) {
            log.warn("transfer history unavailable for profile '{}' ({}: {}); the daemon runs without it", profileName, e.javaClass.simpleName, e.message)
            null
        }

    // archive#111: the push channel is best effort; a channel that cannot start leaves the daemon on its poll alone.
    // The registration suspends (a provider may refresh a token over HTTP to open its channel) instead of
    // blocking the daemon's event loop, which start() runs on.
    private suspend fun wakePollerOnRemoteChangeHint(
        provider: CloudProvider,
        poller: EnumeratePoller,
    ) {
        try {
            provider.onRemoteChangeHint { poller.wake() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("remote change hints are off: the provider's channel did not start ({}); the poll stays", e.javaClass.simpleName)
        }
    }

    // Whether the backups folder is shown: only on a mount, and only where the provider declares the backups; one INFO line
    // when the profile asks for it and the provider has none.
    private fun providerShowsBackups(cloud: CloudProvider): Boolean {
        if (profileMode != ProfileMode.MOUNT) return false
        if (Capability.Backups in cloud.capabilities()) return true
        log.info("The backups folder is not shown: the provider '{}' has no backups", cloud.id)
        return false
    }

    // The views the profile asks for that the provider declares; each one it does not is left out with one INFO line. A provider
    // that declares a kind and answers Unsupported at runtime (a server without the route) keeps it: that view is shown unavailable.
    private fun viewsTheProviderHas(cloud: CloudProvider): List<ViewSpec> {
        if (views.isEmpty()) return views
        val declared = cloud.capabilities()
        val (kept, dropped) = views.partition { Capability.View(it.kind) in declared }
        for (spec in dropped) {
            log.info("The {} view is not shown: the provider '{}' has no such view", ViewNamespaces.wireName(spec.kind), cloud.id)
        }
        return kept
    }

    /** archive#652: writes what is queued and closes the history file. Bounded by the history's own close; never throws. */
    @Suppress("TooGenericExceptionCaught")
    private fun closeTransferHistory(history: TransferHistory?) {
        try {
            history?.close()
        } catch (e: Exception) {
            log.warn("closing the transfer history of profile '{}' failed: {}", profileName, e.message)
        }
    }

    private fun cleanup() {
        runCatching { ipcServer?.close() }
        ipcServer = null
        // gkrost/unidrive-cli#455: the provider's notification channel and HTTP client end with the daemon, not with the JVM:
        // an in-process restart (and every test) would otherwise keep them open.
        servingProvider?.let { provider -> runCatching { provider.close() }.onFailure { log.debug("closing the provider failed", it) } }
        servingProvider = null
        runCatching { quotaMonitor.retryQuotaPersist(db) }
        runCatching { db?.close() }
        db = null
        runCatching { lock?.unlock() }
        lock = null
    }

    /**
     * The one real cycle in the daemon's wiring, made explicit: the [SyncEngine] is built first and the
     * hydration layer over it ([MountEngine.over]), but two of the engine's hooks call into that layer
     * (whether an upload of a path is queued or in flight, and the view.invalidated fan-out). The engine
     * takes them from this link, which is bound once, right after the hydration layer is built.
     *
     * Unbound (before that point, and always on a mirror daemon, which builds no hydration layer): no
     * upload slot is held, and a view invalidation has no subscriber to reach, so it is dropped. No IPC
     * request sees the unbound state: the socket binds after every handler is registered.
     */
    internal class HydrationLink {
        private class Bound(val hydration: HydrationImpl, val ipc: HydrationIpcHandler)

        @Volatile private var bound: Bound? = null

        /** The bound hydration layer (the one the shutdown joins the uploads of), or null. */
        val hydration: HydrationImpl? get() = bound?.hydration

        fun bind(
            hydration: HydrationImpl,
            ipc: HydrationIpcHandler,
        ) {
            check(bound == null) { "the hydration link is bound once" }
            bound = Bound(hydration, ipc)
        }

        fun hasUploadSlot(path: String): Boolean = bound?.hydration?.hasUploadSlot(path) ?: false

        fun dispatchEvent(event: HydrationEvent) {
            bound?.ipc?.dispatchEvent(event)
        }
    }

    companion object {
        /** gkrost/unidrive-cli#72: the cleanup headroom of a stop (spec I7), on top of the upload drain. */
        const val SHUTDOWN_CLEANUP_MS: Long = 10_000

        /** Spec I7: the whole stop, the upload drain (gkrost/unidrive-cli#72) plus the cleanup headroom it had before. */
        const val SHUTDOWN_DEADLINE_MS: Long = HydrationImpl.UPLOAD_DRAIN_MS + SHUTDOWN_CLEANUP_MS

        /** archive#652: the transfer history's file, in the profile's state folder next to state.db. */
        const val HISTORY_FILE_NAME = "telemetry.db"

        /**
         * The log context of everything a daemon runs: the `profile` and `build` MDC keys the log pattern prints, kept across
         * suspensions and thread hops by [MDCContext]. Two daemons write one unidrive.log; a line without them reads `[*]` and
         * cannot be told apart (gkrost/unidrive-cli#225). A child coroutine inherits it; a thread the daemon's code starts
         * itself carries it with [org.krost.unidrive.MdcSnapshot].
         */
        /** The constants that bound a mount daemon's transfers, for one INFO line at start (gkrost/unidrive-cli#72). Numbers only. */
        internal fun hydrationLimitsLine(
            transfers: Int,
            uploadQueueDepth: Int,
            cacheAccessGraceMs: Long,
        ): String = "hydration limits: $transfers concurrent transfers, upload queue depth $uploadQueueDepth, cache access grace ${cacheAccessGraceMs / 1000} s"

        internal fun logContext(profileName: String): MDCContext = MDCContext(mapOf("build" to BuildInfo.COMMIT, "profile" to profileName))

        /**
         * The serve scope: the daemon's launches (enumerations, event writers, pollers) run on [kotlinx.coroutines.Dispatchers.Default],
         * not on the event loop of the `runBlocking` that calls [start]. That loop is one thread, which the shutdown's
         * `closeSignal.await()` also needs: a blocking span on it would stall every writer and the stop. The IPC handlers
         * already run on their own pool, so no state here relied on that single thread. Blocking JDBC and file work still
         * goes through `withContext(Dispatchers.IO)` at its call site, so it does not hold a Default thread.
         */
        internal fun serveScopeOf(
            parent: kotlin.coroutines.CoroutineContext,
            job: kotlinx.coroutines.Job,
        ): kotlinx.coroutines.CoroutineScope =
            kotlinx.coroutines.CoroutineScope(parent + job + kotlinx.coroutines.Dispatchers.Default)

        /**
         * The scope the mount's hydration uploads and replays run in: a scope of its own (the shutdown joins it apart from
         * the serve scope), so it carries the daemon's [logContext] itself (archive#150, gkrost/unidrive-cli#225).
         */
        internal fun hydrationUploadScope(profileName: String): kotlinx.coroutines.CoroutineScope =
            kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() +
                    kotlinx.coroutines.Dispatchers.IO +
                    logContext(profileName),
            )

        // Cross-repo IPC wire-protocol version, surfaced as the additive
        // protocol_version field in the daemon.status reply so co-clients
        // (Rust FUSE crate, C# CfAPI client) can warn/refuse on mismatch
        // instead of failing on a missing field mid-operation. Bump ONLY on
        // a breaking wire change; additive fields do not bump it. The golden
        // corpus under src/test/resources/ipc-contract/ pins the current
        // shape (IpcContractCorpusTest). Version 2 authenticates every
        // connection (IpcAuth, docs/dev/specs/ipc-authentication.md).
        const val IPC_PROTOCOL_VERSION: Int = IpcAuth.PROTOCOL_VERSION

        // contract_level (gkrost/unidrive-cli#445): an integer raised by one with every additive IPC change (a new
        // verb, capability or field), so a client orders engine builds by what they answer; protocol_version moves only
        // on a breaking change and engine_version does not order dev builds. IpcContractLevelTest pins the IPC surface
        // of each level and fails when the surface changes without a raise.
        const val IPC_CONTRACT_LEVEL: Int = 3

        /**
         * Whether a successful `hydration.subscribe` is followed by the one guarded enumerate: only for a
         * full-scope connection, or when the server does not authenticate at all (the protocol-1 fallback,
         * until the handshake is mandatory everywhere). With authentication on, anything but `full`, an
         * unknown scope included, does not enumerate.
         */
        internal fun subscribeEnumerates(
            authEnabled: Boolean,
            scope: String?,
        ): Boolean = !authEnabled || scope == IpcAuth.Scope.FULL.wire
    }
}

/** The `state_recovery` object of `daemon.status` (gkrost/unidrive-cli#581): additive, present only after a corrupt state.db was moved aside. */
internal fun stateRecoveryJson(r: StateDbRecovery): String =
    """{"reason":"corrupt","moved_to":${ndjsonString(r.movedTo.toString())},"at_ms":${r.atMs},"local_changes_lost":true,"message":${ndjsonString(r.message)}}"""
