package org.krost.unidrive.http

import io.ktor.client.call.HttpClientCall
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpReceivePipeline
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.Headers
import io.ktor.http.contentLength
import io.ktor.util.AttributeKey
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Job
import kotlinx.io.Buffer
import kotlinx.io.Source
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/**
 * UD-255: correlation id for every outbound HTTP request.
 *
 * Each request gets an 8-char id injected into the `X-Unidrive-Request-Id`
 * header. The plugin also logs a DEBUG line on request send and on
 * response receipt, carrying `req=<id>`, the HTTP method, a redacted URL
 * (query string dropped), status code, and latency. A single grep
 * `req=<id>` gives the request/response pair plus any provider-level
 * WARN/ERROR that references the id in its message text.
 *
 * No MDC propagation: Ktor's request/response pipelines run on dispatchers
 * that don't consistently carry MDC across suspensions, so we attach the
 * id to a per-request attribute and surface it in log messages instead.
 * Callers that need a correlation id in their own log lines can pull it
 * via [requestId].
 */
val RequestIdKey: AttributeKey<String> = AttributeKey("unidrive.requestId")

/** Nanotime the request was created at (its `→` line), from which [QueuedMsKey] is measured. */
private val CreatedNanosKey: AttributeKey<Long> = AttributeKey("unidrive.requestCreatedNanos")

/** Milliseconds the request waited locally from its creation to its send; travels to the `←` line as `queued=<ms>`. */
private val QueuedMsKey: AttributeKey<Long> = AttributeKey("unidrive.requestQueuedMs")

/**
 * The reply of this attempt whose size was unknown when the response arrived; its body is counted
 * as the client reads it, and the `←` line is logged once the count is final — or at the attempt's
 * own end, with the count so far or unknown.
 */
private val CountedKey: AttributeKey<CountedReply> = AttributeKey("unidrive.countedReply")

/**
 * Accessor for the correlation id assigned to a request. Returns `null`
 * if called before the plugin has run, or for clients that don't install
 * the plugin.
 */
fun HttpRequestBuilder.requestId(): String? = attributes.getOrNull(RequestIdKey)

/**
 * Build the Ktor client plugin. Install with:
 * ```
 * HttpClient { install(RequestId) }
 * ```
 * To also report every attempt (see [RequestAttempt]) to whatever the daemon attaches to [RequestIdConfig.metrics],
 * and account the attempts open now to a [RequestIdConfig.inFlight] registry:
 * ```
 * HttpClient { install(RequestId) { metrics = tap; provider = "x"; endpoints = classifier } }
 * ```
 * With no sink on the tap, nothing is recorded; the attempt is still classified, for the abandonment WARN and the
 * in-flight registry, which do not depend on the tap.
 */
/**
 * The process-wide sequence every request id is drawn from, initialized with randomness once at
 * class-load: every client of this process shares it, so a client recreated mid-run cannot restart
 * onto ids another client already used, and the random start makes two processes writing one shared
 * log almost surely never overlap. One AtomicLong increment per request — no randomness on the
 * request path. The counter masks to 32 bits, so the wrap needs 4.3G requests in one process.
 */
private val requestIdSequence = AtomicLong(UUID.randomUUID().mostSignificantBits and 0xFFFFFFFFL)

