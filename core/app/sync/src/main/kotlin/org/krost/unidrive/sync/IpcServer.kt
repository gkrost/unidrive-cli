package org.krost.unidrive.sync

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.http.UrlRedaction
import org.krost.unidrive.io.Os
import org.krost.unidrive.io.OwnerOnly
import org.krost.unidrive.io.ndjsonLine
import org.krost.unidrive.io.ndjsonString
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CopyOnWriteArrayList

class IpcServer(
    private val socketPath: Path,
    private val transportDispatcher: kotlinx.coroutines.CoroutineDispatcher? = null,
    private val handlerDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    writeTimeoutMs: Long = readWriteTimeoutFromEnv(),
    // IPC authentication (docs/dev/specs/ipc-authentication.md). `daemon run` and `sync` always pass one;
    // null (no handshake) is for the unit tests of the bare server only.
    private val auth: IpcAuth? = null,
    // How many connections are served at once; one more is refused with too_many_clients.
    internal val maxClients: Int = readMaxClientsFromEnv(),
    // A connection that sent no request for this long is closed (0 = never); see idleExpired.
    private val idleTimeoutMs: Long = readIdleTimeoutFromEnv(),
    // Monotonic milliseconds for the idle timeout; injectable so tests move time instead of waiting.
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    // How long a refused connection is kept open for its peer's request (see refuse); a test widens it so that a slow
    // runner cannot make the peer's request arrive after the server stopped waiting for it.
    private val refusalLingerMs: Long = REFUSAL_LINGER_MS,
) {
    // How long close() waits for the server's own transport threads to finish before it interrupts them (#531).
    internal var transportShutdownWaitMs: Long = TRANSPORT_SHUTDOWN_WAIT_MS

    // How often start()'s IpcSocketWatcher checks that the socket file is still there and binds it again (0 = never,
    // gkrost/unidrive-cli#794). Not a constructor parameter: the tests set it before start(), like the wait above.
    internal var socketWatchIntervalMs: Long = SOCKET_WATCH_INTERVAL_MS
    internal val socketProbeSuccesses: Int get() = socketWatcher?.probeSuccesses ?: 0

    // gkrost/unidrive-cli#926 S2: what the watcher takes for the socket file's identity; a test stands in for a file system that reuses an inode.
    internal var socketFileIdentity: (Path) -> Any? = IpcSocketWatcher::fileIdentity

    // Captured at construction so a later companion-object change can't accidentally
    // re-read the env var mid-flight. Multiply once into nanos so writeNonBlocking
    // does no per-call arithmetic.
    private val writeTimeoutNs: Long = writeTimeoutMs * 1_000_000L

    data class SyncState(
        val profile: String,
        val phase: String? = null,
        val scanCount: Int = 0,
        val actionTotal: Int = 0,
        val actionIndex: Int = 0,
        val lastAction: String? = null,
        val lastPath: String? = null,
        val isComplete: Boolean = false,
        val downloaded: Int = 0,
        val uploaded: Int = 0,
        val conflicts: Int = 0,
        val durationMs: Long = 0,
    )

    @Volatile
    var syncState: SyncState? = null
        private set

    /**
     * UD-214: number of currently-connected clients. Exposed so callers can
     * synchronize on "accept loop has registered a freshly-connected client"
     * without polling private state. The value is eventually-consistent —
     * `accept()` runs on `Dispatchers.IO` and there is a small window
     * between the client's TCP connect completing and `clients.add(...)`
     * running — but is monotonic for a given client until that client
     * closes. Primary consumer: `IpcProgressReporterTest` and any tool
     * that needs to gate emission on "at least one consumer is listening".
     */
    val clientCount: Int
        get() = clients.size

    private val log = LoggerFactory.getLogger(IpcServer::class.java)
    // Each connected client is tracked as a (SocketChannel, connectionId) pair.
    // connectionId is a random UUID assigned at accept-time; stable for the lifetime
    // of the connection regardless of how other clients join or leave.
    private data class ClientEntry(
        val channel: SocketChannel,
        val id: String,
        val writeMutex: Mutex = Mutex(),
    ) {
        // When the last request line was handled (clock()); the accept time until the first one.
        @Volatile
        var lastRequestAtMs: Long = 0L

        // Set once the connection used one of the idle-exempt verbs (registerIdleExemptVerbs).
        @Volatile
        var idleExempt: Boolean = false
    }
    private val clients = CopyOnWriteArrayList<ClientEntry>()
    private val channel = Channel<String>(capacity = 256)
    private val handlers = java.util.concurrent.ConcurrentHashMap<String, suspend (String, String) -> String>()

    // gkrost/unidrive-cli#460: requests served per verb since this server was made (daemon.status `ipc.requests`). Only a
    // request that reaches its registered handler counts, so the keys are registered verbs and nothing a client sends
    // can add one; a request the authentication gate answers or refuses does not count.
    private val servedByVerb = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.LongAdder>()
    private val closeListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private var serverChannel: ServerSocketChannel? = null

    // Wakes the connection readers when their socket has data (archive#687); set by start(), cleared by close().
    @Volatile
    private var readSelector: IpcReadSelector? = null

    // archive#419: set once start() has bound the socket, so close() removes only files this server owns.
    @Volatile
    private var ownsSocketFiles = false
    private var acceptJob: Job? = null
    private var broadcastJob: Job? = null

    // gkrost/unidrive-cli#926 S3: one queue and writer per sync subscriber; made by start().
    @Volatile
    private var syncFanOut: IpcSyncFanOut? = null
    private var socketWatchJob: Job? = null
    private var socketWatcher: IpcSocketWatcher? = null

    // gkrost/unidrive-cli#794: how often the watcher had to bind the socket file again, and the guard between a rebind and close().
    internal val socketRebinds: Int
        get() = socketWatcher?.rebinds ?: 0
    private val rebindLock = Any()

    @Volatile
    private var closing = false
    private var ownedTransport: java.util.concurrent.ExecutorService? = null

    // Connections that issued `sync.subscribe`. Filtered by the broadcast loop
    // (§3.2.5) so request-reply-only clients (e.g. FUSE co-daemon) never
    // receive unsolicited sync-progress bytes. Cleanup is via the close-
    // listener registered externally by SyncCommand (§3.3 of the spec).
    private val syncSubscribers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // Refused connections currently waiting for their peer to finish sending (see refuse).
    private val lingeringRefusals = java.util.concurrent.atomic.AtomicInteger(0)

    // One-shot per-connection slot for an action that runs AFTER the current
    // request's reply is written to the socket. See scheduleAfterReply()
    // and the post-reply hook in dispatchRequest. Single in-flight request
    // per connection (the per-client reader processes lines sequentially),
    // so a per-connId slot is sufficient — no queue.
    private val pendingPostReply = java.util.concurrent.ConcurrentHashMap<String, suspend () -> Unit>()

    // IPC authentication: one handshake state per connection (connId), only when [auth] is set.
    private val authSessions = java.util.concurrent.ConcurrentHashMap<String, IpcAuth.Session>()

    /**
     * Register an inbound-verb handler. The handler receives the connection ID
     * (a per-client UUID, stable for the life of the connection) and the raw
     * JSON line (excluding the trailing newline), and returns the JSON reply
     * line (the server appends the newline). Verb dispatch keys on a top-level
     * "verb" field in the request JSON. Throws IllegalArgumentException on
     * duplicate registration (registration is one-shot per verb).
     */
    fun registerHandler(verb: String, handler: suspend (connectionId: String, json: String) -> String) {
        require(handlers.putIfAbsent(verb, handler) == null) {
            "Handler for verb '$verb' is already registered"
        }
    }

    /**
     * Register a listener invoked when a client disconnects (EOF or IOException).
     * Called with the same connectionId that was passed to registered handlers.
     */
    fun registerConnectionCloseListener(listener: (connectionId: String) -> Unit) {
        closeListeners.add(listener)
    }

    private val idleExemptVerbs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val idleHolds = CopyOnWriteArrayList<(String) -> Boolean>()

    /** gkrost/unidrive-cli#926 S4: [holds] says whether a connection holds something (a subscription, open handles), until the hold ends. */
    fun registerIdleHold(holds: (connectionId: String) -> Boolean) {
        idleHolds.add(holds)
    }

    /**
     * A connection that has sent a request for one of [verbs] (and a handler took it) is never
     * closed for being idle. The daemon passes the hydration verbs: a mount client's open handles
     * and its event subscription live on its connections, and its live connections are what the
     * daemon recognises a mount by. Sync subscribers are exempt without this.
     */
    fun registerIdleExemptVerbs(verbs: Collection<String>) {
        idleExemptVerbs.addAll(verbs)
    }

    private val cancelOnPeerGoneVerbs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * gkrost/unidrive-cli#760: a request for one of [verbs] is cancelled when its peer goes away while it runs, and its
     * connection is closed without a reply, so an abandoned request no longer holds a connection slot. Only for verbs
     * whose work may stop anywhere (reads); any other request runs to its end as before.
     */
    fun registerCancelOnPeerGoneVerbs(verbs: Collection<String>) {
        cancelOnPeerGoneVerbs.addAll(verbs)
    }

    fun updateState(state: SyncState) {
        syncState = state
    }

    // NEW-2: count silently-dropped broadcast events so a full-channel drop
    // (the fan-out loop not keeping up; a slow subscriber no longer fills the
    // channel, each has its own queue) is observable instead of invisible. Exposed
    // internally for the regression test; production drop semantics are
    // unchanged (we still drop — observability only).
    private val droppedBroadcastEvents = java.util.concurrent.atomic.AtomicLong(0)

    internal val droppedBroadcastEventsForTest: Long
        get() = droppedBroadcastEvents.get()

    fun emit(json: String) {
        // trySend on a closed channel reports "closed" instead of throwing, so no isClosedForSend
        // pre-check (a delicate API) is needed: a closed channel is neither sent to nor counted as a drop.
        val r = channel.trySend(json)
        if (r.isFailure && !r.isClosed) {
            droppedBroadcastEvents.incrementAndGet()
            log.warn("sync-progress broadcast event dropped (channel full, cap=256) — the fan-out loop did not keep up")
        }
    }

    /**
     * Write a single JSON line to one specific connection (newline is appended
     * server-side). Used by the hydration subscriber pipeline and the sync
     * event fan-out ([IpcSyncFanOut]) to push events to just the connections
     * that subscribed, instead of writing to all of them one after another.
     * Returns false when the connection is unknown or the write failed (dead
     * client is then removed and close-listeners fire).
     */
    suspend fun writeToConnection(connectionId: String, json: String): Boolean {
        val entry = clients.firstOrNull { it.id == connectionId } ?: return false
        val bytes = (ndjsonLine(json) + "\n").toByteArray(Charsets.UTF_8)
        return try {
            entry.writeMutex.withLock {
                writeNonBlocking(entry.channel, ByteBuffer.wrap(bytes))
            }
            true
        } catch (e: IOException) {
            log.debug("IPC: writeToConnection failed for id={}: {}", entry.id, e.message)
            clients.remove(entry)
            closeChannel(entry.channel)
            closeListeners.forEach { it(entry.id) }
            false
        }
    }

    /**
     * Close one connection from the daemon's side (gkrost/unidrive-cli#845: a hydration subscriber at its queue's hard cap).
     * Its reader ends with the closed channel, removes the client and fires the close listeners. Unknown id: nothing.
     */
    fun closeConnection(connectionId: String) {
        clients.firstOrNull { it.id == connectionId }?.let { closeChannel(it.channel) }
    }

    /**
     * Mark a connection as a sync-progress subscriber. After this call, the
     * connection receives sync-progress events from `emit(...)` until it
     * disconnects (cleanup via `unregisterSyncSubscriber`, called from a
     * connection-close listener registered externally by `SyncCommand` for
     * stylistic parity with the existing hydration close listeners).
     *
     * Symmetric with `HydrationIpcHandler.registerSubscriber` at the wire
     * level; the two subscriber sets are independent (a client may
     * subscribe to one, both, or neither).
     */
    fun registerSyncSubscriber(connectionId: String) {
        syncSubscribers.add(connectionId)
        syncFanOut?.add(connectionId)
    }

    /**
     * Remove a connection from the sync-progress subscriber set. Idempotent;
     * called from the connection-close listener `registerSyncSubscribeVerb`
     * registers, which [writeToConnection] also fires when it finds the
     * subscriber's connection gone.
     */
    fun unregisterSyncSubscriber(connectionId: String) {
        syncSubscribers.remove(connectionId)
        syncFanOut?.remove(connectionId)
    }

    /**
     * Schedule a one-shot action to run AFTER the current request's reply
     * is written to the socket. Used by `sync.subscribe` to push the state
     * dump and subscriber-set registration only after the {"ok":true} reply
     * is on the wire — so the subscriber's first observed line is always
     * the reply, never an event.
     *
     * Must be called from inside a verb handler running under
     * dispatchRequest's withContext(handlerDispatcher) block. Calling
     * outside a handler is a no-op (the action will never fire because
     * the next dispatchRequest call clears the slot at the top).
     *
     * The action fires only on the successful-reply path. If the handler
     * threw and dispatchRequest wrote an error envelope, the scheduled
     * action is discarded (R7 in the spec).
     */
    fun scheduleAfterReply(connectionId: String, action: suspend () -> Unit) {
        pendingPostReply[connectionId] = action
    }

    /** True when this server authenticates its connections (an [IpcAuth] was given); false only for the bare server of the unit tests. */
    val authEnabled: Boolean
        get() = auth != null

    /**
     * The scope the connection's handshake granted, as its wire word (`full` or `read`); null when it has none:
     * the server has no [IpcAuth], the handshake is not complete, or the connection is gone. A verb handler
     * only runs for an authenticated connection, so with [authEnabled] a null here means "not known to be
     * full" and callers deciding whether to start work on the client's behalf must treat it as read.
     */
    fun scopeOf(connectionId: String): String? = authSessions[connectionId]?.scope?.wire

    /**
     * gkrost/unidrive-cli#460: the requests each verb's handler has been given since this server was made, sorted by
     * verb; a verb not asked yet is absent. A reading taken now: it never blocks, and it holds no path or id.
     */
    fun requestCounts(): Map<String, Long> = servedByVerb.entries.associateTo(sortedMapOf()) { it.key to it.value.sum() }

    /**
     * Test-only accessor used by IpcSyncSubscriberSetTest to assert that
     * disconnected connections are removed from the subscriber set (T4 in
     * the spec). Same-module visibility is sufficient — `internal` works
     * for tests living in `:app:sync`.
     */
    internal val syncSubscribersSnapshot: Set<String>
        get() = syncSubscribers.toSet()

    /**
     * `sync.subscribe` (spec docs/dev/specs/sync-progress-subscriber-set-design.md), as both hosts serve it, the daemon and
     * `unidrive sync` (gkrost/unidrive-cli#442): the client reads `{"ok":true}` as the first line, then the state dump, then the
     * live events. The post-reply hook defers the dump and the registration until the reply is written, so no event is
     * interleaved with it; a closed connection leaves the subscriber set.
     */
    fun registerSyncSubscribeVerb() {
        registerHandler("sync.subscribe") { connId, _ ->
            scheduleAfterReply(connId) {
                flushStateDumpTo(connId)
                registerSyncSubscriber(connId)
            }
            """{"ok":true}"""
        }
        registerConnectionCloseListener { connId -> unregisterSyncSubscriber(connId) }
    }

    fun start(scope: CoroutineScope) {
        reclaimStaleSocket()

        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(socketPath))
        ownsSocketFiles = true
        // UD-100: defense-in-depth — the socket file is owner-only too (0600 on POSIX, an owner-only
        // ACL on Windows), for parity with its owner-only folder (defaultSocketPath). The folder is
        // what keeps others out, so a failure here is logged rather than fatal.
        val restricted = OwnerOnly.restrictFile(socketPath)
        if (!restricted.restricted) log.warn("IPC: could not restrict the permissions of {}: {}", socketPath, restricted)
        serverChannel = server

        val selector = IpcReadSelector()
        readSelector = selector

        val transport: kotlinx.coroutines.CoroutineDispatcher = transportDispatcher ?: newTransportDispatcher()
        log.info(
            "IPC: transport pool size={} write_timeout_ms={} idle_timeout_ms={} max_clients={}",
            TRANSPORT_POOL_SIZE,
            writeTimeoutNs / 1_000_000L,
            idleTimeoutMs,
            maxClients,
        )

        val probe = IpcSocketProbe(socketPath)
        // The accept loop of one bound listener; the socket watcher starts a new one when it binds the file again.
        fun launchAcceptLoop(listener: ServerSocketChannel): Job =
            scope.launch(transport) {
                while (isActive) {
                    try {
                        val sc = listener.accept()
                        if (probe.accept(sc) || !admit(sc, scope, transport)) continue
                        sc.configureBlocking(false)
                        val connId = java.util.UUID.randomUUID().toString()
                        val entry = ClientEntry(sc, connId).also { it.lastRequestAtMs = clock() }
                        auth?.let { authSessions[connId] = it.newSession() }
                        clients.add(entry)
                        log.debug("IPC: client connected id={} (total={})", connId, clients.size)
                        scope.launch(transport) {
                            val buf = ByteBuffer.allocate(MAX_REQUEST_BYTES)
                            // archive#1054 S2: bytes, decoded one whole line at a time. A read can end inside a multi-byte
                            // character; decoding each read on its own turned that character into U+FFFD.
                            val pending = java.io.ByteArrayOutputStream()
                            // gkrost/unidrive-cli#23: what the peer sent while a request of it ran (see watchPeer); it follows
                            // the lines already read, in order.
                            val readAhead = java.io.ByteArrayOutputStream()
                            try {
                                while (isActive) {
                                    // IPC authentication: a connection still unauthenticated after the timeout is closed.
                                    if (authSessions[connId]?.timedOut() == true) {
                                        log.debug("IPC: closing unauthenticated client id={} after the handshake timeout", connId)
                                        break
                                    }
                                    buf.clear()
                                    val n = sc.read(buf)
                                    if (n < 0) break  // client closed
                                    if (n == 0) {
                                        if (idleExpired(entry)) {
                                            log.info("IPC: closing idle connection id={} (no request for {} ms)", connId, idleTimeoutMs)
                                            break
                                        }
                                        // Sleep until the socket has data (archive#687); the timeout only re-runs the checks above.
                                        selector.awaitReadable(sc, recheckDelayMs(connId, entry))
                                        continue
                                    }
                                    buf.flip()
                                    val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                                    pending.write(bytes)
                                    // Split on \n (a byte no multi-byte UTF-8 sequence contains); dispatch each complete line.
                                    // The cap applies to each complete request line and to the line-less tail, not to the raw
                                    // read: two pipelined requests that a read boundary happens to split are each within the
                                    // cap and both get their reply (gkrost/unidrive-cli#264).
                                    var data = pending.toByteArray()
                                    var start = 0
                                    var tooLarge = false
                                    var idx = data.indexOfNewline(start)
                                    while (idx >= 0) {
                                        if (idx - start > MAX_REQUEST_BYTES) {
                                            log.warn("IPC: request too large ({} bytes), closing client", idx - start)
                                            tooLarge = true
                                            break
                                        }
                                        val line = String(data, start, idx - start, Charsets.UTF_8)
                                        start = idx + 1
                                        dispatchRequest(sc, connId, line, readAhead)
                                        if (!sc.isOpen) break // closed by the request (failed handshakes)
                                        entry.lastRequestAtMs = clock()
                                        if (readAhead.size() > 0) {
                                            data = data.copyOfRange(start, data.size) + readAhead.toByteArray()
                                            readAhead.reset()
                                            start = 0
                                        }
                                        idx = data.indexOfNewline(start)
                                    }
                                    // A line-less tail already over the cap can only grow into a longer request; drop it here
                                    // instead of letting pending grow without bound.
                                    if (!tooLarge && sc.isOpen && data.size - start > MAX_REQUEST_BYTES) {
                                        log.warn("IPC: request too large ({} bytes without a newline), closing client", data.size - start)
                                        tooLarge = true
                                    }
                                    if (tooLarge) {
                                        runCatching { sc.close() }
                                        break
                                    }
                                    pending.reset()
                                    pending.write(data, start, data.size - start)
                                }
                            } catch (e: IOException) {
                                log.debug("IPC: client reader closed: {}", e.message)
                            } finally {
                                clients.remove(entry)
                                authSessions.remove(connId)
                                runCatching { sc.close() }
                                log.debug("IPC: reader exited, client removed id={} (total={})", connId, clients.size)
                                closeListeners.forEach { it(connId) }
                            }
                        }
                    } catch (_: java.nio.channels.AsynchronousCloseException) {
                        break
                    } catch (e: IOException) {
                        if (isActive) log.warn("IPC: accept error", e)
                    }
                }
            }
        acceptJob = launchAcceptLoop(server)
        val watcher = IpcSocketWatcher(socketPath, socketWatchIntervalMs, { closing }, socketFileIdentity, probe) { installListener(it) { next -> launchAcceptLoop(next) } }
        socketWatcher = watcher
        if (socketWatchIntervalMs > 0) socketWatchJob = scope.launch(transport) { watcher.run() }

        // gkrost/unidrive-cli#926 S3: the loop only deals each event to the subscribers' queues; their writers do the waiting.
        val fanOut = IpcSyncFanOut(CoroutineScope(scope.coroutineContext + transport), ::writeToConnection)
        syncFanOut = fanOut
        broadcastJob = scope.launch(transport) { for (json in channel) fanOut.offer(json) }
    }

    fun close() {
        closing = true
        socketWatchJob?.cancel()
        channel.close()
        // With the watcher's rebind excluded: it never installs a new listener after this block.
        synchronized(rebindLock) {
            acceptJob?.cancel()
            broadcastJob?.cancel()
            runCatching { serverChannel?.close() }
        }
        for (entry in clients) {
            runCatching { entry.channel.close() }
            closeListeners.forEach { it(entry.id) }
        }
        clients.clear()
        // Readers waiting for data resume and end with their closed channels.
        readSelector?.close()
        readSelector = null
        syncSubscribers.clear()
        syncFanOut?.clear()
        pendingPostReply.clear()
        authSessions.clear()
        // archive#419: only remove the files of a socket THIS server bound. A start() that was refused
        // ("Another daemon is already listening") or failed to bind still gets close()d by its
        // caller's cleanup, and must not unlink the live daemon's socket. Cleared afterwards so a
        // second close() cannot delete a successor's socket.
        if (ownsSocketFiles) {
            ownsSocketFiles = false
            IpcSocketFiles.deleteSocketFiles(socketPath)
        }
        ownedTransport?.let { es ->
            es.shutdown()
            val finished =
                try {
                    es.awaitTermination(transportShutdownWaitMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    false
                }
            if (!finished) {
                // A wedged task (a refusal drain, a blocked write) would keep the pool's threads past the daemon's stop.
                log.warn("IPC: transport tasks still running {} ms after close; interrupting them", transportShutdownWaitMs)
                es.shutdownNow()
            }
            ownedTransport = null
        }
    }

    /** The socket watcher's swap (gkrost/unidrive-cli#794): the new listener replaces the old one, unless close() came first. */
    private fun installListener(
        next: ServerSocketChannel,
        startAcceptLoop: (ServerSocketChannel) -> Job,
    ): Boolean =
        synchronized(rebindLock) {
            if (closing) return@synchronized false
            val old = serverChannel
            acceptJob?.cancel()
            serverChannel = next
            acceptJob = startAcceptLoop(next)
            runCatching { old?.close() }
            true
        }

    private fun newTransportDispatcher(): kotlinx.coroutines.CoroutineDispatcher {
        val es = java.util.concurrent.Executors.newFixedThreadPool(TRANSPORT_POOL_SIZE, ipcIoThreadFactory())
        ownedTransport = es
        return es.asCoroutineDispatcher()
    }

    // Other IOExceptions propagate: never delete a socket we can't classify (gkrost/unidrive-cli#926 S5).
    private fun reclaimStaleSocket() {
        when (IpcStaleSocket.removeIfStale(socketPath)) {
            IpcStaleSocket.Outcome.LIVE -> error("Another daemon is already listening on $socketPath")
            IpcStaleSocket.Outcome.CHANGED -> error("The socket file $socketPath was replaced while it was checked")
            IpcStaleSocket.Outcome.UNKNOWN -> error("Could not determine whether the socket file $socketPath is stale")
            else -> Unit
        }
    }

    /**
     * Replay the current SyncState as initial state-dump events to a
     * single subscriber connection. Called from the `sync.subscribe`
     * post-reply hook (see scheduleAfterReply) so that subscribers see
     * context for the in-progress sync, but non-subscribers (e.g. the
     * FUSE co-daemon, request-reply clients) never receive these
     * unsolicited bytes on their socket.
     *
     * If `syncState` is null (no sync in progress at subscribe time),
     * this is a no-op. If the connection has closed between subscribe
     * and the post-reply hook firing, logs a WARN and returns —
     * surfacing the rare timing window for future operator debugging.
     *
     * Public for the same module-boundary reason as `registerSyncSubscriber`
     * (called from `SyncCommand` in `:app:cli`; `IpcServer` is in `:app:sync`).
     *
     * On partial-write failure, the subscriber receives a truncated state
     * dump and then nothing further from this method. The connection's
     * subsequent dead-client cleanup (via the broadcast loop's dropping
     * path or the per-client reader's IOException path) handles the
     * unhealthy connection. Subscribers should validate they receive
     * expected event shapes rather than assume the dump completed.
     */
    suspend fun flushStateDumpTo(connectionId: String) {
        val state = syncState ?: return
        val entry = clients.firstOrNull { it.id == connectionId } ?: run {
            log.warn(
                "IPC: flushStateDumpTo called for unknown connection id={}; " +
                    "client likely closed between sync.subscribe parse and post-reply hook",
                connectionId,
            )
            return
        }
        val ts =
            java.time.Instant
                .now()
                .toString()
        val lines = mutableListOf<String>()
        lines.add(buildStateDumpLine("sync_started", state.profile, ts))
        if (state.phase != null) {
            lines.add(
                buildStateDumpLine(
                    "scan_progress",
                    state.profile,
                    ts,
                    """"phase":${jsonString(state.phase)},"count":${state.scanCount}""",
                ),
            )
        }
        if (state.actionTotal > 0) {
            lines.add(
                buildStateDumpLine(
                    "action_count",
                    state.profile,
                    ts,
                    """"total":${state.actionTotal}""",
                ),
            )
        }
        if (state.actionIndex > 0 && state.lastAction != null) {
            lines.add(
                buildStateDumpLine(
                    "action_progress",
                    state.profile,
                    ts,
                    """"index":${state.actionIndex},"total":${state.actionTotal},"action":${jsonString(state.lastAction)},"path":${jsonString(state.lastPath ?: "")}""",
                ),
            )
        }
        for (line in lines) {
            val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
            try {
                entry.writeMutex.withLock {
                    writeNonBlocking(entry.channel, ByteBuffer.wrap(bytes))
                }
            } catch (e: IOException) {
                log.debug("IPC: failed to flush state dump to subscriber: {}", e.message)
                clients.remove(entry)
                runCatching { entry.channel.close() }
                syncSubscribers.remove(connectionId)
                return
            }
        }
    }

    private fun buildStateDumpLine(
        event: String,
        profile: String,
        timestamp: String,
        extra: String? = null,
    ): String {
        val sb = StringBuilder()
        sb.append("""{"event":${jsonString(event)},"profile":${jsonString(profile)}""")
        if (extra != null) {
            sb.append(",")
            sb.append(extra)
        }
        sb.append(""","timestamp":${jsonString(timestamp)}}""")
        return sb.toString()
    }

    // The exception text can hold local paths and provider error excerpts; only a full-scope connection (or the bare
    // server of the unit tests) gets it, URL-redacted. A read-scope connection gets the fixed reply, the log the detail.
    private fun handlerThrewReply(
        connId: String,
        verb: String,
        e: Exception,
    ): String {
        val head = """{"ok":false,"error":"handler_threw","verb":${jsonString(verb)}"""
        val full = auth == null || authSessions[connId]?.scope == IpcAuth.Scope.FULL
        return if (full) "$head,\"message\":${jsonString(UrlRedaction.redact(e.message ?: ""))}}" else "$head}"
    }

    private suspend fun dispatchRequest(
        client: SocketChannel,
        connId: String,
        line: String,
        readAhead: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream(),
    ) {
        // R8: defensive clear at the top — any stale entry from a prior request
        // that errored on this connection is removed before this request runs.
        // No-op on the happy path (the entry was already consumed at the end
        // of the prior successful request).
        pendingPostReply.remove(connId)

        // Every well-framed request line gets a reply, including the unanswerable
        // ones. A dropped request (no verb field, or no handler for the verb) is
        // indistinguishable from a lost frame: the client waits on a reply line
        // that never arrives and hangs. This was the daemon-startup hang — a
        // client polling `daemon.status` while the daemon was still registering
        // handlers got silence and waited forever. Handlers are now registered
        // before start() binds the socket, so a live connection always finds a
        // handler for a documented verb; `unknown_verb` then only answers verbs
        // no version of this daemon knows, which the client can surface instead
        // of hanging on.
        val verb = parseVerb(line)
        // IPC authentication (IpcAuth): before the handler lookup, the connection's session answers the
        // handshake itself and refuses what the connection may not call (yet); null = go on as before.
        val gate = auth?.gate(authSessions[connId], verb, line)
        val handler = verb?.let { handlers[it] }
        // Only a request that reaches its handler (the gate let it through) makes the connection idle-exempt.
        if (gate == null && handler != null && verb in idleExemptVerbs) clients.firstOrNull { it.channel === client }?.idleExempt = true
        // Counted before the handler runs, so a daemon.status reply counts itself.
        if (gate == null && handler != null) servedByVerb.computeIfAbsent(verb) { java.util.concurrent.atomic.LongAdder() }.increment()
        var handlerThrew = false
        val reply = when {
            gate != null -> gate.json
            verb == null -> {
                log.warn("IPC: request without a usable 'verb' (missing, not a string, repeated, or not one JSON object): {}", requestShape(line))
                """{"ok":false,"error":"missing_verb"}"""
            }
            handler == null -> {
                log.warn("IPC: no handler for verb {}", jsonString(verb.take(80)))
                """{"ok":false,"error":"unknown_verb"}"""
            }
            else -> try {
                watchingPeer(client, connId, readAhead, verb in cancelOnPeerGoneVerbs) {
                    // gkrost/unidrive-cli#74: a verb is user-started, so it runs on the user lane and may take the reserved slot.
                    kotlinx.coroutines.withContext(handlerDispatcher + org.krost.unidrive.http.Priority.Foreground) { handler(connId, line) }
                }
            } catch (_: IpcPeerGoneException) {
                // gkrost/unidrive-cli#760: nobody waits for this reply; the closed channel ends the reader, which frees the slot.
                log.debug("IPC: request '{}' of id={} cancelled, its client went away", verb, connId)
                closeChannel(client)
                return
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive() // #743-12: our own cancellation (daemon stop) propagates
                handlerThrew = true
                log.error("IPC: handler '$verb' threw", e)
                handlerThrewReply(connId, verb, e)
            }
        }
        val entry = clients.firstOrNull { it.channel === client } ?: return
        runCatching {
            entry.writeMutex.withLock {
                writeNonBlocking(entry.channel, ByteBuffer.wrap((ndjsonLine(reply) + "\n").toByteArray(Charsets.UTF_8)))
            }
        }.onFailure { e ->
            // A reply that could not be written (broken pipe, closed socket, write timeout) would
            // otherwise leave the connection open: the client waits on a reply line that never
            // comes, until its own timeout. Close it instead, so the client fails fast; the
            // connection's reader then does the usual cleanup (entry removal, close listeners).
            log.warn("IPC: reply write failed for id={}, closing the connection: {}", connId, e.message)
            closeChannel(entry.channel)
        }
        // The third failed handshake closes the connection (its reader then cleans up).
        if (gate?.close == true) runCatching { client.close() }
        // R7: post-reply hook fires ONLY on the successful-reply path.
        // If the handler threw, the scheduled action (if any) is discarded.
        val pending = pendingPostReply.remove(connId)
        if (!handlerThrew && pending != null) {
            runCatching {
                kotlinx.coroutines.withContext(handlerDispatcher) { pending() }
            }.onFailure { e ->
                currentCoroutineContext().ensureActive()
                log.warn("IPC: post-reply hook for connId={} threw: {}", connId, e.message)
            }
        }
    }

    // gkrost/unidrive-cli#23: runs a handler while watching its connection. The reader waits for the handler, so without this
    // a client that goes away during a long request (a download) is noticed only when the request ends. A handler that
    // has run for PEER_WATCH_AFTER_MS gets a watch that reads on: the bytes go to [readAhead], dispatched after the handler
    // in their order; an end of stream (or a reset) fires the close listeners at once, which end that connection's waits.
    // The reader fires them again when it ends; every listener is idempotent.
    private suspend fun <T> watchingPeer(
        client: SocketChannel,
        connId: String,
        readAhead: java.io.ByteArrayOutputStream,
        cancelOnPeerGone: Boolean,
        handler: suspend () -> T,
    ): T {
        val selector = readSelector ?: return handler()
        val peerGone = java.util.concurrent.atomic.AtomicBoolean(false)
        return coroutineScope {
            val work = async { handler() }
            val watch =
                launch {
                    delay(PEER_WATCH_AFTER_MS)
                    if (watchPeer(client, connId, readAhead, selector) && cancelOnPeerGone) {
                        peerGone.set(true)
                        work.cancel()
                    }
                }
            try {
                work.await()
            } catch (e: CancellationException) {
                if (peerGone.get()) throw IpcPeerGoneException(connId, e)
                throw e
            } finally {
                watch.cancelAndJoin()
            }
        }
    }

    private suspend fun watchPeer(
        client: SocketChannel,
        connId: String,
        readAhead: java.io.ByteArrayOutputStream,
        selector: IpcReadSelector,
    ): Boolean {
        val buf = ByteBuffer.allocate(PEER_WATCH_CHUNK)
        try {
            // A peer that sends more than one request's worth meanwhile is left in the socket for the reader.
            while (readAhead.size() < MAX_REQUEST_BYTES) {
                buf.clear()
                val n = client.read(buf)
                when {
                    n < 0 -> break
                    n == 0 -> selector.awaitReadable(client)
                    else -> readAhead.write(buf.array(), 0, n)
                }
            }
            if (readAhead.size() >= MAX_REQUEST_BYTES) return false
        } catch (e: IOException) {
            log.debug("IPC: connection id={} failed while a request ran: {}", connId, e.message)
        }
        log.debug("IPC: client id={} went away while a request ran", connId)
        closeListeners.forEach { it(connId) }
        return true
    }

    // Closed for being idle: no request line for idleTimeoutMs (0 = never), and neither a sync
    // subscriber nor a connection that used an idle-exempt verb. Time spent waiting for a reply does
    // not count: the reader only checks between requests.
    private fun idleExpired(entry: ClientEntry): Boolean =
        idleTimeoutMs > 0 &&
            clock() - entry.lastRequestAtMs >= idleTimeoutMs &&
            !permanentlyIdleExempt(entry) && idleHolds.none { it(entry.id) }

    private fun permanentlyIdleExempt(entry: ClientEntry): Boolean =
        entry.id in syncSubscribers || (entry.idleExempt && scopeOf(entry.id) != IpcAuth.Scope.READ.wire)

    // gkrost/unidrive-cli#926 S4: false when the connection was refused; a connection still in its handshake may close an older one.
    private fun admit(
        sc: SocketChannel,
        scope: CoroutineScope,
        transport: CoroutineDispatcher,
    ): Boolean =
        when (val a = IpcClientSlots.admission(clients.map { it.id }, { id -> authSessions[id]?.let { it.scope == null } == true }, maxClients)) {
            IpcClientSlots.Admission.Admit -> true
            IpcClientSlots.Admission.Refuse -> false.also {
                log.warn("IPC: max clients ({}) reached, refusing connection", maxClients)
                refuse(sc, scope, transport)
            }
            is IpcClientSlots.Admission.EvictThenAdmit -> true.also {
                log.info("IPC: {} connections wait for their handshake; closing the oldest id={}", IpcClientSlots.MAX_PENDING_HANDSHAKES, a.oldest)
                clients.firstOrNull { it.id == a.oldest }?.let { clients.remove(it); closeChannel(it.channel) }
            }
        }

    // Closes a connection from outside its reader and wakes the reader, which would otherwise sleep
    // until data arrives (closing a channel does not interrupt the selector).
    private fun closeChannel(sc: SocketChannel) {
        runCatching { sc.close() }
        readSelector?.wake(sc)
    }

    // How long the reader may sleep before it re-runs its timeout checks, or null when none applies
    // (data, a close or a cancellation then wake it). The handshake timeout has no accessor for its
    // remaining time, so an unauthenticated connection is rechecked often; the idle timeout is
    // rechecked at most every IDLE_RECHECK_MS (the clock is injectable, so the remaining time alone
    // is not a safe sleep).
    private fun recheckDelayMs(
        connId: String,
        entry: ClientEntry,
    ): Long? {
        val handshake = if (authSessions[connId]?.let { it.scope == null } == true) HANDSHAKE_RECHECK_MS else null
        val idle =
            when {
                idleTimeoutMs <= 0 || permanentlyIdleExempt(entry) -> null
                idleHolds.any { it(entry.id) } -> IDLE_RECHECK_MS // a hold (a subscription, a handle) can end between requests
                else -> (idleTimeoutMs - (clock() - entry.lastRequestAtMs)).coerceIn(1L, IDLE_RECHECK_MS)
            }
        return if (handshake != null && idle != null) minOf(handshake, idle) else handshake ?: idle
    }

    // A connection over maxClients: best effort, one line that says why, then close. A fresh
    // connection's send buffer is empty, so the line goes out at once; the short deadline only
    // bounds a peer that reads nothing. Closing a socket that still has unread input can reset the
    // connection, and a reset discards a reason the client has not read yet (Windows does it for any
    // request that arrives after the close, however soon). So the write side is shut down, and the
    // read side is kept open and drained until the peer's end of stream or [refusalLingerMs], whichever
    // comes first. That wait runs on a coroutine of its own: the accept loop never sleeps for it.
    // At most MAX_LINGERING_REFUSALS wait at once; past that a refusal drains what has arrived and closes.
    // gkrost/unidrive-cli#456: the wait for writability is a selector wait, not a sleep-poll — [writeWhileWritable].

    private fun refuse(
        sc: SocketChannel,
        scope: CoroutineScope,
        transport: CoroutineDispatcher,
    ) {
        try {
            sc.configureBlocking(false)
            val line = ByteBuffer.wrap(TOO_MANY_CLIENTS_LINE.toByteArray(Charsets.UTF_8))
            writeWhileWritable(sc, line, System.nanoTime() + REFUSAL_WRITE_TIMEOUT_NS)
            sc.shutdownOutput()
        } catch (_: Exception) {
            runCatching { sc.close() }
            return
        }
        if (lingeringRefusals.incrementAndGet() <= MAX_LINGERING_REFUSALS) {
            try {
                val job = scope.launch(transport) { IpcRefusalDrain.untilEofOrDeadline(sc, refusalLingerMs, MAX_REQUEST_BYTES) }
                // Runs on every end of the job, including a cancellation before it started (server stop).
                job.invokeOnCompletion {
                    lingeringRefusals.decrementAndGet()
                    runCatching { sc.close() }
                }
                return
            } catch (_: Exception) {
                // Could not schedule: fall through to the immediate close.
            }
        }
        lingeringRefusals.decrementAndGet()
        runCatching { IpcRefusalDrain.available(sc, MAX_REQUEST_BYTES) }
        runCatching { sc.close() }
    }

    private suspend fun writeNonBlocking(
        client: SocketChannel,
        buf: ByteBuffer,
    ) = writeAllNonBlocking(client, buf, writeTimeoutNs)

    companion object {
        // Connection cap (UNIDRIVE_IPC_MAX_CLIENTS, docs/env-vars.md): 32 by default; unset,
        // unparseable or <= 0 gives the default, other values are clamped to 4..256.
        internal const val DEFAULT_MAX_CLIENTS = 32
        private const val MIN_MAX_CLIENTS = 4
        private const val MAX_MAX_CLIENTS = 256

        // Pure function for testing the parse + clamp without touching System.getenv.
        internal fun parseMaxClients(raw: String?): Int {
            val n = raw?.trim()?.toIntOrNull() ?: return DEFAULT_MAX_CLIENTS
            return if (n <= 0) DEFAULT_MAX_CLIENTS else n.coerceIn(MIN_MAX_CLIENTS, MAX_MAX_CLIENTS)
        }

        internal fun readMaxClientsFromEnv(): Int = parseMaxClients(System.getenv("UNIDRIVE_IPC_MAX_CLIENTS"))

        // What a connection over the cap reads before it is closed (contract corpus:
        // ipc-contract/connection/too_many_clients.ndjson).
        private const val TOO_MANY_CLIENTS_LINE = "{\"ok\":false,\"error\":\"too_many_clients\"}\n"
        private const val REFUSAL_WRITE_TIMEOUT_NS = 200_000_000L

        // How long a refused connection is kept open, reading and dropping its input, for the peer's end of
        // stream (see refuse), and how many may wait at once.
        internal const val REFUSAL_LINGER_MS = 200L
        private const val MAX_LINGERING_REFUSALS = 64

        // Idle timeout (UNIDRIVE_IPC_IDLE_TIMEOUT_MS, docs/env-vars.md): 30 min by default, 0 = off,
        // other values clamped to 1 min..24 h.
        internal const val DEFAULT_IDLE_TIMEOUT_MS = 30 * 60 * 1000L
        private const val MIN_IDLE_TIMEOUT_MS = 60 * 1000L
        private const val MAX_IDLE_TIMEOUT_MS = 24 * 60 * 60 * 1000L

        // Pure function for testing the parse + clamp without touching System.getenv.
        internal fun parseIdleTimeoutMs(raw: String?): Long {
            val ms = raw?.trim()?.toLongOrNull() ?: return DEFAULT_IDLE_TIMEOUT_MS
            return when {
                ms == 0L -> 0L
                ms < 0L -> DEFAULT_IDLE_TIMEOUT_MS
                else -> ms.coerceIn(MIN_IDLE_TIMEOUT_MS, MAX_IDLE_TIMEOUT_MS)
            }
        }

        internal fun readIdleTimeoutFromEnv(): Long = parseIdleTimeoutMs(System.getenv("UNIDRIVE_IPC_IDLE_TIMEOUT_MS"))

        // gkrost/unidrive-cli#794: how often a running server checks that its socket file is still there.
        const val SOCKET_WATCH_INTERVAL_MS = 5_000L
        private const val MAX_REQUEST_BYTES = 64 * 1024
        private const val TRANSPORT_POOL_SIZE = 4
        internal const val TRANSPORT_SHUTDOWN_WAIT_MS = 5_000L

        // gkrost/unidrive-cli#23: a request that runs this long has its connection watched for the client going away.
        private const val PEER_WATCH_AFTER_MS = 100L
        private const val PEER_WATCH_CHUNK = 4096
        private const val IDLE_RECHECK_MS = 1_000L
        private const val HANDSHAKE_RECHECK_MS = 250L

        /** [s] as one JSON string literal, quotes included: the one NDJSON escaper, [ndjsonString]; see there. */
        fun jsonString(s: String): String = ndjsonString(s)

        // No request nests deeper than a few levels. The walk below refuses deeper lines before
        // kotlinx sees them: its tree reader recurses once per array level.
        private const val MAX_REQUEST_DEPTH = 64

        /**
         * The verb of a request line: the top-level string member `verb` of the request object.
         * Anything else (not JSON, not an object, no `verb`, a `verb` that is not a string,
         * `verb` named twice, nesting deeper than [MAX_REQUEST_DEPTH]) yields null, which the
         * caller answers with `missing_verb`. A second `verb` is refused rather than resolved:
         * kotlinx keeps the last one, the hydration handler's own parse the first, and both must
         * see the same verb.
         */
        internal fun parseVerb(line: String): String? {
            if (countTopLevelMembers(line, "verb") != 1) return null
            val request =
                try {
                    Json.parseToJsonElement(line)
                } catch (_: Exception) {
                    return null
                }
            val verb = (request as? JsonObject)?.get("verb") as? JsonPrimitive ?: return null
            return if (verb.isString) verb.content else null
        }

        /**
         * What a log may say about an unanswerable request: its length and the top-level member names. The line
         * itself is never logged — an authenticated request can carry a password (`auth.login`), and the value of
         * any member would put it in the log (issue#83 E).
         */
        internal fun requestShape(line: String): String =
            runCatching { (Json.parseToJsonElement(line) as? JsonObject)?.keys?.joinToString(", ") { jsonString(it) } }
                .getOrNull()
                ?.let { "length ${line.length}, members: ${it.take(200)}" }
                ?: "length ${line.length}, not one JSON object"

        // How many members of the top-level object are named [name], or -1 when the line nests
        // deeper than MAX_REQUEST_DEPTH or a string does not end. Only a count of exactly 1 on a
        // line kotlinx then parses as one object is used, so the walk can stay simple: depth
        // outside strings, and a string at depth 1 right after `{` or `,` is a member name
        // (decoded only when it carries an escape).
        private fun countTopLevelMembers(line: String, name: String): Int {
            var count = 0
            var depth = 0
            var atName = false
            var i = 0
            while (i < line.length) {
                when (val c = line[i]) {
                    '{', '[' -> {
                        if (++depth > MAX_REQUEST_DEPTH) return -1
                        atName = c == '{' && depth == 1
                    }
                    '}', ']' -> depth--
                    ',' -> atName = depth == 1
                    '"' -> {
                        val end = closingQuote(line, i)
                        if (end < 0) return -1
                        if (atName && stringIs(line, i, end, name)) count++
                        atName = false
                        i = end
                    }
                }
                i++
            }
            return count
        }

        // Index of the quote that closes the string opening at [open], or -1 when it does not close.
        private fun closingQuote(line: String, open: Int): Int {
            var i = open + 1
            while (i < line.length) {
                when (line[i]) {
                    '\\' -> i += 2
                    '"' -> return i
                    else -> i++
                }
            }
            return -1
        }

        // Whether the string literal between the quotes at [open] and [close] reads [text].
        private fun stringIs(line: String, open: Int, close: Int, text: String): Boolean {
            if ((open + 1 until close).none { line[it] == '\\' }) {
                return close - open - 1 == text.length && line.regionMatches(open + 1, text, 0, text.length)
            }
            val decoded = runCatching { Json.parseToJsonElement(line.substring(open, close + 1)).jsonPrimitive.content }
            return decoded.getOrNull() == text
        }

        // Pure-function overload for testing the parse + clamp logic without
        // touching System.getenv. Production path delegates here.
        internal fun parseWriteTimeoutMs(raw: String?): Long {
            if (raw == null) return 5_000L
            return raw.toLongOrNull()?.takeIf { it in 100L..600_000L } ?: 5_000L
        }

        internal fun readWriteTimeoutFromEnv(): Long =
            parseWriteTimeoutMs(System.getenv("UNIDRIVE_IPC_WRITE_TIMEOUT_MS"))

        private fun ipcIoThreadFactory(): java.util.concurrent.ThreadFactory {
            val counter = java.util.concurrent.atomic.AtomicInteger(0)
            return java.util.concurrent.ThreadFactory { r ->
                Thread(r, "ipc-io-${counter.incrementAndGet()}").apply {
                    isDaemon = true
                }
            }
        }

        // Where the socket lies and what it is called: IpcSocketPaths (this file's size is ratcheted, gkrost/unidrive-cli#534).
        fun socketBaseName(profileName: String): String = IpcSocketPaths.socketBaseName(profileName)

        /** The one canonical on-disk name for a profile (archive#135): see [IpcSocketPaths.canonicalDiskName]. */
        fun canonicalDiskName(profileName: String): String = IpcSocketPaths.canonicalDiskName(profileName)

        internal fun canonicalDiskName(profileName: String, socketDirectory: Path): String =
            IpcSocketPaths.canonicalDiskName(profileName, socketDirectory)

        /** Removes the socket file and its `.meta` sibling of a daemon that is gone (archive#419): see [IpcSocketFiles.removeStaleSocketFiles]. */
        fun removeStaleSocketFiles(socketPath: Path) = IpcSocketFiles.removeStaleSocketFiles(socketPath)

        fun defaultSocketPath(profileName: String): Path = IpcSocketPaths.defaultSocketPath(profileName)

        internal fun defaultSocketPath(
            profileName: String,
            tmpDir: Path,
            windows: Boolean,
            override: Path? = IpcSocketDir.overrideDirectory(),
        ): Path = IpcSocketPaths.defaultSocketPath(profileName, tmpDir, windows, override)

        internal fun defaultSocketDirectory(): Path = IpcSocketPaths.defaultSocketDirectory()

        internal fun diskNameDirectory(): Path = IpcSocketPaths.diskNameDirectory()
    }
}

// gkrost/unidrive-cli#760: a request of a cancel-on-peer-gone verb was cancelled because its client went away.
private class IpcPeerGoneException(connId: String, cause: Throwable) : IOException("client $connId went away", cause)

// The index of the first '\n' byte at or after [from], or -1 (archive#1054 S2).
private fun ByteArray.indexOfNewline(from: Int): Int {
    for (i in from until size) if (this[i] == '\n'.code.toByte()) return i
    return -1
}
