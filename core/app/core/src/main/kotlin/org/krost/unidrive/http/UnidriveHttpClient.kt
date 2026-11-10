package org.krost.unidrive.http

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Connection
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Protocol
import java.io.EOFException
import java.io.IOException
import java.net.BindException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The one place that names the HTTP engine of the data plane (gkrost/unidrive-cli#566). Every Ktor client of the app (the S3,
 * OneDrive, Dropbox and Internxt providers, their sign-in clients) is built here, so that adding another engine jar to the class
 * path cannot change the engine of a client that named none (Ktor picks the first engine it finds).
 *
 * **Why OkHttp, not CIO.** CIO sends every request that is not a plain GET/HEAD, and every request under `HttpTimeout`, over a
 * connection of its own and closes it afterwards (`requiresDedicatedConnection`): 651 requests on 651 connections for the 323-file
 * transfer, a TCP and TLS handshake each (CIO's own TLS implementation, no session resumption). OkHttp keeps a connection pool, and
 * it maps all three timeouts of `HttpTimeout`: connect to the connect timeout, the socket (read-idle) timeout to its read and write
 * timeouts, the request timeout stays with the Ktor plugin. The JDK client behind `ktor-client-java` has no socket idle timeout,
 * which would have disabled the stall detection of downloads. The numbers are in the pull request.
 *
 * What the factory fixes, because the defaults would change behaviour:
 * - HTTP/1.1 only. OkHttp would negotiate h2 over TLS; h2 is gkrost/unidrive-cli#577 and needs its own measurement.
 * - One connection pool and one dispatcher for all calls of the client. Ktor derives an OkHttp client for every distinct
 *   timeout setting (a download is sized from its declared size) and gives each its own dispatcher unless told otherwise; the
 *   default dispatcher admits 5 calls per host, the retry budgets admit up to 16.
 * - The dispatcher's threads are daemons: an idle one must not keep a one-shot CLI process alive.
 * - Idle connections are dropped after 15 s, a conservative guess below the idle limits servers use (not measured per vendor),
 *   so that a request seldom meets a connection the server closed.
 *
 * **A pooled connection the server closed (stale).** OkHttp has no reader on an idle connection, so it learns that the server
 * closed one only when it uses it (it probes a connection only after 10 s idle, and only for requests other than GET). The
 * request then fails with an EOF on zero bytes ("unexpected end of stream", `newline not found: limit=0`) or a reset, which CIO (a
 * fresh socket per request) never showed. OkHttp's own recovery, `retryOnConnectionFailure`, cannot stay on: it also re-sends a
 * body whose write was committed and whose answer was lost (see below). What the factory does instead retries only where the
 * request provably or harmlessly did not take effect:
 * - Before a request goes out on a connection that has been idle for [PROBE_AFTER_IDLE_NANOS] or more, a network interceptor
 *   probes it (a 1 ms read, the check OkHttp itself makes). A connection found closed is dropped, together with every other idle
 *   one (the server closes them together), and the request is sent again on a fresh connection: nothing of it was written, so
 *   this is safe for every method, a PUT with a body included.
 * - A GET or HEAD that fails before its answer on a connection-level error (EOF, reset; not a timeout, not a refused connect, not
 *   a cancellation) is sent once more on a fresh connection, idle connections dropped: reads have no effect to duplicate.
 * - Nothing else is retried: a body that was written and whose answer was lost surfaces to the providers' ladders as before.
 */
public fun unidriveHttpClient(
    tls: ClientTls? = null,
    configure: HttpClientConfig<*>.() -> Unit,
): HttpClient {
    val pool = ConnectionPool(MAX_IDLE_CONNECTIONS, IDLE_CONNECTION_SECONDS, TimeUnit.SECONDS)
    val lastUse = Collections.synchronizedMap(WeakHashMap<Connection, Long>())
    val dispatcher =
        Dispatcher(
            ThreadPoolExecutor(0, Int.MAX_VALUE, 60, TimeUnit.SECONDS, SynchronousQueue()) { runnable ->
                Thread(runnable, "unidrive-http").apply { isDaemon = true }
            },
        ).apply {
            maxRequests = MAX_CALLS
            maxRequestsPerHost = MAX_CALLS
        }
    return HttpClient(OkHttp) {
        configure()
        engine {
            config {
                // No transport-level retries: OkHttp's default silently re-sends a request whose committed write
                // lost the answer to a closed keep-alive connection, which can duplicate a create or turn a
                // successful conditional write into a conflict. The providers' retry ladders and the reconciliation
                // own every retry; a lost answer surfaces to them as the failure it was.
                retryOnConnectionFailure(false)
                // The stale-connection recovery described above, outermost so that it sees what the others raise.
                addInterceptor { chain ->
                    val request = chain.request()
                    try {
                        chain.proceed(request)
                    } catch (e: IOException) {
                        if (!e.canBeSentAgain(request.method, chain.call().isCanceled())) throw e
                        pool.evictAll()
                        chain.proceed(request)
                    }
                }
                // The object's stored bytes, always: OkHttp's `Accept-Encoding: gzip` default would transparently
                // decode an answer whose content was stored encoded, so a staged download would write plaintext
                // for object bytes and lose the length check with it. A client that wants an encoding itself still
                // names it, and its choice stands.
                addInterceptor { chain ->
                    val request = chain.request()
                    if (request.header("Accept-Encoding") == null) {
                        chain.proceed(request.newBuilder().header("Accept-Encoding", "identity").build())
                    } else {
                        chain.proceed(request)
                    }
                }
                addNetworkInterceptor { chain ->
                    val connection = chain.connection()
                    if (connection != null) {
                        val last = lastUse[connection]
                        if (last != null && System.nanoTime() - last >= PROBE_AFTER_IDLE_NANOS && connection.socket().isClosedByPeer()) {
                            pool.evictAll()
                            runCatching { connection.socket().close() }
                            throw StaleConnectionException()
                        }
                    }
                    val response = chain.proceed(chain.request())
                    if (connection != null) lastUse[connection] = System.nanoTime()
                    response
                }
                protocols(listOf(Protocol.HTTP_1_1))
                connectionPool(pool)
                dispatcher(dispatcher)
                if (tls != null) sslSocketFactory(tls.socketFactory, tls.trustManager)
                if (tls != null) hostnameVerifier { _, _ -> true }
            }
        }
    }
}

/** Raised before a byte of the request was written: the pooled connection was found closed by the server. */
private class StaleConnectionException : IOException("the server had closed the pooled connection before the request was sent")

private val READ_METHODS = setOf("GET", "HEAD")

/**
 * Whether a request that failed with this may go out once more: not a cancelled call; a request nothing of which was written
 * ([StaleConnectionException]) of any method; a read that failed before its answer on a connection-level error.
 */
private fun IOException.canBeSentAgain(
    method: String,
    cancelled: Boolean,
): Boolean = !cancelled && (this is StaleConnectionException || (method in READ_METHODS && looksLikeDeadConnection()))

/** An EOF or a reset: how a connection the server closed shows. Not a timeout, not a refused or unreachable connect. */
private fun IOException.looksLikeDeadConnection(): Boolean =
    generateSequence<Throwable>(this) { it.cause }.take(6).any { t ->
        t is EOFException ||
            (
                t is SocketException &&
                    t !is ConnectException &&
                    t !is NoRouteToHostException &&
                    t !is BindException &&
                    t !is PortUnreachableException
            )
    } && generateSequence<Throwable>(this) { it.cause }.take(6).none { it is SocketTimeoutException }

/** The probe OkHttp makes itself on connections idle for 10 s: a 1 ms read; end of stream or an error means the peer closed. */
private fun Socket.isClosedByPeer(): Boolean {
    val before = soTimeout
    return try {
        soTimeout = 1
        getInputStream().read() // -1: the peer closed. A byte: data on an idle HTTP/1.1 connection is as unusable as an EOF
        true
    } catch (_: SocketTimeoutException) {
        false
    } catch (_: IOException) {
        true
    } finally {
        runCatching { soTimeout = before }
    }
}

private const val PROBE_AFTER_IDLE_NANOS = 50_000_000L
private const val MAX_IDLE_CONNECTIONS = 16
private const val IDLE_CONNECTION_SECONDS = 15L
private const val MAX_CALLS = 64