@Suppress("TooGenericExceptionCaught") // the failure is classified, recorded and rethrown unchanged
@OptIn(io.ktor.utils.io.InternalAPI::class) // the counted reply replaces the response body (see CountedReply)
val RequestId =
    createClientPlugin("UnidriveRequestId", ::RequestIdConfig) {
        val log = LoggerFactory.getLogger("org.krost.unidrive.http.RequestId")
        val metrics = pluginConfig.metrics
        val provider = pluginConfig.provider
        val endpoints = pluginConfig.endpoints
        val profile = pluginConfig.profile
        val serverRequestIdHeaders = pluginConfig.serverRequestIdHeaders
        val inFlight = pluginConfig.inFlight
        val retries = RetryTracker()

        // Attempt metrics. The Send hook wraps one trip to the engine: it sees the failure of a request that never
        // got a response, and the response whose body is consumed later (its job completes then). Redirects and
        // provider-level retries are further requests, each its own attempt.
        on(Send) { request ->
            val startedAtMs = System.currentTimeMillis()
            val startNanos = System.nanoTime()
            // gkrost/unidrive-cli#416: the request may have waited locally before this hook ran; the wait
            // from its creation to its send travels to the reply line as `queued=<ms>`.
            val queuedMs =
                request.attributes.getOrNull(CreatedNanosKey)?.let { nanosToMs(System.nanoTime() - it) } ?: 0L
            request.attributes.put(QueuedMsKey, queuedMs)
            val method = request.method.value
            val endpointClass = endpoints.classify(method, request.url.host, "/" + request.url.encodedPathSegments.joinToString("/"))
            val retryKey = RetryTracker.keyOf(method + ' ' + request.url.buildString())
            val retryNo = retries.retryNo(retryKey, startNanos)
            val openRequest = inFlight?.started(endpointClass)

            fun report(
                outcome: AttemptOutcome,
                headersMs: Long?,
                bytesUp: Long?,
                bytesDown: Long?,
                throttled: Boolean,
            ) {
                val endNanos = System.nanoTime()
                openRequest?.close()
                // gkrost/unidrive-cli#74: a request abandoned by its timeout or a cancel leaves no ← line. One WARN
                // names the kind, the class and the duration, and nothing that identifies the call: no id, no path.
                if (outcome == AttemptOutcome.TIMEOUT || outcome == AttemptOutcome.CANCELLED) {
                    val profileSegment = profile?.let { " [$it]" } ?: ""
                    log.warn(
                        "request abandoned ({}): {} {}{} after {} ms",
                        outcome.wire,
                        provider,
                        endpointClass,
                        profileSegment,
                        nanosToMs(endNanos - startNanos),
                    )
                }
                val sink = metrics?.sink ?: return
                retries.ended(retryKey, outcome.retryable, endNanos)
                // The sink promises not to throw; a broken one must still never fail the request that was measured.
                runCatching {
                    sink.record(
                        RequestAttempt(
                            startedAtMs = startedAtMs,
                            provider = provider,
                            endpointClass = endpointClass,
                            method = method,
                            outcome = outcome,
                            headersMs = headersMs,
                            totalMs = nanosToMs(endNanos - startNanos),
                            bytesUp = bytesUp,
                            bytesDown = bytesDown,
                            retryNo = retryNo,
                            throttled = throttled,
                        ),
                    )
                }
            }

            val call =
                try {
                    proceed(request)
                } catch (e: Throwable) {
                    report(AttemptOutcome.ofFailure(e), headersMs = null, bytesUp = null, bytesDown = null, throttled = false)
                    throw e
                }
            val headersMs = nanosToMs(System.nanoTime() - startNanos)
            val response = call.response
            val status = response.status.value
            val throttled = status == HttpStatusCode.TooManyRequests.value || response.headers.contains(HttpHeaders.RetryAfter)
            val bytesUp = call.request.content.contentLength
            val counted = call.request.attributes.getOrNull(CountedKey)
            fun finish(cause: Throwable?) {
                val outcome = if (cause == null) AttemptOutcome.ofStatus(status) else AttemptOutcome.ofFailure(cause)
                // gkrost/unidrive-cli#416: a reply without Content-Length contributes the bytes the client
                // read; a failed attempt leaves the size unknown. The reply line of a counted body whose
                // own end did not get to it is logged here, so every attempt pairs its → with a ←: unknown
                // for a failed attempt, the count so far for a successful one whose body the caller never
                // read (a delete's 204 among them) — the same number the metric below reports.
                val bytesDown = if (cause == null) response.contentLength() ?: counted?.bytesRead() else null
                if (cause == null) counted?.logCountSoFar() else counted?.logUnknown()
                report(outcome, headersMs, bytesUp, bytesDown, throttled)
            }
            val job = response.coroutineContext[Job]
            if (job == null) finish(null) else job.invokeOnCompletion { finish(it) }
            call
        }

        onRequest { request, _ ->
            val id = "%08x".format(requestIdSequence.getAndIncrement() and 0xFFFFFFFFL)
            request.attributes.put(CreatedNanosKey, System.nanoTime())
            request.attributes.put(RequestIdKey, id)
            request.headers.append("X-Unidrive-Request-Id", id)
            // The URL string and its redaction run only when the line is actually logged.
            if (log.isDebugEnabled) {
                log.debug(
                    "→ req={} {} {}",
                    id,
                    request.method.value,
                    redact(request.url.buildString()),
                )
            }
        }

        onResponse { response ->
            val request = response.call.request
            val id = request.attributes.getOrNull(RequestIdKey)
            val status = response.status.value
            val warn = status >= HttpStatusCode.BadRequest.value
            // The segments of the close line are built only when the line is logged; a DEBUG reply of a
            // client whose DEBUG is off costs nothing here. The WARN branch stays unconditional.
            if (!warn && !log.isDebugEnabled) return@onResponse
            val queuedMs = request.attributes.getOrNull(QueuedMsKey) ?: 0L
            // The total now runs from the request's creation, so the local wait it includes is told apart
            // as `queued=<ms>` (gkrost/unidrive-cli#416) and the engine's own time is what remains.
            val elapsed =
                response.responseTime.timestamp - response.requestTime.timestamp + queuedMs
            // archive#644: the profile travels in the message itself, independent of the thread's MDC (hydration
            // downloads and other worker threads have none), so a shared log splits per account. The server's
            // own request id (first present header of [RequestIdConfig.serverRequestIdHeaders]) rides along
            // so a slow call can be handed to the provider's support. Both segments are empty when unknown,
            // which keeps the line byte for byte the old format for clients that do not name them.
            // UD-773: append payload byte counts to the close line so log analysers can
            // derive bytes/sec throughput per provider/host instead of just ops/sec. Goes
            // AFTER the `(Nms)` chunk so existing regexes that end at `\d+ms\)` still match.
            // up = request body size, dn = response body size; a reply without Content-Length has no size
            // yet (gkrost/unidrive-cli#416): its line is logged when the body has been read, which is when
            // the size becomes known, and `?` only when it never does — the attempt's own end is the last
            // resort when nothing read the body. Suppress the entire segment when
            // both counts are zero (most metadata calls on streamed paths) to keep the log readable.
            val upBytes = request.content.contentLength
            val dnBytes = response.contentLength() ?: return@onResponse
            val sizeSegment = formatSizeSegment(upBytes, dnBytes)
            val profileSegment = profile?.let { " [$it]" } ?: ""
            val serverId = serverRequestIdHeaders.firstNotNullOfOrNull { response.headers[it] }
            val srvSegment = serverId?.let { " srv=$it" } ?: ""
            if (warn) {
                log.warn(
                    "← req={}{}{} {} {} ({}ms) queued={}ms{}",
                    id ?: "--------",
                    profileSegment,
                    srvSegment,
                    status,
                    response.status.description,
                    elapsed,
                    queuedMs,
                    sizeSegment,
                )
            } else {
                log.debug(
                    "← req={}{}{} {} ({}ms) queued={}ms{}",
                    id ?: "--------",
                    profileSegment,
                    srvSegment,
                    status,
                    elapsed,
                    queuedMs,
                    sizeSegment,
                )
            }
        }

        // gkrost/unidrive-cli#416: a reply whose size is not in the headers gets its body wrapped in a
        // counting channel, so the `←` line can name the size the client actually read. The counting
        // channel replaces the reply's body for everything downstream of this phase, including the
        // attempt metrics, which read the same counter when the body has been consumed.
        client.receivePipeline.intercept(HttpReceivePipeline.Before) { response ->
            if (response.contentLength() != null) return@intercept
            val request = response.call.request
            val status = response.status.value
            val warn = status >= HttpStatusCode.BadRequest.value
            val debug = log.isDebugEnabled
            val queuedMs = request.attributes.getOrNull(QueuedMsKey) ?: 0L
            val elapsedMs = response.responseTime.timestamp - response.requestTime.timestamp
            val upBytes = request.content.contentLength
            val id = request.attributes.getOrNull(RequestIdKey) ?: "--------"
            val counted =
                CountedReply(response.rawContent) { dn ->
                    // The wrapper itself stays for the metrics; only the line's segments are built and
                    // logged when the level wants them.
                    if (warn || debug) {
                        val sizeSegment = formatCountedSizeSegment(upBytes, dn)
                        val profileSegment = profile?.let { " [$it]" } ?: ""
                        val serverId = serverRequestIdHeaders.firstNotNullOfOrNull { response.headers[it] }
                        val srvSegment = serverId?.let { " srv=$it" } ?: ""
                        if (warn) {
                            log.warn(
                                "← req={}{}{} {} {} ({}ms) queued={}ms{}",
                                id,
                                profileSegment,
                                srvSegment,
                                status,
                                response.status.description,
                                elapsedMs + queuedMs,
                                queuedMs,
                                sizeSegment,
                            )
                        } else {
                            log.debug(
                                "← req={}{}{} {} ({}ms) queued={}ms{}",
                                id,
                                profileSegment,
                                srvSegment,
                                status,
                                elapsedMs + queuedMs,
                                queuedMs,
                                sizeSegment,
                            )
                        }
                    }
                }
            request.attributes.put(CountedKey, counted)
            subject = CountedHttpResponse(response, counted.channel)
        }
    }

