package org.krost.unidrive.hydration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.krost.unidrive.io.ndjsonString
import org.krost.unidrive.sync.PathNormalizer
import java.nio.file.InvalidPathException
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Translates IpcServer JSON-line requests to Hydration verb calls
 * and back. Registers six verbs on the IpcServer; the per-connection
 * id used in the Hydration API is the IpcServer's per-client opaque
 * connection identifier (passed by the caller of handle()).
 *
 * Wire format (all JSON):
 *   Paths:      every logical path field (path, old_path, new_path, prefix) is validated before
 *               the verb runs: a `.` or `..` segment, a control character (NUL included), an
 *               empty segment other than that of a leading or trailing slash, or a segment
 *               longer than 255 UTF-16 units answers {"ok":false,"error":"invalid_path"}.
 *               mkdir, create and rename also answer invalid_path for a new name the host's file
 *               system cannot hold, and every verb for a path whose cache file would not lie inside
 *               the profile's hydration cache folder.
 *   open_read   request:  {"verb":"hydration.open_read","handle_id":"...","path":"/foo"}
 *   open_read   reply ok: {"ok":true,"cache_path":"/home/.../foo.txt"[,"content_id":"..."]}
 *                         content_id (gkrost/unidrive-cli#171): the name of the bytes served, as hydration.list hands it out, where
 *                         they are the version the row records (no unsynced edit, not a kept-local copy); absent otherwise.
 *   open_read   reply err:{"ok":false,"error":"<message>"}
 *                         an excluded cloud item answers with the `excluded` token without a download,
 *                         even if a warm cache copy exists.
 *   open_write  request:  {"verb":"hydration.open_write"[,"handle_id":"..."],"path":"/foo","cache_path":"/home/.../foo.txt"[,"base_etag":"..."]}
 *               handle_id absent: the daemon issues the id and the reply adds ,"handle_id":"dh-<run>-<n>".
 *               A retry of that open on the same connection, for the same path, still without handle_id, while that
 *               reply has not been returned, is the same open: the same id, no second registration.
 *               A client-named id that starts with dh- answers {"ok":false,"error":"invalid_handle_id"} (every verb that opens a handle).
 *   open_write  reply:    same as open_read; base_etag (OPTIONAL) is the etag the client
 *                         observed at list/open time — a mismatch refuses the write with
 *                         {"ok":false,"error":"conflict"} before any upload starts.
 *                         An excluded path (exclude_patterns match) answers
 *                         {"ok":true,"cache_path":"...","excluded":true} and emits a
 *                         `skipped` event instead of running an upload.
 *                         cache_path must lie inside the profile's hydration cache
 *                         folder (the path create / open_write_begin / open_read hand
 *                         out, in any spelling); otherwise {"ok":false,"error":"invalid_path"},
 *                         before anything is changed or queued.
 *   close_handle request: {"verb":"hydration.close_handle","handle_id":"..."}
 *   close_handle reply:   {"ok":true}
 *   hydrate     request:  {"verb":"hydration.hydrate","path":"/foo"}
 *   hydrate     reply:    {"ok":true} or {"ok":false,"error":"<message>"}
 *   While the daemon is paused (daemon.pause) open_read and hydrate answer {"ok":false,"error":"paused"} for a
 *   file that is not in the cache: nothing is started and nothing waits (a file in the cache is served as usual).
 *   matches_local request: {"verb":"hydration.matches_local","path":"/foo.txt","local_path":"C:\\Users\\me\\foo.txt"}
 *               (archive#1219) Whether the PLAIN file at local_path (an absolute path of this computer; a cloud
 *               placeholder would be recalled by the read) holds exactly the bytes of the cloud copy of path, proven
 *               WITHOUT downloading that copy: the size, the row's content hash (OneDrive quickXor), the SHA-256 the engine
 *               recorded for bytes it wrote or sent itself, or the provider's stored-object proof (Internxt: the hash of the
 *               ciphertext of the local file under the file's own key). Full scope only: it reads a file the caller names.
 *               reply:    {"ok":true,"result":"same","method":"<m>"}      a real comparison found them equal
 *                         {"ok":true,"result":"differs","method":"<m>"}   a real comparison found them different
 *                         {"ok":true,"result":"unknown","reason":"<r>"}   no proof is possible here: NEVER a difference; fall back to
 *                                                                          a download and a byte compare. reasons: unknown_path,
 *                                                                          no_cloud_copy, no_proof, paused, proof_failed
 *                         {"ok":false,"error":"invalid_local_path"}       not an absolute path of this computer
 *                         {"ok":false,"error":"local_file_unreadable"}    not a regular file, or unreadable
 *                         {"ok":false,"error":"missing_local_path"}
 *               m: size, quickxor, sha256, recorded-sha256, internxt-shard-hash, empty. No reply carries a hash value.
 *   dehydrate   request:  {"verb":"hydration.dehydrate","path":"/foo"}
 *   dehydrate   reply:    {"ok":true} or {"ok":false,"error":"busy"} or {"ok":false,"error":"<message>"}
 *   mkdir       request:  {"verb":"hydration.mkdir","path":"/foo"}
 *               reply:    {"ok":true}
 *                         {"ok":false,"error":"parent_not_found"}   ENOENT
 *                         {"ok":false,"error":"outside_scope"}      path outside the profile's sync_path set
 *                         {"ok":false,"error":"<msg>"}              EIO
 *
 *   unlink      request:  {"verb":"hydration.unlink","path":"/foo.txt"}
 *               reply:    {"ok":true}
 *                         {"ok":false,"error":"busy"}               the upload is in flight (archive#87)
 *                         {"ok":false,"error":"path_is_folder"}     EISDIR
 *                         {"ok":false,"error":"<msg>"}              EIO
 *
 *   rmdir       request:  {"verb":"hydration.rmdir","path":"/foo"[,"recursive":true]}
 *               reply:    {"ok":true}
 *                         {"ok":false,"error":"path_is_file"}       ENOTDIR
 *                         {"ok":false,"error":"not_empty"}          ENOTEMPTY: alive rows below (plain), or a file
 *                                                                   below holds bytes only this computer has (recursive, archive#1035)
 *                         {"ok":false,"error":"busy"}               an upload below is in flight (archive#87)
 *                         {"ok":false,"error":"<msg>"}              EIO
 *
 *   name_clash  (gkrost/unidrive-cli#24) create, mkdir, open_write, open_write_begin, rename, unlink and rmdir answer
 *               {"ok":false,"error":"name_clash"} when the path names a name, or a folder above it, by another Unicode
 *               spelling (composed / decomposed) than the one a client used for it first in this daemon run: both are one
 *               row and one cloud path, so the second would overwrite or delete the first. Rename one of them.
 *
 *   refuse_name (gkrost/unidrive-cli#24) request: {"verb":"hydration.refuse_name","path":"/café.txt"[,"sibling":"café.txt"]}
 *               the client does not upload this file: another file of its folder (sibling, its name as spelled on disk) is the
 *               same name after NFC. Kept as a row blocked by name (blocked:"name_collides_after_normalisation" in list,
 *               uploads.blocked in daemon.status); never pending, never replayed. Idempotent.
 *               reply:    {"ok":true}
 *                         {"ok":false,"error":"path_exists"}        the cloud has the name: its row is not marked
 *                         {"ok":false,"error":"path_is_folder"}     only files are refused this way
 *                         {"ok":false,"error":"parent_not_found"}   no folder row above it
 *                         {"ok":false,"error":"read_only"|"outside_scope"|"invalid_path"}
 *               request:  {"verb":"hydration.refuse_name","path":"/café.txt","clear":true}  the clash is over
 *               reply:    {"ok":true,"cleared":true|false}   false: there was no such refusal (idempotent)
 *               daemon.status lists "hydration.refuse_name" in capabilities on an engine that answers it.
 *
 *   create      request:  {"verb":"hydration.create",["handle_id":"...",]"path":"/foo.txt"}
 *               (handle_id absent: the daemon issues it, as for open_write)
 *               reply:    {"ok":true,"cache_path":"/home/.../foo.txt","handle_id":"..."}
 *                         (an excluded path adds ,"excluded":true — accepted, never uploaded)
 *                         {"ok":false,"error":"parent_not_found"}   ENOENT
 *                         {"ok":false,"error":"path_exists"}        EEXIST
 *                         {"ok":false,"error":"outside_scope"}      path outside the profile's sync_path set
 *                         {"ok":false,"error":"<msg>"}              EIO
 *
 *   rename      request:  {"verb":"hydration.rename","old_path":"/a","new_path":"/b"[,"replace":true]}
 *               reply:    {"ok":true}
 *                         {"ok":false,"error":"old_path_not_found"}    ENOENT
 *                         {"ok":false,"error":"new_parent_not_found"}  ENOENT
 *                         {"ok":false,"error":"new_path_exists"}       EEXIST
 *                         {"ok":false,"error":"outside_scope"}         either end outside the profile's sync_path set
 *                         {"ok":false,"error":"<msg>"}                 EIO
 *                         replace (OPTIONAL, default false) is POSIX
 *                         overwrite-if-exists for a FILE destination: the
 *                         existing destination is deleted first (same delete
 *                         path as unlink — trash/undo semantics), then the
 *                         source takes its place. With replace, an
 *                         open_write upload of the source or the destination
 *                         that is still queued or running refuses the rename
 *                         with {"ok":false,"error":"busy"} (nothing touched):
 *                         retry after the "completed" event for that handle.
 *
 *   open_write_begin request: {"verb":"hydration.open_write_begin","path":"/foo"}  [,"handle_id":"wh-N"]  reply ok: {"ok":true,"cache_path":"..."}  errs: unknown_path / path_is_folder / outside_scope
 *
 *   cancel      request:  {"verb":"hydration.cancel","path":"/foo"[,"handle_id":"h1"]}
 *               reply:    {"ok":true,"cancelled":true}    a queued/running upload, or a wait for a download, was aborted
 *                         {"ok":false,"error":"cancelled"} → the aborted upload's Completed carries this token
 *                         {"ok":true,"cancelled":false}    nothing in flight (idempotent)
 *                         Aborts every in-flight submission for the path (waiting, running,
 *                         or in retry backoff); the caller follows with the row-level verb
 *                         (unlink/rename) for the path itself.
 *                         (gkrost/unidrive-cli#23) It also ends every open_read and hydrate waiting for the path's
 *                         download; with handle_id only the open_read of that handle id, and no upload. Each one answers
 *                         {"ok":false,"error":"cancelled"} (open_read also with failed and completed events carrying the
 *                         token, hydrate with the failed event). The download stops when nobody waits for it any more: its
 *                         partial bytes go, the row stays not hydrated and without an error. A connection that closes
 *                         ends its own waits the same way.
 *
 *   Upload progress: while a client-written file uploads, the stream carries
 *   {"event":"uploading","path":"...","handle_id":"...","bytes_done":N,"bytes_total":M}
 *   coalesced to a few per second per file, correlated by the open_write
 *   handle like `completed`.
 *   Download progress (gkrost/unidrive-cli#23): while a download runs, every open_read and hydrate waiting for it gets
 *   {"event":"downloading","path":"...","handle_id":"...","bytes_done":N,"bytes_total":M} about twice a second
 *   (handle_id absent for hydrate; bytes_total 0 when unknown).
 *                            handle_id is OPTIONAL: present → registers a JVM open-set entry (O_TRUNC live open);
 *                            absent → no registration (one-shot setattr/bare-truncate, backward-compatible).
 *
 *   subscribe   request:  {"verb":"hydration.subscribe"}
 *   subscribe   reply:    {"ok":true} — and from then on, the connection becomes a one-way
 *                         event stream (server-pushed NDJSON of HydrationEvent serializations)
 *
 * Subscribe pipeline: each subscribed connection gets its own bounded queue
 * (capacity 64). The single fan-out site (dispatchEvent) deals events to all
 * subscribers; per-subscriber writer coroutines drain their queue and push
 * NDJSON lines via the writer function. When a subscriber's queue is full the
 * dispatcher drops the OLDEST queued event and bumps a per-subscriber lost
 * counter; the writer emits one `{"event":"lost","since_last":N}` sentinel
 * line ahead of the next deliverable event and resets the counter. The whole
 * registry plus writer-coroutine teardown is owned by this handler;
 * onSubscriberDisconnect(connectionId) is the production hook called from
 * IpcServer's connection-close listener.
 */
class HydrationIpcHandler(
    private val hydration: Hydration,
    // The random part of the write-handle ids this daemon run issues; a seam for the contract corpus.
    handleIdRun: String = randomHandleIdRun(),
) {
    // Write-handle ids the daemon issues (open_write or create without handle_id): this run's prefix and a counter.
    // No client can name one (see reservedHandleId), so a close from one client never releases another's handle.
    private val issuedHandlePrefix = "$ISSUED_HANDLE_PREFIX$handleIdRun-"
    private val issuedHandles = AtomicLong()

    // open_write ids whose reply has not been returned, keyed by connection and path. The id is registered inside
    // openForWrite before launchSerializedUpload waits for a queue slot, and the reply — the only copy of the id —
    // is sent only after that wait. A retry in that window cannot echo the id (dh- is refused) and must not open
    // a second one. create does not wait on the queue; its reply is formed as soon as the row exists.
    private val unansweredOpenWrite = ConcurrentHashMap<String, String>()

    private fun issueHandleId(): String = issuedHandlePrefix + issuedHandles.incrementAndGet()

    private data class Subscriber(
        val queue: Channel<HydrationEvent>,
        val dropped: AtomicInteger,
        val job: Job,
    )

    private val subscribers = ConcurrentHashMap<String, Subscriber>()
    private var subscriberScope: CoroutineScope? = null
    private var subscriberWriter: (suspend (String, String) -> Boolean)? = null

    // Connections that have issued any hydration verb and are still connected — i.e. the
    // FUSE co-daemon serving this profile's view. `handle()` is only ever called for
    // hydration verbs, and the refresh CLI uses sync.subscribe/refresh.run (NOT hydration
    // verbs), so this set never contains a transient CLI connection. Cleared on disconnect.
    private val mountConnections = ConcurrentHashMap.newKeySet<String>()

    /**
     * True when at least one connection has issued `hydration.subscribe`. The Phase-3 mount
     * signal once the co-daemon subscribes on mount; see mount-view-refresh-design.md §6.
     */
    fun hasSubscribers(): Boolean = subscribers.isNotEmpty()

    /**
     * True when a FUSE co-daemon is currently serving this profile's view (any live connection
     * has issued a hydration verb). The daemon uses this to route `refresh.run` to the one-way
     * enumerate path rather than the legacy sync_root reconcile (mount-view-refresh-design.md §4).
     * Until the co-daemon subscribes on mount (Phase 3), this — not [hasSubscribers] — is the
     * working mount probe; it becomes true once the kernel first accesses the mount.
     */
    fun hasActiveMountConnection(): Boolean = mountConnections.isNotEmpty()

    /**
     * Production wiring entry point. Stores the scope used to launch per-subscriber
     * writer coroutines and the function used to write a single NDJSON line to a
     * given connection. The daemon also registers onSubscriberDisconnect on the
     * IpcServer's connection-close listener so subscribers tear down when their
     * UDS connection drops.
     */
    fun start(scope: CoroutineScope, writer: suspend (connectionId: String, line: String) -> Boolean) {
        subscriberScope = scope
        subscriberWriter = writer
    }

    /**
     * Fan an event out to every registered subscriber. Non-suspending: each
     * subscriber's bounded queue is fed via trySend; on overflow the oldest
     * unsent event is drained and the subscriber's dropped counter increments.
     * The single collector at the caller site drains hydration.events into here.
     */
    fun dispatchEvent(event: HydrationEvent) {
        for (sub in subscribers.values) {
            offerWithDropOldest(sub, event)
        }
    }

    private fun offerWithDropOldest(sub: Subscriber, event: HydrationEvent) {
        while (true) {
            val r = sub.queue.trySend(event)
            if (r.isSuccess) return
            if (r.isClosed) return
            // Buffer full: drain one (the oldest) and retry. A concurrent writer
            // drain shrinks the buffer too — either path frees a slot.
            val drained = sub.queue.tryReceive()
            if (drained.isSuccess) sub.dropped.incrementAndGet()
            // If tryReceive itself failed (race with writer just emptied it), loop
            // and retry trySend — the buffer now has room.
        }
    }

    /**
     * Tear down a subscriber: cancel its writer coroutine, close its queue, drop
     * the registry entry. Idempotent — disconnect for a non-subscribed connection
     * is a no-op (broadcast clients trigger close listeners too, and only some of
     * them are subscribers).
     */
    fun onSubscriberDisconnect(connectionId: String) {
        // Called for EVERY connection close (not only subscribers), so it's also where a
        // mount-client connection drops out of the routing probe. Must run before the
        // not-a-subscriber early return.
        mountConnections.remove(connectionId)
        val prefix = "$connectionId\u0000"
        unansweredOpenWrite.keys.removeIf { it.startsWith(prefix) }
        val sub = subscribers.remove(connectionId) ?: return
        sub.job.cancel()
        sub.queue.close()
    }

    private fun registerSubscriber(connectionId: String) {
        val scope = subscriberScope ?: return  // pre-start() subscribe: caller didn't wire the pipeline
        val writer = subscriberWriter ?: return
        // Repeat-subscribe on the same connection: tear down the prior subscriber first
        // so we never have two writer coroutines competing on the same connection.
        subscribers.remove(connectionId)?.let { it.job.cancel(); it.queue.close() }
        val queue = Channel<HydrationEvent>(capacity = SUBSCRIBER_QUEUE_CAPACITY)
        val dropped = AtomicInteger(0)
        val job = scope.launch {
            try {
                for (event in queue) {
                    val lost = dropped.getAndSet(0)
                    if (lost > 0) {
                        val ok = writer(connectionId, """{"event":"lost","since_last":$lost}""")
                        if (!ok) break
                    }
                    val ok = writer(connectionId, serialiseHydrationEvent(event))
                    if (!ok) break
                }
            } finally {
                // archive#1036: only this subscription's own entry. A repeat-subscribe cancels this writer and registers the
                // new one before the cancellation lands here; removing by connection id would drop the new entry.
                subscribers.computeIfPresent(connectionId) { _, current -> if (current.queue === queue) null else current }
                queue.close()
            }
        }
        subscribers[connectionId] = Subscriber(queue, dropped, job)
    }

    companion object {
        // Per-subscriber bounded queue capacity. Matches the MutableSharedFlow's
        // extraBufferCapacity in HydrationImpl; the SharedFlow is the upstream
        // source and the queue is the per-subscriber smoothing layer.
        const val SUBSCRIBER_QUEUE_CAPACITY = 64

        // The longest name NTFS and the common Linux file systems hold, in UTF-16 units.
        private const val MAX_SEGMENT_UTF16_UNITS = 255

        // The prefix of every write-handle id the daemon issues, then the run's random part and a counter: dh-<12 hex>-<n>.
        const val ISSUED_HANDLE_PREFIX = "dh-"
        private const val INVALID_HANDLE_ID = "invalid_handle_id"
        private const val HANDLE_ID_RUN_BYTES = 6

        private fun randomHandleIdRun(): String =
            java.util.HexFormat.of().formatHex(ByteArray(HANDLE_ID_RUN_BYTES).also { java.security.SecureRandom().nextBytes(it) })

        // Single source of truth for the verbs this handler answers. The daemon
        // (SyncCommand) iterates this list to wire each verb on IpcServer via
        // registerHandler. The dispatch table in `handle()` below must stay in
        // lockstep — there's a unit test pinning the two together.
        val VERBS: List<String> = listOf(
            "hydration.open_read",
            "hydration.open_write",
            "hydration.open_write_begin",
            "hydration.close_handle",
            "hydration.hydrate",
            "hydration.dehydrate",
            "hydration.subscribe",
            "hydration.last_synced",
            "hydration.matches_local",
            "hydration.list",
            "hydration.mkdir",
            "hydration.unlink",
            "hydration.rmdir",
            "hydration.create",
            "hydration.rename",
            "hydration.cancel",
            REFUSE_NAME,
            VIEW_PAGE,
        )

        /** A file the client does not upload for its twin, recorded and cleared (gkrost/unidrive-cli#24); also the daemon.status capability. */
        const val REFUSE_NAME = "hydration.refuse_name"

        /** One page of a read-only view for a front-end's own list (the Recents list's "more" button). */
        const val VIEW_PAGE = "view.page"

        // A page holds this many references when the request names no limit, and at most the most a provider's route answers.
        private const val VIEW_PAGE_DEFAULT_LIMIT = 50
        private const val VIEW_PAGE_MAX_LIMIT = 1000
    }
    suspend fun handle(connectionId: String, jsonRequest: String): String {
        val request = HydrationRequest.parse(jsonRequest) ?: return reply(ok = false, error = "missing_verb")
        val verb = request.string("verb") ?: return reply(ok = false, error = "missing_verb")
        // Any hydration verb on a connection marks it a live mount client (see mountConnections); a page of a view is
        // what a status UI's list asks for, not a mount.
        if (verb != VIEW_PAGE) mountConnections.add(connectionId)
        return try {
            dispatch(connectionId, verb, request)
        } catch (_: InvalidLogicalPath) {
            reply(ok = false, error = HydrationError.INVALID_PATH_TOKEN)
        }
    }

    private suspend fun dispatch(
        connectionId: String,
        verb: String,
        request: HydrationRequest,
    ): String {
        return when (verb) {
            "hydration.open_read" -> {
                val handleId = request.string("handle_id") ?: return reply(ok = false, error = "missing_handle_id")
                if (reservedHandleId(handleId)) return reply(ok = false, error = INVALID_HANDLE_ID)
                val path = pluckPath(request, "path") ?: return reply(ok = false, error = "missing_path")
                when (val r = hydration.openForRead(connectionId, handleId, path)) {
                    is OpenResult.Ok -> openOkReply(r)
                    is OpenResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.open_write" -> {
                // handle_id is OPTIONAL: absent, the daemon issues one and answers it (a client that saw
                // `open_write_create_handle_ids` in daemon.status — open_write and create, not open_write_begin);
                // present, it is the client's own name, as before.
                val clientHandleId = request.string("handle_id")
                if (reservedHandleId(clientHandleId)) return reply(ok = false, error = INVALID_HANDLE_ID)
                val path = pluckPath(request, "path") ?: return reply(ok = false, error = "missing_path")
                val cache = request.string("cache_path") ?: return reply(ok = false, error = "missing_cache_path")
                if (cache.isEmpty()) return reply(ok = false, error = "missing_cache_path")
                // A local path; Hydration accepts it only inside the profile's hydration cache folder.
                val cachePath =
                    try {
                        Paths.get(cache)
                    } catch (_: InvalidPathException) {
                        return reply(ok = false, error = HydrationError.INVALID_PATH_TOKEN)
                    }
                // base_etag is OPTIONAL: absent (or a row with no recorded token) →
                // unconditional upload, byte-identical to the pre-guard contract.
                val baseEtag = request.string("base_etag")
                nameClash(rawPath(request, "path"))?.let { return it }
                val unansweredKey = "$connectionId\u0000$path"
                if (clientHandleId == null) {
                    val pending = unansweredOpenWrite[unansweredKey]
                    // Registered means openForWrite got past its refusals and the path is pinned under this id.
                    // Before that, a retry must run the verb, not be told an id for an open that did not happen.
                    if (pending != null && hydration.hasRegisteredWriteHandle(connectionId, pending, path)) {
                        return openOkReply(OpenResult.Ok(cachePath), issuedHandleId = pending)
                    }
                }
                val handleId = clientHandleId ?: issueHandleId()
                if (clientHandleId == null) unansweredOpenWrite[unansweredKey] = handleId
                try {
                    when (val r = hydration.openForWrite(connectionId, handleId, path, cachePath, baseEtag)) {
                        is OpenResult.Ok -> {
                            spellings.record(rawPath(request, "path"))
                            openOkReply(r, issuedHandleId = handleId.takeIf { clientHandleId == null })
                        }
                        is OpenResult.Failed -> reply(ok = false, error = r.error.message)
                    }
                } finally {
                    if (clientHandleId == null) unansweredOpenWrite.remove(unansweredKey, handleId)
                }
            }
            "hydration.open_write_begin" -> {
                val path = pluckPath(request, "path") ?: return reply(ok = false, error = "missing_path")
                // handle_id is OPTIONAL: present → O_TRUNC live open (registers open-set entry);
                // absent → one-shot setattr/bare-truncate (no registration).
                val handleId = request.string("handle_id")
                if (reservedHandleId(handleId)) return reply(ok = false, error = INVALID_HANDLE_ID)
                nameClash(rawPath(request, "path"))?.let { return it }
                when (val r = hydration.openWriteBegin(connectionId, path, handleId)) {
                    is OpenResult.Ok -> {
                        spellings.record(rawPath(request, "path"))
                        openOkReply(r)
                    }
                    is OpenResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.close_handle" -> {
                val handleId = request.string("handle_id") ?: return reply(ok = false, error = "missing_handle_id")
                hydration.closeHandle(connectionId, handleId)
                reply(ok = true)
            }
            "hydration.hydrate" -> {
                val path = pluckPath(request, "path") ?: return reply(ok = false, error = "missing_path")
                // gkrost/unidrive-cli#23: on behalf of this connection, so that its close ends the wait.
                when (val r = hydration.hydrate(path, connectionId)) {
                    HydrateResult.Ok -> reply(ok = true)
                    is HydrateResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.dehydrate" -> {
                val path = pluckPath(request, "path") ?: return reply(ok = false, error = "missing_path")
                when (val r = hydration.dehydrate(path)) {
                    DehydrateResult.Ok -> reply(ok = true)
                    DehydrateResult.Busy -> reply(ok = false, error = "busy")
                    is DehydrateResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.last_synced" -> {
                val path = pluckPath(request, "path") ?: return reply(ok = false, error = "missing_path")
                when (val r = hydration.lastSynced(path)) {
                    is LastSyncedResult.Ok -> """{"ok":true,"mtime_ms":${r.mtimeEpochMillis}}"""
                    is LastSyncedResult.Unknown -> reply(ok = false, error = r.reason)
                }
            }
            "hydration.matches_local" -> {
                val path = pluckPath(request, "path") ?: return reply(ok = false, error = "missing_path")
                val local = request.string("local_path")
                if (local.isNullOrEmpty()) return reply(ok = false, error = "missing_local_path")
                // Whether it is an absolute path of THIS computer is the engine's to say (MountEngine.contentMatchesLocal); a string that is
                // no path at all (a NUL, ...) stops here.
                val localPath =
                    try {
                        Paths.get(local)
                    } catch (_: InvalidPathException) {
                        return reply(ok = false, error = "invalid_local_path")
                    }
                when (val r = hydration.matchesLocal(path, localPath)) {
                    is MatchesLocalResult.Same -> """{"ok":true,"result":"same","method":${jsonEsc(r.method)}}"""
                    is MatchesLocalResult.Differs -> """{"ok":true,"result":"differs","method":${jsonEsc(r.method)}}"""
                    is MatchesLocalResult.Unknown -> """{"ok":true,"result":"unknown","reason":${jsonEsc(r.reason)}}"""
                    is MatchesLocalResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.list" -> {
                // Not NFC-normalised here (StateDatabase.listDirectChildren does that), but validated like every path.
                val prefix = request.string("prefix")?.let { requireLogicalPath(it) }
                    ?: return reply(ok = false, error = "missing_prefix")
                when (val r = hydration.list(prefix)) {
                    is ListResult.Ok -> serialiseListEntries(r.entries)
                    is ListResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.mkdir" -> {
                val path = pluckPath(request, "path") ?: return reply(ok = false, error = "missing_path")
                nameClash(rawPath(request, "path"))?.let { return it }
                when (val r = hydration.mkdir(path)) {
                    is MkdirResult.Ok -> {
                        spellings.record(rawPath(request, "path"))
                        reply(ok = true)
                    }
                    MkdirResult.ParentNotFound -> reply(ok = false, error = "parent_not_found")
                    is MkdirResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.unlink" -> {
                val path = pluckPath(request, "path") ?: return reply(ok = false, error = "missing_path")
                // archive#1178: "permanent":true deletes for good (rm --permanent); the provider must be able to.
                val permanent = request.bool("permanent") == true
                nameClash(rawPath(request, "path"))?.let { return it }
                when (val r = hydration.unlink(path, permanent)) {
                    is UnlinkResult.Ok -> {
                        spellings.forget(rawPath(request, "path"))
                        reply(ok = true)
                    }
                    UnlinkResult.PathIsFolder -> reply(ok = false, error = "path_is_folder")
                    UnlinkResult.Busy -> reply(ok = false, error = "busy")
                    is UnlinkResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.rmdir" -> {
                val path = pluckPath(request, "path") ?: return reply(ok = false, error = "missing_path")
                // archive#1035: plain rmdir is strict; "recursive":true is the client meaning the whole tree.
                val recursive = request.bool("recursive") == true
                val permanent = request.bool("permanent") == true
                nameClash(rawPath(request, "path"))?.let { return it }
                when (val r = hydration.rmdir(path, recursive, permanent)) {
                    is RmdirResult.Ok -> {
                        spellings.forget(rawPath(request, "path"))
                        reply(ok = true)
                    }
                    RmdirResult.PathIsFile -> reply(ok = false, error = "path_is_file")
                    RmdirResult.NotEmpty -> reply(ok = false, error = "not_empty")
                    RmdirResult.Busy -> reply(ok = false, error = "busy")
                    is RmdirResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.create" -> {
                // handle_id is OPTIONAL like open_write's: absent, the reply carries the id the daemon issued.
                val clientHandleId = request.string("handle_id")
                if (reservedHandleId(clientHandleId)) return reply(ok = false, error = INVALID_HANDLE_ID)
                val path = pluckPath(request, "path") ?: return reply(ok = false, error = "missing_path")
                nameClash(rawPath(request, "path"))?.let { return it }
                val handleId = clientHandleId ?: issueHandleId()
                when (val r = hydration.create(connectionId, handleId, path)) {
                    is CreateResult.Ok -> {
                        spellings.record(rawPath(request, "path"))
                        if (r.excluded) {
                            """{"ok":true,"cache_path":${jsonEsc(r.cachePath.toString())},"handle_id":${jsonEsc(r.handleId)},"excluded":true}"""
                        } else {
                            """{"ok":true,"cache_path":${jsonEsc(r.cachePath.toString())},"handle_id":${jsonEsc(r.handleId)}}"""
                        }
                    }
                    CreateResult.ParentNotFound -> reply(ok = false, error = "parent_not_found")
                    CreateResult.PathExists -> reply(ok = false, error = "path_exists")
                    is CreateResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            REFUSE_NAME -> {
                // Not guarded by name_clash: two spellings of one name are exactly what this verb reports.
                val path = pluckPath(request, "path") ?: return reply(ok = false, error = "missing_path")
                if (request.bool("clear") == true) {
                    return """{"ok":true,"cleared":${hydration.clearNameRefusal(path)}}"""
                }
                when (val r = hydration.refuseName(path, request.string("sibling")?.takeIf { it.isNotEmpty() })) {
                    RefuseNameResult.Ok -> reply(ok = true)
                    RefuseNameResult.InCloud -> reply(ok = false, error = "path_exists")
                    RefuseNameResult.PathIsFolder -> reply(ok = false, error = "path_is_folder")
                    RefuseNameResult.ParentNotFound -> reply(ok = false, error = "parent_not_found")
                    is RefuseNameResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.rename" -> {
                val oldPath = pluckPath(request, "old_path") ?: return reply(ok = false, error = "missing_old_path")
                val newPath = pluckPath(request, "new_path") ?: return reply(ok = false, error = "missing_new_path")
                // replace is OPTIONAL (default false): POSIX overwrite-if-exists.
                val replace = request.bool("replace") ?: false
                nameClash(rawPath(request, "old_path"), rawPath(request, "new_path"))?.let { return it }
                when (val r = hydration.rename(oldPath, newPath, replace)) {
                    is RenameResult.Ok -> {
                        spellings.forget(rawPath(request, "old_path"))
                        spellings.record(rawPath(request, "new_path"))
                        reply(ok = true)
                    }
                    RenameResult.OldPathNotFound -> reply(ok = false, error = "old_path_not_found")
                    RenameResult.NewParentNotFound -> reply(ok = false, error = "new_parent_not_found")
                    RenameResult.NewPathExists -> reply(ok = false, error = "new_path_exists")
                    is RenameResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.cancel" -> {
                val path = pluckPath(request, "path") ?: return reply(ok = false, error = "missing_path")
                // Idempotent: cancelled=true when a queued/running upload or a wait for a download was
                // aborted, false when nothing was in flight — both satisfy the
                // caller's "nothing more happens" goal, so both are ok.
                // gkrost/unidrive-cli#23: with handle_id, only the open_read of that handle stops waiting (uploads untouched).
                val handleId = request.string("handle_id")
                val cancelled =
                    if (handleId != null) {
                        hydration.cancelDownload(path, handleId)
                    } else {
                        val upload = hydration.cancelUpload(path)
                        hydration.cancelDownload(path) || upload
                    }
                """{"ok":true,"cancelled":$cancelled}"""
            }
            "hydration.subscribe" -> {
                registerSubscriber(connectionId)
                reply(ok = true)
            }
            VIEW_PAGE -> viewPage(request)
            else -> reply(ok = false, error = "unknown_verb")
        }
    }

    private fun reply(ok: Boolean, error: String? = null): String =
        if (ok) """{"ok":true}""" else """{"ok":false,"error":${jsonEsc(error ?: "unknown")}}"""

    // `excluded` is only serialized when true — an additive wire field: a reply
    // on the common (non-excluded) path is byte-identical to the pre-field
    // contract, and existing corpus lines stay valid.
    // `handle_id` likewise only for an id the daemon issued: a client that named its own gets the reply it always got.
    // `content_id` (gkrost/unidrive-cli#171) only where open_read names the bytes it served.
    private fun openOkReply(r: OpenResult.Ok, issuedHandleId: String? = null): String {
        val handle = issuedHandleId?.let { ""","handle_id":${jsonEsc(it)}""" } ?: ""
        val excluded = if (r.excluded) ""","excluded":true""" else ""
        val contentId = r.contentId?.let { ""","content_id":${jsonEsc(it)}""" } ?: ""
        return """{"ok":true,"cache_path":${jsonEsc(r.cachePath.toString())}$handle$excluded$contentId}"""
    }

    // A client-named handle id with the prefix of the ids the daemon issues: refused, so no client can hold one.
    private fun reservedHandleId(handleId: String?): Boolean = handleId != null && handleId.startsWith(ISSUED_HANDLE_PREFIX)

    // Reads a LOGICAL co-daemon path field and canonicalises it to NFC. This is the
    // single point where co-daemon paths enter the JVM; normalizing once here makes
    // every downstream op (cache resolution + provider delete/createFolder/move + the
    // StateDatabase NFC lookups) see the same NFC form as the NFC-stored data. The
    // co-daemon may send a decomposed (NFD) name (macOS-origin accents) for a row
    // stored composed (NFC) — without this an NFD deleteRemote 404s on the NFC cloud
    // object and an NFD cache lookup misses the NFC-named file. Mirrors the existing
    // ingestion-chokepoint approach. Runs on the JSON-DECODED value, so an escaped
    // decomposed form (o + escaped combining diaeresis) is normalized too. NOT applied to
    // `cache_path` (a local filesystem path, not a logical one) or `prefix`
    // (StateDatabase.listDirectChildren already normalizes it). Every value is then
    // validated (requireLogicalPath): this is the one place every verb's logical path
    // passes through.
    private fun pluckPath(request: HydrationRequest, key: String): String? =
        request.string(key)?.let { requireLogicalPath(PathNormalizer.nfc(it)) }

    // gkrost/unidrive-cli#24: the path field as the client spelled it, before the NFC canonicalisation; see [NameSpellings].
    private val spellings = NameSpellings()

    private fun rawPath(request: HydrationRequest, key: String): String = request.string(key).orEmpty()

    // The name_clash answer for a verb that names a name by another spelling than the one a client used for it first.
    private fun nameClash(vararg raws: String): String? =
        if (raws.any { spellings.clashes(it) }) reply(ok = false, error = HydrationError.NAME_CLASH_TOKEN) else null

    // The validation of a LOGICAL path at the IPC boundary, for every verb that takes one.
    // A path is a list of names separated by '/'; a `.` or `..` segment, a control
    // character (NUL included), an empty segment other than that of a leading or trailing
    // slash ("/a//b", "//a") or a segment longer than 255 UTF-16 units (no supported file
    // system holds a longer name) throws [InvalidLogicalPath], which [handle] answers with
    // invalid_path before the verb runs. "", "/" and "/a/" stay accepted. DEL is a legal
    // name character on NTFS and POSIX file systems and stays accepted. A name the host's
    // file system cannot hold is the verb's own check (mkdir, create and rename refuse it in
    // HydrationImpl).
    private fun requireLogicalPath(path: String): String {
        val segments = path.split('/')
        for ((i, segment) in segments.withIndex()) {
            val emptyInside = segment.isEmpty() && i != 0 && i != segments.lastIndex
            if (emptyInside || segment == "." || segment == ".." || segment.length > MAX_SEGMENT_UTF16_UNITS || segment.any { it < ' ' }) {
                throw InvalidLogicalPath()
            }
        }
        return path
    }

    // No stack trace: it is a refusal, answered on the wire.
    private class InvalidLogicalPath : RuntimeException(null, null, false, false)

    // view.page: one page of a read-only view for a front-end's own list (ipc-write-protocol.md section 4.2).
    private suspend fun viewPage(request: HydrationRequest): String {
        val kind = request.string("kind") ?: return reply(ok = false, error = "missing_kind")
        val offset = request.count("offset", 0, 0..Int.MAX_VALUE) ?: return reply(ok = false, error = "invalid_offset")
        val limit = request.count("limit", VIEW_PAGE_DEFAULT_LIMIT, 1..VIEW_PAGE_MAX_LIMIT)
            ?: return reply(ok = false, error = "invalid_limit")
        return when (val r = hydration.viewPage(kind, offset, limit)) {
            is ViewPageResult.Ok -> serialiseViewPage(r)
            ViewPageResult.UnknownView -> reply(ok = false, error = "unknown_view")
            is ViewPageResult.Failed -> reply(ok = false, error = r.error.message)
        }
    }
}

/**
 * Serialise a HydrationEvent to a JSON-line string. Called by the
 * per-subscriber writer coroutine inside HydrationIpcHandler.
 */
// view.page's reply: the page's place in the list, its entries as hydration.list writes them, and how far to trust it.
private fun serialiseViewPage(r: ViewPageResult.Ok): String {
    val entries = serialiseListEntries(r.entries)
    val sb = StringBuilder("""{"ok":true,"kind":""").append(jsonEsc(r.kind))
        .append(",\"offset\":").append(r.offset)
        .append(entries.removePrefix("""{"ok":true""").removeSuffix("}"))
        .append(",\"skipped\":").append(r.skipped)
    if (r.nextOffset != null) sb.append(",\"next_offset\":").append(r.nextOffset) else sb.append(",\"end\":true")
    sb.append(",\"state\":").append(jsonEsc(r.state))
        .append(",\"as_of_ms\":").append(r.asOfMs?.toString() ?: "null")
        .append('}')
    return sb.toString()
}

private fun serialiseListEntries(entries: List<ListResult.Entry>): String {
    val sb = StringBuilder("""{"ok":true,"entries":[""")
    for ((i, e) in entries.withIndex()) {
        if (i > 0) sb.append(',')
        sb.append("{\"path\":").append(jsonEsc(e.path))
            .append(",\"size\":").append(e.size)
            .append(",\"mtime_ms\":").append(e.mtimeEpochMillis)
            .append(",\"hydrated\":").append(e.isHydrated)
            .append(",\"folder\":").append(e.isFolder)
            .append(",\"remote_modified_ms\":").append(e.remoteModifiedEpochMillis?.toString() ?: "null")
            .append(",\"remote_id\":").append(e.remoteId?.let { jsonEsc(it) } ?: "null")
            .append(",\"etag\":").append(e.etag?.let { jsonEsc(it) } ?: "null")
        // gkrost/unidrive-cli#171: additive, beside the etag, and only where the row names its bytes (daemon.status
        // capability `hydration.list_content_id`).
        if (e.contentId != null) sb.append(",\"content_id\":").append(jsonEsc(e.contentId))
        sb.append(",\"pending_upload\":").append(e.pendingUpload)
            .append(",\"error\":").append(e.hasError)
        if (e.excluded) sb.append(",\"excluded\":true")
        if (e.readOnly) sb.append(",\"read_only\":true")
        if (e.trashRoot) sb.append(",\"trash_root\":true")
        if (e.blocked != null) sb.append(",\"blocked\":").append(jsonEsc(e.blocked))
        if (e.view != null) sb.append(",\"view\":").append(jsonEsc(e.view))
        if (e.viewTarget != null) sb.append(",\"view_target\":").append(jsonEsc(e.viewTarget))
        sb.append('}')
    }
    sb.append("]}")
    return sb.toString()
}

fun serialiseHydrationEvent(e: HydrationEvent): String = when (e) {
    is HydrationEvent.Hydrating  -> """{"event":"hydrating","path":${jsonEsc(e.path)}}"""
    is HydrationEvent.Hydrated   -> """{"event":"hydrated","path":${jsonEsc(e.path)},"bytes":${e.bytes}}"""
    is HydrationEvent.Dehydrated -> """{"event":"dehydrated","path":${jsonEsc(e.path)}}"""
    is HydrationEvent.Skipped    -> """{"event":"skipped","path":${jsonEsc(e.path)}}"""
    is HydrationEvent.Queued     -> """{"event":"queued","path":${jsonEsc(e.path)}}"""
    is HydrationEvent.Uploading -> {
        """{"event":"uploading","path":${jsonEsc(e.path)},"handle_id":${jsonEsc(e.handleId)},""" +
            """"bytes_done":${e.bytesDone},"bytes_total":${e.bytesTotal}}"""
    }
    is HydrationEvent.Downloading -> {
        val handle = e.handleId?.let { ""","handle_id":${jsonEsc(it)}""" } ?: ""
        """{"event":"downloading","path":${jsonEsc(e.path)}$handle,"bytes_done":${e.bytesDone},"bytes_total":${e.bytesTotal}}"""
    }
    is HydrationEvent.Failed -> {
        val base = """{"event":"failed","path":${jsonEsc(e.path)},"error":${jsonEsc(e.error.message)}"""
        // retry_scheduled is only serialized for upload attempts; download and
        // verb failures keep the pre-existing shape byte-identical.
        if (e.retryScheduled == null) "$base}" else "$base,\"retry_scheduled\":${e.retryScheduled}}"
    }
    is HydrationEvent.Completed -> {
        val direction = if (e.direction == HydrationEvent.Completed.Direction.UPLOAD) "upload" else "download"
        val base = """{"event":"completed","path":${jsonEsc(e.path)},"handle_id":${jsonEsc(e.handleId)},"direction":"$direction","ok":${e.ok}"""
        if (e.ok) "$base}" else "$base,\"error\":${jsonEsc(e.error?.message ?: "unknown")}}"
    }
    is HydrationEvent.Paused -> {
        val base = """{"event":"paused","reason":${jsonEsc(e.reason)},"since_ms":${e.sinceMs},"auto":${e.auto}"""
        if (e.untilMs == null) "$base}" else "$base,\"until_ms\":${e.untilMs}}"
    }
    is HydrationEvent.Resumed -> if (e.reason == null) """{"event":"resumed"}""" else """{"event":"resumed","reason":${jsonEsc(e.reason)}}"""
    is HydrationEvent.ViewInvalidated -> {
        if (e.full) {
            """{"event":"view.invalidated","full":true}"""
        } else {
            val paths = e.paths.joinToString(",") { jsonEsc(it) }
            // archive#595: the rename hint is additive — omitted when empty, so consumers
            // written before it parse the line unchanged.
            val base = """{"event":"view.invalidated","paths":[$paths]"""
            if (e.moved.isEmpty()) {
                "$base}"
            } else {
                val moved = e.moved.joinToString(",") { """{"from":${jsonEsc(it.from)},"to":${jsonEsc(it.to)}}""" }
                "$base,\"moved\":[$moved]}"
            }
        }
    }
}

// JSON string literal for [s]: the NDJSON escaper of the socket protocol. A provider's error message (an HTTP error body, say)
// routinely carries a newline, and one raw newline (or NEL, U+2028, U+2029) in a reply or event line splits the framing for
// the reader.
private fun jsonEsc(s: String): String = ndjsonString(s)
