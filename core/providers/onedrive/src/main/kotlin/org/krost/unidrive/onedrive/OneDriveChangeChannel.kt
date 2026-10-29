package org.krost.unidrive.onedrive

import io.socket.client.Socket
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.MdcSnapshot
import org.krost.unidrive.RemoteChangeChannelStatus
import org.krost.unidrive.http.UrlRedaction
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * What a OneDrive change channel has done since the provider was created; outlives each [OneDriveChangeChannel]
 * (sign-out and sign-in make a new one), so `daemon.status` counts across them.
 */
internal class ChangeChannelCounters {
    val hints = AtomicLong(0)
    val rotations = AtomicLong(0)

    @Volatile var lastHintAtMs: Long? = null
}

/**
 * The OneDrive remote-change hint: Graph's socket.io endpoint for the drive (`GET /me/drive/root/subscriptions/socketIo`,
 * archive#111). Every `notification` event is a "something changed, walk the delta" hint; the payload is not parsed.
 *
 * ## Rotation (spike E2, 2026-10-21)
 *
 * The subscription behind the endpoint expires 30 minutes after it was created and cannot be renewed (PATCH answers
 * 404); a GET before the expiry returns the same URL and expiry, a GET after it a new URL. The old socket goes silent
 * at the expiry but stays "connected" until the server closes it much later. So the channel reads `expirationDateTime`
 * and, [ROTATION_GRACE_MS] after it, GETs a new endpoint, closes the old socket and connects the new URL. A server-side
 * disconnect or a connect error does the same (GET, then connect) after a back-off of 1 s doubling to 60 s; socket.io's
 * own reconnect is off ([SocketIoChangeSocketFactory]). A failing GET backs off the same way. The back-off starts
 * again from 1 s only after a connection that held for [STABLE_CONNECTION_MS].
 *
 * Hints: every notification, every rotation at an expiry (a change made in the seconds before it would have been
 * notified on the old endpoint) and every (re)connect (what happened while the channel was down). The receiver
 * debounces; the delta walk is the authority and the periodic poll stays the safety net.
 *
 * ## The endpoint is a credential
 *
 * Its query carries `token` and `applicationId`. It is never logged (the log names it by [SocketIoEndpoint.tag], a
 * short hash), never put in a status, and error texts pass through [UrlRedaction] before they reach the log.
 *
 * All work runs on [scheduler]'s single thread; socket.io's listeners only post to it. [stop] is final: the provider
 * makes a new channel to open it again.
 */
