package org.krost.unidrive.http

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Protocol
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
 * - Idle connections are dropped after 30 s, before the usual server-side idle limits, so that a request seldom meets a
 *   connection the server closed.
 */
public fun unidriveHttpClient(
    tls: ClientTls? = null,
    configure: HttpClientConfig<*>.() -> Unit,
): HttpClient {
    val pool = ConnectionPool(MAX_IDLE_CONNECTIONS, IDLE_CONNECTION_SECONDS, TimeUnit.SECONDS)
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
                protocols(listOf(Protocol.HTTP_1_1))
                connectionPool(pool)
                dispatcher(dispatcher)
                if (tls != null) sslSocketFactory(tls.socketFactory, tls.trustManager)
                if (tls != null) hostnameVerifier { _, _ -> true }
            }
        }
    }
}

private const val MAX_IDLE_CONNECTIONS = 16
private const val IDLE_CONNECTION_SECONDS = 30L
private const val MAX_CALLS = 64
