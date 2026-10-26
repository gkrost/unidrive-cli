package org.krost.unidrive.onedrive

import io.ktor.client.*
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.HttpDefaults
import org.krost.unidrive.ProviderException
import org.krost.unidrive.ShareInfo
import org.krost.unidrive.http.HttpRetryBudget
import org.krost.unidrive.http.RequestMetricsSink
import org.krost.unidrive.http.RequestMetricsTap
import org.krost.unidrive.http.UploadProgress
import org.krost.unidrive.http.UploadTimeoutPolicy
import org.krost.unidrive.http.UploadWatchdogException
import org.krost.unidrive.http.UploadWatchdogLimits
import org.krost.unidrive.http.assertNotHtml
import org.krost.unidrive.http.parseRetryAfterHeader
import org.krost.unidrive.http.progressTrackingBody
import org.krost.unidrive.http.readBoundedErrorBody
import org.krost.unidrive.http.truncateErrorBody
import org.krost.unidrive.http.withUploadWatchdog
import org.krost.unidrive.onedrive.model.*
import org.slf4j.LoggerFactory
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

class GraphApiService(
    private val config: OneDriveConfig,
    private val throttleBudget: HttpRetryBudget = HttpRetryBudget(maxConcurrency = 8),
    // Tests hand in a MockEngine; production lets Ktor pick the engine.
    engine: HttpClientEngine? = null,
    // The limits of the chunk PUT watchdog; tests run it on a scale of milliseconds.
    private val uploadWatchdog: UploadWatchdogLimits = UploadWatchdogLimits.DEFAULT,
    private val tokenProvider: suspend (forceRefresh: Boolean) -> String,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(GraphApiService::class.java)

    // The seam the daemon attaches its request-attempt recorder to; no sink, no metrics (see attachRequestMetrics).
    private val requestMetrics = RequestMetricsTap()

    // archive#644: the token path's leaf is the profile name, so the request log lines carry the account.
    private val httpClient = graphHttpClient(requestMetrics, config.userAgent, engine, profile = config.tokenPath.fileName.toString())

    fun attachRequestMetrics(sink: RequestMetricsSink?) {
        requestMetrics.sink = sink
    }

    private val json = org.krost.unidrive.UnidriveJson
    private val sessionStore = UploadSessionStore(config.tokenPath)
    private val baseUrl = "${OneDriveConfig.GRAPH_BASE_URL}/${OneDriveConfig.GRAPH_VERSION}"

    private val deltaSafetyFile: Path get() = config.tokenPath.resolve("delta_last_seen")
    private val cursorAgeWarned = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun checkDeltaCursorAge() {
        if (cursorAgeWarned.get()) return
        val prior =
            runCatching { Files.readString(deltaSafetyFile).trim() }
                .mapCatching { java.time.Instant.parse(it) }
                .getOrNull() ?: return
        val ageDays = java.time.Duration.between(prior, java.time.Instant.now()).toDays()
        if (ageDays >= DELTA_SAFE_WINDOW_DAYS && cursorAgeWarned.compareAndSet(false, true)) {
            log.warn(
                "OneDrive delta cursor was last advanced {} days ago. Graph's soft-delete TTL " +
                    "for delta markers is roughly {} days, so deletions that happened in the " +
                    "intervening window may have aged out and will not appear as deletions in " +
                    "future delta responses. A 410 Gone on the next call would force a full " +
                    "resync; otherwise a manual `unidrive --reset` is the only way to rediscover " +
                    "missed deletions.",
                ageDays,
                DELTA_SAFE_WINDOW_DAYS,
            )
        }
    }

    private fun recordDeltaSeen() {
        runCatching {
            Files.createDirectories(config.tokenPath)
            Files.writeString(deltaSafetyFile, java.time.Instant.now().toString())
        }
    }

    private fun encodePath(path: String): String =
        path.split("/").joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }

    suspend fun getDrive(): Drive {
        val response = authenticatedRequest("$baseUrl/me/drive")
        val body = response.bodyAsText()
        val parsed = json.decodeFromString<DriveResponse>(body)

        return Drive(
            id = parsed.id ?: "",
            driveType = parsed.driveType ?: "personal",
            owner = parsed.owner,
            quota = parsed.quota,
        )
    }

    suspend fun getQuota(): org.krost.unidrive.QuotaInfo {
        val drive = getDrive()
        val quota = drive.quota ?: return org.krost.unidrive.QuotaInfo(0, 0, 0)
        return org.krost.unidrive.QuotaInfo(
            total = quota.total,
            used = quota.used,
            remaining = quota.remaining,
        )
    }

    suspend fun getItemByPath(path: String): DriveItem {
        val cleanPath = path.removePrefix("/").ifEmpty { "" }
        val url =
            if (cleanPath.isEmpty()) {
                "$baseUrl/me/drive/root"
            } else {
                "$baseUrl/me/drive/root:/${encodePath(cleanPath)}"
            }

        val response = authenticatedRequest(url)
        val body = response.bodyAsText()
        return json.decodeFromString<DriveItem>(body)
    }

    // The URL of the item [id]: in the caller's own drive, or, for a shared-with-me item (see [SharedItemId]),
    // in the drive that holds it.
    private fun itemUrl(id: String): String {
        val shared = SharedItemId.parse(id) ?: return "$baseUrl/me/drive/items/$id"
        return "$baseUrl/drives/${shared.driveId.encodeURLPathPart()}/items/${shared.itemId.encodeURLPathPart()}"
    }

    suspend fun getItemById(id: String): DriveItem {
        val response = authenticatedRequest(itemUrl(id))
        val body = response.bodyAsText()
        return json.decodeFromString<DriveItem>(body)
    }

    suspend fun listChildren(path: String): List<DriveItem> {
        val cleanPath = path.removePrefix("/").ifEmpty { "" }
        val firstUrl =
            if (cleanPath.isEmpty()) {
                "$baseUrl/me/drive/root/children?\$top=999"
            } else {
                "$baseUrl/me/drive/root:/${encodePath(cleanPath)}:/children?\$top=999"
            }

        val accumulated = mutableListOf<DriveItem>()
        var nextUrl: String? = firstUrl
        var pageIndex = 0
        while (nextUrl != null) {
            val response = authenticatedRequest(nextUrl)
            val body = response.bodyAsText()
            val collection = json.decodeFromString<DriveItemCollectionResponse>(body)
            accumulated.addAll(collection.value)
            log.debug("listChildren page {}: {} items, hasMore={}", pageIndex, collection.value.size, collection.nextLink != null)
            nextUrl = collection.nextLink
            pageIndex++
        }
        return accumulated
    }

    suspend fun getDelta(
        link: String? = null,
        fromLatest: Boolean = false,
        // A preview must not reset the delta-cursor age clock that recordDeltaSeen maintains.
        readOnly: Boolean = false,
    ): DeltaResult {
        val url = link ?: if (fromLatest) "$baseUrl/me/drive/root/delta?token=latest" else "$baseUrl/me/drive/root/delta"
        log.debug("Delta cursor: {}", link?.takeLast(40) ?: if (fromLatest) "(token=latest bootstrap)" else "(initial)")
        if (link != null) checkDeltaCursorAge()

        var attempt = 0
        val maxAttempts = 3
        while (true) {
            try {
                val response = authenticatedRequest(url)
                val body = response.bodyAsText()
                val parsed = json.decodeFromString<DriveItemCollectionResponse>(body)
                if (!readOnly) recordDeltaSeen()
                return DeltaResult(
                    items = parsed.value,
                    nextLink = parsed.nextLink,
                    deltaLink = parsed.deltaLink,
                )
            } catch (e: AuthenticationException) {
                throw e // token failures aren't retryable
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: kotlinx.serialization.SerializationException) {
                attempt++
                if (attempt >= maxAttempts) {
                    log.warn("Graph delta parse failed after {} attempts at {}: {}", attempt, url.takeLast(60), e.message)
                    throw e
                }
                val backoff = 2000L * (1L shl (attempt - 1)) // 2s, 4s
                log.warn("Graph delta parse failed (attempt {}/{}), retrying in {}ms: {}", attempt, maxAttempts, backoff, e.message)
                delay(backoff)
            } catch (e: java.io.IOException) {
                attempt++
                if (attempt >= maxAttempts) throw e
                val backoff = 2000L * (1L shl (attempt - 1))
                log.warn("Graph delta I/O failed (attempt {}/{}), retrying in {}ms: {}", attempt, maxAttempts, backoff, e.message)
                delay(backoff)
            }
        }
    }

    suspend fun downloadFile(
        itemId: String,
        destPath: Path,
    ): Long {
        val item = getItemById(itemId)
        var downloadedBytes = 0L
        var url = item.downloadUrl ?: "${itemUrl(itemId)}/content"
        var authNeeded = item.downloadUrl == null
        var flakeAttempts = 0
        var throttleAttempts = 0
        var totalThrottleWaitMs = 0L
        var authRefreshed = false
        // A CDN edge that serves an HTML throttle page (HTTP 200,
        // Content-Type: text/html) instead of bytes can't be recovered by
        // re-GETting the same stale URL. Re-resolve the download URL once.
        var htmlReresolved = false
        // archive#329: the pre-authenticated @microsoft.graph.downloadUrl carries a tempauth
        // token that expires (~1 h). A 401/403 on that URL says nothing about account
        // auth: re-resolve the item once for a fresh URL before treating it as an
        // authentication failure. Bounded to a single re-resolve (no loop).
        var authUrlReresolved = false
        while (true) {
            try {
                throttleBudget.awaitSlot()
                val statement =
                    httpClient.prepareGet(url) {
                        if (authNeeded) bearerAuth(tokenProvider(authRefreshed))
                    }
                val outcome: DownloadOutcome =
                    statement.execute { response ->
                        if (authNeeded && response.status == HttpStatusCode.Unauthorized && !authRefreshed) {
                            return@execute DownloadOutcome.RetryAuth
                        }
                        if (!authNeeded && (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden)) {
                            return@execute DownloadOutcome.ExpiredUrl(response.status.value, readBoundedErrorBody(response))
                        }
                        if (response.status == HttpStatusCode.Unauthorized) {
                            throw AuthenticationException(
                                "Authentication failed (401): ${readBoundedErrorBody(response)}",
                            )
                        }
                        if (shouldBackoff(response, throttleAttempts, totalThrottleWaitMs)) {
                            val body = readBoundedErrorBody(response, maxBytes = 16384)
                            val waitMs = pickBackoffMsWithBody(response, body, throttleAttempts, totalThrottleWaitMs)
                            return@execute DownloadOutcome.Throttle(waitMs, response.status.value)
                        }
                        if (!response.status.isSuccess()) {
                            throw GraphApiException(
                                "Download failed: ${response.status} - ${readBoundedErrorBody(response)}",
                                response.status.value,
                            )
                        }
                        assertNotHtml(response, contextMsg = "Download itemId=$itemId")
                        throttleBudget.recordSuccess()
                        val channel: ByteReadChannel = response.bodyAsChannel()
                        val written =
                            withContext(Dispatchers.IO) {
                                Files.createDirectories(destPath.parent)
                                // archive#529: a name near the 255-byte component limit cannot take the suffix — stage short.
                                val tmpPath = destPath.parent.resolve(
                                    org.krost.unidrive.io.stagingSiblingName(destPath.fileName.toString(), ".unidrive-tmp"),
                                )
                                try {
                                    val w =
                                        Files.newOutputStream(tmpPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { out ->
                                            val buf = ByteArray(8192)
                                            var n = 0L
                                            while (true) {
                                                val r = channel.readAvailable(buf)
                                                if (r <= 0) break
                                                out.write(buf, 0, r)
                                                n += r
                                            }
                                            n
                                        }
                                    // archive#354: the truncation guard runs on the TEMP file, before the
                                    // atomic move. A connection that closes mid-body without throwing
                                    // used to have its short temp promoted over the destination
                                    // first and only detected afterwards — the intact previous
                                    // version was gone before truncation surfaced. A short read now
                                    // deletes the temp and throws with the destination untouched,
                                    // the same guarantee the thrown-exception path already had.
                                    val expected = response.contentLength()
                                    if (expected != null && expected >= 0L && w != expected) {
                                        throw java.io.IOException(
                                            "Truncated download itemId=$itemId: got $w of $expected bytes",
                                        )
                                    }
                                    Files.move(tmpPath, destPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                                    w
                                } catch (e: Exception) {
                                    Files.deleteIfExists(tmpPath)
                                    throw e
                                }
                            }
                        downloadedBytes = written
                        DownloadOutcome.Done
                    }

                when (outcome) {
                    DownloadOutcome.Done -> return downloadedBytes
                    DownloadOutcome.RetryAuth -> {
                        log.info("Got 401 on download itemId={} — forcing token refresh and retrying once", itemId)
                        authRefreshed = true
                        continue
                    }
                    is DownloadOutcome.ExpiredUrl -> {
                        // archive#329: the tempauth token of the pre-authenticated downloadUrl
                        // expired — not an account-auth problem. Re-resolve the item once
                        // for a fresh URL (mirrors the HTML re-resolve arm below); only a
                        // 401/403 on the fresh URL is surfaced as an authentication failure.
                        if (authUrlReresolved) {
                            // Only a 401 is an authentication failure. A 403 that persists on a
                            // fresh URL is an access refusal (before archive#329 it surfaced as the
                            // generic download failure): keep that, so it cannot latch the
                            // stale-auth UX (archive#157).
                            if (outcome.statusCode == HttpStatusCode.Forbidden.value) {
                                throw GraphApiException(
                                    "Download failed: ${HttpStatusCode.Forbidden} - ${outcome.body}",
                                    outcome.statusCode,
                                )
                            }
                            throw AuthenticationException(
                                "Authentication failed (${outcome.statusCode}) on a re-resolved download URL for item $itemId",
                            )
                        }
                        authUrlReresolved = true
                        log.warn(
                            "Got {} on the pre-authenticated download URL itemId={} — re-resolving for a fresh URL and retrying once",
                            outcome.statusCode,
                            itemId,
                        )
                        val fresh = getItemById(itemId)
                        url = fresh.downloadUrl ?: "${itemUrl(itemId)}/content"
                        authNeeded = fresh.downloadUrl == null
                        continue
                    }
                    is DownloadOutcome.Throttle -> {
                        log.warn(
                            "Got {} on download itemId={} — throttled, waiting {}ms (attempt {}/{})",
                            outcome.statusCode,
                            itemId,
                            outcome.waitMs,
                            throttleAttempts + 1,
                            MAX_THROTTLE_ATTEMPTS,
                        )
                        throttleBudget.recordThrottle(outcome.waitMs)
                        delay(outcome.waitMs)
                        throttleAttempts++
                        totalThrottleWaitMs += outcome.waitMs
                        continue
                    }
                }
            } catch (e: AuthenticationException) {
                throw e
            } catch (e: GraphApiException) {
                // HTTP-status failures (403/404/etc) aren't short-read flakes; don't retry.
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                 throw e
            } catch (e: Exception) {
                // assertNotHtml throws IOException when the CDN returned an HTML
                // throttle/captive-portal page instead of file bytes. Re-GETting
                // the same stale URL can never recover; re-resolve the download URL
                // ONCE for a fresh CDN URL, then retry. A second HTML hit falls
                // through to the generic flake-retry below (no loop), so a CDN stuck
                // on HTML still terminates instead of spinning.
                if (isHtmlBodyFailure(e) && !htmlReresolved) {
                    htmlReresolved = true
                    log.warn(
                        "Download itemId={} returned an HTML page instead of bytes — re-resolving the " +
                            "download URL and retrying once: {}",
                        itemId,
                        e.message,
                    )
                    val fresh = getItemById(itemId)
                    url = fresh.downloadUrl ?: "${itemUrl(itemId)}/content"
                    authNeeded = fresh.downloadUrl == null
                    continue
                }
                flakeAttempts++
                if (flakeAttempts >= MAX_FLAKE_ATTEMPTS) {
                    log.warn("Graph download failed after {} attempts for itemId={}: {}", flakeAttempts, itemId, e.message)
                    throw e
                }
                val backoff = 2000L * (1L shl (flakeAttempts - 1)) // 2s, 4s
                log.warn(
                    "Graph download failed (attempt {}/{}), retrying in {}ms: {}",
                    flakeAttempts,
                    MAX_FLAKE_ATTEMPTS,
                    backoff,
                    e.message,
                )
                delay(backoff)
            }
        }
    }

  
    private fun pickBackoffMsWithBody(
        response: HttpResponse,
        body: String,
        attempts: Int,
        totalWaitMs: Long,
    ): Long {
        val retryAfterHeader = parseRetryAfterHeader(response.headers["Retry-After"])
        val retryAfterBody = parseRetryAfterFromJsonBody(body)?.let { it * 1000 }
        val hintedMs = retryAfterHeader ?: retryAfterBody
        val backoffMs = hintedMs ?: (DEFAULT_BACKOFF_START_MS * (1L shl attempts))
        val remainingBudget = MAX_TOTAL_THROTTLE_WAIT_MS - totalWaitMs
        return minOf(backoffMs, MAX_SINGLE_BACKOFF_MS, remainingBudget)
    }

    private fun parseRetryAfterFromJsonBody(body: String): Long? =
        try {
            val element = json.parseToJsonElement(body)
            element.jsonObject["error"]
                ?.jsonObject
                ?.get("retryAfterSeconds")
                ?.jsonPrimitive
                ?.longOrNull
                ?: element.jsonObject["retryAfterSeconds"]?.jsonPrimitive?.longOrNull
        } catch (_: Exception) {
            null
        }

    suspend fun uploadSimple(
        remotePath: String,
        content: ByteArray,
        fileSystemInfo: FileSystemInfo? = null,
        conflictBehavior: String = "replace",
        ifMatchETag: String? = null,
        // The existing item to replace in place, when the upload is an update addressed by id (see [SharedItemId]).
        itemId: String? = null,
    ): DriveItem {
        log.debug("Upload (simple): {} ({} bytes, conflictBehavior={})", remotePath, content.size, conflictBehavior)
        val cleanPath = remotePath.removePrefix("/")
        val encoded = encodePath(cleanPath)
        val uploadTimeoutMs = UploadTimeoutPolicy.computeRequestTimeoutMs(content.size.toLong())

        // conflictBehavior pins the simple-PUT and upload-session paths to the same overwrite
        // policy so it doesn't shift based on file size crossing the 4-MiB threshold. The
        // caller chooses: "replace" for an UPDATE of a file we own (a re-upload of a touched
        // file must not 409), "fail" for a CREATE so a pre-existing remote at the path is never
        // blind-overwritten (UD-366 data-loss), with the create-collision keep-both handled by
        // OneDriveProvider.upload.
        val contentUrl =
            if (itemId != null) {
                "${itemUrl(itemId)}/content"
            } else {
                "$baseUrl/me/drive/root:/$encoded:/content?@microsoft.graph.conflictBehavior=$conflictBehavior"
            }
        val response =
            sendThrottled(contentUrl, HttpMethod.Put) {
                timeout {
                    requestTimeoutMillis = uploadTimeoutMs
                    socketTimeoutMillis = uploadTimeoutMs
                }
                contentType(ContentType.Application.OctetStream)
                // archive#291: If-Match makes the content PUT conditional on the caller's view of the item
                // being current. A concurrent edit between the engine's plan and this PUT flips the
                // server-side eTag and Graph returns 412 Precondition Failed instead of silently
                // overwriting the other editor's change. Caller passes null to opt out (a NEW file,
                // or a call site without an eTag in hand). Mirrors the upload-session If-Match guard.
                if (ifMatchETag != null) header(HttpHeaders.IfMatch, ifMatchETag)
                setBody(content)
            }

        requireSuccess(response, "Upload")

        val body = response.bodyAsText()
        val item = json.decodeFromString<DriveItem>(body)
        // A content-PUT cannot carry the fileSystemInfo facet inline; round-tripping
        // local mtime/ctime requires a follow-up PATCH on the just-created item.
        return if (fileSystemInfo != null) patchFileSystemInfo(itemId ?: item.id, fileSystemInfo) else item
    }

    private suspend fun patchFileSystemInfo(
        itemId: String,
        info: FileSystemInfo,
    ): DriveItem {
        val body =
            buildJsonObject {
                putJsonObject("fileSystemInfo") {
                    info.createdDateTime?.let { put("createdDateTime", it) }
                    info.lastModifiedDateTime?.let { put("lastModifiedDateTime", it) }
                }
            }
        val response =
            sendThrottled(itemUrl(itemId), HttpMethod.Patch) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        requireSuccess(response, "fileSystemInfo PATCH")
        return json.decodeFromString<DriveItem>(response.bodyAsText())
    }

    suspend fun deleteItem(itemId: String, ifMatchETag: String? = null) {
        val response =
            sendThrottled(itemUrl(itemId), HttpMethod.Delete) {
                // archive#291: If-Match guards the delete against a concurrent edit between plan and apply.
                // A non-null eTag makes Graph return 412 Precondition Failed if the item moved on,
                // rather than destroying a version the engine never saw. Null = legacy unconditional.
                if (ifMatchETag != null) header(HttpHeaders.IfMatch, ifMatchETag)
            }

        // 404 is "already gone", a delete's own success.
        if (response.status != HttpStatusCode.NotFound) requireSuccess(response, "Delete")
    }

    /**
     * archive#1178: permanently delete the item, bypassing the recycle bin (`POST .../items/{id}/permanentDelete`).
     * Only `rm --permanent` reaches this. 404 is already-gone (idempotent), like [deleteItem]; every other
     * non-success is an error (a SharePoint library can answer 501 for what its settings do not allow).
     */
    suspend fun permanentDeleteItem(itemId: String) {
        val response = sendThrottled("${itemUrl(itemId)}/permanentDelete", HttpMethod.Post)

        if (response.status != HttpStatusCode.NotFound) requireSuccess(response, "Permanent delete")
    }

    suspend fun createFolder(
        name: String,
        parentPath: String?,
    ): DriveItem {
        log.debug("mkdir: {}/{}", parentPath ?: "/", name)
        val url =
            if (parentPath == null || parentPath == "/") {
                "$baseUrl/me/drive/root/children"
            } else {
                val clean = parentPath.removePrefix("/")
                "$baseUrl/me/drive/root:/${encodePath(clean)}:/children"
            }

        val requestBody =
            buildJsonObject {
                put("name", name)
                putJsonObject("folder") {}
            }
        val response =
            sendThrottled(url, HttpMethod.Post) {
                contentType(ContentType.Application.Json)
                setBody(requestBody.toString())
            }

        requireSuccess(response, "Create folder")

        return json.decodeFromString<DriveItem>(response.bodyAsText())
    }

    // A shared item can be renamed where it is. A new folder would be one of the caller's own drive, and an item
    // cannot be moved from another user's drive into it with a PATCH.
    private fun requireRenameOnly(
        itemId: String,
        changesFolder: Boolean,
        oldPath: String?,
        newPath: String,
    ) {
        if (changesFolder && SharedItemId.isShared(itemId)) {
            throw ProviderException("OneDrive cannot move the shared item '$oldPath' to '$newPath': a shared item can only be renamed in place")
        }
    }

    suspend fun moveItem(
        itemId: String,
        newPath: String,
        oldPath: String? = null,
        ifMatchETag: String? = null,
    ): DriveItem {
        // UD-753: per-operation log moved to SyncEngine.applyMoveRemote.
        val cleanPath = newPath.removePrefix("/")
        val newName = cleanPath.substringAfterLast("/")
        val newParentPath = cleanPath.substringBeforeLast("/", "")
        val oldParentPath = oldPath?.removePrefix("/")?.substringBeforeLast("/", "")
        val changesFolder = oldParentPath == null || newParentPath != oldParentPath
        requireRenameOnly(itemId, changesFolder, oldPath, newPath)

        val body =
            buildJsonObject {
                put("name", newName)
                if (changesFolder) {
                    val parentItem =
                        if (newParentPath.isEmpty()) {
                            getItemByPath("")
                        } else {
                            getItemByPath(newParentPath)
                        }
                    putJsonObject("parentReference") {
                        put("id", parentItem.id)
                    }
                }
            }

        val response =
            sendThrottled(itemUrl(itemId), HttpMethod.Patch) {
                contentType(ContentType.Application.Json)
                // If-Match makes the PATCH conditional on the caller's view of the item being
                // current. A concurrent edit between getItemByPath and moveItem flips the
                // server-side eTag and the PATCH surfaces 412 Precondition Failed instead of
                // silently overwriting the other editor's change. Caller passes null to opt
                // out (legacy call sites without an eTag in hand).
                if (ifMatchETag != null) header(HttpHeaders.IfMatch, ifMatchETag)
                setBody(body.toString())
            }

        requireSuccess(response, "Move")

        return json.decodeFromString<DriveItem>(response.bodyAsText())
    }

    suspend fun uploadLargeFile(
        localPath: Path,
        remotePath: String,
        onProgress: ((Long, Long) -> Unit)? = null,
        fileSystemInfo: FileSystemInfo? = null,
        conflictBehavior: String = "replace",
        ifMatchETag: String? = null,
        itemId: String? = null,
    ): DriveItem {
        val fileSize = withContext(Dispatchers.IO) { Files.size(localPath) }
        log.debug("Upload (chunked): {} ({} bytes, conflictBehavior={})", remotePath, fileSize, conflictBehavior)

        // Two attempts: the probe-then-PUT race window means a session reported alive at
        // resolveUploadSession can be gone by the first chunk. A second attempt with a
        // freshly-minted session closes that race transparently to the caller.
        var lastSessionGone: GraphApiException? = null
        repeat(2) { attempt ->
            try {
                return uploadLargeFileOnce(localPath, UploadTarget(remotePath, itemId), fileSize, onProgress, fileSystemInfo, conflictBehavior, ifMatchETag)
            } catch (e: GraphApiException) {
                val isSessionGone = e.statusCode == 404 || e.statusCode == 410
                if (attempt == 0 && isSessionGone) {
                    log.info(
                        "OneDrive upload session for {} was gone (status {}); creating a fresh session and retrying once",
                        remotePath,
                        e.statusCode,
                    )
                    sessionStore.delete(remotePath)
                    lastSessionGone = e
                } else {
                    throw e
                }
            }
        }
        // Both attempts surfaced session-gone — propagate the last one so the caller sees
        // a definitive failure rather than a silent loop.
        throw lastSessionGone ?: GraphApiException("Upload retry loop exited without result")
    }

    private suspend fun uploadLargeFileOnce(
        localPath: Path,
        target: UploadTarget,
        fileSize: Long,
        onProgress: ((Long, Long) -> Unit)?,
        fileSystemInfo: FileSystemInfo?,
        conflictBehavior: String = "replace",
        ifMatchETag: String? = null,
    ): DriveItem {
        val remotePath = target.remotePath
        val itemId = target.itemId
        val (uploadUrl, initialOffset) =
            resolveUploadSession(localPath, target, fileSize, fileSystemInfo, conflictBehavior, ifMatchETag)
        val chunkSize = 10L * 1024 * 1024 // 10 MiB (multiple of 320 KiB)
        if (initialOffset > 0) log.debug("Upload resuming at offset {} / {} bytes", initialOffset, fileSize)
        var offset = initialOffset
        var result: DriveItem? = null
        var finalChunkConfirmedByProbe = false
        try {
            while (offset < fileSize) {
                val currentChunkSize = minOf(chunkSize, fileSize - offset).toInt()
                val bytes =
                    withContext(Dispatchers.IO) {
                        val buf = ByteArray(currentChunkSize)
                        RandomAccessFile(localPath.toFile(), "r").use { raf ->
                            raf.seek(offset)
                            raf.readFully(buf)
                        }
                        buf
                    }

                val endByte = offset + currentChunkSize - 1
                val chunkResult = uploadChunkWithRetry(uploadUrl, bytes, offset, endByte, fileSize)
                if (chunkResult is ChunkUploadResult.Committed) {
                    offset = chunkResult.nextOffset
                    finalChunkConfirmedByProbe = offset >= fileSize
                    log.debug("Upload progress: {}/{} bytes", offset, fileSize)
                    onProgress?.invoke(offset, fileSize)
                    continue
                }

                val response = (chunkResult as ChunkUploadResult.Response).response
                when (response.status.value) {
                    200, 201 -> result = json.decodeFromString<DriveItem>(response.bodyAsText())
                    202 -> {} // More chunks needed
                    else -> throw GraphApiException(
                        "Chunk upload failed at offset $offset: ${response.status} - ${response.bodyAsText()}",
                        response.status.value,
                    )
                }
                offset += currentChunkSize
                log.debug("Upload progress: {}/{} bytes", offset, fileSize)
                onProgress?.invoke(offset, fileSize)
            }
        } catch (e: GraphApiException) {
            // Terminal 4xx responses cannot be resumed. Keep the session for 408/429
            // and server errors so a later attempt can continue from its committed offset.
            if (e.statusCode in 400..499 && e.statusCode != 408 && e.statusCode != 429) {
                sessionStore.delete(remotePath)
            }
            throw e
        }

        val uploadedItem =
            result ?: if (finalChunkConfirmedByProbe) {
                if (itemId != null) getItemById(itemId) else getItemByPath(remotePath)
            } else {
                null
            }
        sessionStore.delete(remotePath)
        return uploadedItem ?: throw GraphApiException("Upload session completed but no DriveItem returned")
    }

    /**
     * Returns the upload URL and the byte offset to start from.
     * Checks [sessionStore] first; if a session of this local revision exists that this upload may resume (see
     * [mayResume]), queries its status to find the committed offset. If none, creates a fresh session and persists it.
     */
    private suspend fun resolveUploadSession(
        localPath: Path,
        target: UploadTarget,
        fileSize: Long,
        fileSystemInfo: FileSystemInfo? = null,
        conflictBehavior: String = "replace",
        ifMatchETag: String? = null,
    ): Pair<String, Long> {
        val remotePath = target.remotePath
        val localMtimeMillis = withContext(Dispatchers.IO) { Files.getLastModifiedTime(localPath).toMillis() }
        val resumable =
            sessionStore.getSession(remotePath)?.let { usableStoredSession(remotePath, it, fileSize, localMtimeMillis, conflictBehavior, ifMatchETag) }
        if (resumable != null) {
            resumeStoredSession(remotePath, resumable)?.let { return it }
        }
        return createUploadSession(target, fileSize, localMtimeMillis, fileSystemInfo, conflictBehavior, ifMatchETag)
    }

    // The stored session when this upload may resume it; otherwise it is discarded and null is returned.
    private fun usableStoredSession(
        remotePath: String,
        stored: UploadSessionStore.StoredSession,
        fileSize: Long,
        localMtimeMillis: Long,
        conflictBehavior: String,
        ifMatchETag: String?,
    ): UploadSessionStore.StoredSession? {
        // Bind the session to the local file's identity: resuming a session whose committed
        // offset points into different local bytes would assemble OLD[0..offset) + NEW[offset..)
        // on the remote — silent corruption. Any size/mtime mismatch (or a legacy entry that
        // getSession dropped) discards the stale session and forces a fresh one.
        if (stored.localSize != fileSize || stored.localMtimeMillis != localMtimeMillis) {
            log.info(
                "OneDrive upload session for {} was for a different local revision (stored {}B/{}, now {}B/{}); discarding and creating a fresh session",
                remotePath,
                stored.localSize,
                stored.localMtimeMillis,
                fileSize,
                localMtimeMillis,
            )
            sessionStore.delete(remotePath)
            return null
        }
        // If-Match guard: a stored session that could overwrite more than this upload allows must not be reused.
        // Resuming such a session would skip the createUploadSession If-Match check entirely,
        // silently overwriting a concurrent remote edit that the caller is trying to detect.
        if (!mayResume(stored, conflictBehavior, ifMatchETag)) {
            log.info(
                "OneDrive upload session for {} was created under a different If-Match guard or conflict behaviour " +
                    "(stored={}/{}, now={}/{}); discarding and creating a fresh session",
                remotePath,
                stored.ifMatchETag,
                stored.conflictBehavior,
                ifMatchETag,
                conflictBehavior,
            )
            sessionStore.delete(remotePath)
            return null
        }
        if (stored.conflictBehavior != null && (stored.conflictBehavior != conflictBehavior || stored.ifMatchETag != ifMatchETag)) {
            log.info(
                "OneDrive upload of {} resumes its session of conflictBehavior={} (If-Match {}) although this attempt asks for {} (If-Match {}): " +
                    "one session per file revision, its progress is kept",
                remotePath,
                stored.conflictBehavior,
                stored.ifMatchETag,
                conflictBehavior,
                ifMatchETag,
            )
        }
        return stored
    }

    // Probes a stored session for the offset the server holds. Graph extends the session's lifetime on activity, so the
    // stored expiry is an upper-bound hint, not authoritative. Only an answer that says the session is gone or unusable
    // discards it (null: a fresh one is created). A probe that breaks off or meets a 5xx/408/429 is retried on the chunk
    // back-off; when that is spent the failure is thrown and the session stays stored, so a dropping uplink does not
    // lose the progress once per attempt (archive#1175).
    private suspend fun resumeStoredSession(
        remotePath: String,
        stored: UploadSessionStore.StoredSession,
    ): Pair<String, Long>? {
        val parsedSession =
            when (val probe = probeStoredSession(remotePath, stored.uploadUrl)) {
                is SessionProbe.Alive -> probe.session
                is SessionProbe.Unusable -> {
                    log.info("Upload session for {} is gone or unusable ({}); creating new session", remotePath, probe.reason)
                    sessionStore.delete(remotePath)
                    return null
                }
            }
        // Refresh local hint from the server-reported expiry; an inactive session
        // shrinks toward its original expiry, an active one stretches up to 14 d.
        parsedSession.expirationDateTime
            ?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
            ?.let { sessionStore.put(remotePath, stored.copy(expiresAt = it.toString())) }
        val committedOffset = parsedSession.nextExpectedRanges.nextExpectedStart() ?: 0L
        log.info(
            "Upload session resumed: {} at offset {} (server expiry {})",
            remotePath,
            committedOffset,
            parsedSession.expirationDateTime,
        )
        return stored.uploadUrl to committedOffset
    }

    // Asks the stored session for its state, on the chunk back-off while the answer is transient. Throws the last
    // transient failure (the IOException, or a GraphApiException with the status) when the tries are spent.
    private suspend fun probeStoredSession(
        remotePath: String,
        uploadUrl: String,
    ): SessionProbe {
        for ((attempt, delayMs) in CHUNK_RETRY_DELAYS_MS.withIndex()) {
            when (val probe = probeStoredSessionOnce(uploadUrl, delayMs)) {
                is SessionProbe -> return probe
                is TransientProbe -> {
                    if (attempt == CHUNK_RETRY_DELAYS_MS.lastIndex) throw probe.failure
                    log.info(
                        "Upload session status probe for {} failed ({}); keeping the session and asking again (retry {} of {})",
                        remotePath,
                        probe.failure.message,
                        attempt + 1,
                        CHUNK_RETRY_DELAYS_MS.lastIndex,
                    )
                    delay(probe.waitMs)
                }
            }
        }
        // Unreachable: the last try returns or throws
        throw GraphApiException("Upload session status probe for $remotePath ended without an answer")
    }

    // One status probe: Alive or Unusable (the server's answer is final), or a TransientProbe with the wait before the
    // next try (the probe broke off, or the server answered 408, 429 or 5xx).
    private suspend fun probeStoredSessionOnce(
        uploadUrl: String,
        delayMs: Long,
    ): SessionProbeOutcome {
        val response =
            try {
                httpClient.get(uploadUrl)
            } catch (e: java.io.IOException) {
                return TransientProbe(e, delayMs)
            }
        val status = response.status.value
        return when {
            response.status.isSuccess() -> {
                val body =
                    try {
                        response.bodyAsText()
                    } catch (e: java.io.IOException) {
                        return TransientProbe(e, delayMs)
                    }
                runCatching { json.decodeFromString<UploadSession>(body) }.getOrNull()?.let { SessionProbe.Alive(it) }
                    ?: SessionProbe.Unusable("unreadable status answer")
            }
            status == 429 -> {
                val waitMs = parseRetryAfterHeader(response.headers["Retry-After"]) ?: delayMs
                throttleBudget.recordThrottle(minOf(waitMs, MAX_SINGLE_BACKOFF_MS))
                TransientProbe(GraphApiException("Upload session status probe was rate limited: ${response.status}", status), waitMs)
            }
            status == 408 || status >= 500 -> {
                if (status == 503) throttleBudget.recordThrottle(delayMs)
                TransientProbe(GraphApiException("Upload session status probe failed: ${response.status}", status), delayMs)
            }
            // 404 (the documented answer for a session that no longer exists), 410, and any other 4xx: the session
            // cannot be used again, the same rule the chunk loop applies to a terminal 4xx.
            else -> SessionProbe.Unusable("status $status")
        }
    }

    private suspend fun requireCreatedSession(sessionResponse: HttpResponse) {
        requireSuccess(sessionResponse, "Create upload session")
    }

    private suspend fun createUploadSession(
        target: UploadTarget,
        fileSize: Long,
        localMtimeMillis: Long,
        fileSystemInfo: FileSystemInfo?,
        conflictBehavior: String,
        ifMatchETag: String?,
    ): Pair<String, Long> {
        val remotePath = target.remotePath
        val itemId = target.itemId
        val encoded = encodePath(remotePath.removePrefix("/"))
        val sessionBody =
            buildJsonObject {
                putJsonObject("item") {
                    put("@microsoft.graph.conflictBehavior", conflictBehavior)
                    if (fileSystemInfo != null) {
                        putJsonObject("fileSystemInfo") {
                            fileSystemInfo.createdDateTime?.let { put("createdDateTime", it) }
                            fileSystemInfo.lastModifiedDateTime?.let { put("lastModifiedDateTime", it) }
                        }
                    }
                }
            }
        val sessionResponse =
            sendThrottled(
                if (itemId != null) "${itemUrl(itemId)}/createUploadSession" else "$baseUrl/me/drive/root:/$encoded:/createUploadSession",
                HttpMethod.Post,
            ) {
                contentType(ContentType.Application.Json)
                // If-Match makes the session-create conditional on the caller's view of the item
                // being current. A concurrent edit between the metadata fetch and this call flips
                // the server-side eTag and Graph returns 412 Precondition Failed instead of
                // silently overwriting the other editor's change. Caller passes null to opt out
                // (new files or call sites without an eTag in hand).
                if (ifMatchETag != null) header(HttpHeaders.IfMatch, ifMatchETag)
                setBody(sessionBody.toString())
            }

        requireCreatedSession(sessionResponse)
        val session = json.decodeFromString<UploadSession>(sessionResponse.bodyAsText())
        val url =
            session.uploadUrl ?: throw GraphApiException(
                "Create upload session succeeded but response has no uploadUrl: " +
                    sessionResponse.bodyAsText().take(200),
                200,
            )
        val expiresAt =
            session.expirationDateTime
                ?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
                ?: java.time.Instant
                    .now()
                    .plusSeconds(86_400) // fallback: 24 h

        sessionStore.put(remotePath, url, expiresAt, fileSize, localMtimeMillis, ifMatchETag, conflictBehavior)
        log.info("Upload session created: {} ({} bytes, conflictBehavior={})", remotePath, fileSize, conflictBehavior)
        return url to 0L
    }

    /**
     * Upload one chunk, retrying it on a transient failure: a 408 or 5xx, a 429, a 416 (the server already holds part
     * of the range), or a PUT that broke off with an IOException (a dropped connection, a timeout, the watchdog's cut of
     * a server that never answers). Before a retry (but a 429's) the session is queried for the offset the server
     * holds, so bytes it accepted are not sent again. When the retries are spent the last failure is thrown; the stored
     * session stays for the next attempt (archive#1175: one dropped connection used to cost the whole attempt).
     */
    private suspend fun uploadChunkWithRetry(
        uploadUrl: String,
        bytes: ByteArray,
        offset: Long,
        endByte: Long,
        fileSize: Long,
    ): ChunkUploadResult {
        var currentOffset = offset
        var currentBytes = bytes

        for ((attempt, delayMs) in CHUNK_RETRY_DELAYS_MS.withIndex()) {
            val lastTry = attempt == CHUNK_RETRY_DELAYS_MS.lastIndex
            // Chunks are the bulk of the write traffic: they take a slot like every other request, so an
            // open throttle circuit holds them back too.
            throttleBudget.awaitSlot()
            val response =
                try {
                    putChunk(uploadUrl, currentBytes, currentOffset, endByte, fileSize)
                } catch (e: java.io.IOException) {
                    if (lastTry) throw e
                    log.info(
                        "Chunk upload {}-{}/{} broke off ({}); probing the session and sending the chunk again (retry {} of {})",
                        currentOffset,
                        endByte,
                        fileSize,
                        e.message,
                        attempt + 1,
                        CHUNK_RETRY_DELAYS_MS.lastIndex,
                    )
                    null
                }
            if (response != null && response.status.value in 200..202) {
                throttleBudget.recordSuccess()
                return ChunkUploadResult.Response(response)
            }
            val retry = response?.let { chunkRetryAfterAnswer(it, currentOffset, delayMs, lastTry) } ?: ChunkRetry(delayMs, probe = true)
            delay(retry.waitMs)
            if (!retry.probe) continue

            val nextStart = probeCommittedOffset(uploadUrl, currentOffset) ?: continue
            if (nextStart > endByte) {
                // The server accepted the entire chunk but its response was lost.
                // Advance the outer loop instead of retrying an empty byte range.
                return ChunkUploadResult.Committed(nextStart)
            }
            if (nextStart > currentOffset) {
                // Server committed some bytes; trim the chunk to what's still needed (counted from the chunk's start,
                // the bytes may have been trimmed before).
                currentBytes = bytes.copyOfRange((nextStart - offset).toInt(), bytes.size)
                currentOffset = nextStart
            }
        }

        // Unreachable — loop always throws or returns
        throw GraphApiException("Upload chunk failed at offset $currentOffset")
    }

    // One chunk PUT under the write-progress watchdog: a server that takes the body and never answers (archive#1175: one PUT
    // hung for more than 28 minutes with no error) is cut after the response wait, a link that stops moving after the
    // idle window. The engine's own timeouts become the outer net behind both.
    private suspend fun putChunk(
        uploadUrl: String,
        data: ByteArray,
        from: Long,
        endByte: Long,
        fileSize: Long,
    ): HttpResponse {
        val size = data.size.toLong()
        val progress = UploadProgress(size)
        val responseWaitMs = UploadTimeoutPolicy.computeResponseWaitMs(size, uploadWatchdog.responseWaitFloorMs)
        val outerLimitMs =
            UploadTimeoutPolicy.computeWatchedOuterLimitMs(size, CHUNK_OUTER_MIN_THROUGHPUT_BYTES_PER_SECOND, uploadWatchdog.idleWindowMs, responseWaitMs)
        return try {
            withUploadWatchdog(progress, uploadWatchdog.idleWindowMs, responseWaitMs, uploadWatchdog.tickMs, what = "Chunk upload $from-$endByte/$fileSize") {
                httpClient.put(uploadUrl) {
                    timeout {
                        requestTimeoutMillis = outerLimitMs
                        socketTimeoutMillis = outerLimitMs
                    }
                    header("Content-Range", "bytes $from-$endByte/$fileSize")
                    setBody(progressTrackingBody(data, progress))
                }
            }
        } catch (e: UploadWatchdogException) {
            log.warn("{} ({})", e.message, e.reason)
            throw e
        }
    }

    // How to go on after the server answered a chunk PUT with [response] (not 2xx): the wait before the next try and
    // whether the session is probed first. Throws when the answer is final or the retries are spent.
    private suspend fun chunkRetryAfterAnswer(
        response: HttpResponse,
        currentOffset: Long,
        delayMs: Long,
        lastTry: Boolean,
    ): ChunkRetry {
        val status = response.status.value
        val failure =
            when {
                // Session expired or gone — surface as a 404/410 so uploadLargeFile's two-attempt
                // wrapper recognises it and retries with a fresh session.
                status == 404 || status == 410 ->
                    GraphApiException(
                        "OneDrive upload session is gone (status $status) at offset $currentOffset; " +
                            "the server cleaned it up between probe and PUT — a fresh session will be created on retry",
                        status,
                    )
                // Rate limit — retry with backoff
                status == 429 -> {
                    val retryAfter = parseRetryAfterHeader(response.headers["Retry-After"]) ?: delayMs
                    throttleBudget.recordThrottle(minOf(retryAfter, MAX_SINGLE_BACKOFF_MS)) // same cap the reads apply to a hint
                    if (!lastTry) return ChunkRetry(retryAfter, probe = false)
                    GraphApiException("Rate limited (429) at offset $currentOffset after ${CHUNK_RETRY_DELAYS_MS.size} retries: ${response.status}", status)
                }
                // Transient server error (5xx, 408), or a range the server already holds part of (416: a resend after
                // a broken PUT whose probe told nothing) — query status and retry
                status == 408 || status == 416 || status >= 500 -> {
                    if (status == 503) throttleBudget.recordThrottle(delayMs) // 503 is a throttle signal, as on reads
                    if (!lastTry) return ChunkRetry(delayMs, probe = true)
                    GraphApiException("Chunk upload failed after ${CHUNK_RETRY_DELAYS_MS.size} retries at offset $currentOffset: ${response.status}", status)
                }
                // Permanent client error — don't retry
                else -> GraphApiException("Chunk upload failed at offset $currentOffset: ${response.status} - ${response.bodyAsText()}", status)
            }
        throw failure
    }

    // The offset the session expects next, or null when the probe told nothing (it broke off, or the server erred); a
    // session the server reports gone throws.
    private suspend fun probeCommittedOffset(
        uploadUrl: String,
        currentOffset: Long,
    ): Long? {
        val statusResponse =
            try {
                httpClient.get(uploadUrl)
            } catch (e: java.io.IOException) {
                log.info("Upload session status probe at offset {} broke off ({}); retrying the chunk from the offset it had", currentOffset, e.message)
                return null
            }
        if (statusResponse.status.value == 404 || statusResponse.status.value == 410) {
            throw GraphApiException("Upload session expired while retrying at offset $currentOffset", statusResponse.status.value)
        }
        if (!statusResponse.status.isSuccess()) return null
        val body =
            try {
                statusResponse.bodyAsText()
            } catch (e: java.io.IOException) {
                log.info("Upload session status probe at offset {} broke off ({}); retrying the chunk from the offset it had", currentOffset, e.message)
                return null
            }
        return runCatching { json.decodeFromString<UploadSession>(body) }.getOrNull()?.nextExpectedRanges.nextExpectedStart()
    }

    private fun List<String>?.nextExpectedStart(): Long? = this?.firstOrNull()?.substringBefore("-")?.toLongOrNull()

    private suspend fun authenticatedRequest(
        url: String,
        method: HttpMethod = HttpMethod.Get,
    ): HttpResponse = requireSuccess(sendThrottled(url, method))

    private suspend fun authenticatedRequest(
        url: String,
        method: HttpMethod,
        body: String,
    ): HttpResponse =
        requireSuccess(
            sendThrottled(url, method) {
                setBody(body)
                contentType(ContentType.Application.Json)
            },
        )

    /**
     * archive#293: the one path every Graph request that does not stream goes through, reads and writes alike.
     *
     * Before each attempt it takes a slot from [throttleBudget] (the shared circuit breaker and token
     * bucket), so a throttle storm brakes writes as well as reads. A 401 is retried once with a forced
     * token refresh. A 429 or 503 is retried with the server's Retry-After (or exponential backoff) up
     * to [MAX_THROTTLE_ATTEMPTS] times within [MAX_TOTAL_THROTTLE_WAIT_MS], and every throttle is
     * recorded on the budget. This is the Graph row of the canonical matrix in [HttpRetryBudget].
     *
     * [configure] builds the request afresh for every attempt, so a body is sent again intact. The final
     * response is returned as it is, whatever its status: a caller that needs a particular error for a
     * status (a delete that treats 404 as done, a create that reads 409) keeps its own mapping; the
     * rest use [requireSuccess]. Only 401, 429 and 503 are retried: the answers Graph gives instead of
     * acting on a request. A 409, 412 or 5xx other than 503 can mean the write took effect, so it
     * reaches the caller and is never repeated here.
     */
    private suspend fun sendThrottled(
        endpoint: String,
        method: HttpMethod,
        configure: suspend HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse {
        var refreshed = false
        var throttleAttempts = 0
        var totalThrottleWaitMs = 0L
        while (true) {
            throttleBudget.awaitSlot()
            val response =
                httpClient.request(
                    HttpRequestBuilder().apply {
                        url(endpoint)
                        this.method = method
                        bearerAuth(tokenProvider(refreshed))
                        configure()
                    },
                )
            if (response.status == HttpStatusCode.Unauthorized && !refreshed) {
                log.info("Got 401 on {} — forcing token refresh and retrying once", endpoint.takeLast(60))
                refreshed = true
                continue
            }
            if (shouldBackoff(response, throttleAttempts, totalThrottleWaitMs)) {
                val waitMs = pickBackoffMs(response, throttleAttempts, totalThrottleWaitMs)
                log.warn(
                    "Got {} on {} — throttled, waiting {}ms (attempt {}/{})",
                    response.status.value,
                    endpoint.takeLast(60),
                    waitMs,
                    throttleAttempts + 1,
                    MAX_THROTTLE_ATTEMPTS,
                )
                throttleBudget.recordThrottle(waitMs)
                delay(waitMs)
                throttleAttempts++
                totalThrottleWaitMs += waitMs
                continue
            }
            if (response.status.isSuccess()) throttleBudget.recordSuccess()
            return response
        }
    }

    // [what] names the operation in the message of a write ("Upload failed: ..."); without it the message is "API error: ...".
    // Either way the Graph request id rides on the exception and the error body is cut.
    private suspend fun requireSuccess(
        response: HttpResponse,
        what: String? = null,
    ): HttpResponse {
        if (response.status == HttpStatusCode.Unauthorized) {
            // UD-203: capture request-id so the failing call is correlatable in Graph's logs.
            throw AuthenticationException(
                "Authentication failed (401): ${truncateErrorBody(response.bodyAsText())}",
                requestId = extractRequestId(response),
            )
        }
        if (!response.status.isSuccess()) {
            // UD-203: capture request-id for support-ticket correlation.
            throw GraphApiException(
                "${what?.let { "$it failed" } ?: "API error"}: ${response.status} - ${truncateErrorBody(response.bodyAsText())}",
                response.status.value,
                requestId = extractRequestId(response),
            )
        }
        return response
    }

    /**
     * UD-203: pull the Microsoft Graph server-side request id off a response.
     *
     * Graph sets two correlation headers per response: `request-id` (the
     * server-side trace id, surfaced in Microsoft support tickets and
     * Graph's own logs) and `client-request-id` (echoed from the request if
     * the client supplied one). We prefer `request-id` when both are
     * present; fall back to `client-request-id` so a missing server header
     * still gives the user something to grep on.
     *
     * Returns null when neither header is present, e.g. for synthetic
     * responses (MockEngine, captive-portal HTML pages) or pre-flight
     * failures that never reached Graph's edge.
     */
    private fun extractRequestId(response: HttpResponse): String? =
        response.headers["request-id"]
            ?: response.headers["client-request-id"]

    /**
     * Recognise the IOException that [assertNotHtml] raises when a CDN edge
     * serves an HTML throttle/captive-portal page (HTTP 200, text/html) in
     * place of the file bytes. Matched on the stable message substring so the
     * download path can re-resolve the download URL rather than re-GETting the
     * same dead URL through the generic flake-retry.
     */
    private fun isHtmlBodyFailure(e: Throwable): Boolean =
        e is java.io.IOException && e.message?.contains("HTML instead of file bytes") == true

    private fun shouldBackoff(
        response: HttpResponse,
        attempts: Int,
        totalWaitMs: Long,
    ): Boolean {
        if (attempts >= MAX_THROTTLE_ATTEMPTS) return false
        if (totalWaitMs >= MAX_TOTAL_THROTTLE_WAIT_MS) return false
        val code = response.status.value
        return code == 429 || code == 503
    }

    private fun pickBackoffMs(
        response: HttpResponse,
        attempts: Int,
        totalWaitMs: Long,
    ): Long {
        // Prefer the server's Retry-After if present (delta-seconds or an HTTP date).
        val retryAfterMs =
            parseRetryAfterHeader(response.headers["Retry-After"])
                ?: (DEFAULT_BACKOFF_START_MS * (1L shl attempts)) // 2s, 4s, 8s, 16s
        // Cap per-call and per-sequence budgets — never block a request longer than the
        // remaining throttle budget, and never honour a per-call hint above the single-call cap.
        val remainingBudget = MAX_TOTAL_THROTTLE_WAIT_MS - totalWaitMs
        return minOf(retryAfterMs, MAX_SINGLE_BACKOFF_MS, remainingBudget)
    }

       private sealed class DownloadOutcome {
        data object Done : DownloadOutcome()

        data object RetryAuth : DownloadOutcome()

        // archive#329: the pre-authenticated downloadUrl's tempauth token expired (401/403 on
        // an authNeeded == false request). Carries the status for the failure surface.
        data class ExpiredUrl(
            val statusCode: Int,
            val body: String,
        ) : DownloadOutcome()

        data class Throttle(
            val waitMs: Long,
            val statusCode: Int,
        ) : DownloadOutcome()
    }

    companion object {
        private const val MAX_THROTTLE_ATTEMPTS = 5
        private const val MAX_SINGLE_BACKOFF_MS = 300_000L // 5 min per individual retry
        private const val MAX_TOTAL_THROTTLE_WAIT_MS = 900_000L // 15 min budget per request
        private const val DEFAULT_BACKOFF_START_MS = 2_000L
        private const val MAX_FLAKE_ATTEMPTS = 3

        // The waits before the retries of one chunk (archive#1175: a dropped connection is retried as a chunk, not as an attempt).
        private val CHUNK_RETRY_DELAYS_MS = listOf(2_000L, 4_000L, 8_000L)

        // The floor rate of the outer net behind the chunk watchdog: far below any usable link, as for the Internxt shards.
        private const val CHUNK_OUTER_MIN_THROUGHPUT_BYTES_PER_SECOND = 10L * 1024

        // Microsoft doesn't publish a hard TTL for the delta soft-delete marker, but
        // operational experience on personal tenants is ~30 days; business tenants can
        // be shorter. Treat this as "warn floor", not a guarantee.
        internal const val DELTA_SAFE_WINDOW_DAYS = 30L
    }

    suspend fun createSharingLink(
        path: String,
        type: String = "view",
        scope: String = "anonymous",
        expiryHours: Int = 24,
        password: String? = null,
    ): String? {
        val item = getItemByPath(path)
        val url = "$baseUrl/me/drive/items/${item.id}/createLink"
        val expiration =
            java.time.Instant
                .now()
                .plusSeconds(expiryHours.toLong() * 3600)
                .toString()
        val bodyObj =
            buildJsonObject {
                put("type", type)
                put("scope", scope)
                put("expirationDateTime", expiration)
                if (password != null) put("password", password)
            }
        val body = bodyObj.toString()

        val response = authenticatedRequest(url, HttpMethod("POST"), body)
        if (!response.status.isSuccess()) {
            log.warn("Failed to create sharing link: {}", response.bodyAsText())
            return null
        }

        return try {
            val linkResponse = json.decodeFromString<SharingLinkResponse>(response.bodyAsText())
            linkResponse.link?.webUrl
        } catch (e: Exception) {
            log.warn("Failed to parse sharing link response: {}", e.message)
            null
        }
    }

    suspend fun listPermissions(path: String): List<ShareInfo> {
        val item = getItemByPath(path)
        val url = "$baseUrl/me/drive/items/${item.id}/permissions"
        val response = authenticatedRequest(url)
        val body = response.bodyAsText()
        val collection = json.decodeFromString<PermissionCollectionResponse>(body)
        return collection.value
            .filter { it.link != null }
            .mapNotNull { perm ->
                val link = perm.link ?: return@mapNotNull null
                val id = perm.id ?: return@mapNotNull null
                ShareInfo(
                    id = id,
                    url = link.webUrl ?: "",
                    type = link.type ?: "unknown",
                    scope = link.scope ?: "unknown",
                    hasPassword = perm.hasPassword,
                    expiration = perm.expirationDateTime,
                )
            }
    }

    suspend fun deletePermission(
        path: String,
        permissionId: String,
    ): Boolean {
        val item = getItemByPath(path)
        val url = "$baseUrl/me/drive/items/${item.id}/permissions/$permissionId"
        val response = sendThrottled(url, HttpMethod.Delete)
        // Only a refused token is an error here; any other answer is "not removed".
        if (response.status == HttpStatusCode.Unauthorized) requireSuccess(response, "Delete permission")
        return response.status == HttpStatusCode.NoContent
    }

    suspend fun listSharedWithMe(): List<DriveItem> {
        val accumulated = mutableListOf<DriveItem>()
        val seenUrls = mutableSetOf<String>()
        var nextUrl: String? = "$baseUrl/me/drive/sharedWithMe"
        var pageIndex = 0
        // Same walk as listChildren, through the same throttled request path. A server that repeats a
        // nextLink would otherwise loop forever. The caller takes this list as the complete shared set
        // (an item missing from it reads as removed), so a walk that cannot finish fails instead of
        // returning the pages it got.
        while (nextUrl != null) {
            if (!seenUrls.add(nextUrl)) {
                throw ProviderException("sharedWithMe repeated a nextLink after $pageIndex pages; the shared listing is incomplete")
            }
            val response = authenticatedRequest(nextUrl)
            val body = response.bodyAsText()
            val collection = json.decodeFromString<DriveItemCollectionResponse>(body)
            accumulated.addAll(collection.value)
            log.debug("listSharedWithMe page {}: {} items, hasMore={}", pageIndex, collection.value.size, collection.nextLink != null)
            nextUrl = collection.nextLink
            pageIndex++
        }
        return accumulated
    }

    suspend fun createSubscription(
        notificationUrl: String,
        expirationDateTime: String,
    ): Subscription? {
        val url = "$baseUrl/subscriptions"
        val body =
            """
            {
                "changeType": "updated",
                "notificationUrl": "$notificationUrl",
                "resource": "/me/drive/root",
                "expirationDateTime": "$expirationDateTime"
            }
            """.trimIndent()

        return try {
            val response = authenticatedRequest(url, HttpMethod("POST"), body)
            if (!response.status.isSuccess()) {
                log.warn("Failed to create subscription: {}", response.bodyAsText())
                return null
            }
            val respBody = response.bodyAsText()
            json.decodeFromString<SubscriptionResponse>(respBody).toSubscription()
        } catch (e: Exception) {
            log.warn("Failed to create subscription: {}", e.message)
            null
        }
    }

    suspend fun renewSubscription(
        subscriptionId: String,
        expirationDateTime: String,
    ): Boolean {
        val url = "$baseUrl/subscriptions/$subscriptionId"
        val body = """{"expirationDateTime":"$expirationDateTime"}"""

        return try {
            val response = authenticatedRequest(url, HttpMethod("PATCH"), body)
            response.status.isSuccess()
        } catch (e: Exception) {
            log.warn("Failed to renew subscription: {}", e.message)
            false
        }
    }

    /**
     * The socket.io endpoint of the drive's change notifications (`GET /me/drive/root/subscriptions/socketIo`). The
     * reply's `notificationUrl` carries a token in its query: it is a credential, so neither it nor the reply body is
     * ever logged here (a failure throws with the status and Graph's error body, which does not carry it).
     */
    internal suspend fun getSocketIoEndpoint(): SocketIoEndpoint {
        val response = authenticatedRequest("$baseUrl/me/drive/root/subscriptions/socketIo")
        val parsed = json.decodeFromString<SubscriptionResponse>(response.bodyAsText())
        val url = parsed.notificationUrl?.takeIf { it.isNotBlank() }
            ?: throw GraphApiException("socketIo subscription answered without a notificationUrl", response.status.value)
        val expiresAtMs = parsed.expirationDateTime?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
        return SocketIoEndpoint(url, expiresAtMs)
    }

    suspend fun deleteSubscription(subscriptionId: String): Boolean {
        val url = "$baseUrl/subscriptions/$subscriptionId"

        return try {
            val response = sendThrottled(url, HttpMethod.Delete)
            response.status.isSuccess()
        } catch (e: Exception) {
            log.warn("Failed to delete subscription: {}", e.message)
            false
        }
    }

    override fun close() {
        httpClient.close()
    }
}

data class DeltaResult(
    val items: List<DriveItem>,
    val nextLink: String?,
    val deltaLink: String?,
)

class GraphApiException(
    message: String,
    val statusCode: Int = 0,
    requestId: String? = null,
) : ProviderException(message, requestId = requestId, httpStatus = statusCode.takeIf { it > 0 })

// Where an upload lands: [remotePath] names the file, [itemId] (when set) is the existing item it replaces in place.
private class UploadTarget(
    val remotePath: String,
    val itemId: String?,
)

private sealed interface ChunkUploadResult {
    data class Response(val response: HttpResponse) : ChunkUploadResult

    data class Committed(val nextOffset: Long) : ChunkUploadResult
}

// How a chunk is retried after a failed try: the wait before the next one, and whether the session is probed first.
private data class ChunkRetry(
    val waitMs: Long,
    val probe: Boolean,
)

// What one status probe of a stored upload session told: a final answer (SessionProbe), or a transient failure.
private sealed interface SessionProbeOutcome

private sealed interface SessionProbe : SessionProbeOutcome {
    // The session is alive; its state names the offset to resume at.
    class Alive(
        val session: UploadSession,
    ) : SessionProbe

    // The server said the session is gone or unusable (404, 410, another 4xx, an unreadable answer).
    class Unusable(
        val reason: String,
    ) : SessionProbe
}

// The probe broke off or met a 408/429/5xx; [failure] is thrown when the tries are spent, [waitMs] is the wait first.
private class TransientProbe(
    val failure: Exception,
    val waitMs: Long,
) : SessionProbeOutcome

/**
 * archive#1175: whether a stored session of the same local revision may be resumed by an upload that asks for
 * [conflictBehavior] under [ifMatchETag]. One file revision keeps one session (and the conflict behaviour it was created
 * with) through every failed attempt and replay, as long as resuming cannot overwrite more than the asking upload
 * allows:
 * - a session created with `fail` and no guard never overwrites anything (a name conflict is refused when the upload
 *   completes), so it serves a create, a keep-both and a guarded replace of the same bytes alike;
 * - any other session (a `replace` with or without a guard, a `rename`) serves only the same behaviour under the same
 *   guard, so the If-Match check its creation carried is never skipped;
 * - an entry stored before the behaviour was recorded keeps the old rule: the same guard.
 */
internal fun mayResume(
    stored: UploadSessionStore.StoredSession,
    conflictBehavior: String,
    ifMatchETag: String?,
): Boolean =
    when (stored.conflictBehavior) {
        null -> stored.ifMatchETag == ifMatchETag
        "fail" -> stored.ifMatchETag == null || stored.ifMatchETag == ifMatchETag
        else -> stored.conflictBehavior == conflictBehavior && stored.ifMatchETag == ifMatchETag
    }
