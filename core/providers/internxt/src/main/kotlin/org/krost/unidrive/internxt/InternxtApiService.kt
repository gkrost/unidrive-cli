package org.krost.unidrive.internxt

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.HttpDefaults
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.RemoteContentRefusedDownloadException
import org.krost.unidrive.RemoteIncompleteDownloadException
import org.krost.unidrive.ViewRef
import org.krost.unidrive.http.ErrorBodyExcerpt
import org.krost.unidrive.http.HttpRetryBudget
import org.krost.unidrive.http.InFlightDedup
import org.krost.unidrive.http.RequestMetricsSink
import org.krost.unidrive.http.RequestMetricsTap
import org.krost.unidrive.http.UploadProgress
import org.krost.unidrive.http.UploadTimeoutPolicy
import org.krost.unidrive.http.UploadWatchdogException
import org.krost.unidrive.http.UploadWatchdogLimits
import org.krost.unidrive.http.assertNotHtml
import org.krost.unidrive.http.currentPriority
import org.krost.unidrive.http.parseRetryAfterHeader
import org.krost.unidrive.http.progressTrackingBody
import org.krost.unidrive.http.progressTrackingFileBody
import org.krost.unidrive.http.progressTrackingFileRangeBody
import org.krost.unidrive.http.withUploadWatchdog
import org.krost.unidrive.internxt.model.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.HexFormat
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Marks the coroutines of a drive enumeration (archive#693). A GET inside one that fails to connect at all
 * (status 0: no route, refused, unknown host, a timeout before any response) keeps retrying on a backoff
 * of up to [InternxtApiService.CONNECT_OUTAGE_BUDGET_MS] instead of ending after the 2 s / 4 s ladder,
 * because that one failure otherwise discards the whole listing and the next attempt starts again at
 * item 0. Calls outside an enumeration (a mount lookup, an upload, a download) keep the short ladder.
 * HTTP status errors are not affected.
 */
internal class EnumerationOutageRetry : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<EnumerationOutageRetry>
}

/**
 * A shard download ran into its whole-request time budget while it was still moving (archive#1065). The budget is sized
 * from the declared size (see [InternxtApiService.downloadRequestTimeoutMs]), so another attempt from byte 0 would
 * use it up again: the shard retry ladder passes this on instead of repeating it. Still an [java.io.IOException], so
 * the caller's next pass retries the file as it does any transient failure.
 */
internal class InternxtDownloadBudgetExhaustedException(
    message: String,
    cause: Throwable,
) : java.io.IOException(message, cause)

