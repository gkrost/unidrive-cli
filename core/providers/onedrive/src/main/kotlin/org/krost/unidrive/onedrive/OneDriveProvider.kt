package org.krost.unidrive.onedrive

import org.krost.unidrive.*
import org.krost.unidrive.onedrive.model.DriveItem
import org.krost.unidrive.onedrive.model.FileSystemInfo
import org.krost.unidrive.onedrive.model.Subscription
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import org.krost.unidrive.io.withLocalIoRetry

// A file above this size is uploaded through a session in chunks; at or below it, in one request.
private const val CHUNKED_UPLOAD_THRESHOLD = 4L * 1024 * 1024
private const val MAX_FOLDER_CHAIN = 256

class OneDriveProvider(
    internal val config: OneDriveConfig = OneDriveConfig(),
    // Tests hand in a MockEngine for the Graph calls; production lets Ktor pick the engine.
    engine: io.ktor.client.engine.HttpClientEngine? = null,
    // Tests hand in a GraphApiService with a fixed token; production builds one over the token manager.
    graphApi: GraphApiService? = null,
) : CloudProvider {
    override val id = "onedrive"
    override val displayName = "Microsoft OneDrive"

    // OneDrive names are case-insensitive and case-preserving: `a.txt` and `A.txt` are one item (OneDriveServerNames.sameName).
    override val caseSensitivity: CaseSensitivity get() = CaseSensitivity.INSENSITIVE_PRESERVING

    private val log = LoggerFactory.getLogger(OneDriveProvider::class.java)
    private val oauthService = OAuthService(config)
    internal val tokenManager = TokenManager(config, oauthService)

    private val loggedVaultThisSession = AtomicBoolean(false)

    private val graphApi =
        graphApi ?: GraphApiService(config, engine = engine) { forceRefresh ->
            tokenManager.getValidToken(forceRefresh).accessToken
        }

    // UD-007: tokenManager is the source of truth — the setter is a no-op so
    // the default CloudProvider.logout()'s `isAuthenticated = false` becomes
    // harmless; this provider's own `logout()` override flips state via
    // `tokenManager.logout()` which the getter then reflects.
    override var isAuthenticated: Boolean
        get() = tokenManager.isAuthenticated
        set(_) { /* delegated to tokenManager; setter is a no-op */ }
    override val canAuthenticate: Boolean get() = Files.exists(config.tokenPath.resolve("token.json"))
    val includeShared: Boolean get() = config.includeShared

    override fun capabilities(): Set<Capability> =
        setOf(
            Capability.Delta,
            Capability.DeltaShared,
            Capability.Share,
            Capability.ListShares,
            Capability.RevokeShare,
            Capability.VerifyItem,
            Capability.QuotaExact,
            Capability.FastBootstrap,
            Capability.PermanentDelete,
        )

    override suspend fun authenticate() {
        tokenManager.initialize()
        if (!tokenManager.isAuthenticated) {
            tokenManager.authenticateWithBrowser()
        }
    }

    suspend fun authenticateWithDeviceCode() {
        tokenManager.initialize()
        if (!tokenManager.isAuthenticated) {
            tokenManager.authenticateWithDeviceCode()
        }
    }

    // archive#1240: one GET of the drive root with the stored token; never the browser fallback of authenticate().
    override suspend fun checkCredentials(): CredentialProof {
        if (!tokenManager.isAuthenticated) tokenManager.reload()
        if (!tokenManager.isAuthenticated) throw NotSignedInException("no OneDrive token is stored for this profile")
        val drive = graphApi.getDrive()
        val account = AccountName.read(config.tokenPath) ?: drive.owner?.user?.displayName?.trim()?.takeIf { it.isNotEmpty() }
        return CredentialProof(account)
    }

    // The token file a sign-in through the daemon wrote, read in place; never the browser fallback of authenticate().
    // A change channel that a sign-out stopped opens again for the new credentials.
    override suspend fun reloadCredentials() {
        tokenManager.reload()
        if (hintCallback != null) openChangeChannel()
    }

    // archive#1186: the daemon's own instance after a sign-out; the token file is already gone.
    override suspend fun forgetCredentials() {
        stopChangeChannel()
        tokenManager.forget()
    }

    override suspend fun logout() {
        stopChangeChannel()
        tokenManager.logout()
    }

    override fun attachRequestMetrics(sink: org.krost.unidrive.http.RequestMetricsSink?) = graphApi.attachRequestMetrics(sink)

    override fun close() {
        synchronized(channelLock) { closed = true }
        stopChangeChannel()
        oauthService.close()
        graphApi.close()
    }

    // ── remote-change hints (archive#111): Graph's socket.io endpoint, see OneDriveChangeChannel ──

    private val channelLock = Any()
    private val channelCounters = ChangeChannelCounters()

    @Volatile private var hintCallback: (() -> Unit)? = null

    private var changeChannel: OneDriveChangeChannel? = null
    private var closed = false

    /** Makes the channel; tests replace it with one over a fake socket and a fake clock. */
    internal var changeChannelFactory: (fetch: suspend () -> SocketIoEndpoint, onHint: () -> Unit, counters: ChangeChannelCounters) -> OneDriveChangeChannel =
        { fetch, onHint, counters -> OneDriveChangeChannel(fetch, onHint, counters) }

    /**
     * Opens the change channel lazily (unless the profile set `remote_notifications = false`). The second call replaces
     * the callback and keeps the channel.
     */
    override fun onRemoteChangeHint(callback: () -> Unit) {
        hintCallback = callback
        openChangeChannel()
    }

    override fun remoteChangeChannelStatus(): RemoteChangeChannelStatus =
        synchronized(channelLock) { changeChannel }?.status()
            ?: RemoteChangeChannelStatus(
                state = RemoteChangeChannelStatus.State.OFF,
                lastHintAtMs = channelCounters.lastHintAtMs,
                hints = channelCounters.hints.get(),
                rotations = channelCounters.rotations.get(),
            )

    private fun openChangeChannel() {
        if (!config.remoteNotifications) return
        synchronized(channelLock) {
            if (closed || changeChannel != null) return
            changeChannel =
                changeChannelFactory({ graphApi.getSocketIoEndpoint() }, { hintCallback?.invoke() }, channelCounters)
                    .also { it.start() }
        }
        log.debug("OneDrive change notifications: channel opened")
    }

    private fun stopChangeChannel() {
        val stopped = synchronized(channelLock) { changeChannel.also { changeChannel = null } } ?: return
        stopped.stop()
        log.debug("OneDrive change notifications: channel closed")
    }

    override suspend fun listChildren(path: String): List<CloudItem> {
            return graphApi.listChildren(path).filterNot { noteAndSkipVault(it) }.map { it.toCloudItem() }
    }

    override suspend fun getMetadata(path: String): CloudItem = itemAt(path).toCloudItem()

    override suspend fun download(
        remotePath: String,
        destination: Path,
    ): Long = downloadReading(remotePath, destination).bytes

    // gkrost/unidrive-cli#233: the receipt names the bytes the download read, as a listing of that item does: a file replaced since it
    // was listed is named as the version that came down, and a row never loses its name to a download that named none.
    override suspend fun downloadWithHash(
        remotePath: String,
        destination: Path,
        targetMtimeMillis: Long?,
    ): DownloadReceipt = downloadReading(remotePath, destination).receipt()

    override suspend fun downloadByIdWithHash(
        remoteId: String,
        remotePath: String,
        destination: Path,
        targetMtimeMillis: Long?,
    ): DownloadReceipt =
        if (SharedItemId.isShared(remoteId)) {
            downloadSharedReading(remoteId, remotePath, destination).receipt()
        } else {
            downloadWithHash(remotePath, destination, targetMtimeMillis)
        }

    private fun GraphApiService.DownloadedFile.receipt(): DownloadReceipt =
        DownloadReceipt(bytesWritten = bytes, contentId = oneDriveContentId(item.file?.hashes, item.size))

    private suspend fun downloadReading(
        remotePath: String,
        destination: Path,
    ): GraphApiService.DownloadedFile =
        // archive#247: a 404 with Graph error code `itemNotFound` that escapes the download
        // flow (the item is gone at the first path resolve, or still gone after the
        // bounded stale-URL re-resolve below) is a permanent failure, not a transient
        // one: the engine quarantines the row via PermanentDownloadFailureException —
        // the same machinery the Internxt provider feeds — instead of retrying the
        // download on every poll. Any other 404 error code and every 5xx/429 keep
        // today's retryable behaviour. The re-resolve arm below runs FIRST, so a
        // stale download URL on a live item still recovers.
        try {
            downloadResolved(remotePath, destination)
        } catch (e: GraphApiException) {
            if (e.isItemNotFound()) {
                throw PermanentDownloadFailureException(
                    "OneDrive item at '$remotePath' is gone (404 itemNotFound): the download can never succeed until the row is re-resolved",
                    cause = e,
                )
            }
            throw e
        }

    // A shared-with-me item cannot be found by its path (it is not in the caller's drive), so it is always fetched by
    // its id. The other items keep the path-resolving download.
    override suspend fun downloadById(
        remoteId: String,
        remotePath: String,
        destination: Path,
    ): Long {
        if (!SharedItemId.isShared(remoteId)) return super.downloadById(remoteId, remotePath, destination)
        return downloadSharedReading(remoteId, remotePath, destination).bytes
    }

    private suspend fun downloadSharedReading(
        remoteId: String,
        remotePath: String,
        destination: Path,
    ): GraphApiService.DownloadedFile =
        try {
            graphApi.downloadFileReading(remoteId, destination)
        } catch (e: GraphApiException) {
            if (e.isItemNotFound()) {
                throw PermanentDownloadFailureException(
                    "OneDrive shared item at '$remotePath' is gone (404 itemNotFound): the download can never succeed until the row is re-resolved",
                    cause = e,
                )
            }
            throw e
        }

    private suspend fun downloadResolved(
        remotePath: String,
        destination: Path,
    ): GraphApiService.DownloadedFile {
        val item = itemAt(remotePath)
        return try {
            graphApi.downloadFileReading(item.id, destination)
        } catch (e: GraphApiException) {
            // A 404 on the content GET means the pre-resolved download URL went
            // stale (the CDN URL expired) and/or the cached remoteId drifted off
            // the live item. Re-resolving BY PATH once fixes both: it yields a
            // fresh id AND a fresh download URL. Bounded to a single retry so a
            // genuinely-missing item still surfaces a clean not-found instead of
            // spinning. Any non-404 (403, 410, 5xx) propagates unchanged.
            if (e.statusCode != 404) throw e
            log.info(
                "OneDrive download of '{}' got 404 (stale download URL or drifted remoteId); " +
                    "re-resolving the item by path and retrying once",
                remotePath,
            )
            val fresh = itemAt(remotePath)
            graphApi.downloadFileReading(fresh.id, destination)
        }
    }

    // archive#247: the Graph error body is embedded in the message by requireSuccess and the
    // download path ("API error: 404 Not Found - {\"error\":{\"code\":\"itemNotFound\",...}}").
    // statusCode alone cannot distinguish "gone" from any other 404.
    private fun GraphApiException.isItemNotFound(): Boolean =
        message?.contains("\"code\":\"itemNotFound\"") == true

    private fun GraphApiException.isInvalidRequest(): Boolean =
        statusCode == 400 && message?.contains("\"code\":\"invalidRequest\"") == true

    private fun GraphApiException.isNameAlreadyExists(): Boolean =
        statusCode == 409 && message?.contains("nameAlreadyExists") == true

    // A name the drive compares as equal to a sibling's although it differs: the file shapes of the refusal say
    // so ("The specified item name is incompatible with a similar name on an existing item.").
    private fun GraphApiException.isSimilarName(): Boolean =
        isNameAlreadyExists() && message?.contains("similar name", ignoreCase = true) == true

    // The item at [path], and only that item. The drive resolves a path with the same name comparison it
    // applies to siblings, so a lookup of one name can answer its similar sibling: a delete or a move built on it
    // would act on the wrong file. A resolution to another name is answered as a missing item instead.
    private suspend fun itemAt(path: String): DriveItem {
        val item = graphApi.getItemByPath(path)
        val clean = "/" + path.trim('/')
        val wanted = clean.substringAfterLast('/')
        val got = item.name
        if (wanted.isEmpty() || got == null) return item
        // The parent the drive reports, when it reports one, must be the parent asked for as well.
        val actualParent = item.parentReference?.decodedPath?.removePrefix("/drive/root:")?.trimEnd('/')
        val parentMatches = actualParent == null || OneDriveServerNames.sameName(actualParent, clean.substringBeforeLast('/'))
        if (OneDriveServerNames.sameName(got, wanted) && parentMatches) return item
        throw GraphApiException(
            "API error: 404 Not Found - {\"error\":{\"code\":\"itemNotFound\",\"message\":\"'$path' resolved to a different name\"}}",
            404,
        )
    }

    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        existingRemoteId: String?,
        ifMatchETag: String?,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem {
        rejectUnsupportedName(remotePath)
        val attrs = Files.readAttributes(localPath, BasicFileAttributes::class.java)
        val fileSize = attrs.size()
        val fsInfo =
            FileSystemInfo(
                createdDateTime = attrs.creationTime().toInstant().toString(),
                lastModifiedDateTime = attrs.lastModifiedTime().toInstant().toString(),
            )

        // One upload, two conflict policies. "replace" is only legitimate for an UPDATE of a
        // file we own (existingRemoteId != null) — a re-upload of a touched file must not 409.
        // A CREATE (existingRemoteId == null) uploads with "fail" so a pre-existing remote at
        // the path is never blind-overwritten (UD-366 data-loss); the create-collision is then
        // resolved by keeping BOTH below.
        // A shared-with-me item is updated in place by its id, in the drive that holds it; there is no path to put it at.
        val sharedId = existingRemoteId?.takeIf { SharedItemId.isShared(it) }
        suspend fun put(conflictBehavior: String, etag: String? = null, target: String = remotePath, itemId: String? = null) =
            if (fileSize <= CHUNKED_UPLOAD_THRESHOLD) {
                // archive#802: the read races whatever holds the file open (indexer, backup); a short lock is retried.
                val content = withLocalIoRetry { Files.readAllBytes(localPath) }
                graphApi.uploadSimple(target, content, fsInfo, conflictBehavior, etag, itemId)
            } else {
                graphApi.uploadLargeFile(localPath, target, onProgress, fsInfo, conflictBehavior, etag, itemId)
            }

        // The drive refused the NAME as equal to a sibling's (a similar name; see [OneDriveServerNames]). Keep-both
        // by `rename` is refused too, so the file lands under a server name that carries an ASCII discriminator
        // derived from the original name (stable across retries and runs). Created with `fail` like any create:
        // an existing item of that name is never overwritten. When that is refused as well, the entry is
        // blocked by name: the exception names the sibling and is not retried.
        suspend fun landUnderServerName(refused: GraphApiException): CloudItem {
            val serverPath = serverPathFor(remotePath)
            log.warn(
                "OneDrive refuses the name of '{}' as a similar name of an existing sibling; landing the local copy as '{}' " +
                    "instead (the local name is kept, the mapping is recorded by the engine).",
                remotePath,
                serverPath,
            )
            return try {
                put("fail", target = serverPath).toCloudItem().asLanded(serverPath)
            } catch (e: GraphApiException) {
                throw if (e.isNameAlreadyExists()) nameRefused(remotePath, e, refused) else e
            }
        }

        // The keep-both of a true duplicate (the pre-existing item is preserved, the copy gets a server-assigned name).
        // The drive can still refuse it as a similar name, which is landed under a server name like any other.
        suspend fun keepBoth(): CloudItem =
            try {
                put("rename").toCloudItem()
            } catch (renameRefused: GraphApiException) {
                if (renameRefused.isSimilarName()) landUnderServerName(renameRefused) else throw renameRefused
            }

        if (existingRemoteId != null) {
            // MODIFIED file we own → replace-in-place, guarded by If-Match so a concurrent edit
            // surfaces as 412 instead of silently overwriting the other editor's change. archive#291
            // extends this guard to the ≤4 MiB simple-PUT path (previously only >4 MiB sessions
            // carried it, archive#113). Prefer the engine's PLAN-TIME eTag when supplied; otherwise fall
            // back to an at-upload-time fetch (closes the narrower fetch→PUT window, not the
            // engine's stale-view window — see archive#291 for the plan-time-persistence limitation).
            val guardETag =
                ifMatchETag
                    ?: runCatching { if (sharedId != null) graphApi.getItemById(sharedId) else itemAt(remotePath) }.getOrNull()?.eTag
            return try {
                if (sharedId != null) {
                    put("replace", guardETag, itemId = sharedId).toCloudItem().copy(id = sharedId, path = remotePath)
                } else {
                    put("replace", guardETag).toCloudItem()
                }
            } catch (e: GraphApiException) {
                // A keep-both copy would land in the caller's own drive, not beside the shared item: the refusal stands.
                if (e.statusCode == 412 && sharedId != null) throw e
                if (e.statusCode == 412) {
                    // archive#291: 412 Precondition Failed means a concurrent remote edit landed after the
                    // engine planned this replace. Overwriting it would be the classic lost update.
                    // Keep BOTH: the concurrent remote edit stays untouched and the local copy lands
                    // under a server-assigned name via "rename" (no If-Match — this is a fresh name,
                    // not a guarded replace). Routes distinctly from a 409 nameAlreadyExists below.
                    log.warn(
                        "#291: OneDrive replace of '{}' hit 412 Precondition Failed — a concurrent remote " +
                            "edit landed since the engine planned this upload. Preserving the remote change " +
                            "untouched and landing the local copy as a keep-both copy (conflictBehavior=rename) " +
                            "rather than overwriting it.",
                        remotePath,
                    )
                    return keepBoth().copy(keptBoth = true)
                }
                if (e.isSimilarName()) {
                    // The name this update targets is a similar name of another sibling: the update cannot move
                    // to a server name without breaking the item's identity, so it is blocked by name, not retried.
                    throw nameRefused(remotePath, e, e)
                }
                if (e.isNameAlreadyExists()) {
                    log.warn(
                        "UD-307: OneDrive rejected '{}' with nameAlreadyExists on a replace. Sync continues; " +
                            "rename the source file to work around.",
                        remotePath,
                    )
                }
                e.throwIfPermanentNameRefusal(remotePath)
                throw e
            }
        }

        // NEW file → never blind-overwrite an unknown remote (UD-366). Try "fail"; on a
        // collision keep BOTH — the pre-existing remote is preserved untouched and the local
        // copy lands under a server-assigned name via "rename".
        return try {
            put("fail").toCloudItem()
        } catch (e: GraphApiException) {
            e.throwIfPermanentNameRefusal(remotePath)
            if (e.isSimilarName()) {
                landUnderServerName(e)
            } else if (e.isNameAlreadyExists()) {
                identicalRemoteAt(remotePath, localPath, fileSize)?.let { return it }
                completeIntoEmptyItem(remotePath, fileSize) { behavior, etag -> put(behavior, etag) }?.let { return it }
                log.warn(
                    "UD-366: create-collision on '{}' — a different remote already exists at this path; " +
                        "preserving it untouched and landing the local copy as a keep-both copy " +
                        "(conflictBehavior=rename) rather than overwriting it.",
                    remotePath,
                )
                keepBoth()
            } else if (e.isInvalidRequest()) {
                throw PermanentUploadFailureException(
                    "OneDrive rejected the upload to '$remotePath' as invalidRequest: ${e.message}",
                    cause = e,
                )
            } else {
                throw e
            }
        }
    }

    // A create collided with an item of that name. When it is a file of the same size whose quickXorHash equals the
    // local file's, it already holds these bytes: it is adopted as the upload's result, with no copy and no write.
    // Whatever cannot be compared (a folder, no hash reported, the lookup or the local read fails) keeps both.
    private suspend fun identicalRemoteAt(
        remotePath: String,
        localPath: Path,
        fileSize: Long,
    ): CloudItem? {
        val existing =
            try {
                itemAt(remotePath)
            } catch (_: GraphApiException) {
                return null
            } catch (_: java.io.IOException) {
                return null
            } catch (_: kotlinx.serialization.SerializationException) {
                return null
            }
        val remoteHash = existing.file?.hashes?.quickXorHash
        if (existing.isFolder || remoteHash == null || existing.size != fileSize) return null
        val localHash =
            try {
                withLocalIoRetry { QuickXorHash.of(localPath) }
            } catch (_: java.io.IOException) {
                return null
            }
        if (localHash != remoteHash) return null
        log.info(
            "Create-collision on '{}': the remote item at this path has the same size and quickXorHash; " +
                "adopting it instead of uploading a keep-both copy.",
            remotePath,
        )
        return existing.toCloudItem()
    }

    // A chunked create collided with an empty file at its name. An upload that was cut short can leave exactly that
    // (the item an earlier session of the same upload reserved), and a chunked upload only exists for files that are
    // not empty, so the empty item holds no bytes a replace could destroy. The upload completes into it under its
    // eTag: a change since the lookup is a 412 and falls back to keeping both. Null when the colliding item is not
    // such an empty file, or when completing into it was refused.
    private suspend fun completeIntoEmptyItem(
        remotePath: String,
        fileSize: Long,
        put: suspend (String, String?) -> DriveItem,
    ): CloudItem? {
        if (fileSize <= CHUNKED_UPLOAD_THRESHOLD) return null
        val existing =
            try {
                itemAt(remotePath)
            } catch (_: GraphApiException) {
                return null
            } catch (_: java.io.IOException) {
                return null
            } catch (_: kotlinx.serialization.SerializationException) {
                return null
            }
        val eTag = existing.eTag
        val emptyFile = !existing.isFolder && existing.file != null && existing.size == 0L
        if (!emptyFile || eTag == null) return null
        log.info(
            "Create-collision on '{}': the item at this path is empty, the shape an earlier session of this upload leaves; " +
                "completing the upload into it instead of keeping a second copy.",
            remotePath,
        )
        return try {
            put("replace", eTag).toCloudItem()
        } catch (e: GraphApiException) {
            if (e.statusCode != 412) throw e
            log.warn("The empty item at '{}' changed before the upload could complete into it; keeping both.", remotePath)
            null
        }
    }

    override suspend fun delete(remotePath: String, ifMatchETag: String?) {
        val item =
            try {
                itemAt(remotePath)
            } catch (e: GraphApiException) {
                if (e.statusCode == 404) return // Already deleted
                throw e
            }
        // archive#291: when the engine supplies a plan-time eTag, guard the delete with If-Match so a
        // concurrent edit since the plan surfaces as 412 rather than destroying an unseen version.
        // Null (the legacy default and the current engine call sites, which persist no plan-time
        // eTag) preserves the unconditional delete-by-id.
        graphApi.deleteItem(item.id, ifMatchETag)
    }

    // The id decides, for the caller's own items as for shared ones: between the gather and this call the item can have
    // been renamed away and another item created at [remotePath], which a path lookup would delete instead. A 404 means
    // the item is gone (deleteItem treats it as done), and nothing may be looked up by the path then, for the same reason.
    override suspend fun deleteById(
        remoteId: String,
        remotePath: String,
    ) {
        graphApi.deleteItem(remoteId)
    }

    // archive#1178: rm --permanent. The item id resolves exactly as delete() does (a shared item by its
    // composite id, else by path); permanentDelete then bypasses the recycle bin.
    override suspend fun deletePermanently(remotePath: String) {
        rejectUnsupportedName(remotePath)
        val item =
            try {
                itemAt(remotePath)
            } catch (e: GraphApiException) {
                if (e.statusCode == 404) return // Already deleted
                throw e
            }
        graphApi.permanentDeleteItem(item.id)
    }

    override suspend fun deletePermanentlyById(
        remoteId: String,
        remotePath: String,
    ) {
        // The id decides (see deleteById): a delete for good must not reach the item that took the path.
        graphApi.permanentDeleteItem(remoteId)
    }

    override suspend fun createFolder(path: String): CloudItem {
        rejectUnsupportedName(path)
        val cleanPath = path.removePrefix("/")
        val name = cleanPath.substringAfterLast("/")
        val parent =
            cleanPath.substringBeforeLast("/", "").let {
                if (it.isEmpty()) "/" else "/$it"
            }
        return try {
            graphApi.createFolder(name, parent).toCloudItem()
        } catch (e: GraphApiException) {
            e.throwIfPermanentNameRefusal(path)
            if (!e.isNameAlreadyExists()) throw e
            createRefusedFolder(parent, name, e)
        }
    }

    // A folder create answers 409 nameAlreadyExists / "Name already exists" for a true duplicate and for a
    // similar name alike. Only the parent's listing tells them apart: an exact-name sibling is a true duplicate
    // (the existing path: the caller sees the 409); none means the drive compares the name as equal to another
    // sibling's, and the folder lands under a server name with an ASCII discriminator, adopting one that is
    // already there (a retry after a lost response, or a lost mapping).
    private suspend fun createRefusedFolder(
        parent: String,
        name: String,
        refused: GraphApiException,
    ): CloudItem {
        val siblings = listSiblings(parent)
        if (siblings == null || siblings.any { it.name != null && OneDriveServerNames.sameName(it.name, name) }) throw refused
        val serverName = OneDriveServerNames.disambiguate(name)
        val serverPath = (if (parent == "/") "" else parent) + "/" + serverName
        log.warn(
            "OneDrive refuses the folder name '{}/{}' as a similar name of an existing sibling; creating '{}' instead " +
                "(the local name is kept, the mapping is recorded by the engine).",
            parent,
            name,
            serverName,
        )
        return try {
            graphApi.createFolder(serverName, parent).toCloudItem().asLanded(serverPath)
        } catch (e: GraphApiException) {
            serverFolderOrNull(e, parent, serverName, serverPath)
                ?: throw nameRefused((if (parent == "/") "" else parent) + "/" + name, e, refused, siblings)
        }
    }

    // The folder already holding the server name, when the create of it was refused as a duplicate: ours from an
    // earlier attempt. Anything but a name conflict is not this function's to answer.
    private suspend fun serverFolderOrNull(
        refusal: GraphApiException,
        parent: String,
        serverName: String,
        serverPath: String,
    ): CloudItem? {
        if (!refusal.isNameAlreadyExists()) throw refusal
        val taken = listSiblings(parent)?.firstOrNull { it.name != null && OneDriveServerNames.sameName(it.name, serverName) }
        return taken?.takeIf { it.isFolder }?.toCloudItem()?.asLanded(serverPath)
    }

    // The children of [parent], or null when they cannot be listed: a classification that needs them then stays as it was.
    private suspend fun listSiblings(parent: String): List<DriveItem>? =
        try {
            graphApi.listChildren(parent)
        } catch (_: GraphApiException) {
            null
        } catch (_: java.io.IOException) {
            null
        }

    // The name a landed item has on the server, as a CloudItem the engine can tell from a keep-both copy.
    private fun CloudItem.asLanded(serverPath: String): CloudItem =
        copy(path = serverPath, name = serverPath.substringAfterLast('/'), nameOverridden = true)

    private fun serverPathFor(remotePath: String): String {
        val parent = remotePath.substringBeforeLast('/', "")
        return parent + "/" + OneDriveServerNames.disambiguate(remotePath.substringAfterLast('/'))
    }

    // The refusal for [path]: names the sibling the drive treats the name as equal to, when one can be found among
    // the parent's children by the ASCII part of the names (a message and a status detail, never a decision).
    private suspend fun nameRefused(
        path: String,
        cause: GraphApiException,
        first: GraphApiException,
        known: List<DriveItem>? = null,
    ): NameRefusedException {
        val parent = path.substringBeforeLast('/', "").ifEmpty { "/" }
        val name = path.substringAfterLast('/')
        val siblings = known ?: listSiblings(parent).orEmpty()
        val mine = OneDriveServerNames.skeleton(name)
        val sibling =
            siblings.mapNotNull { it.name }
                .filter { !OneDriveServerNames.sameName(it, name) && OneDriveServerNames.skeleton(it) == mine }
                .minOrNull()
        return NameRefusedException(
            "OneDrive treats the name of '$path' as identical to ${sibling?.let { "'$it'" } ?: "another item"} in the same folder " +
                "and refuses it (409 nameAlreadyExists): ${first.message}",
            reason = NameRefusedException.SIMILAR_NAME,
            sibling = sibling,
            cause = cause,
            requestId = cause.requestId,
        )
    }

    override suspend fun move(
        fromPath: String,
        toPath: String,
    ): CloudItem {
        rejectUnsupportedName(toPath)
        val item = itemAt(fromPath)
        return graphApi.moveItem(item.id, toPath, fromPath, ifMatchETag = item.eTag).toCloudItem()
    }

    override suspend fun moveById(
        remoteId: String,
        fromPath: String,
        toPath: String,
    ): CloudItem {
        rejectUnsupportedName(toPath)
        // The id decides (see deleteById). The item is read once for its eTag and for where it is now: if it was moved
        // since the gather, [fromPath] is no longer its place, and whether the write changes its folder is judged from
        // the real one. A 404 here fails the move, as the path based move fails for an item it cannot find.
        val current = graphApi.getItemById(remoteId)
        if (SharedItemId.isShared(remoteId)) {
            return graphApi.moveItem(remoteId, toPath, fromPath, ifMatchETag = current.eTag).toCloudItem().copy(id = remoteId, path = toPath)
        }
        return graphApi.moveItem(remoteId, toPath, current.pathInDrive() ?: fromPath, ifMatchETag = current.eTag).toCloudItem()
    }

    private fun rejectUnsupportedName(path: String) {
        OneDriveNameRules.issue(path)?.let { rule ->
            throw PermanentUploadFailureException("OneDrive cannot use '$path': $rule")
        }
    }

    private fun GraphApiException.throwIfPermanentNameRefusal(path: String) {
        val issue = OneDriveNameRules.issue(path)
        if (isInvalidRequest() || statusCode == 403 && path.contains("_vti_", ignoreCase = true)) {
            val reason = issue ?: if (statusCode == 403) "name contains the reserved _vti_ token" else "Graph rejected this name or path as invalidRequest"
            throw PermanentUploadFailureException(
                "OneDrive rejected '$path': $reason${if (isInvalidRequest()) " (invalidRequest)" else " (403 accessDenied)"}",
                cause = this,
            )
        }
    }

    // 410 Gone on any delta call = cursor aged out / drive re-keyed.  Convert to a
    // typed DeltaCursorExpiredException so callers can self-heal with a full
    // re-enumeration.  Any other status propagates unchanged.
    private suspend fun getDeltaConverting410(
        cursor: String?,
        readOnly: Boolean = false,
    ) =
        try {
            graphApi.getDelta(cursor, readOnly = readOnly)
        } catch (e: GraphApiException) {
            if (e.statusCode == 410) {
                throw DeltaCursorExpiredException(e.message ?: "Delta cursor expired (410 Gone)", e, e.requestId)
            }
            throw e
        }

    override suspend fun delta(
        cursor: String?,
        onPageProgress: ((itemsSoFar: Int) -> Unit)?,
        scanContext: org.krost.unidrive.ScanContext?,
    ): DeltaPage {
        // OneDrive's Graph delta endpoint pages via opaque @odata.nextLink
        // tokens that already encode a resumable cursor; the engine-side
        // staging slice is therefore redundant here; only ScanContext.readOnly is
        // honoured (a preview must not stamp delta_last_seen). See CloudProvider.kt
        // for the contract.
        val result = getDeltaConverting410(cursor, readOnly = scanContext?.readOnly == true)
        val visibleItems =
            result.items
                .filterNot { it.isRootItem() }
                .filterNot { noteAndSkipVault(it) }
        logDeletionStateSummary(visibleItems)
        val items = placed(visibleItems, known = result.items)

        return DeltaPage(
            items = items,
            cursor = result.deltaLink ?: result.nextLink ?: "",
            hasMore = result.nextLink != null,
        )
    }

    private fun logDeletionStateSummary(items: List<DriveItem>) {
        // Graph's two deletion signals on a delta page mean different things; surface the
        // distinction at DEBUG so an operator triaging "deleted X items today" can tell
        // hard deletes (recycle bin) from access-revocations (still extant elsewhere).
        var hardDelete = 0 // @microsoft.graph.removed state=deleted, or deleted facet present
        var softRemove = 0 // @microsoft.graph.removed state=removed (permission lost / out of view)
        var unspecified = 0 // either facet present but no state field (pre-2017 schema fallback)
        for (item in items) {
            val removedState = item.removed?.state
            val deletedPresent = item.deleted != null
            when {
                removedState == "removed" -> softRemove++
                removedState == "deleted" || deletedPresent -> hardDelete++
                item.removed != null -> unspecified++
            }
        }
        if (hardDelete + softRemove + unspecified > 0) {
            log.debug(
                "Delta page deletion mix: hardDelete={} softRemove={} unspecified={} (totalItems={})",
                hardDelete,
                softRemove,
                unspecified,
                items.size,
            )
        }
    }

    override suspend fun deltaFromLatest(scanContext: org.krost.unidrive.ScanContext?): CapabilityResult<DeltaPage> {
        // A preview's bootstrap must not stamp delta_last_seen — same readOnly contract as delta().
        val result = graphApi.getDelta(fromLatest = true, readOnly = scanContext?.readOnly == true)
        val items = placed(result.items.filterNot { it.isRootItem() }, known = result.items)
        return CapabilityResult.Success(
            DeltaPage(
                items = items,
                cursor = result.deltaLink ?: result.nextLink ?: "",
                hasMore = result.nextLink != null,
            ),
        )
    }

    override suspend fun deltaWithShared(cursor: String?): CapabilityResult<DeltaPage> {
        if (!config.includeShared) {
            // The capability is supported in principle, but the config has not
            // opted in. Fall through to a plain delta by returning Unsupported so
            // callers can decide what to do — mirrors the previous super call.
            return CapabilityResult.Unsupported(
                Capability.DeltaShared,
                "includeShared is disabled in provider config",
            )
        }
        val result = getDeltaConverting410(cursor)

        val mainItems =
            placed(
                result.items
                    .filterNot { it.isRootItem() }
                    .filterNot { noteAndSkipVault(it) },
                known = result.items,
            )

        val sharedItems = graphApi.listSharedWithMe().mapNotNull { it.toSharedCloudItem() }
        log.debug("Delta: {} shared items", sharedItems.size)

        return CapabilityResult.Success(
            DeltaPage(
                items = mainItems + sharedItems,
                cursor = result.deltaLink ?: result.nextLink ?: "",
                hasMore = result.nextLink != null,
            ),
        )
    }

    override suspend fun quota(): QuotaInfo = graphApi.getQuota()

    // The drive owner id (GET /me/drive; /me is 403 for the app's file-only scopes) with the token the provider runs with: one call, for a profile signed in before ids were kept (AccountOwner).
    override suspend fun currentAccountId(): String? = graphApi.getDrive().accountId()

    override suspend fun verifyItemExists(remoteId: String): CapabilityResult<Boolean> =
        try {
            graphApi.getItemById(remoteId)
            CapabilityResult.Success(true)
        } catch (e: GraphApiException) {
            if (e.statusCode == 404) {
                CapabilityResult.Success(false)
            } else {
                throw e
            }
        }

    // A shared-with-me entry as the engine sees it. The entry itself is a stub in the caller's drive; the item is
    // `remoteItem`, and its drive is `remoteItem.parentReference.driveId` (on OneDrive Business the stub has no
    // parentReference at all, and the file or folder facet and the hashes are only on `remoteItem`). The pair is
    // what addresses the item (see [SharedItemId]); an entry without it cannot be reached by any call, so it is left out.
    private fun DriveItem.toSharedCloudItem(): CloudItem? {
        val remote = remoteItem
        val itemId = remote?.id?.takeIf { it.isNotEmpty() }
        val driveId = (remote?.parentReference?.driveId ?: remote?.driveId)?.takeIf { it.isNotEmpty() }
        if (remote == null || itemId == null || driveId == null) {
            log.warn("Skipping shared-with-me entry '{}': it names no remote drive and item, so it cannot be addressed", name ?: "(unnamed)")
            return null
        }
        val displayName = name ?: remote.name ?: "unnamed"
        val hashes = file?.hashes ?: remote.file?.hashes
        val modifiedSource =
            fileSystemInfo?.lastModifiedDateTime ?: remote.fileSystemInfo?.lastModifiedDateTime
                ?: lastModifiedDateTime ?: remote.lastModifiedDateTime
        val createdSource =
            fileSystemInfo?.createdDateTime ?: remote.fileSystemInfo?.createdDateTime
                ?: createdDateTime ?: remote.createdDateTime
        return CloudItem(
            id = SharedItemRef(driveId, itemId).id,
            name = displayName,
            path = "/Shared/$displayName",
            size = if (size > 0) size else remote.size ?: 0,
            isFolder = folder != null || remote.folder != null,
            modified = modifiedSource?.let { runCatching { Instant.parse(it) }.getOrNull() },
            created = createdSource?.let { runCatching { Instant.parse(it) }.getOrNull() },
            hash = hashes?.quickXorHash ?: hashes?.sha256Hash,
            contentId = oneDriveContentId(hashes, if (size > 0) size else remote.size ?: 0),
            mimeType = file?.mimeType ?: remote.file?.mimeType,
            // No version: the entry's own eTag is the stub's, not the shared item's. A write of the item then
            // fetches the item's current eTag by its id (see upload).
        )
    }

    // archive#287: a deletion tombstone is never the root item. Graph's documented minimal
    // tombstone shape carries just id + removal marker — parentReference, when present
    // at all, has no path — so the null-path heuristic would swallow it and the remote
    // delete would never propagate. Check the deletion facets first; the engine
    // resolves a pathless tombstone via its remote id.
    private fun DriveItem.isRootItem(): Boolean {
        if (isTombstone()) return false
        if (root != null) return true
        val parentPath = parentReference?.decodedPath
        // A live item with a parent id but no path is not the root: Graph may leave the path off (see [placed]).
        return parentPath == null && parentReference?.id == null || parentPath == "/drive/root"
    }

    private fun DriveItem.isTombstone(): Boolean = removed != null || deleted != null

    // Where the drive has the item now, from its own parentReference.path; null when the answer carries none.
    private fun DriveItem.pathInDrive(): String? {
        val parentPath = parentReference?.decodedPath?.removePrefix("/drive/root:") ?: return null
        val leaf = name ?: return null
        return if (parentPath.isEmpty() || parentPath == "/") "/$leaf" else "$parentPath/$leaf"
    }

    /**
     * The delta items as [CloudItem]s, each with the path the engine keys it by. Graph documents that a delta item may
     * come without `parentReference.path`, and OneDrive Business and SharePoint drives leave it off every item: such a
     * live item is placed below its parent folder, found among [known] (the page's own items) or by id, and one whose
     * folder is gone is left out (a deletion below it comes as its own tombstone). A tombstone without a path gets
     * the unknown-path marker "/" the engine resolves by remote id; it never gets `/<name>`, which would name a file
     * at the root. A failing lookup fails the page: guessing a path would delete or overwrite the wrong item.
     */
    private suspend fun placed(
        items: List<DriveItem>,
        known: List<DriveItem>,
    ): List<CloudItem> {
        val folders = HashMap<String, DriveItem>()
        for (item in known) if (!item.isTombstone() && (item.isFolder || item.root != null)) folders[item.id] = item
        val resolved = HashMap<String, String?>()
        val placedItems = ArrayList<CloudItem>(items.size)
        for (item in items) {
            val parentId = item.parentReference?.id
            if (item.parentReference?.decodedPath != null || item.isTombstone() || parentId == null) {
                placedItems += item.toCloudItem()
                continue
            }
            val parentPath = folderPath(parentId, folders, resolved, 0)
            if (parentPath == null) {
                log.warn("Delta item '{}' ({}) is below a folder that is gone; it is left out", item.name ?: "(unnamed)", item.id)
                continue
            }
            placedItems += item.toCloudItem(parentPath)
        }
        return placedItems
    }

    // The path of the folder [id], or null when it is gone (404). [resolved] remembers every answer for the page.
    private suspend fun folderPath(
        id: String,
        folders: Map<String, DriveItem>,
        resolved: MutableMap<String, String?>,
        depth: Int,
    ): String? {
        if (resolved.containsKey(id)) return resolved[id]
        if (depth > MAX_FOLDER_CHAIN) throw ProviderException("The OneDrive folder chain above '$id' is longer than $MAX_FOLDER_CHAIN levels or loops")
        val folder =
            folders[id] ?: try {
                graphApi.getItemById(id)
            } catch (e: GraphApiException) {
                if (e.statusCode == 404) {
                    resolved[id] = null
                    return null
                }
                throw e
            }
        val path = pathOfFolder(folder, folders, resolved, depth)
        resolved[id] = path
        return path
    }

    private suspend fun pathOfFolder(
        folder: DriveItem,
        folders: Map<String, DriveItem>,
        resolved: MutableMap<String, String?>,
        depth: Int,
    ): String? {
        if (folder.root != null) return "/"
        val reference = folder.parentReference
        val parentPath =
            when {
                reference?.decodedPath != null -> reference.decodedPath!!.removePrefix("/drive/root:").ifEmpty { "/" }
                reference?.id != null -> folderPath(reference.id, folders, resolved, depth + 1) ?: return null
                else -> return "/" // no parent at all: the root, as isRootItem reads it
            }
        val leaf = folder.name ?: throw ProviderException("The OneDrive folder '${folder.id}' has no name")
        return if (parentPath == "/") "/$leaf" else "$parentPath/$leaf"
    }

    private fun noteAndSkipVault(item: DriveItem): Boolean {
        if (!item.isPersonalVault) return false
        val displayName = item.name ?: "(unnamed)"
        if (loggedVaultThisSession.compareAndSet(false, true)) {
            log.info("Skipping Personal Vault entry (protected): {}", displayName)
        } else {
            log.debug("Skipping Personal Vault entry (protected): {}", displayName)
        }
        return true
    }

    // [resolvedParentPath]: the parent folder's path, for an item that came without parentReference.path (see [placed]).
    private fun DriveItem.toCloudItem(resolvedParentPath: String? = null): CloudItem {
        val parentPath =
            resolvedParentPath
                ?: parentReference
                    ?.decodedPath
                    ?.removePrefix("/drive/root:")
                    ?.ifEmpty { "/" }
        val fullPath =
            when {
                // A tombstone that does not say where it was: not "/<name>", which the engine would take for the file of
                // that name at the root. The engine resolves a "/" tombstone by its remote id.
                parentPath == null && isTombstone() -> "/"
                parentPath == null || parentPath == "/" -> "/${name ?: ""}"
                else -> "$parentPath/${name ?: ""}"
            }

        // Graph's top-level lastModifiedDateTime/createdDateTime are server-stamped on
        // commit; fileSystemInfo carries the client-supplied local mtime/ctime that
        // round-trips through the API. Prefer it when present so a fresh download
        // gives the local file the same mtime it had at upload time.
        val modifiedSource = fileSystemInfo?.lastModifiedDateTime ?: lastModifiedDateTime
        val createdSource = fileSystemInfo?.createdDateTime ?: createdDateTime

        return CloudItem(
            id = id,
            name = name ?: "",
            path = fullPath,
            size = size,
            isFolder = folder != null,
            modified = modifiedSource?.let { runCatching { Instant.parse(it) }.getOrNull() },
            created = createdSource?.let { runCatching { Instant.parse(it) }.getOrNull() },
            // archive#112 / PR archive#193: prefer quickXorHash so remoteHash is consistent with
            // hashAlgorithm()=QuickXor. Personal OneDrive reliably supplies quickXorHash
            // for all files; sha256Hash is a fallback for drives that omit it (e.g. some
            // SharePoint/business tenants). When neither field is present (folder, root,
            // or draft item) hash is null and archive#112's isTouchOnly guard (remoteHash != null)
            // safely falls back to mtime+size. One-time effect: existing DB entries whose
            // stored remoteHash was SHA-256 will mismatch on the next enumeration and get
            // re-stored as QuickXor — a single re-compare, not data loss.
            hash = file?.hashes?.quickXorHash ?: file?.hashes?.sha256Hash,
            // gkrost/unidrive-cli#233: the bytes' own name, which a rename leaves alone (the eTag and the modified time below
            // both move with it); see oneDriveContentId.
            contentId = oneDriveContentId(file?.hashes, size),
            mimeType = file?.mimeType,
            deleted = isHardDeleted(),
            // archive#183: a `@microsoft.graph.removed` facet with state="removed" means the
            // item's access was revoked (shared link revoked / moved out of scope). The
            // path resolver (SyncEngine.resolveItemPath) uses this flag to retire the
            // DB row via TRASHED without touching the local file.
            accessRevoked = removed?.state == "removed",
            // The Graph eTag: the only token Graph's If-Match accepts (the content hash above never matches it).
            version = eTag,
        )
    }

    // Graph's delta page carries two distinct removal signals, and only one of them
    // means the local copy should go. A `@microsoft.graph.removed` facet with
    // `state == "removed"` is a SOFT removal — the item still exists, the user merely
    // lost access (a shared-with-me link was revoked, or the item moved out of scope).
    // Reaping the local copy on that signal destroys still-valid data. A hard delete is
    // either `removed.state == "deleted"` or the standalone `deleted` facet. A removed
    // facet with no state at all is the pre-2017 schema fallback; we cannot prove it is
    // soft, so we keep the historical reading and treat it as a hard delete.
    private fun DriveItem.isHardDeleted(): Boolean {
        val removedFacet = removed
        if (removedFacet != null) {
            return removedFacet.state != "removed"
        }
        return deleted != null
    }

    override suspend fun share(
        path: String,
        expiryHours: Int,
        password: String?,
    ): CapabilityResult<String> {
        val url =
            graphApi.createSharingLink(path, expiryHours = expiryHours, password = password)
                ?: return CapabilityResult.Unsupported(Capability.Share, "OneDrive could not create a sharing link for $path")
        return CapabilityResult.Success(url)
    }

    override suspend fun listShares(path: String): CapabilityResult<List<ShareInfo>> =
        CapabilityResult.Success(graphApi.listPermissions(path))

    override suspend fun revokeShare(
        path: String,
        shareId: String,
    ): CapabilityResult<Unit> =
        if (graphApi.deletePermission(path, shareId)) {
            CapabilityResult.Success(Unit)
        } else {
            CapabilityResult.Unsupported(Capability.RevokeShare, "OneDrive failed to revoke share $shareId at $path")
        }

    suspend fun createSubscription(
        notificationUrl: String,
        expirationDateTime: String,
    ): Subscription? = graphApi.createSubscription(notificationUrl, expirationDateTime)

    suspend fun renewSubscription(
        subscriptionId: String,
        expirationDateTime: String,
    ): Boolean = graphApi.renewSubscription(subscriptionId, expirationDateTime)

    suspend fun deleteSubscription(subscriptionId: String): Boolean = graphApi.deleteSubscription(subscriptionId)

    override fun hashAlgorithm(): HashAlgorithm = HashAlgorithm.QuickXor

    // CloudItem.version carries the Graph eTag, and If-Match takes nothing else: the quickXor hash in CloudItem.hash
    // never matches it.
    override val reportsItemVersions: Boolean get() = true

    // gkrost/unidrive-cli#233: every file a listing converts names its bytes by its content hash (CloudItem.contentId, oneDriveContentId),
    // so the once-per-profile backfill fills the rows from before the column.
    override val reportsContentIds: Boolean get() = true

    override fun statusFields(): List<StatusField> =
        listOf(
            StatusField(
                label = "Include shared",
                value = config.includeShared.toString(),
            ),
        )
}
