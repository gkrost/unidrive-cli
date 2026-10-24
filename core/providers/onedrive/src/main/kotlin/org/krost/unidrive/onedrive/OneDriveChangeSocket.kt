package org.krost.unidrive.onedrive

import io.socket.client.IO
import io.socket.client.Socket
import org.slf4j.LoggerFactory
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The drive's socket.io endpoint as `GET /me/drive/root/subscriptions/socketIo` answers it. [notificationUrl] carries
 * `token` and `applicationId` in its query: it is a credential. [toString] shows a short hash of it, never the URL.
 */
internal class SocketIoEndpoint(
    val notificationUrl: String,
    /** Epoch ms of the subscription's `expirationDateTime`, null when the reply has none that parses. */
    val expiresAtMs: Long?,
) {
    /** A short, stable tag of the URL for the log: the first 8 hex digits of its SHA-256. */
    val tag: String get() = shortHash(notificationUrl)

    override fun toString(): String = "SocketIoEndpoint(#$tag, expiresAtMs=$expiresAtMs)"

    companion object {
        fun shortHash(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(4).joinToString("") { "%02x".format(it) }
    }
}

/**
 * The slice of socket.io's [Socket] that [OneDriveChangeChannel] uses. A seam so the rotation and back-off are
 * testable without a network (a fake socket the test drives by hand).
 */
internal interface ChangeSocket {
    /** Register [listener] for [event]; it receives the event's arguments. */
    fun on(
        event: String,
        listener: (List<Any?>) -> Unit,
    )

    fun connect()

    /** Disconnect and drop every listener. Never throws. */
    fun close()
}

internal fun interface ChangeSocketFactory {
    /** A socket for [url], exactly as Graph returned it. */
    fun create(url: String): ChangeSocket
}

/**
 * Production factory: websocket transport only (Graph does not support polling), a new manager per endpoint, and the
 * library's own reconnect off: a socket of an expired subscription stays "connected" but silent, and one the server
 * closed is not reconnected by socket.io, so [OneDriveChangeChannel] fetches a fresh endpoint itself every time.
 */
internal object SocketIoChangeSocketFactory : ChangeSocketFactory {
    override fun create(url: String): ChangeSocket {
        val options =
            IO.Options.builder()
                .setTransports(arrayOf("websocket"))
                .setForceNew(true)
                .setReconnection(false)
                .build()
        return SocketIoChangeSocket(IO.socket(URI.create(url), options))
    }
}

private class SocketIoChangeSocket(
    private val socket: Socket,
) : ChangeSocket {
    private val log = LoggerFactory.getLogger(SocketIoChangeSocket::class.java)

    override fun on(
        event: String,
        listener: (List<Any?>) -> Unit,
    ) {
        socket.on(event) { args -> listener(args.toList()) }
    }

    override fun connect() {
        socket.connect()
    }

    @Suppress("TooGenericExceptionCaught") // close() never throws: a failure here only means the socket is gone already
    override fun close() {
        try {
            socket.disconnect()
            socket.off()
        } catch (e: Exception) {
            log.debug("closing the OneDrive notifications socket raised (ignored): {}", e.javaClass.simpleName)
        }
    }
}

/**
 * Where [OneDriveChangeChannel] runs its work (the endpoint GET is a suspend HTTP round-trip that must not block
 * socket.io's event thread) and its timers. One thread, so the channel's state needs no lock. A seam so tests drive
 * time by hand.
 */
internal interface ChangeChannelScheduler {
    /** Run [task] on the channel's thread as soon as possible. */
    fun execute(task: Runnable)

    /** Run [task] on the channel's thread after [delayMs]. */
    fun schedule(
        delayMs: Long,
        task: Runnable,
    )

    /** Drop pending work and release the thread. Idempotent. */
    fun shutdown()
}

/** One daemon thread, so a channel that is left open cannot keep the JVM alive. */
internal class ThreadChangeChannelScheduler : ChangeChannelScheduler {
    private val executor =
        ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "unidrive-onedrive-notifications").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }

    override fun execute(task: Runnable) {
        try {
            executor.execute(task)
        } catch (_: RejectedExecutionException) {
            // shut down by stop(); nothing left to do
        }
    }

    override fun schedule(
        delayMs: Long,
        task: Runnable,
    ) {
        try {
            executor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            // shut down by stop(); nothing left to do
        }
    }

    override fun shutdown() {
        executor.shutdownNow()
    }
}