/** Settings of [RequestId]; every one is optional. */
class RequestIdConfig {
    /** Where attempts are reported once the tap has a sink; null for a client that reports nothing. */
    var metrics: RequestMetricsTap? = null

    /**
     * Where the attempts open now are counted (gkrost/unidrive-cli#74); null for a client whose requests nobody
     * asks after. The registry is read while requests run, so it is the client's own object, not a late-bound tap.
     */
    var inFlight: RequestInFlight? = null

    /** The provider id written into every attempt. */
    var provider: String = "unknown"

    /** Names the endpoints of this client's API; the default hides every path ([EndpointClassifier.OPAQUE]). */
    var endpoints: EndpointClassifier = EndpointClassifier.OPAQUE

    /**
     * The profile name printed into the response line (`[profile]`); null (the default) keeps the line in
     * the old format. The name travels in the message because the MDC does not reach every worker thread.
     */
    var profile: String? = null

    /**
     * Response headers that may carry the server's own request id, tried in order; the first present
     * value is printed as `srv=<id>` (empty when none). Redaction rule as ever: the URL stays
     * query-free, the header value comes from the response, not from anything the request carried.
     */
    var serverRequestIdHeaders: List<String> = emptyList()
}

/**
 * UD-773: format the `up=N dn=M` segment for the RequestId close line.
 *
 * Suppress (return empty) when:
 * - both byte counts are explicit zero (a true metadata call — no body either way), OR
 * - both byte counts are null (Content-Length absent on both sides — no signal anyway).
 *
 * Otherwise emit `" up=N dn=M"` with `?` substituted for any single null (e.g. a known
 * upload size with a streaming response → `up=12345 dn=?`, signalling the streaming
 * response distinctly from a zero-byte one).
 */