class InternxtApiService(
    private val config: InternxtConfig,
    private val credentialsProvider: suspend (forceRefresh: Boolean) -> InternxtCredentials,
    // Two budgets matching drive-desktop's audited `bottleneck` config:
    //   - Drive REST: maxConcurrency=2, minSpacing=500ms (storm widens to 1000ms).
    //   - Bridge upload: maxConcurrency=4, minSpacing=0 (concurrency-only).
    // The spacing paces every call (awaitSlot). maxConcurrency is a real permit only where a caller takes it
    // (withPermit): the heavy Drive listings in authenticatedGetReply do, so at most two of them are in flight and a
    // throttle storm halves that; every other call, and the whole bridge budget, is paced but not counted.
    // Composition order on the Drive REST surface is
    // `retryOnTransient { withAuthRetry { budget.awaitSlot(); ... } }` — the
    // budget MUST stay the innermost wrapper so retries re-acquire a
    // per-attempt slot, and `withAuthRetry` sits between so a mid-call 401
    // replay reuses the current `retryOnTransient` attempt's delay budget
    // rather than restarting the 3-iteration ladder. Bridge calls use HTTP
    // Basic auth and do not 401 on JWT expiry, so they remain unwrapped.
    private val driveBudget: HttpRetryBudget =
        HttpRetryBudget(maxConcurrency = 2, minSpacingMs = 500, stormSpacingMs = 1_000),
    private val bridgeBudget: HttpRetryBudget =
        HttpRetryBudget(maxConcurrency = 4, minSpacingMs = 0, stormSpacingMs = 0),
    // The socket timeout of the listings, whose answers can need over a minute on a large account (see
    // [LISTING_SOCKET_TIMEOUT_MS]). A parameter so that loopback tests can run with a small one.
    private val listingSocketTimeoutMs: Long = LISTING_SOCKET_TIMEOUT_MS,
    // The seam the daemon attaches its request-attempt recorder to; no sink, no metrics (see attachRequestMetrics).
    private val requestMetrics: RequestMetricsTap = RequestMetricsTap(),
    // Tests hand in a client on a MockEngine; production builds the default one. The token path's leaf is the
    // profile name, so the request log lines carry the account independent of the thread's MDC (archive#644).
    private val httpClient: HttpClient = defaultHttpClient(requestMetrics, profile = config.tokenPath.fileName.toString()),
    // The limits of the shard PUT's write-progress watchdog (archive#571). A parameter so that loopback tests can run on a
    // scale of milliseconds.
    private val shardPutWatchdog: UploadWatchdogLimits = UploadWatchdogLimits.DEFAULT,
    // How long a GET inside an enumeration (see [EnumerationOutageRetry]) keeps retrying a connect failure
    // that got no response at all. A parameter so that tests can shrink or switch it off.
    private val connectOutageBudgetMs: Long = CONNECT_OUTAGE_BUDGET_MS,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(InternxtApiService::class.java)

    // gkrost/unidrive-cli#181: the server's clock, from the `Date` header of the newest successful drive answer.
    private val serverClock = ServerClock()

    fun attachRequestMetrics(sink: RequestMetricsSink?) {
        requestMetrics.sink = sink
    }

    /**
     * gkrost/unidrive-cli#181: the server's clock now, from the newest `Date` header seen; null when no answer has carried one
     * yet. It advances by the monotonic clock from that answer on ([ServerClock]), so a correction of the client's wall clock
     * since then does not move it. The header's second resolution and the answer's transit are well inside the cursor's rewind
     * window.
     */
    internal fun serverNowOrNull(): java.time.Instant? = serverClock.nowOrNull()

    // An answer that names its age came from a cache and says nothing about the server's clock now.
    private fun noteServerTime(response: HttpResponse) {
        if (response.headers[HttpHeaders.Age] != null) return
        val raw = response.headers[HttpHeaders.Date] ?: return
        val server =
            try {
                java.time.ZonedDateTime
                    .parse(raw, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
            } catch (_: java.time.format.DateTimeParseException) {
                return
            }
        serverClock.observe(server)
    }

    companion object {
        /** Total backoff a connect failure inside an enumeration may wait before the call fails ([EnumerationOutageRetry]). */
        internal const val CONNECT_OUTAGE_BUDGET_MS: Long = 180_000L
        private const val OUTAGE_FIRST_DELAY_MS: Long = 2_000L
        private const val OUTAGE_MAX_DELAY_MS: Long = 30_000L

        /** How many shard pointers one [getBridgeMirrors] query asks for; an object with more shards cannot be judged from it. */
        internal const val BRIDGE_MIRROR_LIMIT: Int = 3

        /** Safety bound of one paged per-folder stream (10,000 pages are 10M items at the cursor page size of 1000). */
        internal const val MAX_LISTING_PAGES: Int = 10_000

        /** The most files the server's recents route answers with; it refuses a larger limit. */
        internal const val MAX_RECENT_FILES: Int = 1000

        /** Rows asked per page of the favorites route; the server accepts 1 to 1000. */
        internal const val FAVORITES_PAGE_SIZE: Int = 1000

        /** How many references [listFavorites] returns at most; one more makes the list truncated. */
        internal const val MAX_FAVORITE_REFS: Int = 5_000

        /** How many references [listSharedByMe] returns at most; one more makes the list truncated. */
        internal const val MAX_SHARED_REFS: Int = 5_000

        internal fun defaultHttpClient(
            tap: RequestMetricsTap,
            engine: io.ktor.client.engine.HttpClientEngine? = null,
            profile: String? = null,
        ): HttpClient {
            val configure: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
                install(HttpTimeout) {
                    connectTimeoutMillis = HttpDefaults.CONNECT_TIMEOUT_MS
                    socketTimeoutMillis = HttpDefaults.SOCKET_TIMEOUT_MS
                    requestTimeoutMillis = HttpDefaults.REQUEST_TIMEOUT_MS
                }
                // UD-255: per-request correlation id + DEBUG req/response logging; the attempt metrics ride on it.
                // archive#644: the profile and the server's x-request-id join the response line.
                install(org.krost.unidrive.http.RequestId) {
                    metrics = tap
                    provider = "internxt"
                    endpoints = InternxtEndpoints.classifier
                    this.profile = profile
                    serverRequestIdHeaders = listOf("x-request-id")
                }
            }
            return if (engine != null) HttpClient(engine, configure) else HttpClient(configure)
        }

        // archive#1065: the request timeout of one shard GET. Ktor counts it up to the end of the `execute` block, so the client's
        // flat 600 s cut every download that needed longer, steady transfer or not. Sized like a data-plane PUT (UD-337) from
        // the declared size: the larger of the 600 s floor and the time at 50 KiB/s. Without a size nothing sensible can
        // be computed, so the call is unbounded and the client's read-idle socket timeout is the only watchdog.
        internal fun downloadRequestTimeoutMs(declaredSize: Long?): Long =
            if (declaredSize == null || declaredSize <= 0L) {
                HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            } else {
                UploadTimeoutPolicy.computeRequestTimeoutMs(declaredSize)
            }

        private val TRANSIENT_STATUSES = setOf(429, 500, 502, 503, 504)

        // The gateway's own answers for an origin that did not answer (Cloudflare): 520 unknown error, 521 down, 522
        // connection timed out, 523 unreachable, 524 timed out. A GET treats them like 502/503/504: the same
        // "the server is unavailable" the callers' fallbacks act on, which is the 503 they end as.
        private val GATEWAY_ORIGIN_ERRORS = setOf(520, 521, 522, 523, 524)

        // Answered after the gateway has waited for the origin for about two minutes: the same request gets the same answer.
        private const val GATEWAY_ORIGIN_TIMEOUT = 524

        private fun gatewayOriginErrorName(status: Int): String =
            when (status) {
                520 -> "the origin returned an unknown error"
                521 -> "the origin is down"
                522 -> "the connection to the origin timed out"
                523 -> "the origin is unreachable"
                else -> "the origin timed out"
            }

        // A gateway origin error as the synthetic 503 of "the server is unavailable". The gateway's own status and body stay
        // in the cause, its Retry-After on the exception.
        private fun gatewayUnavailable(
            e: InternxtApiException,
            url: String,
            elapsedMs: Long?,
        ): InternxtApiException =
            InternxtApiException(
                "the gateway answered ${e.statusCode} (${gatewayOriginErrorName(e.statusCode)}) for GET $url" +
                    (elapsedMs?.let { " after ${String.format(java.util.Locale.ROOT, "%.1f s", it / 1000.0)}" } ?: ""),
                503,
                requestId = e.requestId,
                retryAfterMs = e.retryAfterMs,
                cause = e,
            )

        // UD-335: capture `"retry_after": <seconds>` from Cloudflare /
        // Internxt JSON error bodies. Returns the integer seconds.
        private val RETRY_AFTER_REGEX = Regex(""""retry_after"\s*:\s*(\d+)""")

        // The place a kotlinx decoding error names (`at path: $.folders[0].parentId`): field names and indices only.
        private val JSON_PATH = Regex("""at path:? (\$[A-Za-z0-9_.\[\]]*)""")

        internal fun listingQueryParams(
            updatedAt: String?,
            limit: Int,
            offset: Int,
            status: String = "ALL",
            sort: String = "uuid",
        ): Map<String, String> {
            val params =
                mutableMapOf(
                    "status" to status,
                    "limit" to limit.toString(),
                    "offset" to offset.toString(),
                    "sort" to sort,
                    "order" to "ASC",
                )
            if (updatedAt != null) params["updatedAt"] = updatedAt
            return params
        }

        // The socket timeout of the listings, whose answers can need tens of seconds before the first byte:
        // a whole-drive offset page takes 25-56 s (archive#517 R2/F3), and a flat folder of 29,000 files needed
        // 106 s and 31 MB in one body, so getFolderContents rides at this limit as well. The former 330 s (the
        // server's 300 s statement timeout + 10 percent, so its own error answer would arrive instead of a
        // silent client cut) could never fire: every Drive REST call rides the Cloudflare gateway, which
        // answers a 524 of its own at about 125 s — mapped by [authenticatedGetReply] to the synthetic 503 the
        // callers' fallbacks act on — so no answer can arrive between the gateway's cut and the statement
        // timeout. The limit sits just above the cap (#71): it only bounds a connection that hangs without any
        // answer, and bounds it at 150 s instead of 330 s.
        internal const val LISTING_SOCKET_TIMEOUT_MS: Long = 150_000

        // The floor of the shard PUT's outer safety net (UD-337/UD-353): an upload slower than this on average is
        // the only one the net cuts while it still moves (archive#571).
        private const val OVH_PUT_MIN_THROUGHPUT_BPS: Long = 10L * 1024

        // archive#517 F1/F2 sized the read-idle socket watchdog of a shard PUT for the body at an assumed 2 MiB/s
        // (SHARD_PUT_MIN_THROUGHPUT_BPS); every large upload on a slower link was cut at the same point and each
        // retry restarted from byte 0. archive#571 removed that floor: a shard PUT runs under the write-progress watchdog
        // (UploadProgressWatchdog.kt), which cuts only a body that stopped moving or a server that does not answer.

        private const val SHARD_PUT = "Shard upload"

        // archive#1197: how often a running shard PUT reports the bytes it has sent.
        const val SHARD_PROGRESS_INTERVAL_MS = 250L
    }

    private val json = InternxtJson
    private val baseUrl = InternxtConfig.API_BASE_URL

    // UD-205: read-heavy provider operations are a known dedup target.
    // Concurrent sync-engine coroutines descending overlapping subtrees can
    // legitimately ask for the same metadata at the same time; the dedup
    // collapses those into one HTTP round-trip without changing call-site
    // semantics. One InFlightDedup per call site (per `InFlightDedup` KDoc
    // key-design guidance), mirroring the original folderContentsDedup wiring.
    internal val folderContentsDedup = InFlightDedup<String, FolderContentResponse>()
    internal val fileMetaDedup = InFlightDedup<String, InternxtFile>()
    internal val folderMetaDedup = InFlightDedup<String, InternxtFolder>()
    internal val listFilesDedup = InFlightDedup<String, List<InternxtFile>>()
    internal val listFoldersDedup = InFlightDedup<String, List<InternxtFolder>>()

    suspend fun getFolderContents(folderUuid: String): FolderContentResponse =
        folderContentsDedup.load(folderUuid, currentPriority()) {
            val body = authenticatedGet("$baseUrl/folders/content/$folderUuid", socketTimeoutMs = listingSocketTimeoutMs)
            json.decodeFromString<FolderContentResponse>(body)
        }

    internal val folderContentsPagedDedup = InFlightDedup<String, FolderContentResponse>()

    /**
     * archive#523/#647: the paged per-folder listing, the fallback for a folder whose combined content
     * call fails server-side or cannot fit the gateway (a 29k-file folder needs ~105 s in one
     * 30 MB body; the gateway cuts at ~125 s, so folders beyond ~35k files never fit).
     *
     * Primary: the cursor endpoints `GET /folders/v2/content/{uuid}/folders` and `/files`
     * (`order`, `cursor`, `limit` 50..1000 per the server's CursorPaginationDto; the reply is
     * `{folders|files: [...], nextCursor}`, `nextCursor` null on the last page). They serve only
     * existing items, in the item shape of the combined call, and take 1000 per page: 30
     * requests for a folder of 29,186 files.
     *
     * Only when such a call answers 404 or 405 (a gateway without the route) does that stream
     * fall back to the deprecated offset endpoints `GET /folders/content/{uuid}/folders` and
     * `/files`, whose query validation takes `limit` as REQUIRED 1..50: 50 per page, `sort=uuid`
     * for offset stability, `order=ASC`. Any other client error, a 400 included, propagates:
     * the request would be rejected the same way again.
     *
     * Each page rides the same retry ladder and listing watchdog as the offset listings.
     */
    suspend fun getFolderContentsPaged(folderUuid: String): FolderContentResponse =
        folderContentsPagedDedup.load(folderUuid, currentPriority()) {
            val folders =
                cursorStreamOrOffsetFallback(
                    folderUuid,
                    "folders",
                    { it.uuid },
                    { parseFoldersSyncPage(it) },
                ) { json.decodeFromString<PagedFolderFoldersResponse>(it).folders }
            val files =
                cursorStreamOrOffsetFallback(
                    folderUuid,
                    "files",
                    { it.uuid },
                    { parseFilesSyncPage(it) },
                ) { json.decodeFromString<PagedFolderFilesResponse>(it).files }
            FolderContentResponse(children = folders, files = files)
        }

    internal val childFoldersPagedDedup = InFlightDedup<String, List<InternxtFolder>>()

    /**
     * gkrost/unidrive-cli#134: the child FOLDERS of a folder through the paged listing alone (the cursor stream, the offset one
     * when the gateway has no cursor route), for the second look at a path segment the combined call did not show. The stream
     * ends only on a page that names no next cursor (an empty page for the offset one), so a result is the complete list of
     * the folders, never a first page; a stream that cannot end fails instead of answering short. The files stream is not read:
     * the walk never needs it, and a folder of 30k files needs 30 requests for it.
     */
    suspend fun getChildFoldersPaged(folderUuid: String): List<InternxtFolder> =
        childFoldersPagedDedup.load(folderUuid, currentPriority()) {
            cursorStreamOrOffsetFallback(
                folderUuid,
                "folders",
                { it.uuid },
                { parseFoldersSyncPage(it) },
            ) { json.decodeFromString<PagedFolderFoldersResponse>(it).folders }
        }

    private suspend fun <T> cursorStreamOrOffsetFallback(
        folderUuid: String,
        stream: String,
        uuidOf: (T) -> String,
        parseCursorPage: (String) -> SyncPage<T>,
        parseOffsetPage: (String) -> List<T>,
    ): List<T> =
        try {
            cursorFolderStream(folderUuid, stream, uuidOf, parseCursorPage)
        } catch (e: InternxtApiException) {
            if (e.statusCode != 404 && e.statusCode != 405) throw e
            log.warn(
                "The cursor listing of the {} of folder {} answered {}; using the offset endpoint with {} per page",
                stream,
                folderUuid,
                e.statusCode,
                InternxtConfig.FOLDER_CONTENT_OFFSET_PAGE_SIZE,
            )
            offsetFolderStream(folderUuid, stream, uuidOf, parseOffsetPage)
        }

    /**
     * One stream of the cursor listing of a folder: pages until a page names no next cursor. A
     * server that ignores the cursor would serve the same page for ever, and one that names the
     * cursor it was just given would never advance; that is no listing, so the stream fails (a
     * 503, which the walk counts as a skipped folder and reports as incomplete) when a page starts
     * with the item the page before it started with, when a page names the cursor that was sent
     * with it, or after [MAX_LISTING_PAGES].
     */
    private suspend fun <T> cursorFolderStream(
        folderUuid: String,
        stream: String,
        uuidOf: (T) -> String,
        parse: (String) -> SyncPage<T>,
    ): List<T> {
        val items = ArrayList<T>()
        var cursor: String? = null
        var previousFirst: String? = null
        var pages = 0
        while (true) {
            val params = linkedMapOf("order" to "ASC", "limit" to InternxtConfig.FOLDER_CONTENT_CURSOR_PAGE_SIZE.toString())
            cursor?.let { params["cursor"] = it }
            val body =
                authenticatedGet(
                    "$baseUrl/folders/v2/content/$folderUuid/$stream",
                    params,
                    socketTimeoutMs = listingSocketTimeoutMs,
                )
            val page = parse(body)
            val first = page.items.firstOrNull()?.let(uuidOf)
            val next = page.nextCursor
            val broken =
                when {
                    ++pages > MAX_LISTING_PAGES -> "exceeded $MAX_LISTING_PAGES pages without ending"
                    first != null && first == previousFirst ->
                        "returned the same page twice (cursor $cursor): the server does not honour the cursor"
                    next != null && next == cursor -> "named the cursor it was given again ($next): the listing cannot advance"
                    else -> null
                }
            if (broken != null) {
                throw InternxtApiException("The cursor $stream listing of folder $folderUuid $broken, so it cannot be completed", statusCode = 503)
            }
            previousFirst = first ?: previousFirst
            items.addAll(page.items)
            if (next == null) return items
            cursor = next
        }
    }

    /**
     * One stream of the deprecated offset listing of a folder, [InternxtConfig.FOLDER_CONTENT_OFFSET_PAGE_SIZE]
     * per page (the endpoint validates `limit` as 1..50). The server may serve fewer items than `limit`
     * asks for, so a short page is no sign of the last one: the stream ends only on an EMPTY page, and
     * the offset advances by the number of items a page actually returned. A server that ignores
     * `offset` would serve the same page for ever; that is no listing, so the stream fails (a 503,
     * which the walk counts as a skipped folder and reports as incomplete) when a page starts with
     * the item the page before it started with, or after [MAX_LISTING_PAGES].
     */
    private suspend fun <T> offsetFolderStream(
        folderUuid: String,
        stream: String,
        uuidOf: (T) -> String,
        parse: (String) -> List<T>,
    ): List<T> {
        val items = ArrayList<T>()
        var offset = 0
        var previousFirst: String? = null
        var pages = 0
        while (true) {
            val body =
                authenticatedGet(
                    "$baseUrl/folders/content/$folderUuid/$stream",
                    linkedMapOf(
                        "limit" to InternxtConfig.FOLDER_CONTENT_OFFSET_PAGE_SIZE.toString(),
                        "offset" to offset.toString(),
                        "sort" to "uuid",
                        "order" to "ASC",
                    ),
                    socketTimeoutMs = listingSocketTimeoutMs,
                )
            val page = parse(body)
            if (page.isEmpty()) return items
            val first = uuidOf(page.first())
            if (first == previousFirst) {
                throw InternxtApiException(
                    "The paged $stream listing of folder $folderUuid returned the same page twice (offset $offset): " +
                        "the server does not honour the offset, so the listing cannot be completed",
                    statusCode = 503,
                )
            }
            if (++pages > MAX_LISTING_PAGES) {
                throw InternxtApiException(
                    "The paged $stream listing of folder $folderUuid exceeded $MAX_LISTING_PAGES pages without ending",
                    statusCode = 503,
                )
            }
            previousFirst = first
            items.addAll(page)
            offset += page.size
        }
    }

    suspend fun listFiles(
        updatedAt: String? = null,
        limit: Int = InternxtConfig.LISTING_PAGE_SIZE,
        offset: Int = 0,
        status: String = "ALL",
        sort: String = "uuid",
    ): List<InternxtFile> =
        listFilesDedup.load("$updatedAt|$limit|$offset|$status|$sort", currentPriority()) {
            val body =
                authenticatedGet(
                    "$baseUrl/files",
                    listingQueryParams(updatedAt, limit, offset, status, sort),
                    socketTimeoutMs = listingSocketTimeoutMs,
                )
            json.decodeFromString<List<InternxtFile>>(body)
        }

    suspend fun listFolders(
        updatedAt: String? = null,
        limit: Int = InternxtConfig.LISTING_PAGE_SIZE,
        offset: Int = 0,
        status: String = "ALL",
        sort: String = "uuid",
    ): List<InternxtFolder> =
        listFoldersDedup.load("$updatedAt|$limit|$offset|$status|$sort", currentPriority()) {
            val body =
                authenticatedGet(
                    "$baseUrl/folders",
                    listingQueryParams(updatedAt, limit, offset, status, sort),
                    socketTimeoutMs = listingSocketTimeoutMs,
                )
            json.decodeFromString<List<InternxtFolder>>(body)
        }

    // The cursor listings of a full enumeration (see InternxtCursorListing.kt). The first page names
    // [updatedAt], every later page only the [cursor] the page before it returned, with the same
    // status and limit. A page's nextCursor is null on the last page. They are account-wide
    // listings: the read-idle watchdog rides at the listing limit like the offset ones above, so a
    // slow page is never cut by the engine's own default timer. A null [status] sends no status
    // filter (the server then answers rows of every status, each naming its own, and issues cursors
    // without a status; it refuses a cursor sent with another status than it was issued for).
    suspend fun getFoldersSync(
        updatedAt: String?,
        cursor: String?,
        status: String? = SYNC_LISTING_STATUS,
        limit: Int = InternxtConfig.SYNC_PAGE_SIZE,
    ): SyncPage<InternxtFolder> =
        parseFoldersSyncPage(
            authenticatedGet(
                "$baseUrl/folders/sync",
                syncQueryParams(updatedAt, cursor, status, limit),
                socketTimeoutMs = listingSocketTimeoutMs,
            ),
            rowsCarryStatus = status == null,
        )

    suspend fun getFilesSync(
        updatedAt: String?,
        cursor: String?,
        status: String? = SYNC_LISTING_STATUS,
        limit: Int = InternxtConfig.SYNC_PAGE_SIZE,
    ): SyncPage<InternxtFile> =
        parseFilesSyncPage(
            authenticatedGet(
                "$baseUrl/files/sync",
                syncQueryParams(updatedAt, cursor, status, limit),
                socketTimeoutMs = listingSocketTimeoutMs,
            ),
            rowsCarryStatus = status == null,
        )

    suspend fun getFileMeta(uuid: String): InternxtFile =
        fileMetaDedup.load(uuid, currentPriority()) {
            val body = authenticatedGet("$baseUrl/files/$uuid/meta")
            json.decodeFromString<InternxtFile>(body)
        }

    // A 200 whose body the model cannot read is not a transport failure: the same body comes back on every attempt, so it
    // is not retried and is told apart from the HTTP errors, with the server's request id for its support.
    suspend fun getFolderMeta(uuid: String): InternxtFolder =
        folderMetaDedup.load(uuid, currentPriority()) {
            val reply = authenticatedGetReply("$baseUrl/folders/$uuid/meta")
            try {
                json.decodeFromString<InternxtFolder>(reply.body)
            } catch (e: SerializationException) {
                throw InternxtUnreadableResponse("The meta of folder $uuid cannot be read: ${unreadableReason(e)}", reply.requestId)
            }
        }

    // What an unreadable body's report says of it: the missing fields by name, else the decoder's class and the JSON path
    // of the place it failed at (field names and indices only). Never the decoder's message, which quotes the body (and the
    // body holds names, ids and, on some routes, keys), and not the decoder's exception as a cause, for the same reason.
    @OptIn(ExperimentalSerializationApi::class) // MissingFieldException.missingFields: names of fields only
    private fun unreadableReason(e: SerializationException): String {
        if (e is MissingFieldException) return "a required field is missing: ${e.missingFields.joinToString()}"
        val at = JSON_PATH.find(e.message.orEmpty())?.groupValues?.get(1)
        return if (at == null) e.javaClass.simpleName else "${e.javaClass.simpleName} at $at"
    }

    // The folder uuids of the account's backup devices (`GET /backup/deviceAsFolder`, what the desktop app lists): each
    // device is a folder without a parent. Null when the account has no backups: the server answers 400 while backups are
    // not enabled for the user, and a gateway without the route 404. Every other failure propagates. The GET stands
    // outside an enumeration's connect-outage budget and records no throttle: the census asks it after the listing is
    // already assembled, and a failure must not stall the gather or open the drive storm circuit.
    internal suspend fun listBackupDeviceFolders(): Set<String>? {
        val body =
            try {
                withContext(coroutineContext.minusKey(EnumerationOutageRetry)) {
                    measurementGet("$baseUrl/backup/deviceAsFolder")
                }
            } catch (e: InternxtApiException) {
                if (e.statusCode == 400 || e.statusCode == 404) return null
                throw e
            }
        return json.decodeFromString<List<BackupDeviceFolder>>(body).mapTo(LinkedHashSet()) { it.uuid }
    }

    // One attempt, and not an enumeration GET: no outage schedule, no transient-retry ladder, no throttle and no
    // I/O-retry count. A 401 is still refreshed once. A connect failure propagates as status 0; a closed connection
    // before any response as 503, the same statuses [authenticatedGetReply] ends on.
    private suspend fun measurementGet(url: String): String =
        withAuthRetry { creds ->
            driveBudget.awaitSlot()
            try {
                val response =
                    httpClient.get(url) {
                        applyAuth(creds)
                    }
                checkResponse(response)
                driveBudget.recordSuccess()
                response.bodyAsText()
            } catch (e: java.io.IOException) {
                val closed = e.closedBeforeResponse()
                throw InternxtApiException(
                    if (closed) "Server closed connection for GET $url" else "Connection error for GET $url",
                    if (closed) 503 else 0,
                    cause = e,
                )
            }
        }

    /**
     * What the account shares by itself: `GET /sharings/folders`, then `GET /sharings/files`, [InternxtConfig.SHARINGS_PAGE_SIZE]
     * per page from page 0 until a short page. Each route answers both directions (what the account shares and what is shared
     * with it), so only the items with `sharedWithMe == false` that are live become references, once each by uuid (the
     * server's paging has no stable order, so a page can repeat an item and skip one; a later call finds the skipped one), at
     * most [MAX_SHARED_REFS]. A 404 or 405 on the first page of a route is a gateway without the route: [SharedByMe.Unsupported].
     * Every other failure propagates as everywhere else (the retry of a 5xx, one token refresh on a 401); a page that cannot be
     * read, or a server that does not advance, ends the call with an exception and no partial list. The array of the route that
     * was asked is required on every page (`folders` on the folders route, `files` on the files route): an answer without it, with
     * a null in its place or with something that is no array is a page that cannot be read, not an empty page, while `[]` is an
     * empty page and the array of the other route may be absent. A listing is therefore never taken for complete on an answer
     * that did not list anything.
     *
     * These answers carry credentials, keys and tokens per item. Nothing here reads them, and nothing here may print the body
     * of an answer: see [checkResponse] and [decodeSharingsPage].
     */
    internal suspend fun listSharedByMe(): SharedByMe {
        val collected = SharedRefCollector(MAX_SHARED_REFS)
        for (isFolder in listOf(true, false)) {
            var page = 0
            while (true) {
                val items = sharingsPage(isFolder, page) ?: return SharedByMe.Unsupported
                val fresh = collected.offer(items, isFolder)
                if (collected.truncated) return collected.result()
                if (isFolder && collected.foldersFull) break
                if (items.size < InternxtConfig.SHARINGS_PAGE_SIZE) break
                if (fresh == 0) {
                    throw InternxtApiException(
                        "The sharings ${sharingsRoute(isFolder)} listing returned page $page without a single new item: " +
                            "the server does not honour the page, so the listing cannot be completed",
                        statusCode = 503,
                    )
                }
                if (++page >= MAX_LISTING_PAGES) {
                    throw InternxtApiException(
                        "The sharings ${sharingsRoute(isFolder)} listing exceeded $MAX_LISTING_PAGES pages without ending",
                        statusCode = 503,
                    )
                }
            }
        }
        return collected.result()
    }

    /**
     * The files the account changed most recently: `GET /files/recents?limit=<n>`, a bare array of files, newest change first,
     * files only, the server's window of seven days. The uuids of the live ones, in the server's order, once each; null when
     * the gateway has no such route (HTTP 404 or 405). [limit] is clamped to what the server accepts, 1 to 1000 (outside it
     * the server answers 400). Every other failure propagates as everywhere else.
     */
    internal suspend fun listRecentFiles(limit: Int): List<String>? {
        val params = linkedMapOf("limit" to limit.coerceIn(1, MAX_RECENT_FILES).toString())
        val body =
            try {
                authenticatedGet("$baseUrl/files/recents", params)
            } catch (e: InternxtApiException) {
                if (e.statusCode == 404 || e.statusCode == 405) return null
                throw e
            }
        return json.decodeFromString<List<InternxtFile>>(body)
            .filter { !it.deleted && !it.removed && it.status == "EXISTS" }
            .map { it.uuid }
            .distinct()
    }

    /** What [listFavorites] kept: the uuids of the live favorites, folders first, and whether the cap cut the list. */
    internal class FavoriteList(
        val refs: List<ViewRef>,
        val truncated: Boolean,
    )

    /**
     * The items the account marked as favorites: `GET /favorites?type=folder|file&limit=1000&offset=<n>&sort=uuid&order=ASC`,
     * one stream per type (folders first, then files; folders only with [includeFolders]), each by offset. The offset advances
     * by the rows a page returned and the stream ends on a short or empty page. Live items only (files `EXISTS`, folders neither
     * deleted nor removed), once each by uuid (offset paging over a list that changes can repeat an item or skip one), at most
     * [MAX_FAVORITE_REFS] per stream (folders and files are counted apart, so a long run of folders never crowds the files out); one more makes the list truncated. Null when the gateway has no such route (HTTP 404 or 405 on the
     * first page of a stream). Every other failure propagates as everywhere else.
     */
    internal suspend fun listFavorites(includeFolders: Boolean): FavoriteList? {
        val collected = FavoriteCollector()
        for (isFolder in if (includeFolders) listOf(true, false) else listOf(false)) {
            var offset = 0
            var pages = 0
            while (true) {
                val rows = favoritesPage(isFolder, offset) ?: return null
                if (!collected.offer(rows, isFolder)) break
                if (rows.size < FAVORITES_PAGE_SIZE) break
                offset += rows.size
                if (++pages >= MAX_LISTING_PAGES) {
                    throw InternxtApiException("The favorites listing exceeded $MAX_LISTING_PAGES pages without ending", statusCode = 503)
                }
            }
        }
        return FavoriteList(collected.refs.values.toList(), collected.truncated)
    }

    // The references kept so far, once each by uuid; truncated once a live item beyond MAX_FAVORITE_REFS of its stream turns up.
    private class FavoriteCollector {
        val refs = LinkedHashMap<String, ViewRef>()
        private val seen = HashSet<String>()
        private var folders = 0
        private var files = 0
        var truncated = false
            private set

        /** Takes one page; false when the stream is full (a live item beyond its cap turned up) and must not be read further. */
        fun offer(
            rows: List<FavoriteRow>,
            isFolder: Boolean,
        ): Boolean {
            for (row in rows) {
                if (!seen.add(row.uuid) || !row.live) continue
                if ((if (isFolder) folders else files) >= MAX_FAVORITE_REFS) {
                    truncated = true
                    return false
                }
                if (isFolder) folders++ else files++
                refs[row.uuid] = ViewRef(row.uuid, isFolder)
            }
            return true
        }
    }

    private class FavoriteRow(
        val uuid: String,
        val live: Boolean,
    )

    // One page of one stream; null when the gateway has no such route (404 or 405 on the first page).
    private suspend fun favoritesPage(
        isFolder: Boolean,
        offset: Int,
    ): List<FavoriteRow>? {
        val params =
            linkedMapOf(
                "type" to if (isFolder) "folder" else "file",
                "limit" to FAVORITES_PAGE_SIZE.toString(),
                "offset" to offset.toString(),
                "sort" to "uuid",
                "order" to "ASC",
            )
        val body =
            try {
                authenticatedGet("$baseUrl/favorites", params)
            } catch (e: InternxtApiException) {
                if (offset == 0 && (e.statusCode == 404 || e.statusCode == 405)) return null
                throw e
            }
        return if (isFolder) {
            json.decodeFromString<List<InternxtFolder>>(body).map { FavoriteRow(it.uuid, !it.deleted && !it.removed) }
        } else {
            json.decodeFromString<List<InternxtFile>>(body).map { FavoriteRow(it.uuid, !it.deleted && !it.removed && it.status == "EXISTS") }
        }
    }

    private fun sharingsRoute(isFolder: Boolean): String = if (isFolder) "folders" else "files"

    // One page of one route; null when the gateway has no such route (404 or 405 on page 0).
    private suspend fun sharingsPage(
        isFolder: Boolean,
        page: Int,
    ): List<SharedItemWire>? {
        val route = sharingsRoute(isFolder)
        val params = linkedMapOf("page" to page.toString(), "perPage" to InternxtConfig.SHARINGS_PAGE_SIZE.toString())
        val reply =
            try {
                authenticatedGetReply("$baseUrl/sharings/$route", params)
            } catch (e: InternxtApiException) {
                if (page == 0 && (e.statusCode == 404 || e.statusCode == 405)) return null
                throw e
            }
        return decodeSharingsPage(reply, isFolder, route, page)
    }

    // The array of the requested route is required: an answer without it (a missing key, a null, or an error object that
    // holds no arrays at all) is not an empty page, it is an answer that cannot be read, and the paging must not take it for
    // the end of a complete listing. A value that is no array fails the decoder (see [readSharingsAnswer]). An empty array
    // is a valid empty page; the array of the other route may be absent.
    private fun decodeSharingsPage(
        reply: GetReply,
        isFolder: Boolean,
        route: String,
        page: Int,
    ): List<SharedItemWire> {
        val answer = readSharingsAnswer(reply, route, page)
        return (if (isFolder) answer.folders else answer.files)
            ?: throw unreadableSharingsPage(reply, route, page, "the $route array is missing or null")
    }

    // The failure names the route, the page and what the decoder says about the structure (the class, and the fields a
    // required one is missing from), never the decoder's own message: that quotes the body, and the body holds the secrets.
    // The decoder's exception is not passed on as the cause for the same reason.
    @OptIn(ExperimentalSerializationApi::class) // MissingFieldException.missingFields: names of fields only
    @Suppress("SwallowedException")
    private fun readSharingsAnswer(
        reply: GetReply,
        route: String,
        page: Int,
    ): SharedItemsPageWire =
        try {
            json.decodeFromString<SharedItemsPageWire>(reply.body)
        } catch (e: MissingFieldException) {
            throw unreadableSharingsPage(reply, route, page, "a required field is missing: ${e.missingFields.joinToString()}")
        } catch (e: SerializationException) {
            throw unreadableSharingsPage(reply, route, page, e.javaClass.simpleName)
        }

    private fun unreadableSharingsPage(
        reply: GetReply,
        route: String,
        page: Int,
        what: String,
    ) = InternxtUnreadableResponse("The sharings $route listing (page $page) cannot be read: $what", reply.requestId)

    suspend fun createFile(
        bucket: String,
        folderUuid: String,
        plainName: String,
        encryptedName: String,
        size: Long,
        type: String?,
        fileId: String? = null,
        // archive#486: CreateFileDto takes an optional modificationTime; without it the server stamps the upload time.
        modificationTime: java.time.Instant? = null,
    ): InternxtFile =
        retryOnTransient {
            withAuthRetry { creds ->
                driveBudget.awaitSlot()
                try {
                    val requestBody =
                        kotlinx.serialization.json.buildJsonObject {
                            put("bucket", kotlinx.serialization.json.JsonPrimitive(bucket))
                            put("folderUuid", kotlinx.serialization.json.JsonPrimitive(folderUuid))
                            put("plainName", kotlinx.serialization.json.JsonPrimitive(plainName))
                            put("name", kotlinx.serialization.json.JsonPrimitive(encryptedName))
                            put("size", kotlinx.serialization.json.JsonPrimitive(size))
                            put("encryptVersion", kotlinx.serialization.json.JsonPrimitive("03-aes"))
                            if (type != null) put("type", kotlinx.serialization.json.JsonPrimitive(type))
                            if (fileId != null) put("fileId", kotlinx.serialization.json.JsonPrimitive(fileId))
                            if (modificationTime != null) {
                                put("modificationTime", kotlinx.serialization.json.JsonPrimitive(modificationTime.toString()))
                            }
                        }
                    val response =
                        httpClient.post("$baseUrl/files") {
                            applyAuth(creds)
                            contentType(ContentType.Application.Json)
                            setBody(requestBody.toString())
                        }
                    checkResponse(response)
                    driveBudget.recordSuccess()
                    json.decodeFromString<InternxtFile>(response.bodyAsText())
                } catch (e: InternxtApiException) {
                    if (e.statusCode == 429 || e.statusCode == 503) driveBudget.recordThrottle(e.retryAfterMs ?: 0L)
                    throw e
                }
            }
        }

    /**
     * UD-366: replace an existing file's content in place via `PUT /files/{uuid}`.
     *
     * Spec: `ReplaceFileDto` accepts only `fileId` (new bridge bucket-entry id), `size`
     * (required), and optional `modificationTime`. The endpoint preserves the existing
     * file's bucket, encrypted name, parent folder, and encryptVersion — there is nothing
     * to re-derive on the client side. Returns the updated `FileDto` (same `uuid`,
     * swapped `fileId`). archive#485: `fileId` is required when `size > 0` and must be absent when
     * `size == 0` (an empty file has no bucket entry); pass null for an empty replacement.
     */
    suspend fun replaceFile(
        uuid: String,
        size: Long,
        fileId: String?,
        modificationTime: java.time.Instant? = null,
    ): InternxtFile =
        retryOnTransient {
            withAuthRetry { creds ->
                driveBudget.awaitSlot()
                try {
                    val requestBody =
                        kotlinx.serialization.json.buildJsonObject {
                            if (fileId != null) put("fileId", kotlinx.serialization.json.JsonPrimitive(fileId))
                            put("size", kotlinx.serialization.json.JsonPrimitive(size))
                            if (modificationTime != null) {
                                put("modificationTime", kotlinx.serialization.json.JsonPrimitive(modificationTime.toString()))
                            }
                        }
                    val response =
                        httpClient.put("$baseUrl/files/$uuid") {
                            applyAuth(creds)
                            contentType(ContentType.Application.Json)
                            setBody(requestBody.toString())
                        }
                    checkResponse(response)
                    driveBudget.recordSuccess()
                    json.decodeFromString<InternxtFile>(response.bodyAsText())
                } catch (e: InternxtApiException) {
                    if (e.statusCode == 429 || e.statusCode == 503) driveBudget.recordThrottle(e.retryAfterMs ?: 0L)
                    throw e
                }
            }
        }

    /**
     * UD-368: bulk folder creation under a single parent via the polymorphic `POST /folders`.
     *
     * Spec: `CreateFolderDto` accepts either `{plainName}` (single) OR
     * `{folders: [{plainName}], parentFolderUuid}` (bulk, 1–5 items per call). The bulk
     * response is undocumented in the swagger 200 schema (which lists only single-folder
     * `FolderDto`) — we parse as `ResultFoldersDto = {result: [FolderDto]}` based on the
     * sibling endpoint convention; if Internxt ships an integration test or the live API
     * returns a different shape, this will fail loudly at deserialise time.
     *
     * The 409 conflict response is bulk-specific: `CreateBulkFoldersConflictResponseDto` =
     * `{message, existentFolders: [string]}` (names, not UUIDs). Caller is responsible for
     * the 409-recovery dance — there is no per-item partial-success path on the wire.
     *
     * Note: the bulk DTO documents `plainName` only per item; the existing single-folder
     * `createFolder` also sends `name` (encrypted) as an undocumented field that the server
     * accepts. We mirror that here for parity, even though the spec is silent.
     */
    suspend fun createFoldersBatch(
        parentUuid: String,
        items: List<Pair<String, String>>,
    ): List<InternxtFolder> {
        require(items.isNotEmpty()) { "createFoldersBatch requires at least one item" }
        require(items.size <= 5) { "POST /folders bulk form is server-capped at 5; got ${items.size}" }
        return retryOnTransient {
            withAuthRetry { creds ->
                driveBudget.awaitSlot()
                try {
                    val requestBody =
                        kotlinx.serialization.json.buildJsonObject {
                            put("parentFolderUuid", kotlinx.serialization.json.JsonPrimitive(parentUuid))
                            put(
                                "folders",
                                kotlinx.serialization.json.buildJsonArray {
                                    items.forEach { (plainName, encryptedName) ->
                                        add(
                                            kotlinx.serialization.json.buildJsonObject {
                                                put("plainName", kotlinx.serialization.json.JsonPrimitive(plainName))
                                                put("name", kotlinx.serialization.json.JsonPrimitive(encryptedName))
                                            },
                                        )
                                    }
                                },
                            )
                        }
                    val response =
                        httpClient.post("$baseUrl/folders") {
                            applyAuth(creds)
                            contentType(ContentType.Application.Json)
                            setBody(requestBody.toString())
                        }
                    checkResponse(response)
                    driveBudget.recordSuccess()
                    // Try ResultFoldersDto shape `{result: [FolderDto]}`; fall back to bare array.
                    val text = response.bodyAsText()
                    val rootElement = json.parseToJsonElement(text)
                    val arr =
                        when {
                            rootElement is kotlinx.serialization.json.JsonObject && "result" in rootElement ->
                                rootElement["result"]!!
                            rootElement is kotlinx.serialization.json.JsonObject && "folders" in rootElement ->
                                rootElement["folders"]!!
                            rootElement is kotlinx.serialization.json.JsonArray ->
                                rootElement
                            else ->
                                throw InternxtApiException(
                                    "createFoldersBatch: unexpected response shape (no result/folders array): ${text.take(200)}",
                                    statusCode = 0,
                                )
                        }
                    json.decodeFromJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(InternxtFolder.serializer()),
                        arr,
                    )
                } catch (e: InternxtApiException) {
                    if (e.statusCode == 429 || e.statusCode == 503) driveBudget.recordThrottle(e.retryAfterMs ?: 0L)
                    throw e
                }
            }
        }
    }

    suspend fun createFolder(
        parentUuid: String,
        plainName: String,
        encryptedName: String,
    ): InternxtFolder =
        retryOnTransient {
            withAuthRetry { creds ->
                driveBudget.awaitSlot()
                try {
                    val requestBody =
                        kotlinx.serialization.json.buildJsonObject {
                            put("parentFolderUuid", kotlinx.serialization.json.JsonPrimitive(parentUuid))
                            put("plainName", kotlinx.serialization.json.JsonPrimitive(plainName))
                            put("name", kotlinx.serialization.json.JsonPrimitive(encryptedName))
                        }
                    val response =
                        httpClient.post("$baseUrl/folders") {
                            applyAuth(creds)
                            contentType(ContentType.Application.Json)
                            setBody(requestBody.toString())
                        }
                    checkResponse(response)
                    driveBudget.recordSuccess()
                    json.decodeFromString<InternxtFolder>(response.bodyAsText())
                } catch (e: InternxtApiException) {
                    if (e.statusCode == 429 || e.statusCode == 503) driveBudget.recordThrottle(e.retryAfterMs ?: 0L)
                    throw e
                }
            }
        }

    /**
     * UD-369: rename a file in place via `PUT /files/{uuid}/meta`.
     *
     * Spec: `UpdateFileMetaDto` requires `{plainName, type}`. Notably, the endpoint does NOT
     * accept the `name` (encrypted) field — only the plaintext name + type are updated. The
     * encrypted-name field stays stale after a rename. Unidrive uses `plainName` for path
     * resolution everywhere ([InternxtProvider.kt] `fileToCloudItem`, `buildFolderPath`,
     * `resolveFolder`), so the staleness is invisible to the sync engine — but document it
     * because it's a sharp edge for any future code that relies on the encrypted name.
     */
    suspend fun renameFile(
        uuid: String,
        plainName: String,
        type: String?,
    ): InternxtFile =
        retryOnTransient {
            withAuthRetry { creds ->
                driveBudget.awaitSlot()
                try {
                    val requestBody =
                        kotlinx.serialization.json.buildJsonObject {
                            put("plainName", kotlinx.serialization.json.JsonPrimitive(plainName))
                            // Spec says type is required; empty string is valid for extensionless files.
                            put("type", kotlinx.serialization.json.JsonPrimitive(type ?: ""))
                        }
                    val response =
                        httpClient.put("$baseUrl/files/$uuid/meta") {
                            applyAuth(creds)
                            contentType(ContentType.Application.Json)
                            setBody(requestBody.toString())
                        }
                    checkResponse(response)
                    driveBudget.recordSuccess()
                    json.decodeFromString<InternxtFile>(response.bodyAsText())
                } catch (e: InternxtApiException) {
                    if (e.statusCode == 429 || e.statusCode == 503) driveBudget.recordThrottle(e.retryAfterMs ?: 0L)
                    throw e
                }
            }
        }

    /**
     * UD-369: rename a folder in place via `PUT /folders/{uuid}/meta`.
     *
     * Spec: `UpdateFolderMetaDto` requires `{plainName}` only — same encrypted-name caveat
     * as [renameFile].
     */
    suspend fun renameFolder(
        uuid: String,
        plainName: String,
    ): InternxtFolder =
        retryOnTransient {
            withAuthRetry { creds ->
                driveBudget.awaitSlot()
                try {
                    val requestBody =
                        kotlinx.serialization.json.buildJsonObject {
                            put("plainName", kotlinx.serialization.json.JsonPrimitive(plainName))
                        }
                    val response =
                        httpClient.put("$baseUrl/folders/$uuid/meta") {
                            applyAuth(creds)
                            contentType(ContentType.Application.Json)
                            setBody(requestBody.toString())
                        }
                    checkResponse(response)
                    driveBudget.recordSuccess()
                    json.decodeFromString<InternxtFolder>(response.bodyAsText())
                } catch (e: InternxtApiException) {
                    if (e.statusCode == 429 || e.statusCode == 503) driveBudget.recordThrottle(e.retryAfterMs ?: 0L)
                    throw e
                }
            }
        }

    suspend fun moveFile(
        uuid: String,
        destinationFolderUuid: String,
    ): InternxtFile =
        retryOnTransient {
            withAuthRetry { creds ->
                driveBudget.awaitSlot()
                try {
                    val requestBody =
                        kotlinx.serialization.json.buildJsonObject {
                            put("destinationFolder", kotlinx.serialization.json.JsonPrimitive(destinationFolderUuid))
                        }
                    val response =
                        httpClient.patch("$baseUrl/files/$uuid") {
                            applyAuth(creds)
                            contentType(ContentType.Application.Json)
                            setBody(requestBody.toString())
                        }
                    checkResponse(response)
                    driveBudget.recordSuccess()
                    json.decodeFromString<InternxtFile>(response.bodyAsText())
                } catch (e: InternxtApiException) {
                    if (e.statusCode == 429 || e.statusCode == 503) driveBudget.recordThrottle(e.retryAfterMs ?: 0L)
                    throw e
                }
            }
        }

    suspend fun moveFolder(
        uuid: String,
        destinationFolderUuid: String,
    ): InternxtFolder =
        retryOnTransient {
            withAuthRetry { creds ->
                driveBudget.awaitSlot()
                try {
                    val requestBody =
                        kotlinx.serialization.json.buildJsonObject {
                            put("destinationFolder", kotlinx.serialization.json.JsonPrimitive(destinationFolderUuid))
                        }
                    val response =
                        httpClient.patch("$baseUrl/folders/$uuid") {
                            applyAuth(creds)
                            contentType(ContentType.Application.Json)
                            setBody(requestBody.toString())
                        }
                    checkResponse(response)
                    driveBudget.recordSuccess()
                    json.decodeFromString<InternxtFolder>(response.bodyAsText())
                } catch (e: InternxtApiException) {
                    if (e.statusCode == 429 || e.statusCode == 503) driveBudget.recordThrottle(e.retryAfterMs ?: 0L)
                    throw e
                }
            }
        }

    suspend fun deleteFile(uuid: String) {
        // 404 is treated as already-gone (idempotent delete) — short-circuits
        // before checkResponse so retryOnTransient never sees it as transient.
        retryOnTransient {
            withAuthRetry { creds ->
                driveBudget.awaitSlot()
                try {
                    val response =
                        httpClient.delete("$baseUrl/files/$uuid") {
                            applyAuth(creds)
                        }
                    if (response.status != HttpStatusCode.NotFound) checkResponse(response)
                    driveBudget.recordSuccess()
                } catch (e: InternxtApiException) {
                    if (e.statusCode == 429 || e.statusCode == 503) driveBudget.recordThrottle(e.retryAfterMs ?: 0L)
                    throw e
                }
            }
        }
    }

    /**
     * UD-367: move items to Internxt's recycle bin via `POST /storage/trash/add`.
     *
     * Spec: `MoveItemsToTrashDto` accepts `items: [{uuid, type: "file"|"folder"}]` with
     * a server-side cap of 50 items per call. Replaces the permanent `DELETE /files/{uuid}`
     * + `DELETE /folders` collection-form path for routine sync-driven deletes — the user
     * recovers via Internxt's web UI within the configured retention window.
     *
     * The permanent `deleteFile` / `deleteFolder` primitives remain available for explicit
     * purge actions (e.g. a future `unidrive trash purge --remote`).
     *
     * @param items list of (uuid, type) pairs where type is "file" or "folder". Caller is
     *   responsible for chunking >50 items into multiple calls — this method does not split.
     */
    suspend fun trashItems(items: List<Pair<String, String>>) {
        require(items.isNotEmpty()) { "trashItems requires at least one item" }
        require(items.size <= 50) { "trashItems is server-capped at 50; got ${items.size}" }
        require(items.all { it.second == "file" || it.second == "folder" }) {
            "type must be 'file' or 'folder'; got ${items.map { it.second }.distinct()}"
        }
        retryOnTransient {
            withAuthRetry { creds ->
                driveBudget.awaitSlot()
                try {
                    val requestBody =
                        kotlinx.serialization.json.buildJsonObject {
                            put(
                                "items",
                                kotlinx.serialization.json.buildJsonArray {
                                    items.forEach { (uuid, type) ->
                                        add(
                                            kotlinx.serialization.json.buildJsonObject {
                                                put("uuid", kotlinx.serialization.json.JsonPrimitive(uuid))
                                                put("type", kotlinx.serialization.json.JsonPrimitive(type))
                                            },
                                        )
                                    }
                                },
                            )
                        }
                    val response =
                        httpClient.post("$baseUrl/storage/trash/add") {
                            applyAuth(creds)
                            contentType(ContentType.Application.Json)
                            setBody(requestBody.toString())
                        }
                    // 200 OK — items moved. 400 means at least one uuid was invalid; let it surface.
                    checkResponse(response)
                    driveBudget.recordSuccess()
                } catch (e: InternxtApiException) {
                    if (e.statusCode == 429 || e.statusCode == 503) driveBudget.recordThrottle(e.retryAfterMs ?: 0L)
                    throw e
                }
            }
        }
    }

    suspend fun deleteFolder(uuid: String) {
        // 404 is treated as already-gone (idempotent delete) — short-circuits
        // before checkResponse so retryOnTransient never sees it as transient.
        retryOnTransient {
            withAuthRetry { creds ->
                driveBudget.awaitSlot()
                try {
                    val requestBody =
                        kotlinx.serialization.json.buildJsonObject {
                            put(
                                "items",
                                kotlinx.serialization.json.buildJsonArray {
                                    add(
                                        kotlinx.serialization.json.buildJsonObject {
                                            put("uuid", kotlinx.serialization.json.JsonPrimitive(uuid))
                                            put("type", kotlinx.serialization.json.JsonPrimitive("folder"))
                                        },
                                    )
                                },
                            )
                        }
                    val response =
                        httpClient.delete("$baseUrl/folders") {
                            applyAuth(creds)
                            contentType(ContentType.Application.Json)
                            setBody(requestBody.toString())
                        }
                    if (response.status != HttpStatusCode.NotFound) checkResponse(response)
                    driveBudget.recordSuccess()
                } catch (e: InternxtApiException) {
                    if (e.statusCode == 429 || e.statusCode == 503) driveBudget.recordThrottle(e.retryAfterMs ?: 0L)
                    throw e
                }
            }
        }
    }

    private fun isHttps(url: String): Boolean =
        try {
            java.net.URI(url).scheme.equals("https", ignoreCase = true)
        } catch (_: java.net.URISyntaxException) {
            false
        }

    /** Streams encrypted bytes through [cipher] to [destination], hashing plaintext before atomic publication. */
    @Suppress("ThrowsCount") // The baseline entry predates target mtime and the streamed content receipt.
    internal suspend fun downloadFileStreaming(
        downloadUrl: String,
        cipher: javax.crypto.Cipher,
        destination: Path,
        expectedSize: Long? = null,
        expectedShardHash: ShardHashExpectation.Checkable? = null,
        targetMtimeMillis: Long? = null,
    ): InternxtDownloadedContent {
        // The content is AES-CTR with no MAC, so a shard fetched over plain http can be altered on the way unseen
        // (the recorded hash is the only check, and it too comes from a response that is not ours to trust blindly).
        // The URL is never put in the message: it can carry a signed token.
        if (!isHttps(downloadUrl)) {
            throw RemoteContentRefusedDownloadException(
                "Internxt shard URL for ${destination.fileName} is not https; refusing to download over an unprotected connection",
                reason = "its shard is not served over https",
            )
        }
        bridgeBudget.awaitSlot()
        try {
            val downloaded =
                httpClient.prepareGet(downloadUrl) {
                    // archive#1065: the client's flat 600 s cap would cut a big file however steadily it moves.
                    timeout { requestTimeoutMillis = downloadRequestTimeoutMs(expectedSize) }
                }.execute { response ->
                    if (!response.status.isSuccess()) {
                        throw InternxtApiException("Download failed: ${response.status}", response.status.value, responseReceived = true)
                    }

                    assertNotHtml(response, contextMsg = "Download (encrypted) -> ${destination.fileName}")
                    // archive#536: the shard's Content-Length already says when the stored object is shorter
                    // than the size the drive reports (a multipart upload another client truncated) —
                    // refuse before reading a single byte instead of pulling 285 MiB that cannot
                    // succeed. archive#940: the same for a longer one; the stream cipher keeps the length, so
                    // the post-download guard would refuse it after the whole transfer too. An absent
                    // header (chunked) or an unknown declared size (0) stays with that post-download
                    // completeness guard, as before.
                    response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { contentLength ->
                        if (expectedSize != null && expectedSize > 0 && contentLength != expectedSize) {
                            val comparison = if (contentLength < expectedSize) "shorter" else "longer"
                            throw RemoteIncompleteDownloadException(
                                "Stored object for ${destination.fileName} is $comparison than the size the drive reports: " +
                                    "$contentLength of $expectedSize bytes",
                                storedBytes = contentLength,
                                declaredBytes = expectedSize,
                            )
                        }
                    }
                    val channel: ByteReadChannel = response.body()
                    var written = 0L
                    var ciphertextBytes = 0L
                    val ciphertextDigest = java.security.MessageDigest.getInstance("SHA-256")
                    val plaintextDigest = java.security.MessageDigest.getInstance("SHA-256")
                    val result = withContext(Dispatchers.IO) {
                        Files.createDirectories(destination.parent)
                        // archive#529: a name near the 255-byte component limit cannot take the suffix — stage short.
                        val tmpPath = destination.parent.resolve(
                            org.krost.unidrive.io.stagingSiblingName(destination.fileName.toString(), ".unidrive-tmp"),
                        )
                        try {
                            Files.newOutputStream(tmpPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { out ->
                                val buf = ByteArray(256 * 1024)
                                while (!channel.isClosedForRead) {
                                    val n = channel.readAvailable(buf)
                                    if (n <= 0) break
                                    ciphertextBytes += n
                                    ciphertextDigest.update(buf, 0, n)
                                    val decrypted = cipher.update(buf, 0, n)
                                    if (decrypted != null) {
                                        out.write(decrypted)
                                        plaintextDigest.update(decrypted)
                                        written += decrypted.size
                                    }
                                }
                                // A stream whose length differs from the declared size (it ended short or ran long, with
                                // no Content-Length to refuse it up front) is refused while it is still staged: the move
                                // below would otherwise replace the previous local copy with the wrong bytes. Before the
                                // hash check: a short stream cannot match the recorded hash, and its length is the
                                // truthful reason.
                                if (expectedSize != null && expectedSize > 0 && ciphertextBytes != expectedSize) {
                                    throw RemoteIncompleteDownloadException(
                                        "Incomplete download of ${destination.fileName}: got $ciphertextBytes of $expectedSize bytes",
                                        storedBytes = ciphertextBytes,
                                        declaredBytes = expectedSize,
                                    )
                                }
                                // archive#295: the stream cipher is malleable, so the ciphertext is checked against
                                // the hash the network recorded before a single plaintext byte is published.
                                // archive#940: the algorithm follows the recorded shape (the caller parsed it), and a
                                // refusal names it: the old SHA-256-only comparison reported every official
                                // client's RIPEMD-160(SHA-256) hash as a "SHA-256 mismatch".
                                expectedShardHash?.let { expected ->
                                    if (!expected.matches(ciphertextDigest.digest())) {
                                        throw RemoteContentRefusedDownloadException(
                                            "Internxt shard ${expected.algorithm} mismatch for ${destination.fileName}; refusing unverified content",
                                            reason = "${expected.algorithm} mismatch",
                                        )
                                    }
                                }
                                val finalBlock = cipher.doFinal()
                                if (finalBlock != null && finalBlock.isNotEmpty()) {
                                    out.write(finalBlock)
                                    plaintextDigest.update(finalBlock)
                                    written += finalBlock.size
                                }
                            }
                            targetMtimeMillis?.let {
                                Files.setLastModifiedTime(tmpPath, java.nio.file.attribute.FileTime.fromMillis(it))
                            }
                            val stagedSize = Files.size(tmpPath)
                            val stagedMtimeMillis = Files.getLastModifiedTime(tmpPath).toMillis()
                            val plaintextSha256 = HexFormat.of().formatHex(plaintextDigest.digest())
                            Files.move(tmpPath, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                            InternxtDownloadedContent(
                                written,
                                plaintextSha256,
                                stagedSize,
                                stagedMtimeMillis,
                                mtimeAppliedBeforePlacement = targetMtimeMillis != null,
                            )
                        } catch (e: Exception) {
                            Files.deleteIfExists(tmpPath)
                            throw e
                        }
                    }
                    result
                }
            bridgeBudget.recordSuccess()
            return downloaded
        } catch (e: InternxtApiException) {
            if (e.statusCode == 429 || e.statusCode == 503) bridgeBudget.recordThrottle(e.retryAfterMs ?: 0L)
            throw e
        } catch (e: HttpRequestTimeoutException) {
            throw InternxtDownloadBudgetExhaustedException(
                "Download of ${destination.fileName} used up its time budget of ${downloadRequestTimeoutMs(expectedSize) / 1000} s: ${e.message}",
                e,
            )
        }
    }

    private val bridgeUrl = "https://api.internxt.com"

    private suspend fun bridgeGet(
        path: String,
        params: Map<String, String> = emptyMap(),
    ): String {
        bridgeBudget.awaitSlot()
        try {
            // Bridge auth is HTTP Basic — JWT refresh wouldn't change bridgeUser/bridgeUserId,
            // so this stays out of withAuthRetry. Pass forceRefresh = false unconditionally.
            val creds = credentialsProvider(false)
            val authHeader = InternxtCrypto.bridgeAuthHeader(creds.bridgeUser, creds.bridgeUserId)
            val response =
                httpClient.get("$bridgeUrl$path") {
                    header("Authorization", authHeader)
                    header("x-api-version", "2")
                    accept(ContentType.Application.Json)
                    params.forEach { (k, v) -> parameter(k, v) }
                }
            checkResponse(response)
            bridgeBudget.recordSuccess()
            return response.bodyAsText()
        } catch (e: InternxtApiException) {
            if (e.statusCode == 429 || e.statusCode == 503) bridgeBudget.recordThrottle(e.retryAfterMs ?: 0L)
            throw e
        }
    }

    private suspend fun bridgePost(
        path: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): String {
        bridgeBudget.awaitSlot()
        try {
            // Bridge auth is HTTP Basic — JWT refresh wouldn't change bridgeUser/bridgeUserId,
            // so this stays out of withAuthRetry. Pass forceRefresh = false unconditionally.
            val creds = credentialsProvider(false)
            val authHeader = InternxtCrypto.bridgeAuthHeader(creds.bridgeUser, creds.bridgeUserId)
            val response =
                httpClient.post("$bridgeUrl$path") {
                    header("Authorization", authHeader)
                    header("x-api-version", "2")
                    contentType(ContentType.Application.Json)
                    headers.forEach { (k, v) -> header(k, v) }
                    setBody(body)
                }
            checkResponse(response)
            bridgeBudget.recordSuccess()
            return response.bodyAsText()
        } catch (e: InternxtApiException) {
            if (e.statusCode == 429 || e.statusCode == 503) bridgeBudget.recordThrottle(e.retryAfterMs ?: 0L)
            throw e
        }
    }

    suspend fun getBridgeFileInfo(
        bucket: String,
        fileId: String,
    ): BridgeFileInfo {
        val body = bridgeGet("/buckets/$bucket/files/$fileId/info")
        return json.decodeFromString<BridgeFileInfo>(body)
    }

    suspend fun getBridgeMirrors(
        bucket: String,
        fileId: String,
    ): List<Mirror> {
        val body = bridgeGet("/buckets/$bucket/files/$fileId", mapOf("limit" to BRIDGE_MIRROR_LIMIT.toString()))
        return json.decodeFromString<List<Mirror>>(body)
    }

    suspend fun startUpload(
        bucket: String,
        fileSize: Long,
    ): StartUploadResponse {
        val requestBody = """{"uploads":[{"index":0,"size":$fileSize}]}"""
        val body = bridgePost("/v2/buckets/$bucket/files/start?multiparts=1", requestBody)
        return json.decodeFromString<StartUploadResponse>(body)
    }

    /**
     * Registers the uploaded shard. [shardHash] is sent as it is: the upload passes RIPEMD-160(SHA-256(ciphertext)) as 40 hex like the
     * official clients (archive#946, [ShardHashWire.forFinish]).
     */
    suspend fun finishUpload(
        bucket: String,
        indexHex: String,
        shardHash: String,
        shardUuid: String,
        @Suppress("UNUSED_PARAMETER") idempotencyKey: String? = null,
    ): BucketEntry {
        val request =
            FinishUploadRequest(
                index = indexHex,
                shards = listOf(ShardMeta(hash = shardHash, uuid = shardUuid)),
            )
        // TODO: wire idempotencyKey to bridgePost headers once vendor ships Idempotency-Key support
        val body =
            bridgePost(
                "/v2/buckets/$bucket/files/finish",
                json.encodeToString(FinishUploadRequest.serializer(), request),
            )
        return json.decodeFromString<BucketEntry>(body)
    }

    suspend fun putEncryptedShard(
        url: String,
        data: ByteArray,
    ) {
        // OVH PUT to a presigned URL is idempotent at the S3 layer — same key,
        // same body overwrites cleanly — so retryOnTransient is safe here.
        // The streaming variant (putEncryptedShardFromFile) takes its retry at
        // the provider layer instead because that wrap also needs to keep
        // indexBytes / iv / fileKey stable across attempts (see Design constraint
        // in BACKLOG.md history).
        retryOnTransient {
            bridgeBudget.awaitSlot()
            watchedShardPut(url, data.size.toLong()) { progress -> progressTrackingBody(data, progress) }
        }
    }

    suspend fun putEncryptedShardFromFile(
        url: String,
        file: java.nio.file.Path,
        size: Long,
        onBytesSent: ((Long) -> Unit)? = null,
        progressIntervalMs: Long = SHARD_PROGRESS_INTERVAL_MS,
    ) {
        bridgeBudget.awaitSlot()
        watchedShardPut(url, size, onBytesSent, progressIntervalMs) { progress -> progressTrackingFileBody(file, size, progress) }
    }

    // archive#578: start a multipart session — the same files/start endpoint with multiparts=N
    // and ONE part spec carrying the total size. The bridge answers a single descriptor
    // whose `urls` array holds one presigned PUT URL per part and the session UploadId.
    suspend fun startMultipartUpload(
        bucket: String,
        totalSize: Long,
        numParts: Int,
    ): UploadDescriptor {
        val requestBody = """{"uploads":[{"index":0,"size":$totalSize}]}"""
        val body = bridgePost("/v2/buckets/$bucket/files/start?multiparts=$numParts", requestBody)
        val response = json.decodeFromString<StartUploadResponse>(body)
        val descriptor =
            response.uploads.singleOrNull()
                ?: throw ProviderException("Multipart start answered ${response.uploads.size} descriptors, expected 1")
        val problem =
            when {
                descriptor.urls.size != numParts -> "answered ${descriptor.urls.size} part URLs, expected $numParts"
                descriptor.uploadId.isNullOrBlank() -> "answered no UploadId"
                else -> null
            }
        if (problem != null) throw ProviderException("Multipart start $problem")
        return descriptor
    }

    // archive#578: PUT one part of the staged ciphertext — [size] bytes at [offset] — to the
    // part's presigned URL, under the same write-progress watchdog as the whole-file
    // shard PUT (archive#571 covers a part the same way it covers a shard). Returns the
    // server's ETag, which the finish call must echo per part. The parts ride their
    // own admission lane, sized to the session's parallelism: the shared bridge budget
    // admits four requests in total, so parts through it would silently cap
    // upload_concurrency above 4 (and crowd out every other bridge call while a large
    // upload runs). The lane's own throttle accounting lives here; a part's 429/503
    // also reaches the shared lane through watchedShardPut, keeping the bridge calls
    // spaced while a large upload runs.
    suspend fun putEncryptedPartFromFile(
        url: String,
        file: java.nio.file.Path,
        offset: Long,
        size: Long,
        budget: org.krost.unidrive.http.HttpRetryBudget,
        onBytesSent: ((Long) -> Unit)? = null,
        progressIntervalMs: Long = SHARD_PROGRESS_INTERVAL_MS,
    ): String? {
        budget.awaitSlot()
        return try {
            watchedShardPut(url, size, onBytesSent, progressIntervalMs) { progress ->
                progressTrackingFileRangeBody(file, offset, size, progress)
            }.also { budget.recordSuccess() }
        } catch (e: InternxtApiException) {
            if (e.statusCode == 429 || e.statusCode == 503) budget.recordThrottle(e.retryAfterMs ?: 0L)
            throw e
        }
    }

    // archive#578: complete the multipart session — the single finish endpoint, with the
    // shard list carrying the session's upload id and the ordered part list. Guarded
    // upstream: it is never called until every part has landed (the provider asserts
    // the part list is complete first). [hash] is sent as it is: like the single-shard
    // finish (archive#946) the caller passes RIPEMD-160(SHA-256(ciphertext)) as 40 hex.
    suspend fun finishMultipartUpload(
        bucket: String,
        indexHex: String,
        hash: String,
        uploadId: String,
        sessionUuid: String,
        parts: List<MultipartPart>,
    ): BucketEntry {
        val request =
            FinishMultipartUploadRequest(
                index = indexHex,
                shards =
                    listOf(
                        MultipartShardMeta(
                            uuid = sessionUuid,
                            hash = hash,
                            uploadId = uploadId,
                            parts = parts,
                        ),
                    ),
            )
        val body =
            bridgePost(
                "/v2/buckets/$bucket/files/finish",
                json.encodeToString(FinishMultipartUploadRequest.serializer(), request),
            )
        return json.decodeFromString<BucketEntry>(body)
    }

    // archive#571: one shard PUT under the write-progress watchdog. The body records every chunk the transport accepted; the
    // watchdog cuts the request only when no body byte was written for the idle window, or when the server did not
    // answer within the response wait after the last byte. A slow upload that keeps moving is never cut, however
    // long it takes (the archive#517 R1 socket timeout sized for 2 MiB/s cut a 1.36 GB file at 650 s on a 1.98 MB/s link,
    // on every attempt). The engine's socket and request timeouts are raised to the outer net for this request: its
    // read-idle socket timer runs from the start (the server is silent while the body flows), so it must not fire
    // before the watchdog can.
    private suspend fun watchedShardPut(
        url: String,
        sizeBytes: Long,
        onBytesSent: ((Long) -> Unit)? = null,
        progressIntervalMs: Long = SHARD_PROGRESS_INTERVAL_MS,
        body: (UploadProgress) -> io.ktor.http.content.OutgoingContent,
    ): String? {
        val progress = UploadProgress(sizeBytes)
        val responseWaitMs = UploadTimeoutPolicy.computeResponseWaitMs(sizeBytes, shardPutWatchdog.responseWaitFloorMs)
        val outerLimitMs =
            UploadTimeoutPolicy.computeWatchedOuterLimitMs(
                fileSize = sizeBytes,
                minThroughputBytesPerSecond = OVH_PUT_MIN_THROUGHPUT_BPS,
                idleWindowMs = shardPutWatchdog.idleWindowMs,
                responseWaitMs = responseWaitMs,
            )
        try {
            val response =
                reportingBytesSent(progress, onBytesSent, progressIntervalMs) {
                    withUploadWatchdog(
                        progress = progress,
                        idleWindowMs = shardPutWatchdog.idleWindowMs,
                        responseWaitMs = responseWaitMs,
                        tickMs = shardPutWatchdog.tickMs,
                        what = SHARD_PUT,
                    ) {
                        httpClient.put(url) {
                            timeout {
                                requestTimeoutMillis = outerLimitMs
                                socketTimeoutMillis = outerLimitMs
                            }
                            header("Content-Type", "application/octet-stream")
                            setBody(body(progress))
                        }
                    }
                }
            if (!response.status.isSuccess()) {
                throw InternxtApiException("Shard upload failed: ${response.status}", response.status.value, responseReceived = true)
            }
            bridgeBudget.recordSuccess()
            // archive#578: the part's ETag, echoed back per part by the multipart finish. Null
            // when the storage layer sends none (the single-part path ignores it). The
            // S3-convention surrounding quotes are normalized away so the finish body
            // and the tombstone carry the bare token.
            return response.headers["ETag"]?.trim('"')
        } catch (e: InternxtApiException) {
            if (e.statusCode == 429 || e.statusCode == 503) bridgeBudget.recordThrottle(e.retryAfterMs ?: 0L)
            throw e
        } catch (e: UploadWatchdogException) {
            log.warn("{} ({})", e.message, e.reason)
            throw e
        } catch (e: java.io.IOException) {
            throw shardPutFailure(progress, outerLimitMs, e)
        }
    }

    // archive#1197: while [block] (the PUT) runs, tell [onBytesSent] how many body bytes the transport accepted, at most once per
    // [intervalMs] and only when the number moved. The count is the one the watchdog keeps (bytes in the bounded
    // buffers on their way to the socket, so it runs ahead of the server by the buffer size, ten MiB at most), which is
    // the right figure for "is it alive" and for a rate. A request the engine repeats starts again at 0 (a redirect).
    private suspend fun <T> reportingBytesSent(
        progress: UploadProgress,
        onBytesSent: ((Long) -> Unit)?,
        intervalMs: Long,
        block: suspend () -> T,
    ): T {
        if (onBytesSent == null) return block()
        return coroutineScope {
            val ticker =
                launch {
                    var last = -1L
                    while (true) {
                        delay(intervalMs)
                        val sent = progress.bytesWritten
                        if (sent == last) continue
                        last = sent
                        runCatching { onBytesSent(sent) }
                    }
                }
            try {
                block()
            } finally {
                ticker.cancel()
            }
        }
    }

    // archive#517 F1, archive#571: say which side ended a shard PUT that broke with an IOException the watchdog did not raise. The
    // engine's own timers report as "the server prematurely closed the connection" over TLS — indistinguishable from a
    // real close — so an end at about the outer limit is named as ours; anything else is the server or the network.
    // Both carry the bytes sent and the average rate. Stays an IOException so retryShardCommit keeps retrying it and
    // no throttle signal is recorded.
    private fun shardPutFailure(
        progress: UploadProgress,
        outerLimitMs: Long,
        e: java.io.IOException,
    ): java.io.IOException {
        val elapsedMs = progress.elapsedMs()
        val text =
            if (UploadTimeoutPolicy.isSocketWatchdogCut(elapsedMs, outerLimitMs)) {
                "$SHARD_PUT cut by our overall time limit of ${UploadProgress.formatSeconds(outerLimitMs)}, " +
                    "not by the server; ${progress.describe()}"
            } else {
                "$SHARD_PUT failed: the server or the network ended the connection; ${progress.describe()}: " +
                    (e.message ?: e.javaClass.simpleName)
            }
        return java.io.IOException(text, e)
    }

    /** archive#1240: the one cheap authenticated GET that proves the stored session (the usage figure; the body is not read). */
    suspend fun checkSession() {
        authenticatedGet("$baseUrl/users/usage")
    }

    suspend fun getQuota(): QuotaInfo {
        val usage = authenticatedGet("$baseUrl/users/usage")
        val limit = authenticatedGet("$baseUrl/users/limit")
        // Not model shapes: a null quota is no quota, and must not be read as a limit of 0.
        val usedBytes = org.krost.unidrive.UnidriveJson.decodeFromString<UsageResponse>(usage).drive
        val maxBytes = org.krost.unidrive.UnidriveJson.decodeFromString<LimitResponse>(limit).maxSpaceBytes
        return QuotaInfo(total = maxBytes, used = usedBytes, remaining = maxBytes - usedBytes)
    }

    /**
     * UD-364: GET /files/limits — per-tier file size cap.
     *
     * Source of truth: drive-server-wip `src/modules/file/dto/get-file-limits.dto.ts`
     * shape: `{ versioning: VersioningLimitsDto, maxUploadFileSize: number | null }`.
     * Only `maxUploadFileSize` is consumed; versioning fields are ignored
     * (kotlinx-serialization's `ignoreUnknownKeys` via [UnidriveJson] handles them).
     * `null` from the server means unlimited / not configured — surfaces as
     * `FileLimitsResponse.maxUploadFileSize = null`, which the provider maps to
     * `maxFileSizeBytes() = null` (i.e. no cap).
     */
    suspend fun getFileLimits(): FileLimitsResponse {
        val body = authenticatedGet("$baseUrl/files/limits")
        return json.decodeFromString<FileLimitsResponse>(body)
    }

    private suspend fun authenticatedGet(
        url: String,
        params: Map<String, String> = emptyMap(),
        socketTimeoutMs: Long = HttpDefaults.SOCKET_TIMEOUT_MS,
    ): String = authenticatedGetReply(url, params, socketTimeoutMs).body

    // The body of a successful GET and the server's own id of that answer, for a caller that may have to report the body.
    private class GetReply(
        val body: String,
        val requestId: String?,
    )

    private suspend fun authenticatedGetReply(
        url: String,
        params: Map<String, String> = emptyMap(),
        socketTimeoutMs: Long = HttpDefaults.SOCKET_TIMEOUT_MS,
    ): GetReply {
        var lastException: InternxtApiException? = null
        // Three attempts have two retry gaps; keep only the delays that are used.
        val delays = listOf(2_000L, 4_000L)
        // Budget acquisition is per-attempt (inside the retry loop) so a retrying
        // call doesn't hold a slot through its full backoff and starve other callers.
        // Composition order is `loop { withAuthRetry { budget.awaitSlot(); ... } }`:
        // the auth-replay sits inside one transient-retry iteration so a mid-call
        // 401 doesn't restart the full 3-iteration delay ladder — it consumes
        // only the current attempt's slot.
        // A connect failure (status 0) during an enumeration is retried on its own, longer schedule and does
        // not consume the ladder above; see EnumerationOutageRetry.
        val outageRetry = coroutineContext[EnumerationOutageRetry] != null
        var outageWaitedMs = 0L
        var outageStep = 0
        var index = -1
        while (true) {
            index++
            if (index > delays.size) break
            val delay = delays.getOrElse(index) { delays.last() }
            // Per-attempt start, taken after the budget slot is granted so the
            // elapsed time matches what the socket watchdog measures (connect +
            // send + wait for the first response byte). 0 = no attempt started.
            var attemptStartNanos = 0L
            try {
                return withAuthRetry { creds ->
                    // The listings whose cost grows with what they list (the elevated socket timeout marks them) hold
                    // one of the drive budget's permits for the request: at most [driveBudget]'s concurrency of them
                    // are in flight, whichever stream asks, and a throttle storm lowers that. The cheap calls only
                    // take their slot, as before. The permit is taken before the slot, so the pacing is claimed by a
                    // request that can start.
                    val attempt: suspend () -> GetReply = {
                        driveBudget.awaitSlot()
                        attemptStartNanos = System.nanoTime()
                        try {
                            val response =
                                httpClient.get(url) {
                                    applyAuth(creds)
                                    params.forEach { (k, v) -> parameter(k, v) }
                                    // Only an elevated limit gets a per-request block; at the
                                    // installed default the request stays byte-identical.
                                    if (socketTimeoutMs != HttpDefaults.SOCKET_TIMEOUT_MS) {
                                        timeout {
                                            socketTimeoutMillis = socketTimeoutMs
                                            // The whole-request cap rides above the watchdog so
                                            // the read-idle timer, not the flat cap, is what a
                                            // stalled call trips.
                                            requestTimeoutMillis = socketTimeoutMs + 60_000
                                        }
                                    }
                                }
                            checkResponse(response)
                            noteServerTime(response)
                            driveBudget.recordSuccess()
                            GetReply(response.bodyAsText(), extractRequestId(response))
                        } catch (e: InternxtApiException) {
                            if (e.statusCode == 429 || e.statusCode == 503) driveBudget.recordThrottle(e.retryAfterMs ?: 0L)
                            throw e
                        }
                    }
                    heavyListingPermit(socketTimeoutMs, attempt)
                }
            } catch (e: InternxtApiException) {
                if (e.statusCode in GATEWAY_ORIGIN_ERRORS) {
                    val elapsedMs = if (attemptStartNanos != 0L) (System.nanoTime() - attemptStartNanos) / 1_000_000 else null
                    val unavailable = gatewayUnavailable(e, url, elapsedMs)
                    // A 524 ends the call: the gateway had waited about two minutes, a repeat gets the same answer.
                    if (e.statusCode == GATEWAY_ORIGIN_TIMEOUT) throw unavailable
                    lastException = unavailable
                    if (index < delays.size) kotlinx.coroutines.delay(delay)
                } else if (e.statusCode in TRANSIENT_STATUSES) {
                    lastException = e
                    if (index < delays.size) {
                        val retryAfterMs = e.retryAfterMs ?: parseRetryAfter(e.message)
                        kotlinx.coroutines.delay(retryAfterMs?.coerceIn(500L, 60_000L) ?: delay)
                    }
                } else {
                    throw e
                }
            } catch (e: java.io.IOException) {
                // A network error without a status. The canonical retry matrix (see HttpRetryBudget) retries it
                // like a 5xx. Ktor reports a connection that is closed before any response byte as a
                // ClosedReadChannelException: an IOException that only WRAPS the EOFException. Catching the
                // EOFException alone, as this did, never saw that commonest case: the gateway cuts a slow
                // /drive/files request after a minute or two, the call gave up at once as "Connection error"
                // (status 0), and neither the retry nor the /files -> folder walk fallback (500/503 only) ran.
                val elapsedMs = (System.nanoTime() - attemptStartNanos) / 1_000_000
                if (attemptStartNanos != 0L &&
                    e.closedBeforeResponse() &&
                    UploadTimeoutPolicy.isSocketWatchdogCut(elapsedMs, socketTimeoutMs)
                ) {
                    // archive#517 R3/F1: a cut whose elapsed matches the request's own read-idle watchdog is not a
                    // server event, and the ladder proved it: retrying with identical parameters won 2 of 26
                    // folder pairs and 0 of 22 file pairs, at ~62 s each. Surface 503 at once so the caller's
                    // fallback (the tree walk) starts now.
                    throw InternxtApiException(
                        "Socket watchdog fired for GET $url after ${elapsedMs}ms (limit ${socketTimeoutMs}ms) — " +
                            "a cut at the limit is our own read-idle timer, not the server; " +
                            "not retrying with identical parameters",
                        503,
                        cause = e,
                    )
                }
                driveBudget.recordIoRetry()
                lastException =
                    if (e.closedBeforeResponse()) {
                        InternxtApiException("Server closed connection for GET $url: ${e.message}", 503, cause = e)
                    } else {
                        InternxtApiException("Connection error for GET $url: ${e.message}", 0, cause = e)
                    }
                if (outageRetry && lastException!!.statusCode == 0) {
                    val remainingMs = connectOutageBudgetMs - outageWaitedMs
                    // The budget is spent: give up now. Falling through would run the rest of the ladder as
                    // immediate attempts, since the ladder's delay is skipped on this branch.
                    if (remainingMs <= 0) break
                    val waitMs =
                        (OUTAGE_FIRST_DELAY_MS shl outageStep.coerceAtMost(10))
                            .coerceAtMost(OUTAGE_MAX_DELAY_MS)
                            .coerceAtMost(remainingMs)
                    outageStep++
                    outageWaitedMs += waitMs
                    log.warn(
                        "GET {} has no connection ({}); retrying in {} ms ({} of {} ms spent)",
                        url,
                        e.message,
                        waitMs,
                        outageWaitedMs,
                        connectOutageBudgetMs,
                    )
                    kotlinx.coroutines.delay(waitMs)
                    index-- // the outage schedule, not the ladder, paces this retry
                } else if (index < delays.size) {
                    kotlinx.coroutines.delay(delay)
                }
            }
        }
        throw lastException!!
    }

    // A GET on the elevated (listing) socket timeout is a heavy listing and holds a drive permit for its request; any other
    // GET only takes its slot.
    private suspend fun heavyListingPermit(
        socketTimeoutMs: Long,
        attempt: suspend () -> GetReply,
    ): GetReply = if (socketTimeoutMs != HttpDefaults.SOCKET_TIMEOUT_MS) driveBudget.withPermit(attempt) else attempt()

    // The server closed the connection before it answered: the EOFException, or what Ktor wraps around it.
    private fun Throwable.closedBeforeResponse(): Boolean = generateSequence(this) { it.cause }.take(8).any { it is java.io.EOFException }

    private fun HttpRequestBuilder.applyAuth(creds: InternxtCredentials) {
        bearerAuth(creds.jwt)
        applyInternxtHeaders(config)
    }

    // UD-203: pull the Internxt server-side request id off a response.
    // Header name confirmed from the upstream SDK
    // (`AxiosResponseError.xRequestId` — extracted at error-normalization
    // time). Returns null if the header isn't present (e.g. synthetic
    // MockEngine responses, pre-flight failures).
    private fun extractRequestId(response: HttpResponse): String? = response.headers["x-request-id"]

    // The sharings routes answer with other parties' credentials, keys and tokens beside every item, and a gateway in front of
    // them may echo what it holds in an error body too: an error text for one of them names the status and nothing of the
    // body. The body's `retry_after` hint is still honoured, passed on in the exception instead of in the message the retry
    // loop would otherwise have read it from.
    private fun HttpResponse.isSharingsRoute(): Boolean = "sharings" in request.url.encodedPath.split('/')

    // Every other route's error text quotes a short, masked excerpt of the body ([ErrorBodyExcerpt]): the reason a server
    // gives stays readable, a token, an id, an address or a whole echoed body never reaches the text. The `retry_after`
    // hint is read from the body itself, so the cut cannot lose it.
    private suspend fun checkResponse(response: HttpResponse) {
        val quoteBody = !response.isSharingsRoute()
        if (response.status == HttpStatusCode.Unauthorized) {
            throw AuthenticationException(
                if (quoteBody) "Authentication failed (401): ${ErrorBodyExcerpt.of(response.bodyAsText())}" else "Authentication failed (401)",
                requestId = extractRequestId(response),
            )
        }
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            throw InternxtApiException(
                if (quoteBody) "API error: ${response.status} - ${ErrorBodyExcerpt.of(body)}" else "API error: ${response.status}",
                statusCode = response.status.value,
                requestId = extractRequestId(response),
                retryAfterMs = parseRetryAfterHeader(response.headers["Retry-After"]) ?: parseRetryAfter(body),
                responseReceived = true,
            )
        }
    }


    // Single-shot 401 → refresh-and-replay. Mirrors OneDrive's
    // `GraphApiService.authenticatedRequest` ladder: fetch creds, run the
    // body, and if it surfaces an [AuthenticationException] (401 mapped by
    // [checkResponse]) and we haven't refreshed yet, ask the
    // `credentialsProvider` for a forced refresh and retry once. A second
    // consecutive 401 propagates — it indicates either a refresh that
    // silently failed or a new token the server also rejects, both of which
    // are auth-flow bugs the user must see.
    //
    // Concurrent forced-refresh callers coalesce inside
    // [AuthService.refreshToken]'s [RefreshableTokenLatch], so the second
    // and Nth replays cost one shared HTTP refresh, not N.
    //
    // Bridge calls (HTTP Basic auth) MUST NOT be wrapped: a 401 from the
    // Bridge surface means the credentials themselves are wrong, not that
    // the JWT expired, and a JWT refresh wouldn't help.
    private suspend fun <T> withAuthRetry(body: suspend (InternxtCredentials) -> T): T {
        var refreshed = false
        while (true) {
            val creds = credentialsProvider(refreshed)
            try {
                return body(creds)
            } catch (e: AuthenticationException) {
                if (!refreshed) {
                    log.info("Got 401 on Drive REST call — forcing JWT refresh and retrying once")
                    refreshed = true
                    continue
                }
                throw e
            }
        }
    }

    internal suspend fun <T> retryOnTransient(
        maxAttempts: Int = 3,
        op: suspend () -> T,
    ): T {
        var attempt = 0
        while (true) {
            try {
                return op()
            } catch (e: InternxtApiException) {
                if (e.statusCode !in TRANSIENT_STATUSES) throw e
                attempt++
                if (attempt >= maxAttempts) throw e
                // Precedence: Retry-After HTTP header (captured at checkResponse
                // time, see InternxtApiException.retryAfterMs) > JSON-body
                // `retry_after` hint (parsed back out of the exception message)
                // > exponential backoff. Header wins per RFC 7231 §7.1.3, which
                // is the canonical place for the server to put this signal.
                val retryAfterMs =
                    e.retryAfterMs
                        ?: parseRetryAfter(e.message)
                        ?: (1000L shl (attempt - 1))
                val cappedMs = retryAfterMs.coerceIn(500L, 60_000L)
                log.warn(
                    "Internxt {} on attempt {}/{}, sleeping {}ms before retry",
                    e.statusCode,
                    attempt,
                    maxAttempts,
                    cappedMs,
                )
                kotlinx.coroutines.delay(cappedMs)
            }
        }
    }


    internal fun parseRetryAfter(message: String?): Long? {
        if (message == null) return null
        val match = RETRY_AFTER_REGEX.find(message) ?: return null
        return match.groupValues[1].toLongOrNull()?.times(1000)
    }



    override fun close() {
        httpClient.close()
    }
}