internal class OneDriveChangeChannel(
    private val fetchEndpoint: suspend () -> SocketIoEndpoint,
    private val onHint: () -> Unit,
    private val counters: ChangeChannelCounters = ChangeChannelCounters(),
    private val socketFactory: ChangeSocketFactory = SocketIoChangeSocketFactory,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scheduler: ChangeChannelScheduler = ThreadChangeChannelScheduler(),
) {
    private val log = LoggerFactory.getLogger(OneDriveChangeChannel::class.java)

    // gkrost/unidrive-cli#225: the MDC (profile, build) of the daemon that made the channel. Every task the channel hands to
    // its scheduler runs around it, so the channel's lines name the daemon in the log two daemons share.
    private val logContext = MdcSnapshot.capture()

    @Volatile private var running = false

    @Volatile private var stopped = false

    @Volatile private var state = RemoteChangeChannelStatus.State.OFF

    @Volatile private var expiresAtMs: Long? = null

    // Changed on the scheduler's thread only (the socket under this object's lock, which stop() takes too).
    @Volatile private var socket: ChangeSocket? = null

    @Volatile private var generation = 0L
    private var endpointsFetched = 0L
    private var failures = 0
    private var connectedSinceMs: Long? = null
    private var outageWarned = false

    /** Open the channel: fetch the endpoint and connect, in the background. Idempotent; a stopped channel stays stopped. */
    @Synchronized
    fun start() {
        if (running || stopped) return
        running = true
        state = RemoteChangeChannelStatus.State.CONNECTING
        scheduler.execute(logContext.wrap { openEndpoint(generation) })
    }

    /** Close the socket and drop every timer. Idempotent and final. */
    @Synchronized
    fun stop() {
        if (stopped) return
        stopped = true
        running = false
        state = RemoteChangeChannelStatus.State.OFF
        expiresAtMs = null
        scheduler.shutdown()
        // The scheduler no longer runs anything, so the socket can be closed from here.
        socket?.close()
        socket = null
    }

    fun status(): RemoteChangeChannelStatus =
        RemoteChangeChannelStatus(
            state = state,
            lastHintAtMs = counters.lastHintAtMs,
            hints = counters.hints.get(),
            expiresAtMs = if (state == RemoteChangeChannelStatus.State.OFF) null else expiresAtMs,
            rotations = counters.rotations.get(),
        )

    // ── on the scheduler's thread ──────────────────────────────────────────────

    /** GET an endpoint and connect to it, unless [expected] is stale (a newer rotation or a stop came first). */
    @Suppress("TooGenericExceptionCaught") // any failure of the GET is one more attempt in the back-off; the poll covers it
    private fun openEndpoint(expected: Long) {
        if (!running || expected != generation) return
        val endpoint =
            try {
                runBlocking { fetchEndpoint() }
            } catch (e: Exception) {
                if (!running) return
                onFailure("the endpoint request failed", e)
                return
            }
        if (!running || expected != generation) return
        val now = clock()
        val expiry = endpoint.expiresAtMs
        if (expiry != null && expiry <= now) {
            // The server has not rotated yet (or the clocks disagree): asking again at once would loop.
            onFailure("the endpoint answered has already expired", null)
            return
        }
        if (endpointsFetched++ > 0) counters.rotations.incrementAndGet()
        val gen = ++generation
        var next: ChangeSocket? = null
        try {
            val created = socketFactory.create(endpoint.notificationUrl)
            next = created
            created.on(Socket.EVENT_CONNECT) { post(created) { onConnected() } }
            created.on(Socket.EVENT_DISCONNECT) { args -> post(created) { onDisconnected(args.firstOrNull()) } }
            created.on(Socket.EVENT_CONNECT_ERROR) { args -> post(created) { onFailure("connect_error", args.firstOrNull()) } }
            created.on(NOTIFICATION_EVENT) { args -> post(created) { onNotification(args.firstOrNull()) } }
            // Swapped under the lock stop() takes, so a stop() during the GET cannot leave this socket open.
            synchronized(this) {
                if (!running) {
                    created.close()
                    return
                }
                socket?.close()
                socket = created
                expiresAtMs = expiry
            }
            val rotateAtMs = (expiry ?: (now + DEFAULT_LIFETIME_MS)) + ROTATION_GRACE_MS
            scheduler.schedule(rotateAtMs - now, logContext.wrap { if (running && gen == generation) rotateAtExpiry() })
            log.debug("OneDrive notifications: connecting to endpoint #{} (expires in {} s)", endpoint.tag, expiry?.let { (it - now) / 1000 })
            created.connect()
        } catch (e: Exception) {
            if (socket !== next) next?.close()
            if (running) onFailure("the socket could not connect", e)
        }
    }

    private fun rotateAtExpiry() {
        log.debug("OneDrive notifications: the endpoint expired, fetching a new one")
        state = RemoteChangeChannelStatus.State.CONNECTING
        hint()
        openEndpoint(generation)
    }

    private fun onConnected() {
        state = RemoteChangeChannelStatus.State.CONNECTED
        connectedSinceMs = clock()
        if (outageWarned) {
            log.info("OneDrive notifications connected again after {} failed attempts", failures)
        } else {
            log.debug("OneDrive notifications connected")
        }
        outageWarned = false
        hint()
    }

    private fun onDisconnected(reason: Any?) {
        // "io client disconnect" is our own close(), which never reaches here for the current socket.
        onFailure("the server disconnected", reason)
    }

    private fun onNotification(payload: Any?) {
        log.debug("OneDrive notification ({} chars)", payload?.toString()?.length ?: 0)
        hint()
    }

    /** Drop the current socket and try again (GET, then connect) after the back-off. */
    private fun onFailure(
        what: String,
        cause: Any?,
    ) {
        val gen = ++generation
        socket?.close()
        socket = null
        state = RemoteChangeChannelStatus.State.CONNECTING
        expiresAtMs = null
        val since = connectedSinceMs
        if (since != null && clock() - since >= STABLE_CONNECTION_MS) failures = 0
        connectedSinceMs = null
        failures++
        val delayMs = backoffMs(failures)
        if (!outageWarned) {
            outageWarned = true
            log.warn("OneDrive notifications: {} ({}); retrying in {} s, the poll covers changes meanwhile", what, describe(cause), delayMs / 1000)
        } else {
            log.debug("OneDrive notifications: {} (attempt {}, {}); retrying in {} s", what, failures, describe(cause), delayMs / 1000)
        }
        scheduler.schedule(delayMs, logContext.wrap { openEndpoint(gen) })
    }

    @Suppress("TooGenericExceptionCaught") // the callback is the engine's; whatever it throws must not stop the channel
    private fun hint() {
        counters.hints.incrementAndGet()
        counters.lastHintAtMs = clock()
        try {
            onHint()
        } catch (e: Exception) {
            // The callback is the engine's; a misbehaving one must not stop the channel.
            log.warn("Remote-change callback threw", e)
        }
    }

    /** Run [task] on the scheduler for an event of [from], dropped when [from] is no longer the current socket. */
    private fun post(
        from: ChangeSocket,
        task: () -> Unit,
    ) {
        scheduler.execute(logContext.wrap { if (running && socket === from) task() })
    }

    companion object {
        /** The socket.io event Graph sends a change notification as. */
        internal const val NOTIFICATION_EVENT = "notification"

        /** How long after `expirationDateTime` the channel rotates (spike: a GET 5 s after the expiry gets the new URL). */
        internal const val ROTATION_GRACE_MS = 5_000L

        /** The lifetime assumed when the reply carries no `expirationDateTime` (what Graph gave in the spike). */
        internal const val DEFAULT_LIFETIME_MS = 30L * 60_000L

        internal const val BACKOFF_INITIAL_MS = 1_000L
        internal const val BACKOFF_MAX_MS = 60_000L

        /** A connection that held this long resets the back-off to [BACKOFF_INITIAL_MS]. */
        internal const val STABLE_CONNECTION_MS = 60_000L

        /** 1 s, 2 s, 4 s, ... capped at 60 s, for the [failures]-th failure in a row (1-based). */
        internal fun backoffMs(failures: Int): Long = minOf(BACKOFF_INITIAL_MS shl (failures - 1).coerceIn(0, 6), BACKOFF_MAX_MS)

        /** One line for the log: exception class and redacted message, or the redacted reason text. */
        internal fun describe(cause: Any?): String {
            val text =
                when (cause) {
                    null -> "no detail"
                    is Throwable -> cause.message?.let { "${cause.javaClass.simpleName}: $it" } ?: cause.javaClass.simpleName
                    else -> cause.toString()
                }
            return UrlRedaction.redact(text).take(300)
        }
    }
}