internal fun formatSizeSegment(
    up: Long?,
    dn: Long?,
): String {
    if (up == null && dn == null) return ""
    if (up == 0L && dn == 0L) return ""
    val upStr = up?.toString() ?: "?"
    val dnStr = dn?.toString() ?: "?"
    return " up=$upStr dn=$dnStr"
}

/**
 * Strip query string from a URL for logging. Query params can leak bearer
 * tokens and access hashes for some providers (e.g. SAS-style URLs).
 */
internal fun redact(url: String): String {
    val q = url.indexOf('?')
    return if (q < 0) url else url.substring(0, q) + "?<redacted>"
}

/**
 * The size segment of a counted reply's line. The line exists to name the body's size: when the
 * transfer died before one could be established, `?` takes the place the count would have had (the
 * plain suppression rules would otherwise drop the whole segment).
 */
internal fun formatCountedSizeSegment(
    up: Long?,
    dn: Long?,
): String = if (dn == null) " up=${up ?: "?"} dn=?" else formatSizeSegment(up, dn)

/**
 * The reply of one attempt whose size the headers did not name (gkrost/unidrive-cli#416). Its [channel]
 * counts the bytes as the client reads them, and [onDone] fires once: with the final count when the body
 * is read to its end, with the count so far when a successful attempt ends first, or null when the
 * transfer died before a size could be established. The `←` line is logged from that callback, so it
 * appears when the size becomes known instead of when the headers arrive — and no later than the
 * attempt's own end.
 */