internal data class InternxtDownloadedContent(
    val bytesWritten: Long,
    val plaintextSha256: String,
    val stagedSize: Long,
    val stagedMtimeMillis: Long,
    val mtimeAppliedBeforePlacement: Boolean,
)

@Serializable
data class UsageResponse(
    val drive: Long = 0,
)

@Serializable
data class LimitResponse(
    val maxSpaceBytes: Long = 0,
)

/**
 * UD-364: response shape of `GET /files/limits` (drive-server-wip
 * `GetFileLimitsDto`). `maxUploadFileSize` is a per-tier byte cap, or null
 * when the server reports no cap. Other fields (`versioning`) are dropped
 * via `ignoreUnknownKeys`.
 */
@Serializable
data class FileLimitsResponse(
    val maxUploadFileSize: Long? = null,
)

class InternxtApiException(
    message: String,
    val statusCode: Int = 0,
    requestId: String? = null,
    val retryAfterMs: Long? = null,
    cause: Throwable? = null,
    responseReceived: Boolean = false,
) : ProviderException(message, cause = cause, requestId = requestId, httpStatus = statusCode.takeIf { responseReceived && it > 0 })

/**
 * The server answered with success and a body the model cannot read: a required field missing or null, a value of
 * another type, something that is no JSON. [requestId] is the server's own id of that answer.
 */
class InternxtUnreadableResponse(
    message: String,
    requestId: String?,
    cause: Throwable? = null,
) : ProviderException(message, cause = cause, requestId = requestId)