internal class CountedReply(
    private val delegate: ByteReadChannel,
    private val onDone: (Long?) -> Unit,
) {
    private val logged = AtomicBoolean(false)

    val channel = CountingByteReadChannel(delegate, ::check)

    /** Bytes the channel has pulled from the wire so far; the final count once the body is done. */
    fun bytesRead(): Long = channel.bytesRead()

    /** The attempt ended without the body completing (failure, cancel); the size stays unknown. */
    fun logUnknown() {
        if (logged.compareAndSet(false, true)) onDone(null)
    }

    /** The attempt succeeded with the body unread or partly read; the count so far is what the line names. */
    fun logCountSoFar() {
        if (logged.compareAndSet(false, true)) onDone(channel.bytesRead())
    }

    private fun check() {
        when {
            delegate.closedCause != null -> logUnknown()
            delegate.isClosedForRead -> if (logged.compareAndSet(false, true)) onDone(channel.bytesRead())
        }
    }
}

/**
 * A [ByteReadChannel] that pulls the arriving body into a [Source] it hands out as the read side, and
 * counts the pulled bytes. The consumer reads the staged buffer exactly as it would read the body, and
 * at the end of the body everything pulled has been consumed, so the count is the body's size. Pure
 * pass-through otherwise: no extra coroutine, and the pull runs only as far as the consumer's reads
 * demand. [onProgress] fires at each step of the consumer's read loop (the end-of-body check among
 * them), which is where the owner learns that the body is done.
 */
@OptIn(io.ktor.utils.io.InternalAPI::class)
internal class CountingByteReadChannel(
    private val delegate: ByteReadChannel,
    private val onProgress: () -> Unit,
) : ByteReadChannel {
    private val pulled = AtomicLong()
    private val staged = Buffer()

    fun bytesRead(): Long = pulled.get()

    override val readBuffer: Source get() = staged

    override val closedCause: Throwable? get() = delegate.closedCause

    override val isClosedForRead: Boolean
        get() {
            val done = delegate.isClosedForRead && staged.exhausted()
            if (done) onProgress()
            return done
        }

    override suspend fun awaitContent(min: Int): Boolean {
        while (true) {
            val moved = delegate.readBuffer.transferTo(staged)
            if (moved > 0) pulled.addAndGet(moved)
            if (staged.size >= min) return true
            if (delegate.isClosedForRead) {
                onProgress()
                return staged.size >= min
            }
            delegate.awaitContent((min - staged.size).toInt())
        }
    }

    override fun cancel(cause: Throwable?) {
        delegate.cancel(cause)
        onProgress()
    }
}

/**
 * The reply of a counted body: everything of the original response, with the body replaced by the
 * counting channel. Keeping the delegate's times and context preserves the engine-side latency the
 * reply line reports.
 */
@OptIn(io.ktor.utils.io.InternalAPI::class)
private class CountedHttpResponse(
    private val delegate: HttpResponse,
    private val content: ByteReadChannel,
) : HttpResponse() {
    override val call: HttpClientCall get() = delegate.call

    override val status: HttpStatusCode get() = delegate.status

    override val version: HttpProtocolVersion get() = delegate.version

    override val requestTime: GMTDate get() = delegate.requestTime

    override val responseTime: GMTDate get() = delegate.responseTime

    override val rawContent: ByteReadChannel get() = content

    override val headers: Headers get() = delegate.headers

    override val coroutineContext: CoroutineContext get() = delegate.coroutineContext
}
