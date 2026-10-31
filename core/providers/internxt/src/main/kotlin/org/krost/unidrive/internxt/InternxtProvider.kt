package org.krost.unidrive.internxt

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.krost.unidrive.*
import org.krost.unidrive.internxt.model.FolderContentResponse
import org.krost.unidrive.internxt.model.InternxtFile
import org.krost.unidrive.internxt.model.InternxtFolder
import org.krost.unidrive.internxt.model.MultipartPart
import org.krost.unidrive.internxt.model.SharedByMe
import org.krost.unidrive.internxt.model.tryParseInternxtInstant
import org.krost.unidrive.io.fileSha256Hex
import org.krost.unidrive.io.withLocalIoRetry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class InternxtProvider(
    private val config: InternxtConfig = InternxtConfig(),
    // Tests hand in a client on a MockEngine; production builds the default one.
    httpClient: io.ktor.client.HttpClient? = null,
) : CloudProvider {
    override val id = "internxt"
    override val displayName = "Internxt Drive"

    // Internxt keeps exact duplicates: two files of one name in one folder (archive#403).
    override val allowsDuplicateNames: Boolean get() = true

    // Internxt tells names apart by case: the drive server keys a folder's names by a unique index on the plain name
    // (default, deterministic collation), so `a.txt` and `A.txt` are two files.
    override val caseSensitivity: CaseSensitivity get() = CaseSensitivity.SENSITIVE

    // gkrost/unidrive-cli#202: every file a listing converts names its stored object (CloudItem.contentId, internxtContentId).
    override val reportsContentIds: Boolean get() = true

    // archive#578: the --upload-concurrency flag's runtime override of the profile's upload_concurrency
    // key; null leaves the key in force. [effectiveUploadConcurrency] is what the part loop reads.
    private var concurrencyOverride: Int? = null
    val effectiveUploadConcurrency: Int get() = concurrencyOverride ?: config.uploadConcurrency

    private val log = org.slf4j.LoggerFactory.getLogger(InternxtProvider::class.java)
    private val crypto = InternxtCrypto()
    private val authService = AuthService(config)

    // archive#730: mnemonicToSeed is PBKDF2-HMAC-SHA512 (2048 iterations) and used to run once per
    // download and upload — 8.2 % of the Java execution samples in the live JFR. The seed is
    // a pure function of the mnemonic, so the last derived value is held for the life of this
    // provider: a JWT refresh keeps the mnemonic and hits the cache, a re-auth that changes it
    // derives a fresh seed and zeroes the replaced bytes. Memory only — never persisted, never
    // logged.
    private val seedLock = Any()

    private var cachedSeed: CachedSeed? = null

    private val api =
        if (httpClient == null) {
            InternxtApiService(config, credentialsProvider = { forceRefresh -> authService.getValidCredentials(forceRefresh) })
        } else {
            InternxtApiService(config, credentialsProvider = { forceRefresh -> authService.getValidCredentials(forceRefresh) }, httpClient = httpClient)
        }
    override val remoteObjectAuditor: RemoteObjectAuditor =
        InternxtRemoteObjectAuditor(
            getFileMeta = api::getFileMeta,
            getBridgeFileInfo = api::getBridgeFileInfo,
            getBridgeMirrors = api::getBridgeMirrors,
        )

    // archive#1219: the proof that a local file is the stored file, by the ciphertext hash the network recorded, no download.
    private val localContentProof =
        InternxtLocalContentProof(
            getFileMeta = api::getFileMeta,
            getBridgeFileInfo = api::getBridgeFileInfo,
            fileKeyFor = { bucket, index ->
                val creds = authService.getValidCredentials()
                crypto.deriveFileKey(crypto.deriveBucketKey(seedFor(creds.mnemonic), bucket), index)
            },
            crypto = crypto,
        )
    private val secureRandom = java.security.SecureRandom()

    override suspend fun contentMatchesLocal(
        remoteId: String,
        remotePath: String,
        local: Path,
    ): ContentProof? = localContentProof.prove(remoteId, local)

    // Crash-recovery sidecar for in-flight uploads. Located alongside the
    // per-profile token dir so the tombstone lifetime is automatically scoped
    // to the same profile that owns the credentials (a stale tombstone from
    // an old profile can't be misread by a new one). See UploadTombstoneStore
    // for the full lifecycle and the spec compensating for Internxt's lack
    // of a resumable-upload protocol.
    // internal and var only so a test can hand in a store whose rename misbehaves (archive#1203); nothing else assigns it.
    internal var tombstoneStore = UploadTombstoneStore(config.tokenPath.resolve("upload-tombstones"))

    // Internxt notifications (socket.io). Only a process that consumes remote-change
    // hints (onRemoteChangeHint, today `sync --watch`) opens the socket: a one-shot
    // `status` / `ls` / `sync` has no use for it and must not reach the endpoint.
    // Started by whichever comes second, authenticate() success (we need a JWT for
    // the handshake) or onRemoteChangeHint(); disposed in close() / logout().
    @Volatile
    private var notificationsClient: NotificationsClient? = null

    // INTERNXT_NOTIFICATIONS=off: say so once per provider, not on every authenticate().
    @Volatile
    private var notificationsOffLogged = false

    @Volatile
    private var remoteChangeCallback: () -> Unit = {}

    @Volatile
    private var remoteHintsWanted = false

    private val notificationsStartLock = Mutex()

    // UD-357: process-local cache of (parentUuid, sanitizedName) -> uuid for
    // folder lookups. Populated on createFolder success (the API returns the
    // new folder's UUID — without this the next resolveFolder call races
    // Internxt's read-after-write inconsistency window: POST /drive/folders
    // returns 200, but GET /drive/folders/{parentUuid} doesn't yet show the
    // child for ~1-3 seconds). Consulted at the top of each resolveFolder
    // segment-walk so multi-level mkdir cascades survive that window.
    //
    // Also populated on read (after a successful list) so subsequent
    // resolveFolder calls hitting the same parent don't re-list — a free
    // latency win on top of the bug fix.
    //
    // Eviction: none. Process-lifetime cache. A folder created via this
    // cache then deleted out-of-band (web UI, another sync session) leaves
    // a stale entry whose UUID will 404 on the next operation; acceptable
    // trade for the in-process scope.
    private val folderCache = FolderUuidCache()

    // A folder with no parent that is not the drive root is the top of a tree of its own (the desktop app's backups keep
    // one per device). Nothing below one is part of the drive: the listing leaves it out, and a write addressed to it by
    // id is refused ([refuseOutsideDrive]). Process-lifetime, like [folderCache]: the folders known to be the top of
    // such a tree or below one, and the folders known to hang below the drive root, so each chain is climbed once.
    private val outsideDriveFolders: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val insideDriveFolders: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // UD-364: process-lifetime cache of /files/limits.maxUploadFileSize.
    // Lazy single-flight: first maxFileSizeBytes() call fetches + caches;
    // subsequent calls return cached. Server returns null for "no cap",
    // which we still cache (the negative answer is also stable per plan).
    // Failures (network, 401) do NOT poison the cache — the next call retries.
    // User plans change rarely; no eviction.
    private val limitsLock = Any()

    @Volatile
    private var fileLimitsCached: Long? = null

    @Volatile
    private var fileLimitsResolved: Boolean = false

    // UD-007: authService is the source of truth — see OneDriveProvider for
    // the rationale. Setter is a no-op; the override of `logout()` below
    // flips state via `authService.logout()`.
    override var isAuthenticated: Boolean
        get() = authService.isAuthenticated
        set(_) { /* delegated to authService; setter is a no-op */ }
    override val canAuthenticate: Boolean get() = Files.exists(config.tokenPath.resolve("credentials.json"))

    override fun capabilities(): Set<Capability> =
        setOf(
            Capability.Delta,
            Capability.QuotaExact,
            Capability.FastBootstrap,
            Capability.PermanentDelete,
            Capability.View(ViewKind.RECENTS),
            Capability.View(ViewKind.FAVORITES),
            Capability.View(ViewKind.SHARED_BY_ME),
        )

    // True while the last list of the shared view was cut short, so that a cut that lasts says so once, not at every refresh.
    private val sharedViewCutLogged = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * The views this provider has: the favorite files ([ViewKind.FAVORITES], from [InternxtApiService.listFavorites]), the recent files ([ViewKind.RECENTS], from [InternxtApiService.listRecentFiles]) and what the account shares by itself ([ViewKind.SHARED_BY_ME]), from [InternxtApiService.listSharedByMe].
     * A reference is the uuid of the real file or folder, and whether it is a folder, and nothing else: no name, no key, no
     * link (the answers carry those, the models of the listing do not declare them). The folders the account shares come
     * first, then the files; the engine shows a folder as a read-only alias of the real folder. A gateway without the sharings routes answers [CapabilityResult.Unsupported]; any other
     * failure throws, as every listing does. The list is cut at [limit] and at the module's cap of references; a cut is
     * logged (counts only), answered as a truncated [ViewList], and the list is still the first references the server served.
     */
    override suspend fun listView(
        kind: ViewKind,
        limit: Int,
    ): CapabilityResult<List<ViewRef>> {
        if (kind == ViewKind.RECENTS) return listRecents(limit)
        if (kind == ViewKind.FAVORITES) return listFavorites(limit)
        if (kind != ViewKind.SHARED_BY_ME) return super.listView(kind, limit)
        val listed =
            when (val shared = api.listSharedByMe()) {
                SharedByMe.Unsupported -> return CapabilityResult.Unsupported(Capability.View(kind), "The gateway has no sharings routes")
                is SharedByMe.Listed -> shared
            }
        val cap = limit.coerceAtLeast(1)
        val cut = listed.truncated || listed.foldersCut || listed.refs.size > cap
        if (!cut) {
            sharedViewCutLogged.set(false)
        } else if (!sharedViewCutLogged.getAndSet(true)) {
            log.warn(
                "The list of the items the account shares is cut: showing the first {} items (the server served {} references{})",
                minOf(listed.refs.size, cap),
                listed.refs.size,
                if (listed.truncated || listed.foldersCut) ", and there are more than the cap of ${InternxtApiService.MAX_SHARED_REFS}" else "",
            )
        }
        return CapabilityResult.Success(ViewList(listed.refs.take(cap).map { ViewRef(it.uuid, it.isFolder) }, truncated = cut))
    }

    // The files the account marked as favorites. Folders are left out for now: the folder stream is not even requested (they
    // would be shown as read-only aliases of the real folders, as the shared view's are; that is still to come). A gateway
    // without the route answers unsupported; any other failure throws.
    private suspend fun listFavorites(limit: Int): CapabilityResult<List<ViewRef>> {
        val listed =
            api.listFavorites(includeFolders = false)
                ?: return CapabilityResult.Unsupported(Capability.View(ViewKind.FAVORITES), "The gateway has no favorites route")
        val cap = limit.coerceAtLeast(1)
        val cut = listed.truncated || listed.refs.size > cap
        if (!cut) {
            favoritesViewCutLogged.set(false)
        } else if (!favoritesViewCutLogged.getAndSet(true)) {
            log.warn("The list of favorites is cut: showing the first {} of {} files", minOf(listed.refs.size, cap), listed.refs.size)
        }
        return CapabilityResult.Success(ViewList(listed.refs.take(cap), truncated = cut))
    }

    private val favoritesViewCutLogged = java.util.concurrent.atomic.AtomicBoolean(false)

    // The files the account changed most recently, newest first, as the server orders them. A reference is the uuid of the real
    // file. A gateway without the route answers unsupported; any other failure throws, as every listing does.
    private suspend fun listRecents(limit: Int): CapabilityResult<List<ViewRef>> {
        val uuids =
            api.listRecentFiles(limit)
                ?: return CapabilityResult.Unsupported(Capability.View(ViewKind.RECENTS), "The gateway has no recents route")
        return CapabilityResult.Success(uuids.take(limit.coerceAtLeast(1)).map { ViewRef(it, isFolder = false) })
    }

    /**
     * UD-364: per-plan upload cap from `GET /files/limits` (`maxUploadFileSize`).
     *
     * Caches the first successful result for the lifetime of this provider
     * instance. On fetch failure (network, 401, malformed body) returns null
     * and leaves the cache unset so the next call retries — never caches
     * the failure. The non-suspend SPI requires `runBlocking`; the call is
     * O(one HTTP round-trip) and only runs once per provider lifetime.
     */
    override fun maxFileSizeBytes(): Long? {
        if (fileLimitsResolved) return fileLimitsCached
        return synchronized(limitsLock) {
            if (fileLimitsResolved) return@synchronized fileLimitsCached
            try {
                val limits = runBlocking { api.getFileLimits() }
                fileLimitsCached = limits.maxUploadFileSize
                fileLimitsResolved = true
                fileLimitsCached
            } catch (e: Exception) {
                log.warn("GET /files/limits failed; maxFileSizeBytes()=null, will retry next call", e)
                null
            }
        }
    }

    override suspend fun authenticate() {
        authService.initialize()
        if (!authService.isAuthenticated || authService.isJwtExpired()) {
            authService.authenticateInteractive()
        }
        // Bring up the notifications WS now that we have a JWT, if anyone listens.
        // Failures are logged inside NotificationsClient; the periodic poll is the
        // safety net so a WS that won't connect mustn't fail the authenticate path.
        if (remoteHintsWanted) ensureNotificationsClient()
    }

    // archive#1240: one GET of the usage figure with the stored session; never the console prompt of authenticate().
    override suspend fun checkCredentials(): CredentialProof {
        if (!authService.isAuthenticated) authService.initialize()
        if (!authService.isAuthenticated) throw NotSignedInException("no Internxt credentials are stored for this profile")
        api.checkSession()
        val account = AccountName.read(config.tokenPath) ?: authService.currentCredentials?.email?.trim()?.takeIf { it.isNotEmpty() }
        return CredentialProof(account)
    }

    /**
     * The credentials a sign-in through the daemon stored, read in place; never the console prompt of [authenticate].
     * The notifications socket was opened with the credentials that were replaced, so it is opened again with the new
     * ones (or stays closed when nothing is stored).
     */
    override suspend fun reloadCredentials() {
        authService.initialize()
        notificationsStartLock.withLock {
            notificationsClient?.disconnect()
            notificationsClient = null
        }
        if (authService.isAuthenticated && remoteHintsWanted) ensureNotificationsClient()
    }

    // archive#1186: the daemon's own instance after a sign-out; the stored credentials and staged uploads are already gone.
    override suspend fun forgetCredentials() {
        authService.forget()
        clearCachedSeed()
        notificationsStartLock.withLock {
            notificationsClient?.disconnect()
            notificationsClient = null
        }
    }

    override suspend fun logout() {
        notificationsClient?.disconnect()
        notificationsClient = null
        clearCachedSeed()
        // archive#1054: the staged ciphertext of the account's files and their resume state do not outlive the sign-in.
        withContext(Dispatchers.IO) { tombstoneStore.discardAll() }
        authService.logout()
    }

    override fun attachRequestMetrics(sink: org.krost.unidrive.http.RequestMetricsSink?) = api.attachRequestMetrics(sink)

    override fun close() {
        notificationsClient?.disconnect()
        notificationsClient = null
        clearCachedSeed()
        authService.close()
        api.close()
    }

    private class CachedSeed(
        val mnemonic: String,
        val seed: ByteArray,
    )

    /**
     * archive#730: the BIP39 seed for [mnemonic], derived at most once per mnemonic for the life of
     * this provider. Every caller gets a fresh copy of the held bytes, and every read or write
     * of those bytes happens under [seedLock] — a transfer that received a seed before a
     * credentials change or a close can therefore neither read zeroed key material nor corrupt
     * the cache, and a burst of transfers on a cold cache still derives once.
     */
    private fun seedFor(mnemonic: String): ByteArray =
        synchronized(seedLock) {
            val current = cachedSeed
            if (current != null && current.mnemonic == mnemonic) {
                current.seed.copyOf()
            } else {
                val fresh = crypto.mnemonicToSeed(mnemonic)
                current?.seed?.fill(0)
                cachedSeed = CachedSeed(mnemonic, fresh)
                fresh.copyOf()
            }
        }

    private fun clearCachedSeed() {
        synchronized(seedLock) {
            cachedSeed?.seed?.fill(0)
            cachedSeed = null
        }
    }

    override fun onRemoteChangeHint(callback: () -> Unit) {
        remoteChangeCallback = callback
        remoteHintsWanted = true
        // Registered after authenticate(): start now. Before it: authenticate() starts it.
        if (authService.isAuthenticated) {
            runBlocking { ensureNotificationsClient() }
        }
    }

    private suspend fun ensureNotificationsClient() {
        if (!config.notificationsEnabled) {
            if (!notificationsOffLogged) {
                notificationsOffLogged = true
                log.info("Internxt notifications disabled by INTERNXT_NOTIFICATIONS; sync polls only")
            }
            return
        }
        notificationsStartLock.withLock { startNotificationsClientLocked() }
    }

    private suspend fun startNotificationsClientLocked() {
        if (notificationsClient != null) return
        val creds =
            try {
                authService.getValidCredentials()
            } catch (e: Exception) {
                log.debug("ensureNotificationsClient: no credentials yet ({}), deferring", e.message)
                return
            }
        val client =
            NotificationsClient(
                notificationsUrl = config.notificationsUrl,
                tokenSupplier = { forceRefresh -> authService.getValidCredentials(forceRefresh).jwt },
                onRemoteChange = { remoteChangeCallback() },
            )
        notificationsClient = client
        client.connect(creds.jwt)
    }

    override suspend fun listChildren(path: String): List<CloudItem> {
        val folderUuid = resolveFolder(path)
        val content = api.getFolderContents(folderUuid)
        val liveContent = filterLiveContent(content)
        return liveContent.children.map { it.toCloudItem(path) } + liveContent.files.map { it.toCloudItem(path) }
    }

    override suspend fun getMetadata(path: String): CloudItem {
        val segments = pathSegments(path)
        if (segments.isEmpty()) {
            val creds = authService.getValidCredentials()
            return CloudItem(creds.rootFolderId, "", "/", 0, true, null, null, null, null)
        }
        val parentPath = "/" + segments.dropLast(1).joinToString("/")
        val name = segments.last()
        val parentUuid = resolveFolder(parentPath)
        val content = api.getFolderContents(parentUuid)

        // UD-317: sanitise child name so entries with stray `\n` still match a clean `name` arg.
        // archive#402: fail closed on ambiguity — with two live same-named matches, `find` picks
        // whichever the listing emitted first, and a delete/move resolved through here can
        // hit the wrong twin. Throw instead; only a single exact match resolves.
        val folders = content.children.filter { it.isLive() && sanitizeName(it.plainName ?: it.name ?: "") == name }
        if (folders.size > 1) throwAmbiguous(folders.size, name, parentPath)
        val folder = folders.firstOrNull()
        if (folder != null) return folder.toCloudItem(parentPath)

        // A file is found by its full name (base + type) only. An extension-stripped base-name
        // fallback let `Makefile` resolve to the sibling `Makefile.am` depending on listing order
        // (archive#294), and with no exact hit at all it still let `README` resolve to `README.md` (archive#1038):
        // the path-based consumers (delete, move, the mount's ghost probe) then trashed or renamed
        // the wrong file. A name the folder does not hold is not found.
        val namedFiles = liveFilesForName(content.files, name)
        if (namedFiles.size > 1) throwAmbiguous(namedFiles.size, name, parentPath)
        val namedFile = namedFiles.firstOrNull()
        if (namedFile != null) return namedFile.toCloudItem(parentPath)

        throw ProviderException("Item not found: $path")
    }

    // Live rows whose full name is exactly [name]. Empty when nothing live matches. More than one
    // entry is ambiguous: callers must not pick.
    private fun liveFilesForName(
        files: List<InternxtFile>,
        name: String,
    ): List<InternxtFile> = files.filter { file -> file.isLive() && fileMatchesFullName(file, name) }

    // archive#294 name logic shared by getMetadata, the 409 handlers and findFileByName:
    // exact full-name (base + type recomposition) match.
    private fun fileMatchesFullName(
        file: InternxtFile,
        name: String,
    ): Boolean {
        val baseName = sanitizeName(file.plainName ?: file.name ?: "")
        val cleanType = file.type?.let { sanitizeName(it) }
        val fullName = if (!cleanType.isNullOrEmpty() && !baseName.endsWith(".$cleanType")) "$baseName.$cleanType" else baseName
        return fullName == name
    }

    private fun throwAmbiguous(
        count: Int,
        name: String,
        parentPath: String,
    ): Nothing = throw ProviderException(
        "ambiguous: $count items named '$name' in $parentPath resolve by path; " +
            "refusing to pick one (delete/move by remote id instead — see the wrong-twin issue #402)",
    )

    // WB-3 (archive#87): the delete of a never-uploaded file removes the staged encrypted copy and its
    // tombstone sidecar — the user deleted the content, so the resume state must not outlive it.
    override suspend fun discardStagedUpload(localPath: String) {
        withContext(Dispatchers.IO) {
            tombstoneStore.discard(UploadTombstoneStore.pathHash(localPath))
        }
    }

    override suspend fun downloadById(
        remoteId: String,
        remotePath: String,
        destination: Path,
    ): Long = downloadWithFileUuid(remoteId, remotePath, destination).bytesWritten

    override suspend fun downloadByIdWithHash(
        remoteId: String,
        remotePath: String,
        destination: Path,
        targetMtimeMillis: Long?,
    ): DownloadReceipt = downloadWithFileUuid(remoteId, remotePath, destination, targetMtimeMillis)

    override suspend fun download(
        remotePath: String,
        destination: Path,
    ): Long {
        val metadata = getMetadata(remotePath)
        if (metadata.isFolder) throw ProviderException("Cannot download a folder: $remotePath")
        return downloadWithFileUuid(metadata.id, remotePath, destination).bytesWritten
    }

    override suspend fun downloadWithHash(
        remotePath: String,
        destination: Path,
        targetMtimeMillis: Long?,
    ): DownloadReceipt {
        val metadata = getMetadata(remotePath)
        if (metadata.isFolder) throw ProviderException("Cannot download a folder: $remotePath")
        return downloadWithFileUuid(metadata.id, remotePath, destination, targetMtimeMillis)
    }

    @Suppress("ThrowsCount") // The baseline entry predates the streamed receipt parameters and return type.
    private suspend fun downloadWithFileUuid(
        fileUuid: String,
        remotePath: String,
        destination: Path,
        targetMtimeMillis: Long? = null,
    ): DownloadReceipt {
        val fileMeta =
            try {
                api.getFileMeta(fileUuid)
            } catch (e: InternxtApiException) {
                // The 404-bucket-entry-not-found shape can also surface from
                // getFileMeta if the file metadata has been hard-deleted
                // upstream. Classify identically to the bridge-side 404 so
                // the engine quarantines the row instead of retrying forever.
                if (isBucketEntryNotFound(e)) throw permanentDownloadFailure(remotePath, e)
                throw e
            }
        // archive#485: an empty file is a drive entry with size 0 and no fileId (no bucket entry exists for it): there is nothing
        // to fetch or decrypt. A size of 0 WITH a fileId is the older "size unknown" shape and still goes to the bridge.
        // A null size is unknown, not empty: with no fileId either there is nothing to read, so it fails below.
        if (fileMeta.fileId == null && fileMeta.size?.toLongOrNull() == 0L) {
            withContext(Dispatchers.IO) {
                destination.parent?.let { Files.createDirectories(it) }
                Files.newOutputStream(
                    destination,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE,
                ).close()
                targetMtimeMillis?.let {
                    Files.setLastModifiedTime(destination, java.nio.file.attribute.FileTime.fromMillis(it))
                }
            }
            val sha256 = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest())
            return DownloadReceipt(
                bytesWritten = 0L,
                plaintextSha256 = sha256,
                stagedSize = Files.size(destination),
                stagedMtimeMillis = Files.getLastModifiedTime(destination).toMillis(),
                mtimeAppliedBeforePlacement = false,
            ).naming(fileMeta)
        }
        val bucket = fileMeta.bucket ?: throw ProviderException("File has no bucket: $remotePath")
        val fileId = fileMeta.fileId ?: throw ProviderException("File has no fileId: $remotePath")

        // Fail-fast guard on unsupported encryptVersion. Server stores the
        // column as a free string with no enum constraint; the public
        // Internxt SDK / drive-desktop / drive-web all use AES-256-CTR
        // unconditionally and only define the `03-aes` constant. A null
        // encryptVersion is treated as `03-aes` (the field is absent on
        // very-old records that predate the column). Anything else throws
        // rather than silently AES-decrypting to garbage bytes — see
        // the investigation note attached to the closed encryptVersion
        // legacy-support entry for the search trail.
        val version = fileMeta.encryptVersion ?: "03-aes"
        if (version != "03-aes") {
            throw ProviderException(
                "Unsupported encryptVersion '$version' for $remotePath. " +
                    "This unidrive build only decrypts files written with the standard '03-aes' format. " +
                    "If you have legacy files that need to be read, file an issue with this version string.",
            )
        }

        // Retry wraps URL re-resolution + cipher reconstruction per attempt:
        // OVH presigned URLs expire (~15 min) so a stale URL would 403 on
        // retry, and javax.crypto.Cipher is single-use after doFinal(). The
        // re-derived iv / fileKey are deterministic given the server-stored
        // indexHex, so a fresh attempt produces a byte-identical cipher state.
        // TRUNCATE_EXISTING on the destination open mode (inside
        // downloadFileStreaming) discards any partial prior attempt's bytes.
        // The declared plaintext size rides along so the streaming call can
        // refuse a short stored object before reading a byte (archive#536); a null/0
        // size stays "unknown" and only the post-download guard applies.
        val expected = fileMeta.size?.toLongOrNull()
        val downloaded =
            try {
                retryShardCommit {
                    val bridgeInfo =
                        try {
                            api.getBridgeFileInfo(bucket, fileId)
                        } catch (e: InternxtApiException) {
                            // The drive metadata resolved above, so a bridge 404 means the
                            // listed item has no stored encrypted object. Quarantine it as
                            // distinct from a missing drive item; the message also survives
                            // through hydration status for clients to explain the failure.
                            if (isBucketEntryNotFound(e)) throw remoteObjectMissingFailure(e)
                            throw e
                        }
                    // archive#333 review: hexToBytes now fails loud on a malformed index. A bridge entry
                    // whose index is not hex can never decrypt, so quarantine it with a clear
                    // reason instead of letting the engine retry the same failure on every poll.
                    val indexBytes =
                        try {
                            InternxtCrypto.hexToBytes(bridgeInfo.index)
                        } catch (e: IllegalArgumentException) {
                            throw UnsupportedRemoteObjectDownloadException(
                                "Internxt bridge info for $remotePath carries a malformed encryption index: ${e.message}",
                                reason = "its encryption index is malformed",
                                cause = e,
                            )
                        }
                    val iv = indexBytes.copyOfRange(0, 16)

                    val creds = authService.getValidCredentials()
                    val seed = seedFor(creds.mnemonic)
                    val bucketKey = crypto.deriveBucketKey(seed, bucket)
                    val fileKey = crypto.deriveFileKey(bucketKey, indexBytes)

                    // archive#335: taking the first usable shard of a multi-shard object decrypted a
                    // truncated file whose length mismatch then surfaced as a RETRIABLE error —
                    // the engine retried forever instead of quarantining. Fail permanently (a
                    // clear quarantine reason) before any download request is made. One shard
                    // keeps working exactly as before; zero usable shards keeps today's error.
                    val usableShards = bridgeInfo.shards.filter { it.url.isNotBlank() }
                    if (usableShards.size > 1) {
                        throw UnsupportedRemoteObjectDownloadException(
                            "multi-shard Internxt files are not supported (${usableShards.size} shards): $remotePath",
                            reason = "it is stored in several parts, which this client cannot read",
                        )
                    }
                    val downloadUrl =
                        usableShards.firstOrNull()?.url
                            ?: throw ProviderException("No download URL in bridge info for $remotePath")

                    // archive#940: the recorded hash is checked in the shape the network stored it; a shape this
                    // client cannot check is read without the ciphertext check and says so (see below).
                    val checkable = shardHashCheck(fileId, remotePath, usableShards.first().hash)
                    val cipher = crypto.createContentDecryptCipher(fileKey, iv)
                    refusalLogged(fileId, remotePath, checkable) {
                        api.downloadFileStreaming(
                            downloadUrl,
                            cipher,
                            destination,
                            expectedSize = expected,
                            expectedShardHash = checkable,
                            targetMtimeMillis = targetMtimeMillis,
                        )
                    }
                }
            } catch (e: RemoteIncompleteDownloadException) {
                // The stored object cannot produce a whole file; say so with the
                // path before the typed failure quarantines the row (archive#536). DEBUG here: the
                // caller owns the WARN, once per object (the mount's MountEngine; the sync
                // engine's quarantine line), because this provider does not know whether it
                // is the first refusal of the object.
                log.debug("download of {} refused: {}", remotePath, e.message)
                throw e
            }
        // Completeness guard (a backstop): downloadFileStreaming already refuses a stream of the
        // wrong length while it is staged, before it can replace the destination. This only
        // catches a decrypt whose plaintext length differs from the ciphertext's; it removes the
        // partial copy and fails as the typed permanent failure so the caller (hydration) never
        // trusts a partial file. Skipped when the declared size is unknown (0).
        if (expected != null && expected > 0L && downloaded.bytesWritten != expected) {
            withContext(Dispatchers.IO) {
                Files.deleteIfExists(destination)
            }
            log.debug(
                "download of {} is incomplete: got {} of {} bytes; removed the partial copy",
                remotePath,
                downloaded.bytesWritten,
                expected,
            )
            throw RemoteIncompleteDownloadException(
                "Incomplete download of $remotePath: got ${downloaded.bytesWritten} of $expected bytes",
                storedBytes = downloaded.bytesWritten,
                declaredBytes = expected,
            )
        }
        return DownloadReceipt(
            bytesWritten = downloaded.bytesWritten,
            plaintextSha256 = downloaded.plaintextSha256,
            stagedSize = downloaded.stagedSize,
            stagedMtimeMillis = downloaded.stagedMtimeMillis,
            mtimeAppliedBeforePlacement = downloaded.mtimeAppliedBeforePlacement,
        ).naming(fileMeta)
    }

    // gkrost/unidrive-cli#171: the version a download read is the one [fileMeta] named when the read began, whatever the file was
    // listed as: its stored object, and the write token and time a listing of the same metadata builds (the token a replace is
    // checked against).
    private fun DownloadReceipt.naming(fileMeta: InternxtFile): DownloadReceipt =
        copy(contentId = internxtContentId(fileMeta), itemHash = internxtVersionToken(fileMeta), itemModified = fileMeta.modificationInstant)

    // archive#940: WARN once per object and run; a pin retries, so later reports of the same object go to DEBUG.
    // Bounded: an account full of such objects must not grow this set without limit. Reaching the bound is said once,
    // so the objects after it are not silently missing from the WARNs. Size check and add run under one lock.
    private val shardHashReported = HashSet<String>()
    private var shardHashCapReported = false

    @Volatile internal var shardHashReportCap: Int = MAX_SHARD_HASH_REPORTS

    @Volatile internal var collisionCompareMaxBytes: Long = COLLISION_COMPARE_MAX_BYTES

    private fun firstShardHashReport(key: String): Boolean {
        var capReachedNow = false
        val first =
            synchronized(shardHashReported) {
                when {
                    key in shardHashReported -> false
                    shardHashReported.size < shardHashReportCap -> shardHashReported.add(key)
                    else -> {
                        capReachedNow = !shardHashCapReported
                        shardHashCapReported = true
                        false
                    }
                }
            }
        if (capReachedNow) {
            log.warn(
                "Internxt shard hash reports reached {} objects in this run; reports of further objects are logged at DEBUG only",
                shardHashReportCap,
            )
        }
        return first
    }

    // The check to run for [recorded]. A shape this client cannot check is refused: the content is AES-CTR with no MAC,
    // so the recorded hash is the only integrity there is, and whoever controls the shard host or the path to it could
    // flip chosen plaintext bits unseen. Only `allow_unverified_shard_hash = true` reads such an object (reported once,
    // then read unverified, the null returned).
    private fun shardHashCheck(
        fileId: String,
        remotePath: String,
        recorded: String,
    ): ShardHashExpectation.Checkable? =
        when (val parsed = ShardHashExpectation.parse(recorded)) {
            is ShardHashExpectation.Checkable -> parsed
            is ShardHashExpectation.Unrecognised -> {
                if (!config.allowUnverifiedShardHash) {
                    warnShardHashRefused(fileId, remotePath, parsed)
                    throw RemoteContentRefusedDownloadException(
                        "Internxt shard hash of $remotePath has a shape this client cannot check (${parsed.description}); " +
                            "refusing to read the content without the integrity check",
                        reason = "its shard hash cannot be checked",
                    )
                }
                warnShardHashUnverified(fileId, remotePath, parsed)
                null
            }
        }

    private fun warnShardHashRefused(
        fileId: String,
        remotePath: String,
        recorded: ShardHashExpectation.Unrecognised,
    ) {
        val message =
            "download of {} refused: its recorded shard hash has a shape this client cannot check ({}); " +
                "known shapes: 40 hex = RIPEMD-160(SHA-256), 64 hex = SHA-256. " +
                "Set allow_unverified_shard_hash = true in the profile to read such objects without the integrity check"
        if (firstShardHashReport("refused-shape:$fileId")) {
            log.warn(message, remotePath, recorded.description)
        } else {
            log.debug(message, remotePath, recorded.description)
        }
    }

    private fun warnShardHashUnverified(
        fileId: String,
        remotePath: String,
        recorded: ShardHashExpectation.Unrecognised,
    ) {
        val message =
            "Internxt shard hash of {} has a shape this client cannot check ({}); " +
                "its ciphertext is read WITHOUT the integrity check (allow_unverified_shard_hash is set; " +
                "known shapes: 40 hex = RIPEMD-160(SHA-256), 64 hex = SHA-256)"
        if (firstShardHashReport("unverified:$fileId")) {
            log.warn(message, remotePath, recorded.description)
        } else {
            log.debug(message, remotePath, recorded.description)
        }
    }

    // The mount logs nothing for a content refusal: record the shape the check ran with, then rethrow.
    private suspend fun <T> refusalLogged(
        fileId: String,
        remotePath: String,
        checked: ShardHashExpectation.Checkable?,
        download: suspend () -> T,
    ): T =
        try {
            download()
        } catch (e: RemoteContentRefusedDownloadException) {
            checked?.let { logShardHashRefusal(fileId, remotePath, it) }
            throw e
        }

    private fun logShardHashRefusal(
        fileId: String,
        remotePath: String,
        recorded: ShardHashExpectation.Checkable,
    ) {
        val message = "download of {} refused: recorded shard hash ({}) did not match the downloaded ciphertext ({}); content discarded"
        if (firstShardHashReport("refused:$fileId")) {
            log.warn(message, remotePath, recorded.description, recorded.algorithm)
        } else {
            log.debug(message, remotePath, recorded.description, recorded.algorithm)
        }
    }

    // Stages 5-7 of the upload pipeline (startUpload → PUT shard → finishUpload)
    // are wrapped in this helper so a transient OVH/Bridge failure doesn't
    // require re-encrypting the whole file. The retry is at provider scope so
    // indexBytes / iv / fileKey / hashHex / tempFile all stay stable across
    // attempts — that's the IV-pinning guarantee the BACKLOG design constraint
    // demands. Retries on IOException and 5xx; other 4xx are final, except a
    // reconciled MissingUploadsError may restart one exhausted Bridge session.
    // Transient failures get at most 3 attempts with simple exponential backoff.
    @Suppress("ThrowsCount") // Separate terminal exits preserve each retryable transport failure's original type.
    private suspend fun <T> retryShardCommit(
        onMissingUploadSession: suspend () -> Unit = {},
        op: suspend () -> T,
    ): T {
        val maxAttempts = 3
        var attempt = 0
        var restartedMissingSession = false
        while (true) {
            try {
                return op()
            } catch (e: MissingUploadSessionException) {
                if (restartedMissingSession) throw e
                restartedMissingSession = true
                onMissingUploadSession()
            } catch (e: InternxtApiException) {
                attempt++
                val transient = e.statusCode in setOf(429, 500, 502, 503, 504)
                if (!transient || attempt >= maxAttempts) throw e
                val backoffMs = e.retryAfterMs ?: (1_000L shl (attempt - 1))
                log.warn(
                    "shard-commit attempt {}/{} hit status {}, sleeping {}ms before retry",
                    attempt,
                    maxAttempts,
                    e.statusCode,
                    backoffMs,
                )
                kotlinx.coroutines.delay(backoffMs.coerceIn(500L, 60_000L))
            } catch (e: InternxtDownloadBudgetExhaustedException) {
                // archive#1065: a download that used up its whole time budget would use it up again from byte 0.
                log.warn("shard-commit attempt {}/{} not repeated: {}", attempt + 1, maxAttempts, e.message)
                throw e
            } catch (e: java.io.IOException) {
                attempt++
                if (attempt >= maxAttempts) throw e
                val backoffMs = (1_000L shl (attempt - 1)).coerceAtMost(60_000L)
                log.warn(
                    "shard-commit attempt {}/{} hit IOException ({}), sleeping {}ms before retry",
                    attempt,
                    maxAttempts,
                    e.message ?: e.javaClass.simpleName,
                    backoffMs,
                )
                kotlinx.coroutines.delay(backoffMs)
            }
        }
    }

    // Recognise the Internxt 404 shape that means "the bucket entry is
    // permanently gone, retry won't recover it". The bridge returns
    // `{"error":"Bucket entry … not found"}` with HTTP 404 when the upstream
    // record has been hard-deleted; the wrapper's `checkResponse` packs that
    // body into the exception's message. We match on (statusCode=404) AND
    // the literal substring so a different 404 path doesn't accidentally
    // quarantine a transient miss.
    private fun isBucketEntryNotFound(e: InternxtApiException): Boolean {
        if (e.statusCode != 404) return false
        val msg = e.message ?: return false
        return msg.contains("Bucket entry") && msg.contains("not found")
    }

    // Build the permanent-download-failure exception the engine catches.
    // The original [cause] is attached so the audit log retains the
    // underlying bridge / metadata diagnostic; the message wraps it in a
    // shape the operator can grep for.
    private fun permanentDownloadFailure(
        remotePath: String,
        cause: InternxtApiException,
    ): PermanentDownloadFailureException =
        PermanentDownloadFailureException(
            "Internxt bucket entry for $remotePath is permanently gone (404). " +
                "Quarantining the row; a fresh delta event clears it. Cause: ${cause.message}",
            cause = cause,
            requestId = cause.requestId,
        )

    private fun remoteObjectMissingFailure(cause: InternxtApiException): RemoteObjectMissingDownloadException =
        RemoteObjectMissingDownloadException(
            "remote_object_missing: Internxt still lists this file, but its encrypted bridge object is absent; " +
                "the local file is preserved and this download is quarantined until the remote item changes.",
            cause = cause,
            requestId = cause.requestId,
        )

    // Destructive-overwrite guard: rename the file at [existingRemoteId] to
    // `${plainName}.unidrive-prev-${utcStamp}` so a subsequent createFile
    // lands the new content as a fresh row without dropping the prior bytes.
    // On 409 (collision — two replaces inside one wall-clock second) walks
    // the counter suffix (`-2`, `-3`, ...) up to [MAX_KEEP_OVERWRITTEN_RETRIES].
    // Returns true on success, false on any failure (collision-exhausted,
    // 5xx, IO). On false the caller falls through to the destructive
    // `replaceFile` rather than stranding the local edit.
    private suspend fun tryKeepOverwrittenRename(
        existingRemoteId: String,
        plainName: String,
    ): Boolean {
        val now = java.time.Instant.now()
        val meta =
            try {
                api.getFileMeta(existingRemoteId)
            } catch (e: Exception) {
                log.warn(
                    "keep_overwritten: getFileMeta({}) failed ({}); falling through to destructive replace",
                    existingRemoteId,
                    e.message ?: e.javaClass.simpleName,
                )
                return false
            }
        val srcPlainName = meta.plainName ?: plainName
        val type = meta.type
        for (counter in 1..MAX_KEEP_OVERWRITTEN_RETRIES) {
            val archiveName = keepOverwrittenArchiveName(srcPlainName, now, counter, type)
            try {
                api.renameFile(existingRemoteId, plainName = archiveName, type = type)
                return true
            } catch (e: InternxtApiException) {
                if (e.statusCode == 409 && counter < MAX_KEEP_OVERWRITTEN_RETRIES) {
                    // Collision — another replace of the same file landed in
                    // the same wall-clock second. Bump the counter and retry.
                    continue
                }
                log.warn(
                    "keep_overwritten: rename of {} to '{}' failed ({}); falling through to destructive replace",
                    existingRemoteId,
                    archiveName,
                    "${e.statusCode} ${e.message ?: ""}",
                )
                return false
            } catch (e: Exception) {
                log.warn(
                    "keep_overwritten: rename of {} to '{}' failed ({}); falling through to destructive replace",
                    existingRemoteId,
                    archiveName,
                    e.message ?: e.javaClass.simpleName,
                )
                return false
            }
        }
        log.warn(
            "keep_overwritten: rename of {} hit {} consecutive 409 collisions; falling through to destructive replace",
            existingRemoteId,
            MAX_KEEP_OVERWRITTEN_RETRIES,
        )
        return false
    }

    // UD-304: multipart constants available in InternxtConfig.MULTIPART_*; not yet consumed (pending UD-307 multipart endpoint impl).
    // archive#464: compares the caller's write token with the item's current metadata. No token (a new file, or a caller that has none)
    // means no check. A different token means the cloud copy changed after the caller listed it.
    private suspend fun checkWriteToken(
        existingRemoteId: String?,
        ifMatchETag: String?,
        remotePath: String,
    ) {
        if (existingRemoteId == null || ifMatchETag == null) return
        compareWriteToken(api.getFileMeta(existingRemoteId), ifMatchETag, remotePath)
    }

    private fun compareWriteToken(
        current: InternxtFile,
        ifMatchETag: String?,
        remotePath: String,
    ) {
        if (ifMatchETag != null && internxtVersionToken(current) != ifMatchETag) {
            throw RemoteConflictException("Internxt item changed before replace: $remotePath")
        }
    }

    // The early check of a replace: the write token (see [checkWriteToken]) and the file's place. A replace keys on the
    // uuid alone, so a file of a tree of its own (see [outsideDriveFolders]) would be overwritten wherever the upload path
    // resolves; it is refused permanently, before anything is encrypted or sent. One meta read serves both checks; a file
    // the server no longer has is left to the replace, as before, unless a token asked for the read.
    private suspend fun checkReplaceTarget(
        existingRemoteId: String?,
        ifMatchETag: String?,
        remotePath: String,
    ) {
        if (existingRemoteId == null) return
        val current =
            try {
                api.getFileMeta(existingRemoteId)
            } catch (e: InternxtApiException) {
                if (ifMatchETag == null && e.statusCode in PERMANENT_ABSENCE_STATUSES) return
                throw e
            }
        compareWriteToken(current, ifMatchETag, remotePath)
        if (isOutsideDrive(current.folderUuid)) throw outsideDriveRefusal(remotePath)
    }

    private fun outsideDriveRefusal(remotePath: String) =
        org.krost.unidrive.PermanentUploadFailureException("Not part of the drive (a tree of its own, such as a device backup); not written to: $remotePath")

    // archive#578: the multipart policy surface — the effective parallelism for status, and the
    // daemon flag's override of it (validated at the flag's parse site; re-bounded here so
    // a programmatic caller cannot set nonsense either).
    override fun multipartUploadConcurrency(): Int? = effectiveUploadConcurrency

    override fun overrideUploadConcurrency(
        concurrency: Int,
    ): Boolean {
        require(concurrency in 1..InternxtConfig.MAX_UPLOAD_CONCURRENCY) {
            "upload concurrency $concurrency is out of range 1..${InternxtConfig.MAX_UPLOAD_CONCURRENCY}"
        }
        concurrencyOverride = concurrency
        return true
    }

    // archive#493: a request the server refuses as such (400 Bad Request, 413, 415, 422) is refused again with the same bytes; it
    // becomes a PermanentUploadFailureException, so the hydration queue does not run its retry ladder on it. Everything else
    // (5xx, 408, unrelated 409, 429, auth, network) keeps its type and stays retryable. gkrost/unidrive-cli#145: a 402 is the plan's
    // refusal (the free plan refuses empty files) and becomes the typed PlanLimitException, with the server's sentence.
    // MissingUploadsError with no landed entry after a successful reconcile is refused at commit time below.
    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        existingRemoteId: String?,
        ifMatchETag: String?,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem =
        try {
            uploadUnclassified(localPath, remotePath, existingRemoteId, ifMatchETag, onProgress)
        } catch (e: InternxtApiException) {
            if (e.statusCode == PLAN_LIMIT_STATUS) {
                throw org.krost.unidrive.PlanLimitException(
                    "Internxt refused the upload of $remotePath (${e.statusCode}): ${e.message}",
                    detail = serverSentence(e.message),
                    cause = e,
                    requestId = e.requestId,
                )
            }
            if (e.statusCode !in PERMANENT_UPLOAD_STATUS) throw e
            throw org.krost.unidrive.PermanentUploadFailureException(
                "Internxt refused the upload of $remotePath (${e.statusCode}): ${e.message}",
                cause = e,
                requestId = e.requestId,
            )
        }

    private suspend fun uploadUnclassified(
        localPath: Path,
        remotePath: String,
        existingRemoteId: String?,
        // archive#291: Internxt replace-in-place keys on the file UUID (existingRemoteId), not an eTag. It has no If-Match-style
        // conditional PUT; archive#464: the token is "<uuid>|<modificationTime>|<size>" ([internxtVersionToken]), compared with fresh
        // metadata before anything is sent and again right before the replace.
        ifMatchETag: String?,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem {
        val segments = pathSegments(remotePath)
        if (segments.isEmpty()) throw ProviderException("Cannot upload to root")

        // archive#464: a stale write token is refused before the file is encrypted and sent, not after: the late check below only
        // narrows the window, but discovering a conflict after a gigabyte went to the storage backend wastes it all.
        checkReplaceTarget(existingRemoteId, ifMatchETag, remotePath)

        val fileName = segments.last()
        val (plainName, ext) = splitLeaf(fileName)
        val parentPath = "/" + segments.dropLast(1).joinToString("/")

        // Inline GC sweep for stale tombstones (missing-local or > RESUME_TTL_MS
        // old). Bounded by the directory listing size — typical accounts see
        // single-digit entries. Runs once per upload() call regardless of which
        // path is being uploaded.
        withContext(Dispatchers.IO) { tombstoneStore.gc(InternxtConfig.RESUME_TTL_MS) }

        val pathHashStr = UploadTombstoneStore.pathHash(localPath.toAbsolutePath().toString())
        val tempFile = tombstoneStore.ciphertextPath(pathHashStr)

        // 1. Get file size + mtime without loading into memory. NoSuchFileException
        // here means the local file vanished between scan and upload; surface
        // after clearing any prior tombstone for the missing path. The content
        // fingerprint (archive#311) pins the head+tail windows of the same file so a
        // same-mtime-same-size rewrite can't ride a resume.
        val fileSize: Long
        val localMtime: java.time.Instant
        val contentFingerprint: String
        try {
            fileSize = withContext(Dispatchers.IO) { Files.size(localPath) }
            localMtime = withContext(Dispatchers.IO) { Files.getLastModifiedTime(localPath).toInstant() }
            contentFingerprint = withContext(Dispatchers.IO) { withLocalIoRetry { UploadTombstoneStore.contentFingerprint(localPath) } }
        } catch (e: java.nio.file.NoSuchFileException) {
            withContext(Dispatchers.IO) { tombstoneStore.discard(pathHashStr) }
            throw e
        }
        onProgress?.invoke(0L, fileSize)

        // archive#485: an empty file has no bucket entry. The drive API refuses a fileId for size 0 (and requires one above), on
        // create and on replace alike (drive-server's ValidateFileIdWithSize), so the bridge flow below must not run for it:
        // its createFile answered 400 and every retry's second finish a 409 MissingUploadsError.
        if (fileSize == 0L) {
            withContext(Dispatchers.IO) { tombstoneStore.discard(pathHashStr) } // nothing to resume for an empty file
            val empty = uploadEmpty(segments, plainName, ext, parentPath, existingRemoteId, ifMatchETag, remotePath, localMtime)
            onProgress?.invoke(0L, 0L)
            return empty
        }

        // Resume hook. A non-null tomb that still matches the call shape lets us
        // reuse the prior attempt's indexBytes / ciphertext / hash / shardUuid /
        // putUrl in whatever combinations are still safe. A mismatch (different
        // mtime/size/existingRemoteId, content fingerprint, expired
        // RESUME_TTL_MS) discards the sidecar + the .enc and forces a cold
        // restart with fresh indexBytes.
        //
        // archive#1054: the sidecar is keyed by the local path alone, and its ciphertext is encrypted under the key
        // of the account that wrote it; the account the call runs under must be the same one (the bucket is
        // the account's), or the file would finish into another account's bucket as ciphertext it cannot read.
        // The tombstone's folder and name are not compared: nothing resumed depends on them (the commit
        // below takes the call's own parent and name), so a rename in between loses no progress.
        val creds = authService.getValidCredentials()
        val bucket =
            creds.bucket.ifEmpty {
                throw ProviderException("No bucket in credentials — re-authenticate with 'unidrive auth --provider internxt'")
            }
        val existingTomb =
            withContext(Dispatchers.IO) { tombstoneStore.read(pathHashStr) }
        val localMtimeMillis = localMtime.toEpochMilli()
        val nowMillis = System.currentTimeMillis()
        // archive#311: mtime+size do not prove content identity — in-place writers
        // that restore mtime can swap bytes underneath a resume. The tombstone
        // pins a head+tail fingerprint at cold start; a mismatch (or a null,
        // i.e. hand-written tombstone) forces fresh indexBytes so the pinned
        // CTR keystream is never applied to different plaintext.
        val contentDrifted = existingTomb != null && existingTomb.contentFingerprintHex != contentFingerprint
        val resumeOk =
            existingTomb != null &&
                existingTomb.localPath == localPath.toAbsolutePath().toString() &&
                existingTomb.localMtimeMillis == localMtimeMillis &&
                existingTomb.localSize == fileSize &&
                existingTomb.existingRemoteId == existingRemoteId &&
                existingTomb.bucket == bucket &&
                !contentDrifted &&
                (nowMillis - existingTomb.startedAtMillis) <= InternxtConfig.RESUME_TTL_MS
        var tomb: UploadTombstone?
        if (existingTomb != null && !resumeOk) {
            log.info(
                "discarding stale upload tombstone for {} ({})",
                localPath,
                if (contentDrifted) {
                    "content-fingerprint drift"
                } else {
                    "mtime/size/existingRemoteId/bucket drift or TTL exceeded"
                },
            )
            withContext(Dispatchers.IO) { tombstoneStore.discard(pathHashStr) }
            tomb = null
        } else {
            tomb = existingTomb
        }

        // archive#311 backstop: beyond ENCRYPTING the prior ciphertext may already
        // sit on the storage backend (partial or complete PUT). If the pinned
        // ciphertext state can't be byte-validated on disk (.enc missing or
        // size-mismatched), the fall-through would re-encrypt *current* bytes
        // under the pinned keystream — a second use of the same (key, IV) if
        // content drifted past the fingerprint windows. Discard and
        // cold-restart with fresh indexBytes instead. At ENCRYPTING no shard
        // was ever started, so a re-encrypt there stays first-use.
        if (tomb != null && tomb.stage != UploadTombstone.Stage.ENCRYPTING) {
            val pinnedCiphertextVerifiable =
                tomb.encryptedSize != null &&
                    tomb.hashHex != null &&
                    withContext(Dispatchers.IO) { Files.exists(tempFile) } &&
                    withContext(Dispatchers.IO) { Files.size(tempFile) } == tomb.encryptedSize
            if (!pinnedCiphertextVerifiable) {
                log.info(
                    "discarding upload tombstone for {} (re-encrypt required at stage {} with unverifiable .enc — " +
                        "backend may hold prior ciphertext; rotating indexBytes)",
                    localPath,
                    tomb.stage,
                )
                withContext(Dispatchers.IO) { tombstoneStore.discard(pathHashStr) }
                tomb = null
            }
        }

        // archive#333 review: a tombstone whose index is not valid hex cannot be resumed (hexToBytes
        // now throws). Treat it like the other unusable tombstones: discard and cold-restart
        // with fresh indexBytes, instead of failing this upload on every retry.
        if (tomb != null && runCatching { InternxtCrypto.hexToBytes(tomb.indexBytesHex) }.isFailure) {
            log.warn("discarding upload tombstone for {} (malformed index bytes; rotating indexBytes)", localPath)
            withContext(Dispatchers.IO) { tombstoneStore.discard(pathHashStr) }
            tomb = null
        }

        // 2. indexBytes — RESUMED from the tombstone when present (the server-
        //    stored hex MUST match the bytes the ciphertext was encrypted under,
        //    or downloads decrypt to garbage). Fresh random only when starting
        //    cold or after a staleness discard.
        val indexBytes =
            tomb?.let { InternxtCrypto.hexToBytes(it.indexBytesHex) }
                ?: ByteArray(32).also { secureRandom.nextBytes(it) }
        val iv = indexBytes.copyOfRange(0, 16)
        val indexHex = HexFormat.of().formatHex(indexBytes)

        val parentUuid = resolveUploadParent(parentPath, remotePath)

        val seed = seedFor(creds.mnemonic)
        val bucketKey = crypto.deriveBucketKey(seed, bucket)
        val fileKey = crypto.deriveFileKey(bucketKey, indexBytes)

        // Tombstone-canonical startedAt: when resuming we carry the ORIGINAL
        // attempt's start instant through to commitWithRetry so the 409-reconcile
        // window floors below the original finishUpload (a resume that runs
        // hours later mustn't pin its window to "now" and miss the prior commit).
        val uploadStartedAtMillis = tomb?.startedAtMillis ?: nowMillis
        val uploadStartedAt = Instant.ofEpochMilli(uploadStartedAtMillis)

        // Persist the initial tombstone BEFORE we touch the ciphertext.
        // A kill mid-encrypt resumes with the same indexBytes and
        // re-encrypts to the same deterministic temp path.
        var currentTomb =
            tomb ?: UploadTombstone(
                localPath = localPath.toAbsolutePath().toString(),
                localMtimeMillis = localMtimeMillis,
                localSize = fileSize,
                bucket = bucket,
                folderUuid = parentUuid,
                plainName = plainName,
                ext = ext,
                indexBytesHex = indexHex,
                existingRemoteId = existingRemoteId,
                contentFingerprintHex = contentFingerprint,
                stage = UploadTombstone.Stage.ENCRYPTING,
                startedAtMillis = uploadStartedAtMillis,
                tombstoneWrittenAtMillis = nowMillis,
            )
        if (tomb == null) {
            withContext(Dispatchers.IO) { tombstoneStore.write(pathHashStr, currentTomb) }
        }

        // Encrypt to deterministic .enc temp file. Skipped on resume from
        // PUT_PENDING or later (the ciphertext + hash + size are already pinned
        // in the tombstone, and the .enc on disk must round-trip the recorded
        // encryptedSize — a tombstone that can't honour that was already
        // discarded by the archive#311 backstop above).
        val needsEncrypt =
            currentTomb.stage == UploadTombstone.Stage.ENCRYPTING ||
                currentTomb.encryptedSize == null ||
                currentTomb.hashHex == null ||
                !withContext(Dispatchers.IO) { Files.exists(tempFile) } ||
                withContext(Dispatchers.IO) { Files.size(tempFile) } != currentTomb.encryptedSize
        val encryptedSize: Long
        val hashHex: String
        if (needsEncrypt) {
            // Re-derive a fresh Cipher per encrypt pass (javax.crypto.Cipher is
            // single-use after doFinal). The fileKey/iv stay byte-identical to
            // whatever the tombstone pinned, which is the IV-pinning guarantee.
            val encCipher = crypto.createContentEncryptCipher(fileKey, iv)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            try {
                withContext(Dispatchers.IO) {
                    Files.createDirectories(tempFile.parent)
                    javax.crypto.CipherInputStream(withLocalIoRetry { Files.newInputStream(localPath) }, encCipher).use { cipherIn ->
                        Files.newOutputStream(tempFile).use { out ->
                            val buf = ByteArray(8192)
                            var n: Int
                            while (cipherIn.read(buf).also { n = it } != -1) {
                                out.write(buf, 0, n)
                                digest.update(buf, 0, n)
                            }
                        }
                    }
                }
                encryptedSize = withContext(Dispatchers.IO) { Files.size(tempFile) }
                hashHex = HexFormat.of().formatHex(digest.digest())
                // The cipher is a stream cipher: the ciphertext is as long as the bytes it read. The file was sized before this
                // pass; a source that changed length in between would be created with the old size and stored with another
                // length, and every download of it refused as incomplete. Nothing is sent: the next attempt reads it afresh.
                if (encryptedSize != fileSize) {
                    withContext(Dispatchers.IO) {
                        runCatching { Files.deleteIfExists(tempFile) }
                        tombstoneStore.discard(pathHashStr)
                    }
                    throw ProviderException(
                        "$remotePath changed while it was being read for upload ($fileSize bytes when sized, $encryptedSize read); will be retried",
                    )
                }
            } catch (e: java.nio.file.NoSuchFileException) {
                // Mid-upload delete — local file vanished after we sized it.
                withContext(Dispatchers.IO) { tombstoneStore.discard(pathHashStr) }
                throw e
            } catch (e: Exception) {
                // Keep the tombstone in place so the next attempt can resume
                // (the .enc may be partial — encryptedSize mismatch on the
                // resume path will trigger re-encrypt anyway).
                throw e
            }
            currentTomb = currentTomb.copy(
                stage = UploadTombstone.Stage.PUT_PENDING,
                encryptedSize = encryptedSize,
                hashHex = hashHex,
                tombstoneWrittenAtMillis = System.currentTimeMillis(),
            )
            withContext(Dispatchers.IO) { tombstoneStore.write(pathHashStr, currentTomb) }
        } else {
            encryptedSize = currentTomb.encryptedSize
            hashHex = currentTomb.hashHex
        }

        // Temp file deletion brackets stages 5-7 (plus the retry loop wrapping
        // them); on success it's removed by tombstoneStore.discard at the
        // bottom of the method. The finally below only fires on a thrown
        // exception that wasn't already handled by the tombstone discard path
        // — in that case we leave the .enc alone so the next upload() call
        // can resume cleanly.
        // archive#578: a file above the upload cutoff goes out in parts, up to
        // uploadConcurrency at a time, each part its own presigned URL from the
        // one multipart session. A single-part session already in flight (the
        // tombstone carries its shardUuid) finishes as the single PUT it started
        // as — the cutoff only gates NEW sessions.
        //
        // Both branches are local: they read and persist [currentTomb] exactly
        // as the inline code did, so the resume rules stay in one place.
        suspend fun singlePartUploadEntry(): org.krost.unidrive.internxt.model.BucketEntry =
            retryShardCommit(
                onMissingUploadSession = {
                    // A 409 plus a successful Drive listing with no matching entry proves this
                    // Bridge session cannot be finished. Preserve the pinned ciphertext/index,
                    // but forget the shard session so the one bounded recovery starts and PUTs a
                    // fresh shard instead of replaying finish against the missing UUID.
                    currentTomb =
                        currentTomb.copy(
                            stage = UploadTombstone.Stage.PUT_PENDING,
                            shardUuid = null,
                            bridgePutUrl = null,
                            tombstoneWrittenAtMillis = System.currentTimeMillis(),
                        )
                    withContext(Dispatchers.IO) { tombstoneStore.write(pathHashStr, currentTomb) }
                },
            ) {
                // Start upload — fresh PUT URL when the cached one is null
                // (cold start), absent, or older than URL_TTL_MS (the OVH
                // presigned-URL safety margin). The ciphertext + indexBytes +
                // hashHex stay pinned across the URL refresh — only the
                // server-allocated shardUuid changes, and commitWithRetry's
                // reconcile keys on plainName+size+window, not on shardUuid.
                val urlAgeMs = System.currentTimeMillis() - currentTomb.tombstoneWrittenAtMillis
                val urlExpired = urlAgeMs > InternxtConfig.URL_TTL_MS
                val shardUuid: String
                val putUrl: String
                if (currentTomb.stage == UploadTombstone.Stage.PUT_DONE ||
                    currentTomb.stage == UploadTombstone.Stage.FINISH_DONE
                ) {
                    // PUT already landed on a prior attempt — we have a recorded
                    // shardUuid that finishUpload still needs. Don't re-PUT.
                    shardUuid = currentTomb.shardUuid!!
                    putUrl = currentTomb.bridgePutUrl ?: ""
                } else if (currentTomb.shardUuid == null || currentTomb.bridgePutUrl == null || urlExpired) {
                    val startResp = api.startUpload(bucket, encryptedSize)
                    val descriptor =
                        startResp.uploads.firstOrNull()
                            ?: throw ProviderException("No upload URL returned for $remotePath")
                    putUrl =
                        descriptor.url
                            ?: throw ProviderException("Upload descriptor has no URL for $remotePath")
                    shardUuid = descriptor.uuid
                    currentTomb = currentTomb.copy(
                        shardUuid = shardUuid,
                        bridgePutUrl = putUrl,
                        stage = UploadTombstone.Stage.PUT_PENDING,
                        tombstoneWrittenAtMillis = System.currentTimeMillis(),
                    )
                    withContext(Dispatchers.IO) { tombstoneStore.write(pathHashStr, currentTomb) }
                } else {
                    shardUuid = currentTomb.shardUuid!!
                    putUrl = currentTomb.bridgePutUrl!!
                }

                // PUT encrypted shard from temp file. Skipped on PUT_DONE
                // resume (idempotent at the S3 layer but a no-op is cheaper).
                if (currentTomb.stage == UploadTombstone.Stage.ENCRYPTING ||
                    currentTomb.stage == UploadTombstone.Stage.PUT_PENDING
                ) {
                    // archive#1197: byte progress while the body streams, in plaintext bytes (the ciphertext can be a few
                    // bytes longer). Held below the size until the PUT has returned; the final report follows it.
                    api.putEncryptedShardFromFile(putUrl, tempFile, encryptedSize, onBytesSent = { sent ->
                        val scaled = if (encryptedSize > 0) (sent.toDouble() / encryptedSize * fileSize).toLong() else 0L
                        onProgress?.invoke(scaled.coerceIn(0L, fileSize - 1), fileSize)
                    })
                    onProgress?.invoke(fileSize, fileSize)
                    currentTomb = currentTomb.copy(
                        stage = UploadTombstone.Stage.PUT_DONE,
                        tombstoneWrittenAtMillis = System.currentTimeMillis(),
                    )
                    withContext(Dispatchers.IO) { tombstoneStore.write(pathHashStr, currentTomb) }
                }

                // Finish upload (with 409-reconcile idempotency) → get
                // bridge fileId. Skipped on FINISH_DONE resume (the
                // tombstone-carried bucket entry id is already in hand).
                val entry =
                    if (currentTomb.stage == UploadTombstone.Stage.FINISH_DONE && currentTomb.bridgeFileId != null) {
                        // archive#1203: finishUpload already answered; its file id is on the tombstone (in memory even when writing it failed).
                        // Finishing the exhausted session again answers 409, and with no Drive entry yet there is nothing to reconcile.
                        org.krost.unidrive.internxt.model.BucketEntry(
                            id = currentTomb.bridgeFileId!!,
                            index = indexHex,
                            bucket = bucket,
                        )
                    } else if (currentTomb.stage == UploadTombstone.Stage.FINISH_DONE) {
                        // We landed here from a crash between finishUpload-OK
                        // and the createFile/replaceFile call, in a sidecar that carries no file id (written before archive#1203). Call finishUpload
                        // again — it'll either succeed (idempotent
                        // at the server's reconcile path) or surface a 409 that
                        // commitWithRetry resolves via the listing reconcile.
                        commitWithRetry(
                            api = api,
                            bucket = bucket,
                            folderUuid = parentUuid,
                            plainName = plainName,
                            ext = ext,
                            fileSize = fileSize,
                            encryptedSize = encryptedSize,
                            indexHex = indexHex,
                            hashHex = hashHex,
                            shardUuid = shardUuid,
                            startedAt = uploadStartedAt,
                        )
                    } else {
                        val e =
                            commitWithRetry(
                                api = api,
                                bucket = bucket,
                                folderUuid = parentUuid,
                                plainName = plainName,
                                ext = ext,
                                fileSize = fileSize,
                                encryptedSize = encryptedSize,
                                indexHex = indexHex,
                                hashHex = hashHex,
                                shardUuid = shardUuid,
                                startedAt = uploadStartedAt,
                            )
                        currentTomb = currentTomb.copy(
                            stage = UploadTombstone.Stage.FINISH_DONE,
                            bridgeFileId = e.id,
                            tombstoneWrittenAtMillis = System.currentTimeMillis(),
                        )
                        // archive#1203: the commit is done and its file id is in hand; the sidecar only serves a crash resume. A write that
                        // fails here (on Windows: access denied on the rename) must not throw: retryShardCommit would take it for a failed
                        // commit and run the whole Bridge session again, and the second finish ends the file as refused.
                        try {
                            withContext(Dispatchers.IO) { tombstoneStore.write(pathHashStr, currentTomb) }
                        } catch (ioe: java.io.IOException) {
                            log.warn(
                                "could not record the finished commit of {} ({}); continuing, the upload does not depend on it",
                                remotePath,
                                ioe.message ?: ioe.javaClass.simpleName,
                            )
                        }
                        e
                    }
                entry
            }

        // archive#578: part plan math — the configured chunk size grows if it would exceed
        // the bridge's 10,000-part ceiling; parts never drop below the configured floor.
        fun ceilDivLong(
            a: Long,
            b: Long,
        ): Long = (a + b - 1) / b

        fun multipartChunkBytes(
            encryptedSize: Long,
            configuredChunkBytes: Long,
        ): Long {
            val floorForPartCap = ceilDivLong(encryptedSize, InternxtConfig.MAX_MULTIPART_PARTS.toLong())
            return maxOf(configuredChunkBytes, floorForPartCap)
        }

        fun partSize(
            session: MultipartResume,
            index: Int,
            encryptedSize: Long,
        ): Long = minOf(session.chunkSize, encryptedSize - index.toLong() * session.chunkSize)

        suspend fun multipartUploadEntry(
            remotePath: String,
            bucket: String,
            parentUuid: String,
            plainName: String,
            ext: String?,
            fileSize: Long,
            encryptedSize: Long,
            indexHex: String,
            hashHex: String,
            startedAt: Instant,
        ): org.krost.unidrive.internxt.model.BucketEntry {
            // Starts a fresh multipart session and persists it. Used at entry, after the
            // presigned-URL TTL drops a session, and after a part PUT meets a dead-URL 403.
            suspend fun startFreshSession(): MultipartResume {
                val chunkBytes = multipartChunkBytes(encryptedSize, config.uploadChunkMb.toLong() * 1_024L * 1_024L)
                val numParts = ceilDivLong(encryptedSize, chunkBytes).toInt()
                val descriptor = api.startMultipartUpload(bucket, encryptedSize, numParts)
                val fresh =
                    MultipartResume(
                        chunkSize = chunkBytes,
                        numParts = numParts,
                        uploadId = descriptor.uploadId!!,
                        sessionUuid = descriptor.uuid,
                        urls = descriptor.urls,
                        issuedAtMillis = System.currentTimeMillis(),
                    )
                log.info(
                    "multipart upload of {}: {} part(s) of {} bytes, {} at a time",
                    remotePath,
                    numParts,
                    chunkBytes,
                    effectiveUploadConcurrency,
                )
                currentTomb =
                    currentTomb.copy(
                        multipart = fresh,
                        stage = UploadTombstone.Stage.PUT_PENDING,
                        tombstoneWrittenAtMillis = System.currentTimeMillis(),
                    )
                withContext(Dispatchers.IO) { tombstoneStore.write(pathHashStr, currentTomb) }
                return fresh
            }

            // Expired session: the presigned part URLs are dead and a fresh start
            // mints a new upload id, so the completed-part records cannot carry
            // over — drop them and re-PUT every part from the staged ciphertext
            // (never a re-encrypt; the .enc, hash and index stay pinned). The age
            // runs from the session's issue time, not from the last sidecar write:
            // every landed part rewrites the sidecar, so the write time keeps
            // calling a dead session fresh.
            //
            // A sidecar whose plan does not describe the ciphertext (a truncated or
            // hand-edited session) is discarded the same way instead of resuming
            // into an index the URL list cannot serve.
            fun sessionUsable(mp: MultipartResume): Boolean =
                mp.chunkSize > 0 &&
                    mp.numParts in 1..InternxtConfig.MAX_MULTIPART_PARTS &&
                    mp.urls.size == mp.numParts &&
                    ceilDivLong(encryptedSize, mp.chunkSize).toInt() == mp.numParts &&
                    mp.parts.all { it.index in 0 until mp.numParts }
            val persisted = currentTomb.multipart
            var session =
                when {
                    persisted == null -> null
                    !sessionUsable(persisted) -> {
                        log.warn(
                            "discarding unusable multipart session for {} (the sidecar's plan does not match the ciphertext); starting a fresh one",
                            remotePath,
                        )
                        null
                    }
                    System.currentTimeMillis() - persisted.issuedAtMillis > InternxtConfig.URL_TTL_MS -> {
                        log.info(
                            "discarding expired multipart session for {} ({} of {} part(s) were done; re-putting from the staged ciphertext)",
                            remotePath,
                            persisted.parts.size,
                            persisted.numParts,
                        )
                        null
                    }
                    else -> persisted
                } ?: startFreshSession()

            // A finished session resumes without re-finishing: the bucket entry id
            // was pinned the moment the finish call answered.
            if (currentTomb.stage == UploadTombstone.Stage.FINISH_DONE && session.bucketEntryId != null) {
                return org.krost.unidrive.internxt.model.BucketEntry(id = session.bucketEntryId, index = indexHex, bucket = bucket)
            }

            // Parts upload in parallel up to the concurrency cap, each with its own
            // retry ladder (retryShardCommit) and its own watchdog window — the same
            // per-request treatment the single PUT got. Completed parts are persisted
            // as they land, so a restart or reconnect re-PUTs only the missing ones.
            // The parts ride their own admission lane sized to the cap: the shared
            // bridge budget paces every bridge call and keeps one throttle memory, so
            // parts through it would share that with every other bridge call while a large
            // upload runs. A lane per parts pass also resets its throttle memory with the
            // session, which is where the parts live.
            // TooGenericExceptionCaught: the drain records whatever a part died of and rethrows it after the drain;
            // a CancellationException is rethrown at once. ThrowsCount: the failures of the ladder are throws.
            @Suppress("TooGenericExceptionCaught", "ThrowsCount")
            suspend fun putParts(current: MultipartResume): Map<Int, String> {
                val partBudget =
                    org.krost.unidrive.http.HttpRetryBudget(
                        maxConcurrency = effectiveUploadConcurrency,
                        minSpacingMs = 0,
                        stormSpacingMs = 0,
                    )
                val completed = java.util.concurrent.ConcurrentHashMap<Int, String>()
                for (part in current.parts) completed[part.index] = part.etag
                val tombMutex = kotlinx.coroutines.sync.Mutex()
                val partsSem = kotlinx.coroutines.sync.Semaphore(effectiveUploadConcurrency)
                // supervisorScope, not coroutineScope: a part that used its ladder up does not cancel the parts still
                // in flight — their PUTs are let finish (bounded by the per-part watchdog), so what the server already
                // has lands in the tombstone and the next attempt re-PUTs only what it must. No new part starts once
                // one failed. The first failure is rethrown below, after the drain.
                val firstFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
                kotlinx.coroutines.supervisorScope {
                    for (index in 0 until current.numParts) {
                        if (completed.containsKey(index) || firstFailure.get() != null) continue
                        val offset = index.toLong() * current.chunkSize
                        val size = partSize(current, index, encryptedSize)
                        launch {
                            partsSem.withPermit {
                                if (firstFailure.get() != null) return@withPermit
                                try {
                                    val etag =
                                        try {
                                            retryShardCommit {
                                                val put =
                                                    api.putEncryptedPartFromFile(
                                                        current.urls[index],
                                                        tempFile,
                                                        offset,
                                                        size,
                                                        partBudget,
                                                        onBytesSent = { sent ->
                                                            // The single-PUT convention (archive#1197), scaled from this
                                                            // part's window of the ciphertext to plaintext bytes:
                                                            // live values while the body streams, always held below
                                                            // the total; the one final report follows the last part.
                                                            val scaled = ((offset + sent).toDouble() / encryptedSize * fileSize).toLong()
                                                            onProgress?.invoke(scaled.coerceIn(0L, fileSize - 1), fileSize)
                                                        },
                                                    )
                                                if (put.isNullOrBlank()) {
                                                    throw InternxtApiException("Part ${index + 1} of $remotePath answered no ETag")
                                                }
                                                put
                                            }
                                        } catch (e: InternxtApiException) {
                                            // A part PUT carries no bridge auth — a 403 means the
                                            // presigned URL itself is refused, which is the session's
                                            // signature having expired (or the URL mangled). Retrying
                                            // the URL cannot help; a fresh session can.
                                            throw if (e.statusCode == 403) {
                                                PresignedUrlsDeadException("part ${index + 1} of $remotePath: ${e.message}", e)
                                            } else {
                                                e
                                            }
                                        }
                                    // Completed in NonCancellable: when the daemon stops an upload, this scope
                                    // cancels every part still in it — a part whose PUT landed but whose write was
                                    // still queued would then never reach the tombstone, and the next attempt would
                                    // re-put a part the server already has. A completed part is always persisted.
                                    withContext(NonCancellable) {
                                        tombMutex.withLock {
                                            completed[index] = etag
                                            val parts =
                                                completed.entries
                                                    .map { MultipartPartRecord(it.key, it.value) }
                                                    .sortedBy { it.index }
                                            currentTomb =
                                                currentTomb.copy(
                                                    multipart = current.copy(parts = parts),
                                                    tombstoneWrittenAtMillis = System.currentTimeMillis(),
                                                )
                                            withContext(Dispatchers.IO) { tombstoneStore.write(pathHashStr, currentTomb) }
                                        }
                                    }
                                } catch (e: CancellationException) {
                                    throw e   // the daemon took the upload down; nothing to drain
                                } catch (e: Throwable) {
                                    firstFailure.compareAndSet(null, e)
                                }
                            }
                        }
                    }
                }
                firstFailure.get()?.let { throw it }
                return completed
            }

            // A dead-URL 403 restarts the session once inside this attempt: the
            // retry ladder treats a 403 as final, so without this a long upload whose
            // URLs expired mid-flight would fail the whole cycle and wait for the
            // engine's next pass (and a fresh session drops every completed part —
            // the second 403 propagates instead of looping).
            var restartedForDeadUrls = false
            var completed: Map<Int, String>
            while (true) {
                try {
                    completed = putParts(session)
                    break
                } catch (e: PresignedUrlsDeadException) {
                    if (restartedForDeadUrls) throw e
                    restartedForDeadUrls = true
                    log.warn(
                        "a part of {} met a 403 on this session's presigned URLs ({}); starting a fresh session and re-putting every part",
                        remotePath,
                        e.message,
                    )
                    session = startFreshSession()
                }
            }

            // The finish call is never sent with an incomplete part list: every
            // part landed (or its failure propagated out of the retry ladder),
            // so the list here is provably whole.
            val finalParts =
                completed.entries.map { MultipartPart(it.key + 1, it.value) }.sortedBy { it.partNumber }
            if (finalParts.size != session.numParts) {
                throw ProviderException(
                    "refusing to finish the multipart upload of $remotePath: ${finalParts.size} of ${session.numParts} parts landed",
                )
            }
            onProgress?.invoke(fileSize, fileSize)
            val entry =
                commitWithRetry(
                    api = api,
                    bucket = bucket,
                    folderUuid = parentUuid,
                    plainName = plainName,
                    ext = ext,
                    fileSize = fileSize,
                    encryptedSize = encryptedSize,
                    indexHex = indexHex,
                    hashHex = hashHex,
                    shardUuid = session.sessionUuid,
                    startedAt = startedAt,
                ) {
                    api.finishMultipartUpload(
                        bucket = bucket,
                        indexHex = indexHex,
                        // archive#946, like the single-shard finish: the wire hash is RIPEMD-160(SHA-256(ciphertext)),
                        // derived from the pinned SHA-256 at the moment it is sent.
                        hash = ShardHashWire.forFinish(hashHex),
                        uploadId = session.uploadId,
                        sessionUuid = session.sessionUuid,
                        parts = finalParts,
                    )
                }
            currentTomb =
                currentTomb.copy(
                    stage = UploadTombstone.Stage.FINISH_DONE,
                    multipart = session.copy(bucketEntryId = entry.id),
                    tombstoneWrittenAtMillis = System.currentTimeMillis(),
                )
            withContext(Dispatchers.IO) { tombstoneStore.write(pathHashStr, currentTomb) }
            log.info(
                "multipart upload of {} finished: {} part(s), {} bytes encrypted",
                remotePath,
                session.numParts,
                encryptedSize,
            )
            return entry
        }

        val cutoffBytes = config.uploadCutoffMb.toLong() * 1_024L * 1_024L
        val useMultipart = currentTomb.multipart != null ||
            (currentTomb.shardUuid == null && fileSize > cutoffBytes)
        val bucketEntry =
            if (useMultipart) {
                retryShardCommit(
                    onMissingUploadSession = {
                        // A multipart finish-409 whose reconcile finds no landed entry means
                        // this session is exhausted server-side — its UploadId and every
                        // recorded part ETag are unusable, and replaying the finish would end
                        // the upload as refused. Drop the session (the staged .enc, hash and
                        // index stay pinned) so the one bounded recovery starts a fresh
                        // session and re-PUTs every part, exactly as the single-shard path
                        // forgets its shard session.
                        currentTomb =
                            currentTomb.copy(
                                stage = UploadTombstone.Stage.PUT_PENDING,
                                multipart = null,
                                tombstoneWrittenAtMillis = System.currentTimeMillis(),
                            )
                        withContext(Dispatchers.IO) { tombstoneStore.write(pathHashStr, currentTomb) }
                    },
                ) {
                    multipartUploadEntry(
                        remotePath = remotePath,
                        bucket = bucket,
                        parentUuid = parentUuid,
                        plainName = plainName,
                        ext = ext,
                        fileSize = fileSize,
                        encryptedSize = encryptedSize,
                        indexHex = indexHex,
                        hashHex = hashHex,
                        startedAt = uploadStartedAt,
                    )
                }
            } else {
                singlePartUploadEntry()
            }

        // UD-366: MODIFIED uploads route through PUT /files/{uuid} (replace-in-place);
        // NEW uploads POST /files (create). Defensive fallback below catches the 409 that
        // appears when the reconciler thought a path was new but the remote already has it.
        val finalItem: CloudItem
        // Destructive-overwrite guard: when `keepOverwritten` is on,
        // rename the prior cloud content to `${plainName}.unidrive-prev-${utcStamp}`
        // and create the new content as a fresh file instead of letting
        // `replaceFile` drop the prior bytes (Internxt has no server-side
        // versioning). Falls through to the destructive replace if the
        // rename can't be made unique after `MAX_KEEP_OVERWRITTEN_RETRIES`
        // collision attempts or the rename itself errors — better to land
        // the new content than to strand the local edit.
        val archivedPrior =
            if (existingRemoteId != null) {
                checkWriteToken(existingRemoteId, ifMatchETag, remotePath) // the narrow window between the early check and the replace
                config.keepOverwritten && tryKeepOverwrittenRename(existingRemoteId, plainName)
            } else {
                false
            }
        if (existingRemoteId != null && !archivedPrior) {
            val replaced =
                api
                    .replaceFile(
                        uuid = existingRemoteId,
                        size = fileSize,
                        fileId = bucketEntry.id,
                        modificationTime = localMtime,
                    ).afterReplace(localMtime)
            finalItem = replaced.toCloudItem(parentPath)
        } else {
            // A create, or the create that follows the archive rename of a replace: the same answers to a 409 and to a
            // lost response. A replace whose create had landed would otherwise fail with the prior content already
            // archived, and its retry would archive the archive.
            val encryptedName = crypto.encryptName(plainName, "${creds.mnemonic}-$parentUuid")
            val created =
                try {
                    api.createFile(
                        bucket = bucket,
                        folderUuid = parentUuid,
                        plainName = plainName,
                        encryptedName = encryptedName,
                        size = fileSize,
                        type = ext,
                        fileId = bucketEntry.id,
                        modificationTime = localMtime,
                    )
                } catch (e: InternxtApiException) {
                    // UD-366 defensive fallback: reconciler/DB drift can leave us POSTing a
                    // path that already exists on remote.  Safe overwrite requires PROVEN
                    // content identity; anything else keeps both copies.
                    if (e.statusCode != 409) throw e
                    log.warn(
                        "UD-366: 409 on POST /files for {} (prior file {}) — " +
                            "reconciler/DB drift or a create that had landed; inspecting remote for content-identity check",
                        remotePath,
                        if (archivedPrior) "archived" else "none",
                    )
                    // Get the raw InternxtFile (not CloudItem) so we keep fileId, the
                    // bucket-entry content-address.  parentUuid is already resolved above.
                    // The candidate is the live file getMetadata would return. A first-hit
                    // of fullName or baseName adopted `Makefile.am` (or a non-live row)
                    // when it was listed before the exact name, and a byte compare against
                    // that wrong row either adopted the sibling or conflict-copied a file
                    // that was already identical.
                    val folderContent = api.getFolderContents(parentUuid)
                    val namedFiles = liveFilesForName(folderContent.files, segments.last())
                    val remoteFile = namedFiles.singleOrNull()
                    val remoteFolder =
                        folderContent.children.find { folder ->
                            sanitizeName(folder.plainName ?: folder.name ?: "") == segments.last()
                        }
                    if (remoteFolder != null || namedFiles.isEmpty()) {
                        // Path maps to a folder, or the listing doesn't see a live file —
                        // keep the original behaviour (re-throw).
                        throw e
                    }
                    // Content-identity: bucket-entry fileId is Internxt's content-address.
                    // Equal fileId AND equal size ⇒ provably same content → safe adopt.
                    // Null fileId, size mismatch, or fileId mismatch → not provably identical
                    // by the content-address alone. More than one live match is not
                    // one file either (remoteFile stays null) → keep both.
                    val remoteFileId = remoteFile?.fileId
                    val remoteSize = remoteFile?.size?.toLongOrNull() ?: -1L
                    // bucketEntry.id is never null, so an equal fileId is a present one.
                    if (remoteFile != null && remoteFile.fileId == bucketEntry.id && remoteSize == fileSize) {
                        log.info(
                            "UD-366: remote {} has identical content (fileId={}, size={}) — adopting via replaceFile",
                            remotePath, bucketEntry.id, fileSize,
                        )
                        api
                            .replaceFile(
                                uuid = remoteFile.uuid,
                                size = fileSize,
                                fileId = bucketEntry.id,
                                modificationTime = localMtime,
                            ).afterReplace(localMtime)
                    } else if (
                        remoteFile != null &&
                            remoteSize == fileSize &&
                            sameContentAsRemote(remoteFile, remotePath, localPath, fileSize, tempFile)
                    ) {
                        // A fresh upload always gets a new bucket entry, so a different fileId says nothing about the
                        // content: the bytes decide. Equal: the remote is the file already, adopted as it is (no
                        // replace, no copy); the bucket entry just sent stays unreferenced.
                        log.info(
                            "409 on POST /files for {}: the remote file (uuid={}, size={}) has the same content as the local one; " +
                                "adopted as the same file, no conflict copy",
                            remotePath, remoteFile.uuid, fileSize,
                        )
                        remoteFile
                    } else {
                        // KEEP BOTH: leave remote untouched; land local under a conflict name.
                        val today =
                            java.time.format.DateTimeFormatter
                                .ofPattern("yyyy-MM-dd")
                                .withZone(java.time.ZoneOffset.UTC)
                                .format(java.time.Instant.now())
                        log.warn(
                            "UD-366: collision on {} — remote fileId={} size={} differs from local fileId={} size={}; " +
                                "preserving remote untouched, uploading local as conflict copy",
                            remotePath, remoteFileId, remoteSize, bucketEntry.id, fileSize,
                        )
                        // Upload conflict copy: reuse the already-committed bucket entry
                        // (same fileId) under a conflict-suffixed name.
                        var conflictResult: org.krost.unidrive.internxt.model.InternxtFile? = null
                        for (counter in 1..MAX_KEEP_OVERWRITTEN_RETRIES) {
                            val conflictPlainName = conflictName(plainName, today, counter, ext)
                            val conflictEncryptedName =
                                crypto.encryptName(conflictPlainName, "${creds.mnemonic}-$parentUuid")
                            try {
                                conflictResult =
                                    api.createFile(
                                        bucket = bucket,
                                        folderUuid = parentUuid,
                                        plainName = conflictPlainName,
                                        encryptedName = conflictEncryptedName,
                                        size = fileSize,
                                        type = ext,
                                        fileId = bucketEntry.id,
                                        modificationTime = localMtime,
                                    )
                                break
                            } catch (ce: InternxtApiException) {
                                if (ce.statusCode == 409 && counter < MAX_KEEP_OVERWRITTEN_RETRIES) {
                                    log.warn(
                                        "UD-366: conflict name '{}' also taken; trying counter {}",
                                        conflictPlainName, counter + 1,
                                    )
                                    continue
                                }
                                throw ce
                            }
                        }
                        conflictResult
                            ?: throw InternxtApiException(
                                message = "UD-366: exhausted $MAX_KEEP_OVERWRITTEN_RETRIES conflict-name attempts for $remotePath",
                                statusCode = 409,
                            )
                    }
                } catch (e: java.io.IOException) {
                    // The createFile response was lost (truncated / "server
                    // prematurely closed the connection") AFTER the server may have
                    // committed the drive entry: the file is on the drive but we
                    // never received its UUID. Leaving the row as a `local:` ghost
                    // loses the next rename/delete and risks a duplicate re-upload.
                    // Re-resolve by path; if the file landed with exactly the
                    // content we just uploaded (same bucket fileId + size), adopt
                    // its real UUID. Otherwise surface the original failure.
                    log.warn(
                        "createFile response lost for {} ({}); re-resolving parent to check server-side commit",
                        remotePath,
                        e.message,
                    )
                    val landed = findFileByName(parentUuid, segments.last()) ?: throw e
                    val landedSize = landed.size?.toLongOrNull() ?: -1L
                    if (landed.fileId == bucketEntry.id && landedSize == fileSize) {
                        log.info(
                            "createFile landed server-side despite the lost response for {} (uuid={}); adopting",
                            remotePath,
                            landed.uuid,
                        )
                        landed
                    } else {
                        throw e
                    }
                }
            finalItem = created.toCloudItem(parentPath)
        }

        // Success-path cleanup. Tombstone + .enc only get removed AFTER the
        // drive metadata write lands so a crash in stages 5-9 leaves us with
        // the data to resume; a crash AFTER this point falls through to the
        // existing "create returns 409 because the cloud already has it"
        // fallback above (existingRemoteId=null on the next call) — that path
        // is covered by the UD-366 logic without needing a tombstone.
        withContext(Dispatchers.IO) { tombstoneStore.discard(pathHashStr) }
        return finalItem
    }

    // archive#485: an empty file is a drive entry with size 0 and no fileId; nothing goes to the bridge. The same decisions as the
    // non-empty path: a replace keeps the prior content when keepOverwritten is on, and a create that collides adopts the
    // remote only when it is provably the same content (here: also empty) and otherwise keeps both under a conflict name.
    /**
     * The parent resolution for an upload (both the normal and the empty-file flow).
     *
     * archive#580: a folder trashed in the cloud while rows below it still exist (archive#132 trash
     * lag) makes the walk fail with "Folder not found" — but that is a POSITIVE proof
     * of absence: the listing succeeded and the segment is not in it. The same call
     * with the same bytes would fail again forever, so surface it as a permanent
     * refusal, typed as [ParentGoneException] with the folder the walk did not find
     * (the `parent_gone` token on the hydration events): the hydration queue skips its retry ladder, the replay and a
     * resubmission of the same bytes skip the provider (archive#493's upload_refused stamp),
     * and the user's bytes stay in the cache until their next edit or the lagging
     * tombstone cleans the subtree. A listing that FAILED (5xx, timeout) is not
     * absence — that keeps its retryable type.
     */
    private suspend fun resolveUploadParent(parentPath: String, remotePath: String): String =
        try {
            try {
                resolveFolder(parentPath)
            } catch (e: FolderNotFoundException) {
                // gkrost/unidrive-cli#134: the first walk may have trusted a cached ancestor or a listing that lags behind the
                // cloud (archive#132, UD-357). Absence stands only after a second, uncached look at complete listings.
                log.info("upload of {}: the walk did not find {}; looking again through the complete listings", remotePath, e.missingFolder)
                resolveFolderAfresh(parentPath)
            }
        } catch (e: FolderNotFoundException) {
            throw org.krost.unidrive.ParentGoneException(
                "parent folder of $remotePath is gone in the cloud (trashed?): ${e.message}",
                parent = e.missingFolder,
                cause = e,
            )
        }

    /**
     * gkrost/unidrive-cli#134: [resolveFolder] for the second look at a path whose first walk did not find [firstMissing].
     * Nothing is taken from [folderCache] on the way down (a stale entry for an ancestor is what the first walk may have
     * followed), the child folders of each level come from the paged listing, which ends only on its last page, after
     * [parentRelistDelayMs] so that a listing that lags behind a create or a trash has caught up, and every folder found
     * replaces the cache's entry for its name. Only a folder missing from such a listing is absent: [FolderNotFoundException].
     * A listing that fails propagates with its own (retryable) type; it proves nothing.
     */
    private suspend fun resolveFolderAfresh(path: String): String {
        kotlinx.coroutines.delay(parentRelistDelayMs)
        val segments = pathSegments(path)
        var currentUuid = authService.getValidCredentials().rootFolderId
        for ((index, segment) in segments.withIndex()) {
            val children =
                api.getChildFoldersPaged(currentUuid).filter { it.isLive() && sanitizeName(it.plainName ?: it.name ?: "") == segment }
            val child =
                when (children.size) {
                    0 -> {
                        folderCache.invalidate(currentUuid, segment)
                        throw FolderNotFoundException(segment, path, "/" + segments.take(index + 1).joinToString("/"))
                    }
                    1 -> children[0]
                    else -> throwAmbiguous(children.size, segment, path)
                }
            folderCache.put(currentUuid, segment, child.uuid)
            currentUuid = child.uuid
        }
        return currentUuid
    }

    // How long the second look at a missing upload parent waits first: a listing that does not show a folder just created or
    // just restored is seen to lag for 1-3 s (FolderUuidCache, archive#132). A test seam, like [clock].
    internal var parentRelistDelayMs: Long = PARENT_RELIST_DELAY_MS

    private suspend fun uploadEmpty(
        segments: List<String>,
        plainName: String,
        ext: String?,
        parentPath: String,
        existingRemoteId: String?,
        ifMatchETag: String?,
        remotePath: String,
        localMtime: Instant,
    ): CloudItem {
        val creds = authService.getValidCredentials()
        val parentUuid = resolveUploadParent(parentPath, remotePath)
        val bucket =
            creds.bucket.ifEmpty {
                throw ProviderException("No bucket in credentials — re-authenticate with 'unidrive auth --provider internxt'")
            }

        suspend fun create(name: String) =
            api.createFile(
                bucket = bucket,
                folderUuid = parentUuid,
                plainName = name,
                encryptedName = crypto.encryptName(name, "${creds.mnemonic}-$parentUuid"),
                size = 0L,
                type = ext,
                fileId = null,
                modificationTime = localMtime,
            )

        if (existingRemoteId != null) {
            checkWriteToken(existingRemoteId, ifMatchETag, remotePath)
            val archived = if (config.keepOverwritten) tryKeepOverwrittenRename(existingRemoteId, plainName) else false
            val item =
                if (archived) {
                    create(plainName)
                } else {
                    api.replaceFile(uuid = existingRemoteId, size = 0L, fileId = null, modificationTime = localMtime).afterReplace(localMtime)
                }
            return item.toCloudItem(parentPath)
        }

        val created =
            try {
                create(plainName)
            } catch (e: InternxtApiException) {
                if (e.statusCode != 409) throw e
                val folderContent = api.getFolderContents(parentUuid)
                if (folderContent.children.any { sanitizeName(it.plainName ?: it.name ?: "") == segments.last() }) throw e
                val remote = findFileByName(parentUuid, segments.last()) ?: throw e
                if ((remote.size?.toLongOrNull() ?: -1L) == 0L) {
                    log.info("#485: remote {} is already an empty file (uuid={}); adopting", remotePath, remote.uuid)
                    remote
                } else {
                    val today =
                        java.time.format.DateTimeFormatter
                            .ofPattern("yyyy-MM-dd")
                            .withZone(java.time.ZoneOffset.UTC)
                            .format(Instant.now())
                    log.warn("#485: collision on {} with a non-empty remote; keeping it, uploading the empty file as a conflict copy", remotePath)
                    var copy: org.krost.unidrive.internxt.model.InternxtFile? = null
                    for (counter in 1..MAX_KEEP_OVERWRITTEN_RETRIES) {
                        try {
                            copy = create(conflictName(plainName, today, counter, ext))
                            break
                        } catch (ce: InternxtApiException) {
                            if (ce.statusCode == 409 && counter < MAX_KEEP_OVERWRITTEN_RETRIES) continue
                            throw ce
                        }
                    }
                    copy ?: throw InternxtApiException(
                        message = "#485: exhausted $MAX_KEEP_OVERWRITTEN_RETRIES conflict-name attempts for $remotePath",
                        statusCode = 409,
                    )
                }
            }
        return created.toCloudItem(parentPath)
    }

    // A create's 409 names a remote of the same size: read it (decrypted into the staging directory, deleted right after)
    // and compare its plaintext SHA-256 with the local file's. A read or compare that fails proves nothing: false, and
    // the caller keeps both as before. Above COLLISION_COMPARE_MAX_BYTES nothing is read: false as well.
    private suspend fun sameContentAsRemote(
        remote: InternxtFile,
        remotePath: String,
        localPath: Path,
        size: Long,
        stagedCiphertext: Path,
    ): Boolean {
        if (size > collisionCompareMaxBytes) {
            log.info(
                "409 on POST /files for {}: the remote file (uuid={}) has the same size ({} bytes) but is above the {}-byte " +
                    "compare cap; not downloaded to compare, keeping both",
                remotePath, remote.uuid, size, collisionCompareMaxBytes,
            )
            return false
        }
        val probe = stagedCiphertext.resolveSibling("${stagedCiphertext.fileName}.compare")
        return try {
            val receipt = downloadWithFileUuid(remote.uuid, remotePath, probe)
            val remoteSha256 = receipt.plaintextSha256 ?: return false
            val localSha256 = withContext(Dispatchers.IO) { withLocalIoRetry { fileSha256Hex(localPath) } }
            receipt.bytesWritten == size &&
                remoteSha256.equals(localSha256, ignoreCase = true)
        } catch (e: ProviderException) {
            log.warn("could not compare {} with the remote file (uuid={}): {}; keeping both", remotePath, remote.uuid, e.message)
            false
        } catch (e: java.io.IOException) {
            log.warn("could not compare {} with the remote file (uuid={}): {}; keeping both", remotePath, remote.uuid, e.message)
            false
        } finally {
            withContext(Dispatchers.IO + NonCancellable) { Files.deleteIfExists(probe) }
        }
    }

    // Re-resolve a file by name within its parent folder after a createFile whose
    // response was lost: the drive entry may exist without a UUID in hand. The same
    // live exact-name choice as getMetadata — a baseName sibling or a second live
    // exact match is not this file, so the caller keeps the original failure
    // instead of adopting the wrong row.
    private suspend fun findFileByName(
        parentUuid: String,
        fileName: String,
    ): org.krost.unidrive.internxt.model.InternxtFile? {
        val folderContent = api.getFolderContents(parentUuid)
        return liveFilesForName(folderContent.files, fileName).singleOrNull()
    }

    override suspend fun delete(remotePath: String, ifMatchETag: String?) {
        // archive#291: Internxt has no conditional-delete primitive, so the optimistic-concurrency
        // token is accepted-and-ignored; routine deletes already route to the recycle bin below.
        // UD-367: route routine sync-driven deletes through Internxt's recycle bin
        // (POST /storage/trash/add) so any spurious del-local from a partial delta()
        // gather is recoverable rather than permanently destructive. The permanent
        // deleteFile/deleteFolder primitives in InternxtApiService remain available
        // for an explicit `unidrive trash purge --remote` operator action (not yet wired).
        val metadata = getMetadata(remotePath)
        val type = if (metadata.isFolder) "folder" else "file"
        api.trashItems(listOf(metadata.id to type))
    }

    // archive#402: resolve an item's CloudItem by uuid instead of by path — same-name siblings
    // make path-based lookup an arbitrary pick. Files are probed first; a 404 falls
    // through to the folder probe. Any other status propagates unchanged: a transient
    // 5xx must not surface as "Item not found" (the engine treats that as already-gone
    // and would tombstone the row).
    private suspend fun metadataById(
        remoteId: String,
        parentPath: String,
        path: String,
    ): CloudItem =
        try {
            api.getFileMeta(remoteId).toCloudItem(parentPath)
        } catch (e: InternxtApiException) {
            if (e.statusCode != 404) throw e
            try {
                api.getFolderMeta(remoteId).toCloudItem(parentPath)
            } catch (e2: InternxtApiException) {
                if (e2.statusCode != 404) throw e2
                throw ProviderException("Item not found: $path (remoteId=$remoteId)")
            }
        }

    /**
     * archive#580: the folder [remoteId] as its meta answers: trashed (status `TRASHED`, or the `removed` flag), not found (HTTP
     * 404/410, or a folder deleted for good), or live. Any other failure (a 5xx, a degraded gateway, a timeout, a body
     * that cannot be read) propagates: it proves nothing. A folder that is gone also leaves the folder-uuid cache, so the
     * next walk asks the listing instead of reaching a dead uuid (the stale-entry case at [resolveFolder]).
     */
    override suspend fun folderStateById(remoteId: String): FolderState {
        val state =
            try {
                val folder = api.getFolderMeta(remoteId)
                when {
                    folder.status == "DELETED" || folder.deleted -> FolderState.NOT_FOUND
                    folder.isTrashed() || folder.removed -> FolderState.TRASHED
                    else -> FolderState.LIVE
                }
            } catch (e: InternxtApiException) {
                if (e.statusCode !in PERMANENT_ABSENCE_STATUSES) throw e
                FolderState.NOT_FOUND
            }
        if (state != FolderState.LIVE) folderCache.invalidateFolder(remoteId)
        return state
    }

    // True when [folderUuid] is the top of a tree of its own or lies below one (see [outsideDriveFolders]): climbed through
    // the folders' meta up to the drive root, each folder asked for once per process. A folder that is gone, or a chain
    // that comes back to itself, proves nothing and is not remembered.
    private suspend fun isOutsideDrive(folderUuid: String?): Boolean {
        if (folderUuid == null) return false
        val rootUuid = authService.getValidCredentials().rootFolderId
        val climbed = LinkedHashSet<String>()
        var uuid: String? = folderUuid
        while (uuid != null && uuid != rootUuid && uuid !in insideDriveFolders) {
            if (uuid in outsideDriveFolders) break
            if (!climbed.add(uuid)) return false
            val folder =
                try {
                    api.getFolderMeta(uuid)
                } catch (e: InternxtApiException) {
                    if (e.statusCode in PERMANENT_ABSENCE_STATUSES) return false
                    throw e
                }
            uuid = folder.parentUuid
        }
        val outside = uuid == null || uuid in outsideDriveFolders
        (if (outside) outsideDriveFolders else insideDriveFolders).addAll(climbed)
        return outside
    }

    // A write by id to an item of a tree of its own never reaches the server. [item] is the item as its meta answered: a
    // folder with no parent is such a tree's top, anything else is judged by its parent. A file whose meta names no
    // folder says nothing, and passes.
    private suspend fun refuseOutsideDrive(
        item: CloudItem,
        remotePath: String,
    ) {
        val rootUuid = authService.getValidCredentials().rootFolderId
        val outside =
            when {
                item.id == rootUuid -> false
                item.isFolder && item.parentId == null -> true.also { outsideDriveFolders.add(item.id) }
                else -> isOutsideDrive(item.parentId)
            }
        if (outside) throw ProviderException("Not part of the drive (a tree of its own, such as a device backup); not written to: $remotePath")
    }

    override suspend fun deleteById(remoteId: String, remotePath: String) {
        // The trash endpoint needs file-vs-folder; resolve it by uuid (see metadataById)
        // so same-name siblings can't redirect the delete to the wrong twin.
        val metadata = metadataById(remoteId, parentPathOf(remotePath), remotePath)
        refuseOutsideDrive(metadata, remotePath)
        val type = if (metadata.isFolder) "folder" else "file"
        api.trashItems(listOf(metadata.id to type))
    }

    // archive#1178: the permanent primitives behind rm --permanent (DELETE /files/{uuid} / DELETE /folders/{uuid}), the
    // explicit purge the trash flow of delete/deleteById deliberately leaves to the operator.
    override suspend fun deletePermanently(remotePath: String) = purge(getMetadata(remotePath), remotePath)

    // By uuid, like deleteById: same-name siblings cannot redirect a purge to the wrong twin.
    override suspend fun deletePermanentlyById(
        remoteId: String,
        remotePath: String,
    ) = purge(metadataById(remoteId, parentPathOf(remotePath), remotePath), remotePath)

    private suspend fun purge(
        metadata: CloudItem,
        remotePath: String,
    ) {
        refuseOutsideDrive(metadata, remotePath)
        if (metadata.isFolder) {
            api.deleteFolder(metadata.id)
        } else {
            api.deleteFile(metadata.id)
        }
    }

    // archive#402: all but the leaf segment of [path] — the parent the by-id probes build
    // item paths against (error-message/path cosmetics only; the uuid is what selects).
    private fun parentPathOf(path: String): String = "/" + pathSegments(path).dropLast(1).joinToString("/")

    override suspend fun createFolder(path: String): CloudItem {
        val segments = pathSegments(path)
        val parentPath = "/" + segments.dropLast(1).joinToString("/")
        val name = segments.last()
        val parentUuid = resolveFolder(parentPath)
        val creds = authService.getValidCredentials()
        val encryptedName = crypto.encryptName(name, "${creds.mnemonic}-$parentUuid")
        val folder =
            try {
                api.createFolder(parentUuid, name, encryptedName)
            } catch (e: InternxtApiException) {
                if (e.statusCode != 409) throw e
                // Folder already exists on the server — recover its UUID instead of failing.
                val existing =
                    api
                        .getFolderContents(parentUuid)
                        .children
                        // UD-317: sanitise child name before matching.
                        .find { sanitizeName(it.plainName ?: it.name ?: "") == name }
                        ?: throw e // 409 but folder not found: re-throw original error
                existing
            }
        // UD-357: cache the new folder's UUID so the next resolveFolder call
        // doesn't race Internxt's read-after-write inconsistency on listing.
        // Use sanitizeName(name) so the lookup key matches what resolveFolder
        // compares against.
        folderCache.put(parentUuid, sanitizeName(name), folder.uuid)
        return folder.toCloudItem(parentPath)
    }

    /**
     * UD-368: bulk-aware override. Groups paths by their parent folder, resolves each parent
     * UUID once, and calls [InternxtApiService.createFoldersBatch] in chunks of 5 (the
     * Internxt server cap). Falls back to the per-path [createFolder] (which carries the
     * UD-317 409-recovery dance and UD-357 cache population) for cross-parent groups,
     * preserving today's correctness guarantees.
     *
     * **Caveat — partial-success on bulk 409.** The server returns
     * `CreateBulkFoldersConflictResponseDto` for any conflict in the batch, listing only the
     * conflicting plain names. Recovering UUIDs for the non-conflicting ones from a bulk
     * response is awkward; on 409 we fall back to serial [createFolder] for the entire
     * group, which trades round-trips for correctness.
     */
    override suspend fun createFolders(paths: List<String>): List<CloudItem> {
        if (paths.isEmpty()) return emptyList()
        if (paths.size == 1) return listOf(createFolder(paths.single()))

        // Preserve input order in the result.
        data class Indexed(val originalIndex: Int, val path: String, val name: String, val parentPath: String)
        val indexed =
            paths.mapIndexed { i, p ->
                val segs = pathSegments(p)
                require(segs.isNotEmpty()) { "Cannot create root: $p" }
                Indexed(i, p, segs.last(), "/" + segs.dropLast(1).joinToString("/"))
            }
        val byParent = indexed.groupBy { it.parentPath }
        val results = arrayOfNulls<CloudItem>(paths.size)

        for ((parentPath, group) in byParent) {
            val parentUuid = resolveFolder(parentPath)
            val creds = authService.getValidCredentials()
            // Chunk against the server's 5-item cap.
            for (chunk in group.chunked(5)) {
                val items =
                    chunk.map { idx ->
                        val encryptedName = crypto.encryptName(idx.name, "${creds.mnemonic}-$parentUuid")
                        idx.name to encryptedName
                    }
                try {
                    val created = api.createFoldersBatch(parentUuid, items)
                    require(created.size == chunk.size) {
                        "createFoldersBatch returned ${created.size} folders for ${chunk.size} requested"
                    }
                    chunk.forEachIndexed { position, idx ->
                        // The answer is paired by name: its order is not documented, and a folder cached under another
                        // folder's name sends everything below that name to the wrong place. An answer that does not name
                        // its folders is taken in the order of the request.
                        val folder =
                            created.firstOrNull { it.plainName != null && sanitizeName(it.plainName) == sanitizeName(idx.name) }
                                ?: created[position]
                        // UD-357: populate cache so later resolveFolder calls don't race.
                        folderCache.put(parentUuid, sanitizeName(idx.name), folder.uuid)
                        results[idx.originalIndex] = folder.toCloudItem(parentPath)
                    }
                } catch (e: InternxtApiException) {
                    if (e.statusCode != 409) throw e
                    // UD-368 caveat: bulk 409 doesn't tell us per-item success/failure.
                    // Fall back to serial createFolder which handles 409 per-item via UD-317.
                    log.warn(
                        "UD-368: bulk POST /folders returned 409 for parent {}; falling back to " +
                            "serial createFolder for {} item(s)",
                        parentPath,
                        chunk.size,
                    )
                    chunk.forEach { idx ->
                        results[idx.originalIndex] = createFolder(idx.path)
                    }
                }
            }
        }
        @Suppress("UNCHECKED_CAST")
        return (results as Array<CloudItem>).toList()
    }

    override suspend fun move(
        fromPath: String,
        toPath: String,
    ): CloudItem = moveResolved(getMetadata(fromPath), fromPath, toPath)

    // archive#402: the engine routes here whenever the source row carries a real cloud uuid,
    // so a same-named sibling cannot redirect the move to the wrong twin via
    // getMetadata's path lookup. The destination parent still resolves by path; if IT
    // is ambiguous, resolveFolder fails closed.
    override suspend fun moveById(
        remoteId: String,
        fromPath: String,
        toPath: String,
    ): CloudItem {
        val metadata = metadataById(remoteId, parentPathOf(fromPath), fromPath)
        refuseOutsideDrive(metadata, fromPath)
        return moveResolved(metadata, fromPath, toPath)
    }

    // UD-369 / archive#417 move legs, shared by the path and by-id entry points. [metadata]
    // is the already-resolved source item.
    private suspend fun moveResolved(
        metadata: CloudItem,
        fromPath: String,
        toPath: String,
    ): CloudItem {
        val fromSegments = pathSegments(fromPath)
        val toSegments = pathSegments(toPath)
        if (fromSegments.isEmpty() || toSegments.isEmpty()) {
            throw ProviderException("Cannot move from/to root: $fromPath -> $toPath")
        }
        val fromParent = fromSegments.dropLast(1)
        val toParent = toSegments.dropLast(1)
        val fromName = fromSegments.last()
        val toName = toSegments.last()
        val toParentPath = "/" + toParent.joinToString("/")

        val sameParent = fromParent == toParent
        val sameName = fromName == toName

        if (sameParent && sameName) return metadata // no-op

        // UD-369: route the rename leg through PUT /…/meta when the parent doesn't change,
        // avoiding the engine's worst-case download → re-encrypt → re-upload → delete-old
        // cycle for what is metadata-only on the wire. Cross-parent moves keep PATCH; mixed
        // (different parent AND different name) does both in sequence.
        // archive#417: every leg below stamps a new modificationTime server-side but answers with the
        // pre-update one; the result is re-read once at the end (afterMove), against the modified
        // time the item had when we started.
        val startedFrom = metadata.modified
        if (sameParent) {
            return if (metadata.isFolder) {
                api.renameFolder(metadata.id, toName).afterMove(startedFrom).toCloudItem(toParentPath)
            } else {
                val newType = newFileType(toName)
                api.renameFile(metadata.id, plainName = stripExtension(toName), type = newType)
                    .afterMove(startedFrom)
                    .toCloudItem(toParentPath)
            }
        }

        val destFolderUuid = resolveFolder(toParentPath)
        // archive#1001: a retry by id starts from where the item is now. A move leg that landed before
        // its rename leg failed left the item in the destination under another name; only the
        // rename is left to do (or nothing, when that leg landed too).
        val needsRename = !sameName && metadata.name != toName
        if (metadata.parentId == destFolderUuid) {
            if (!needsRename) return metadata.copy(path = "/" + toSegments.joinToString("/"))
            return if (metadata.isFolder) {
                api.renameFolder(metadata.id, toName).afterMove(startedFrom).toCloudItem(toParentPath)
            } else {
                api.renameFile(metadata.id, plainName = stripExtension(toName), type = newFileType(toName))
                    .afterMove(startedFrom)
                    .toCloudItem(toParentPath)
            }
        }
        val order = if (needsRename) moveRenameOrder(metadata, fromParent, destFolderUuid, toName) else MoveOrder.MoveOnly
        return if (metadata.isFolder) {
            runMoveLegs(order, toName, { api.moveFolder(metadata.id, destFolderUuid) }) { api.renameFolder(metadata.id, it) }
                .afterMove(startedFrom)
                .toCloudItem(toParentPath)
        } else {
            // The temporary name is the occupancy token (`.unidrive-move-<uuid>`, plus the final
            // type when the destination has one). stripExtension treats that token's only dot as a
            // separator and sends an empty plain name, which Internxt rejects, so the temp leg is
            // not round-tripped through it. The final name still is: `README` stays plain with no
            // type, `g.txt` stays plain `g` and type `txt`.
            val tempName = (order as? MoveOrder.ViaTemp)?.name
            runMoveLegs(order, toName, { api.moveFile(metadata.id, destFolderUuid) }) { name ->
                val (plain, type) =
                    if (name == tempName) {
                        val dot = name.lastIndexOf('.')
                        if (dot <= 0) name to null else name.substring(0, dot) to name.substring(dot + 1)
                    } else {
                        stripExtension(name) to newFileType(name)
                    }
                api.renameFile(metadata.id, plainName = plain, type = type)
            }.afterMove(startedFrom).toCloudItem(toParentPath)
        }
    }

    // archive#1001: the order of the two legs of a move into another folder under a new name. Internxt
    // has no call that does both, and each leg is refused with 409 when its own folder already
    // holds the name the item has at that moment. Moving first fails when the destination holds
    // an item of the OLD name although the final name is free there, so the order is chosen from
    // what is free: move first when the destination has no item of the current name; else rename
    // first in the source folder when the final name is free there; else through a temporary name
    // that neither folder holds. Any kind of item counts for the names on the way (a needless
    // detour costs one call); a folder or file of the same kind with the FINAL name in the
    // destination means the move cannot land, and is refused before anything is changed, with the
    // 409 the server gives for it, instead of leaving the item half moved.
    private suspend fun moveRenameOrder(
        metadata: CloudItem,
        fromParent: List<String>,
        destFolderUuid: String,
        toName: String,
    ): MoveOrder {
        val mnemonic = mnemonicOrNull()
        val dest = api.getFolderContentsPaged(destFolderUuid)
        if (toName in liveNames(dest, mnemonic, onlyFolders = metadata.isFolder)) {
            throw InternxtApiException(
                "A ${if (metadata.isFolder) "folder" else "file"} named '$toName' already exists in the destination folder",
                statusCode = 409,
                responseReceived = true,
            )
        }
        val destNames = liveNames(dest, mnemonic)
        if (metadata.name !in destNames) return MoveOrder.MoveFirst
        val sourceUuid = metadata.parentId ?: resolveFolder("/" + fromParent.joinToString("/"))
        val sourceNames = liveNames(api.getFolderContentsPaged(sourceUuid), mnemonic)
        if (toName !in sourceNames) return MoveOrder.RenameFirst
        val ext = newFileType(toName)?.takeIf { !metadata.isFolder }?.let { ".$it" } ?: ""
        // The whole uuid: its first characters are shared by uuid v7 ids minted in the same window,
        // so two moves would otherwise pick the same temporary name.
        val temp =
            generateSequence(0) { it + 1 }
                .map { n -> ".unidrive-move-${metadata.id}${if (n == 0) "" else "-$n"}$ext" }
                .first { it !in sourceNames && it !in destNames }
        return MoveOrder.ViaTemp(temp)
    }

    // The names of the live items of a listing as the paths name them (a file with its type);
    // [onlyFolders] true: the folders only, false: the files only, null: both. The same converters
    // as metadata.name, mnemonic included, so a legacy ciphertext name is the name the move compares.
    private fun liveNames(
        content: FolderContentResponse,
        mnemonic: String?,
        onlyFolders: Boolean? = null,
    ): Set<String> {
        val folders =
            if (onlyFolders == false) {
                emptyList()
            } else {
                content.children.filter { it.isLive() }.map { folderToCloudItem(it, "/", mnemonic).name }
            }
        val files =
            if (onlyFolders == true) {
                emptyList()
            } else {
                content.files.filter { it.isLive() }.map { fileToCloudItem(it, "/", mnemonic).name }
            }
        return (folders + files).toSet()
    }

    private sealed interface MoveOrder {
        object MoveOnly : MoveOrder

        object MoveFirst : MoveOrder

        object RenameFirst : MoveOrder

        data class ViaTemp(
            val name: String,
        ) : MoveOrder
    }

    private suspend fun <T> runMoveLegs(
        order: MoveOrder,
        toName: String,
        move: suspend () -> T,
        rename: suspend (String) -> T,
    ): T =
        when (order) {
            MoveOrder.MoveOnly -> move()
            MoveOrder.MoveFirst -> move().let { rename(toName) }
            MoveOrder.RenameFirst -> rename(toName).let { move() }
            is MoveOrder.ViaTemp -> rename(order.name).let { move() }.let { rename(toName) }
        }

    // archive#417: PUT /files/{uuid} answers with the entity as it was BEFORE the server stamped its new
    // modificationTime (observed live: size/fileId in the same body already updated, timestamp
    // not); a rename/move changes the server's modified time too, and its PUT .../meta / PATCH
    // responses are treated the same way. The engine stores that value in state.db; the next
    // delta lists the item with the real one, the Reconciler compares the two for equality and
    // plans a download of the very file we just uploaded (or renamed). POST /files (create) is
    // unaffected: the server stamps the row at insert and returns it.
    // Overlay only the timestamp from a fresh GET .../meta (the same read the download path
    // uses): it returns what the listing/delta will report, so no server-side rounding of a time
    // we sent has to be replicated here, and a rename/move sends no time at all (the server
    // stamps its own clock), so it could not be computed anyway. One extra call per
    // replace/move. Best effort: the write has already landed and a move cannot be retried
    // (its source path is gone), so a failed read degrades to the write response (the pre-archive#417
    // behaviour, one redundant download) instead of failing the operation.
    //
    // The meta read itself can lag the write (seen live: an intermittent echo right after a
    // rename). So a value that is provably still the pre-write one is re-read up to
    // [modifiedRereadDelaysMs].size more times, waiting the listed delays in between (1.9 s of
    // deliberate waits by default; a flaky meta endpoint adds its own transient-retry ladder on
    // top, since each read goes through authenticatedGet), and the first value that is not
    // lagging wins. If it never moves, the last value read is kept and logged at debug: the
    // write is not undone by a slow read.
    //   - replace: lagging = older than the modified time we SENT (truncated to whole seconds, so
    //     neither a rounding nor a truncating server trips it), or equal to the write response's
    //     own value when that differs from what we sent. The second clause covers an upload that
    //     restores an OLDER copy: the sent time is then older than the item's pre-write stamp,
    //     and the lagging read reports that newer pre-write value, which the older-than-sent
    //     check alone would mistake for a fresh one. A replace with an unchanged mtime
    //     (response == sent) keeps its single read.
    //   - rename/move: lagging = equal to the time the item had before (the entity we started
    //     from, or the write response), because the server always stamps a new time there.
    internal var modifiedRereadDelaysMs: List<Long> = listOf(300L, 600L, 1_000L)

    private suspend fun InternxtFile.afterReplace(sent: Instant): InternxtFile {
        val sentSecond = sent.truncatedTo(ChronoUnit.SECONDS)
        val responseSecond = modificationInstant?.truncatedTo(ChronoUnit.SECONDS)
        return withServerModified {
            it < sentSecond || (responseSecond != null && responseSecond != sentSecond && it == responseSecond)
        }
    }

    private suspend fun InternxtFile.afterMove(startedFrom: Instant?): InternxtFile =
        withServerModified { it == startedFrom || it == modificationInstant }

    private suspend fun InternxtFolder.afterMove(startedFrom: Instant?): InternxtFolder =
        copy(
            modificationTime =
                rereadModificationTime("folder", uuid, modificationTime, { it == startedFrom || it == modificationInstant }) {
                    api.getFolderMeta(uuid).modificationTime
                },
        )

    private suspend fun InternxtFile.withServerModified(isLagging: (Instant) -> Boolean): InternxtFile =
        copy(
            modificationTime =
                rereadModificationTime("file", uuid, modificationTime, isLagging) {
                    api.getFileMeta(uuid).modificationTime
                },
        )

    // Returns the modificationTime string to use for [uuid]: the first fresh value that is not
    // [isLagging] (bounded by [modifiedRereadDelaysMs]), else the last value read, else [fallback]
    // (the write response's own value) when the read is unavailable. Never throws except for
    // cancellation.
    private suspend fun rereadModificationTime(
        kind: String,
        uuid: String,
        fallback: String?,
        isLagging: (Instant) -> Boolean,
        read: suspend () -> String?,
    ): String? {
        var latest: String? = null
        try {
            latest = read() ?: return fallback
            for (delayMs in modifiedRereadDelaysMs) {
                val instant = tryParseInternxtInstant(latest)
                // An unparseable value is treated like a lagging one: re-read rather than
                // accept, and the last value read still wins if nothing parseable shows up.
                if (instant != null && !isLagging(instant)) return latest
                kotlinx.coroutines.delay(delayMs)
                latest = read() ?: return latest
            }
            val last = tryParseInternxtInstant(latest)
            if (last != null && isLagging(last)) {
                log.debug(
                    "#417: {} {} still reports the pre-write modified time {} after {} re-reads; keeping it",
                    kind,
                    uuid,
                    latest,
                    modifiedRereadDelaysMs.size,
                )
            }
            return latest
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            log.warn(
                "#417: re-reading {} {} after write failed ({}); keeping the last known modified time",
                kind,
                uuid,
                e.message ?: e.javaClass.simpleName,
            )
            return latest ?: fallback
        }
    }

    /**
     * Fast-bootstrap: adopt the current wall-clock instant as the cursor with
     * zero enumeration. Subsequent [delta] calls receive only items whose
     * `updatedAt > now - rewindCursorWindow`, so the engine starts with a
     * clean slate and pays no upfront enumeration cost.
     *
     * Trade-off: every file that exists on the remote *before* this call is
     * invisible to unidrive until it next mutates. Upload-direction sync is
     * unaffected (local files still upload). The engine's fast-bootstrap path
     * in `SyncEngine.gatherRemoteChanges` (UD-223) emits a loud warning so
     * the user knows the cursor was adopted without verification.
     *
     * Internxt has no server-side `?token=latest` equivalent of OneDrive
     * Graph's delta link, but the cursor model here is a plain ISO-8601
     * `updatedAt` timestamp, so synthesising one client-side is equivalent.
     * Wall-clock skew between this host and Internxt's storage is absorbed
     * by the same `rewindCursor` window the regular delta path uses.
     */
    override suspend fun deltaFromLatest(scanContext: org.krost.unidrive.ScanContext?): CapabilityResult<DeltaPage> =
        CapabilityResult.Success(
            DeltaPage(
                items = emptyList(),
                cursor = serverNow().toString(),
                hasMore = false,
                complete = true,
            ),
        )

    /**
     * gkrost/unidrive-cli#181: the instant a cursor is taken at, by the server's clock. The poll compares the cursor with the
     * server's own `updatedAt`, so a client clock more than [REWIND_CURSOR_SECONDS] ahead of it hides every change made since
     * the gather. The server's time is the `Date` header of its answers; where none has been seen yet one cheap authenticated
     * GET ([InternxtApiService.checkSession]) fetches it. Only a server that gives no time at all, or a probe that fails,
     * leaves the client clock, as before.
     */
    private suspend fun serverNow(): Instant {
        api.serverNowOrNull()?.let { return it }
        try {
            api.checkSession()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("No server time for the cursor ({}: {}), using the client clock", e.javaClass.simpleName, e.message)
        }
        return api.serverNowOrNull() ?: Instant.now()
    }

    override suspend fun delta(
        cursor: String?,
        onPageProgress: ((itemsSoFar: Int) -> Unit)?,
        scanContext: org.krost.unidrive.ScanContext?,
    ): DeltaPage =
        // Only a full enumeration waits out a connect outage (archive#693): it has a long listing to lose. An incremental
        // poll is one cheap query that the poller repeats on its cadence, and it must fail fast so that an offline
        // account is reported as such instead of hanging for minutes.
        if (cursor == null) {
            kotlinx.coroutines.withContext(EnumerationOutageRetry()) { deltaImpl(cursor, onPageProgress, scanContext) }
        } else {
            deltaImpl(cursor, onPageProgress, scanContext)
        }

    private suspend fun deltaImpl(
        cursor: String?,
        onPageProgress: ((itemsSoFar: Int) -> Unit)?,
        scanContext: org.krost.unidrive.ScanContext?,
    ): DeltaPage {
        // The next chunk of a full gather that hands its files over from the staging (see [GatherHandover]): not a poll,
        // whatever the cursor looks like.
        if (cursor != null && cursor.startsWith(GATHER_CURSOR_PREFIX)) return continueGather(cursor, onPageProgress, scanContext)
        foldersScanned.set(0)
        foldersSkipped.set(0)
        // A full enumeration of a scoped profile lists only the scope roots. An
        // incremental delta stays account-wide: it is one cheap updatedAt-filtered
        // query and the engine drops what falls outside the scope.
        val scopeRoots = scanContext?.scopeRoots.orEmpty()
        val onProgress = scanContext?.onProgress
        if (cursor == null && scopeRoots.isNotEmpty()) return scopedFullDelta(scopeRoots, onPageProgress, onProgress, scanContext = scanContext)
        // archive#517 R3: once a full gather ended in an unavailable account-wide listing, later
        // full gathers skip the doomed attempt for a while — one doomed attempt is minutes of heavy
        // load the walk was going to replace anyway, the gateway's 524 at about 125 s and the
        // listing watchdog's 150 s (#71) included (the live account re-bought it every ~7 min for 4 h).
        // Full gathers only: an incremental poll is one cheap updatedAt-filtered query,
        // the walk in its place costs a whole enumeration, and a first sync that ended
        // in the walk would be followed by a second walk at its first poll.
        // In-process on purpose: a restart pays one attempt to re-learn what the
        // engine's enumerate_failure_streak already covers on the poll cadence.
        if (cursor == null && Instant.now().isBefore(heavyListingsUnavailableUntil.get())) {
            log.warn(
                "Account-wide listings unavailable until {}; walking the folder tree directly{}",
                heavyListingsUnavailableUntil.get(),
                if (scopeRoots.isEmpty()) "" else " of $scopeRoots",
            )
            return scopedFullDelta(scopeRoots.ifEmpty { listOf("/") }, onPageProgress, onProgress, scanContext = scanContext)
        }
        // A full enumeration of the whole drive pages through the cursor listings: the offset listing below
        // needs 25-56 s per page on a large account and the gateway cuts it at about two minutes. Only a server
        // without those endpoints (older or self-hosted) carries on with the offset listing.
        if (cursor == null) cursorFullDelta(onPageProgress, scanContext, onProgress)?.let { return it }
        val adjustedCursor = cursor?.let { rewindCursor(it) }
        val limit = InternxtConfig.LISTING_PAGE_SIZE
        // drive-desktop parity: fresh full enum (cursor=null) uses sort=uuid
        // for offset-stable pagination + status=EXISTS to skip the tombstone
        // population (20–40 % payload win on a long-lived account — no need
        // for tombstones on first walk because there's no local state to
        // diff them against). The offset listing of an incremental poll
        // (cursor!=null — and only the fallback for a server without the sync
        // listings, archive#312) uses sort=updatedAt so the per-page max() is
        // monotonically advancing (next page advances the effective cursor)
        // and status=ALL because tombstones ARE the signal for deletes since
        // the last sync.
        val sort = if (cursor == null) "uuid" else "updatedAt"
        val status = if (cursor == null) "EXISTS" else "ALL"

        // Resume support: a non-null scanContext means the engine is tracking
        // staged pages in state.db. On a resumed scan the previously-fetched
        // rows arrive via scanContext.resumedItems and the per-stream offsets
        // are encoded in resumeMarker. A fresh scan starts both offsets at 0
        // with an empty resumedItems list.
        val resume = parseResumeOffsets(scanContext?.resumeMarker)
        // Rows the cursor listing or the tree walk staged are theirs: their markers do not parse as offsets, so
        // this listing starts over, and stale rows of another listing would rejoin it as if they were current.
        val resumedRows =
            scanContext?.resumedItems.orEmpty().filter { !isCursorStaged(it) && it.hash != CURSOR_ANCESTOR_TAG && !isTreeStaged(it) }
        val resumedFolders = resumedRows.filter { it.isFolder }
        val resumedFiles = resumedRows.filter { !it.isFolder }
        val allFiles = mutableListOf<InternxtFile>()
        val allFolders = mutableListOf<InternxtFolder>()
        // Resumed items rejoin the gather as Internxt model objects so the
        // post-loop folder-graph reconstruction sees the full union. Their
        // updatedAt is unknown (the engine stripped it during staging), so
        // they're absent from the cursor-advance computation below — the
        // freshly-fetched pages dominate that anyway.
        resumedFolders.mapTo(allFolders) { it.toResumedFolder() }
        resumedFiles.mapTo(allFiles) { it.toResumedFile() }

        // UD-352: this delta() loops internally over both /files and /folders
        // pagination, accumulating *all* pages before returning a single
        // DeltaPage. The engine's outer post-delta tick fires only at the
        // end — for a 63 k-item profile that's many minutes of silence. Hook
        // the heartbeat here so onScanProgress fires every 5k items / 10s
        // wall-clock during the gather.
        val heartbeat = onPageProgress?.let { cb -> ScanHeartbeat(cb) }

        // Parallel listing pagination. Files and folders streams run concurrently;
        // inside each stream up to 2 page fetches are asked for at a time. The heavy
        // listing calls pass the Drive HttpRetryBudget's permits (withPermit, two of
        // them, halved by a throttle storm), so no more than that many of them are on
        // the wire at once, whichever stream asks (archive#517 R7; before that four could
        // be in flight at once, as observed on the live account, F4). The API
        // itself tolerates more (measured on a live account 2026-09-29, archive#392:
        // throughput still scaled at concurrency 8, 8.7 calls/s, zero throttling;
        // /folders/content p50 ~0.3s, p99 ~2-4s, max ~8s) — the overlap exists to
        // absorb the p99 tail instead of stalling the stream behind one slow page.
        // Running counts via AtomicInteger so the heartbeat reports monotonically
        // non-decreasing totals as pages arrive on either stream. The resumed-row
        // contribution is baked in up front so the heartbeat total is monotonic
        // across the seam.
        val filesCount = AtomicInteger(allFiles.size)
        val foldersCount = AtomicInteger(allFolders.size)

        val combinedTotal: () -> Int = { filesCount.get() + foldersCount.get() }

        // Progress for the engine's status: one report per page of either stream, from whichever
        // coroutine received it, so both counters are read at the moment of the report.
        val reportAccountProgress: (() -> Unit)? =
            onProgress?.let { cb ->
                { cb(ScanProgress(items = combinedTotal(), listing = ScanProgress.LISTING_ACCOUNT)) }
            }
        reportAccountProgress?.invoke()

        // Per-page persistence is gated behind the scanContext + the
        // PageBoundaryPersister helper, which coalesces files+folders page
        // arrivals into a single sync_state marker write per page (the engine
        // doesn't care which stream the page came from; only that the
        // checkpoint advances atomically). On a snapshot/no-scanContext
        // delta() this collapses to a no-op.
        val persister =
            scanContext?.let { ctx ->
                PageBoundaryPersister(
                    persistPage = ctx.persistPage,
                    creds = authService.getValidCredentials(),
                    nextFilesOffset = resume.filesOffset,
                    nextFoldersOffset = resume.foldersOffset,
                )
            }

        // The two account-wide listings page through the whole account with an offset.
        // On a large account those calls are slow server-side (25-56 s per folder page,
        // more per file page, latency growing with the account's row count, not the
        // offset, archive#517 F3) and the flat 60 s socket watchdog cut every one of them —
        // reported by Ktor-over-TLS as "the server prematurely closed the connection",
        // which looked like a gateway cap and was our own timer (archive#517 F1, loopback
        // probe). R2 raised their watchdog past the gateway's own cut (330 s then; 150 s, a little
        // above the gateway's ~125 s 524, since #71), so what remains here are real closes and
        // the gateway's 524, which arrives as the synthetic 503. Either
        // listing failing as 500/503 still means: list the folder tree instead
        // (per-folder listings, up to 4 in flight), the walk a scoped profile does
        // anyway. A failing /folders used to end the whole gather, and a failing
        // /files fell back to a sequential walk that would take an hour.
        //
        // The last page each stream served is kept, as (offset, rows): the gather
        // re-reads it afterwards to detect a listing that moved under it (archive#312).
        val lastFolderPage = java.util.concurrent.atomic.AtomicReference<Pair<Int, List<InternxtFolder>>?>(null)
        val lastFilePage = java.util.concurrent.atomic.AtomicReference<Pair<Int, List<InternxtFile>>?>(null)
        try {
            if (cursor != null) {
                // archive#312: the poll reads its changes through the sync listings — the keyset the full
                // enumeration pages through, without the status filter, so that a row that changes
                // mid-gather cannot push another row past a page boundary, and tombstones arrive in
                // the stream. Null here means the server does not serve those endpoints: the offset
                // listing below is the poll then, as it was before.
                cursorIncrementalDelta(cursor, onPageProgress, scanContext, onProgress)?.let { page ->
                    // The sync listings answered: an earlier run of failures is over (archive#463).
                    incrementalUnavailableSince.set(null)
                    return page
                }
            }
            coroutineScope {
                val foldersDeferred =
                    async {
                        speculativeFetchPages(
                            limit = limit,
                            runningCount = foldersCount,
                            heartbeat = heartbeat,
                            combinedTotal = combinedTotal,
                            reportProgress = reportAccountProgress,
                            label = "folders",
                            startOffset = resume.foldersOffset,
                            fetchPage = { offset -> api.listFolders(adjustedCursor, limit, offset, status, sort) },
                            onPage = { page, nextOffset ->
                                if (page.isNotEmpty()) lastFolderPage.set(nextOffset - page.size to page)
                                persister?.notifyFoldersPage(page, nextOffset)
                            },
                        )
                    }
                val filesResult: List<InternxtFile> =
                    speculativeFetchPages(
                        limit = limit,
                        runningCount = filesCount,
                        heartbeat = heartbeat,
                        combinedTotal = combinedTotal,
                        reportProgress = reportAccountProgress,
                        label = "files",
                        startOffset = resume.filesOffset,
                        fetchPage = { offset -> api.listFiles(adjustedCursor, limit, offset, status, sort) },
                        onPage = { page, nextOffset ->
                            if (page.isNotEmpty()) lastFilePage.set(nextOffset - page.size to page)
                            persister?.notifyFilesPage(page, nextOffset)
                        },
                    )
                allFiles.addAll(filesResult)
                allFolders.addAll(foldersDeferred.await())
            }
        } catch (e: InternxtApiException) {
            if (e.statusCode !in SERVER_UNAVAILABLE_STATUSES) throw e
            return unavailableListingFallback(e, cursor, scopeRoots, onPageProgress, onProgress, combinedTotal())
        }
        // The cheap listing answered: an earlier run of failures is over (archive#463).
        if (cursor != null) incrementalUnavailableSince.set(null)

        // Cursor = max(updatedAt) seen across successfully-fetched pages.
        // Skipped (500/503 fallback) folders contribute nothing — their items
        // never made it into allFiles/allFolders.
        val seenMax =
            (allFiles.mapNotNull { it.updatedAt } + allFolders.mapNotNull { it.updatedAt })
                .maxOrNull()
        // Only an incremental delta believes the engine's rows: a full listing is what runs when it cannot.
        val tracked = scanContext?.trackedRow.takeIf { cursor != null }
        // archive#312: the offset listing is only as good as its stability. A row that changes while the
        // gather runs moves inside the updatedAt order, every later row shifts up one, and the row
        // at a page boundary is fetched by no page — its change (a TRASHED tombstone included) never
        // syncs, because the promoted cursor steps over it. The last page of each stream is re-read
        // and compared by uuid: an incremental poll whose tail moved under it does not advance the
        // cursor past what it could not re-read, and a full gather is not trusted as complete. A shift
        // that landed entirely inside an earlier page is not visible here — the sync listings are
        // the fix, this is the net for the servers that do not have them.
        val listingStable =
            lastPageStable(lastFolderPage.get(), { it.uuid }) { offset ->
                api.listFolders(adjustedCursor, limit, offset, status, sort)
            } &&
                lastPageStable(lastFilePage.get(), { it.uuid }) { offset ->
                    api.listFiles(adjustedCursor, limit, offset, status, sort)
                }
        return assembleOffsetDelta(allFiles, allFolders, seenMax, cursor, listingStable, onProgress, tracked)
    }

    // The account-wide listings answered unavailable (a 500/503, or a gateway cut that ends as one):
    // a poll fails fast and walks the folder tree once after INCREMENTAL_WALK_AFTER; a full gather
    // walks at once and opens the skip window.
    private suspend fun unavailableListingFallback(
        e: InternxtApiException,
        cursor: String?,
        scopeRoots: List<String>,
        onPageProgress: ((itemsSoFar: Int) -> Unit)?,
        onProgress: ((ScanProgress) -> Unit)?,
        itemsSoFar: Int,
    ): DeltaPage {
        if (cursor != null) {
            // archive#463: an incremental delta is the daemon's poll, and the daemon polls by default. Its
            // updatedAt-filtered listing is one cheap query (0.24 s live, archive#517); the walk in its place
            // costs a whole enumeration (about half an hour on a large drive) for what is usually a
            // blip. So a blip fails the poll: the poller backs off and the next poll asks the cheap
            // listing again from the same cursor, and nothing changed in between is lost.
            // But the walk is also the only way out when the cheap listing STAYS unavailable (an old
            // cursor whose query the gateway keeps cutting, archive#517): failing forever would freeze the
            // mount's view. Once the listing has failed for INCREMENTAL_WALK_AFTER, the poll walks
            // once, as it always did, and the walk's fresh cursor starts the cheap polls over.
            val now = clock()
            val since = incrementalUnavailableSince.updateAndGet { it ?: now }!!
            if (java.time.Duration.between(since, now) < INCREMENTAL_WALK_AFTER) throw e
            incrementalUnavailableSince.set(null)
            log.warn(
                "Incremental listing unavailable since {} ({}: {}), walking the folder tree once{}",
                since,
                e.statusCode,
                e.message,
                if (scopeRoots.isEmpty()) "" else " of $scopeRoots",
            )
            // An incremental walk does not open the full-gather window (archive#517 R3).
            return scopedFullDelta(scopeRoots.ifEmpty { listOf("/") }, onPageProgress, onProgress, itemsSoFar)
        }
        // Only a full gather opens the window.
        heavyListingsUnavailableUntil.set(Instant.now().plus(LISTING_UNAVAILABLE_TTL))
        log.warn(
            "Account-wide listing unavailable ({}: {}), falling back to the folder tree walk{}; " +
                "skipping the account-wide attempt until {}",
            e.statusCode,
            e.message,
            if (scopeRoots.isEmpty()) "" else " of $scopeRoots",
            heavyListingsUnavailableUntil.get(),
        )
        return scopedFullDelta(scopeRoots.ifEmpty { listOf("/") }, onPageProgress, onProgress, itemsSoFar)
    }

    // The offset gather's DeltaPage: paths for every item, the cursor promoted to the newest
    // updatedAt seen. When the listing moved under the gather (archive#312), a poll keeps its cursor where
    // it was, so the next poll re-reads the window instead of stepping over what it may have
    // missed. The poll stays complete: an incomplete page sets the engine's recovery latch, and
    // every later gather would list the whole drive from zero for a change the next poll re-reads
    // anyway. A full gather hands back the newest updatedAt it saw, incomplete, because a row it
    // missed would otherwise be taken for gone; the engine's recovery lists the drive again.
    private suspend fun assembleOffsetDelta(
        allFiles: List<InternxtFile>,
        allFolders: List<InternxtFolder>,
        seenMax: String?,
        cursor: String?,
        stable: Boolean,
        onProgress: ((ScanProgress) -> Unit)?,
        tracked: ((String) -> CloudItem?)?,
    ): DeltaPage {
        if (stable) {
            return assembleFullDelta(allFiles, allFolders, advanceCursor(seenMax, cursor, api.serverNowOrNull()), onProgress = onProgress, listing = ScanProgress.LISTING_ACCOUNT, tracked = tracked, fullGather = cursor == null)
        }
        if (cursor != null) {
            log.warn("The offset listing moved while the poll ran; the cursor stays at {}, the next poll re-reads the window", cursor)
            return assembleFullDelta(allFiles, allFolders, cursor, onProgress = onProgress, listing = ScanProgress.LISTING_ACCOUNT, tracked = tracked, fullGather = false)
        }
        log.warn("The offset listing moved while the full gather ran; returning DeltaPage(complete=false)")
        return assembleFullDelta(allFiles, allFolders, advanceCursor(seenMax, null, api.serverNowOrNull()), onProgress = onProgress, listing = ScanProgress.LISTING_ACCOUNT, tracked = tracked, fullGather = true)
            .copy(complete = false)
    }

    // The re-read of an offset stream's last page, compared by uuid: false when the page no longer
    // begins with the rows the gather read, and false too when the re-read itself fails — a gather
    // that cannot be checked is not a trusted one. Rows after them are rows that arrived at the end
    // of the listing since (a change sorts last by updatedAt): nothing before them moved, so they
    // do not make the gather untrusted. Null [last] (a stream that served only empty pages) has no
    // boundary to check.
    private suspend fun <T> lastPageStable(
        last: Pair<Int, List<T>>?,
        uuid: (T) -> String,
        refetch: suspend (offset: Int) -> List<T>,
    ): Boolean {
        val (offset, rows) = last ?: return true
        val again =
            try {
                refetch(offset)
            } catch (e: InternxtApiException) {
                log.warn(
                    "Re-reading the last page of the listing at offset {} failed ({}: {}); the gather is not trusted as complete",
                    offset,
                    e.statusCode,
                    e.message,
                )
                return false
            }
        return again.take(rows.size).map(uuid).sorted() == rows.map(uuid).sorted()
    }

    // The incremental poll's gather through the sync listings (archive#312): both streams read their
    // changes by keyset from the cursor rewound by the window, without the status filter, so a row
    // that changes mid-gather cannot push another row past a page boundary — the keyset key of an
    // unchanged row never moves. Trashed and deleted rows arrive in the stream with their status and
    // become tombstones exactly as the offset listing's status=ALL rows do; a row without a status
    // fails the poll (SyncRowWithoutStatus). The cursor handed back is the newest updatedAt actually
    // seen; a stream a guard ended (a cursor asked twice, a second empty page in a row) makes the
    // page incomplete — the engine then re-lists the drive instead of trusting the poll.
    //
    // Null when the server does not serve the listings (404/405/501 on the first page of a stream):
    // the caller keeps the offset listing. Any other failure propagates — an unavailable poll fails
    // fast (archive#463), and a transient one is retried on the next tick from the same cursor.
    private suspend fun cursorIncrementalDelta(
        cursor: String,
        onPageProgress: ((itemsSoFar: Int) -> Unit)?,
        scanContext: ScanContext?,
        onProgress: ((ScanProgress) -> Unit)?,
    ): DeltaPage? {
        val since = rewindCursor(cursor)
        val heartbeat = onPageProgress?.let { cb -> ScanHeartbeat(cb) }
        val lock = Mutex()
        // A row can turn up twice: page for its old key, then again, changed, for its new key at the
        // tail. The later page wins, as in the full enumeration's maps.
        val files = LinkedHashMap<String, InternxtFile>()
        val folders = LinkedHashMap<String, InternxtFolder>()
        var maxSeen: String? = null
        fun report() {
            val total = files.size + folders.size
            heartbeat?.tick(total)
            onProgress?.invoke(ScanProgress(items = total, listing = ScanProgress.LISTING_CURSOR))
        }
        report()

        val cutShort: Boolean
        try {
            cutShort =
                coroutineScope {
                    val foldersEnd =
                        async {
                            pageCursorStream(
                                stream = "folders",
                                start = StreamPosition(),
                                fetch = { c -> api.getFoldersSync(updatedAt = since.takeIf { c == null }, cursor = c, status = null) },
                                onPage = { page, _ ->
                                    lock.withLock {
                                        page.forEach { folders[it.uuid] = it }
                                        maxSeen = seenThrough(maxSeen, page) { it.updatedAt }
                                        report()
                                    }
                                },
                            )
                        }
                    val filesEnd =
                        async {
                            pageCursorStream(
                                stream = "files",
                                start = StreamPosition(),
                                fetch = { c -> api.getFilesSync(updatedAt = since.takeIf { c == null }, cursor = c, status = null) },
                                onPage = { page, _ ->
                                    lock.withLock {
                                        page.forEach { files[it.uuid] = it }
                                        maxSeen = seenThrough(maxSeen, page) { it.updatedAt }
                                        report()
                                    }
                                },
                            )
                        }
                    StreamEnd.CUT_SHORT in listOf(foldersEnd.await(), filesEnd.await())
                }
        } catch (e: CursorListingUnavailable) {
            log.info("{}; the poll falls back to the offset listing", e.message)
            return null
        }

        // A poll re-delivers the items of the rewound window; the unchanged folders above them are
        // answered by the engine's rows (archive#936), as on the offset path.
        val page =
            assembleFullDelta(
                files.values.toList(),
                folders.values.toList(),
                advanceCursor(maxSeen, cursor, api.serverNowOrNull()),
                onProgress = onProgress,
                listing = ScanProgress.LISTING_CURSOR,
                tracked = scanContext?.trackedRow,
                fullGather = false,
            )
        if (!cutShort) return page
        log.warn(
            "A sync stream of the poll was cut short by a guard; returning DeltaPage(complete=false). " +
                "The next gather lists the drive instead of trusting the poll.",
        )
        return page.copy(complete = false)
    }

    // The newest updatedAt on [rows], against the newest seen so far — compared lexicographically,
    // as everywhere a cursor is compared: the timestamps are ISO-8601 with the same suffix. A page
    // that names none leaves [seen] as it is.
    private fun <T> seenThrough(
        seen: String?,
        rows: List<T>,
        updatedAt: (T) -> String?,
    ): String? {
        val newest = rows.mapNotNull(updatedAt).maxOrNull() ?: return seen
        return if (seen == null || newest > seen) newest else seen
    }

    // The DeltaPage of a full gather: the folder graph from the folders, a path for every item, the items
    // whose ancestors cannot be resolved dropped and counted. The offset listing and the cursor listing both
    // end here, so the same drive gives the same items whichever of them listed it. [cursor] is what the
    // page hands the engine for the next incremental delta.
    //
    // [ancestorRows] are the trashed folders the listing saw without returning them as items: a path that runs through
    // one is resolved from its row, and only the folders neither the listing nor [ancestorRows] hold are asked for.
    // Those requests are made before any path is built, a few at a time, and reported through [onProgress] (named
    // [listing]) as the step that follows the listing.
    //
    // [tracked] is the engine's state.db (ScanContext.trackedRow), given for an incremental delta: a poll re-delivers the
    // items of the rewound window, and the unchanged folders above them used to be asked for on every poll. Its rows
    // answer the paths too, where [trackedAncestorRows] finds them believable.
    @Suppress("LongParameterList")
    private suspend fun assembleFullDelta(
        allFiles: Collection<InternxtFile>,
        allFolders: Collection<InternxtFolder>,
        cursor: String,
        ancestorRows: Map<String, InternxtFolder> = emptyMap(),
        onProgress: ((ScanProgress) -> Unit)? = null,
        listing: String? = null,
        tracked: ((String) -> CloudItem?)? = null,
        fullGather: Boolean = false,
    ): DeltaPage {
        val assembly =
            FullAssembly(
                allFolders = allFolders,
                fileParents = allFiles.asSequence().map { it.folderUuid },
                gathered = allFiles.size + allFolders.size,
                trackedFiles = allFiles,
                cursor = cursor,
                ancestorRows = ancestorRows,
                onProgress = onProgress,
                listing = listing,
                tracked = tracked,
                fullGather = fullGather,
            )
        assembly.prepare()
        val files = assembly.placeFiles(allFiles)
        val rest = assembly.finish()
        return rest.copy(items = files + rest.items)
    }

    // The work of [assembleFullDelta] in three steps, so that a gather which cannot hold its files can place them a chunk at
    // a time: [prepare] resolves the folders every path runs through (all of them known before the first path is built),
    // [placeFiles] gives a path to the files it is handed and may be called any number of times, and [finish] places the
    // folders and the trashed folders the items hang below and says what the pass left out. The items come out in the order
    // the single call gave them: the files, then the folders, then the trashed folders. [fileParents] are the parent folders
    // of every file the pass will place, [gathered] the number of items it will see; [trackedFiles] are only read when
    // [tracked] is given (an incremental delta, whose files are all in hand).
    @Suppress("LongParameterList", "TooManyFunctions")
    private inner class FullAssembly(
        private val allFolders: Collection<InternxtFolder>,
        private val fileParents: Sequence<String?>,
        private val gathered: Int,
        private val trackedFiles: Collection<InternxtFile>,
        private val cursor: String,
        private val ancestorRows: Map<String, InternxtFolder>,
        private val onProgress: ((ScanProgress) -> Unit)?,
        private val listing: String?,
        private val tracked: ((String) -> CloudItem?)?,
        private val fullGather: Boolean,
    ) {
        private var rootUuid: String = ""

        // Mutable so the self-healing ancestor re-fetch can splice missing
        // ancestors in as it resolves; `associateBy` keeps the last entry per
        // uuid. Fetched ancestors live only in this map (they are unchanged
        // folders, not part of this delta's changed set) so they enable path
        // resolution without being emitted as spurious folder changes.
        private val folderMap = allFolders.associateByTo(mutableMapOf()) { it.uuid }

        // Memoisation shared across the whole pass so a missing ancestor uuid is
        // fetched at most once: a known-gone uuid never re-requested, a fetched
        // one served from folderMap thereafter. Bounds the re-fetch at the count
        // of distinct missing ancestors (not the count of orphaned leaves) —
        // mandatory at ~196k-file / deep-tree scale.
        private val unfetchableAncestors = mutableSetOf<String>()

        // The folders whose meta the server answered with 404 or 410: gone for good, not merely out of reach. A subset of
        // [unfetchableAncestors], filled from the concurrent fetches of resolveAncestors. Nothing is asked twice: both
        // sets are consulted before a request, so a gone folder costs one request however many items hang below it.
        private val goneAncestors: MutableSet<String> = ConcurrentHashMap.newKeySet()

        // The ancestor can't be resolved (404 / 410, any other non-fatal API error): it degrades to null, the items
        // below it are left out and counted, and never throws out of delta(). What the pass says about them depends on
        // why: a folder that is GONE (404 / 410: deleted or purged, it will not come back) leaves them unreachable, and
        // the pass is still complete; any other failure (5xx, a refused request, ...) may be over by the next pass and
        // keeps complete=false. One log line per gone folder at DEBUG, the pass reports them in one line below.
        // A meta the model cannot read is the second kind: asking again gets the same
        // body, so one odd folder (out of the hundreds fetched on a large first
        // scan) is logged once, with the server's request id, and leaves the rest
        // of the pass alone. A transport failure was retried by the call itself.
        // A fetched folder whose meta answers no parent is the top of a tree of its own: the meta is the server's word,
        // where a row of the staging or of state.db says null for a folder below the root as well.
        private val treeTops: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val fetchAncestor: suspend (String) -> InternxtFolder? = { uuid ->
            try {
                api.getFolderMeta(uuid).also { if (it.parentUuid == null && uuid != rootUuid) treeTops.add(uuid) }
            } catch (e: InternxtApiException) {
                if (e.statusCode in PERMANENT_ABSENCE_STATUSES) {
                    goneAncestors.add(uuid)
                    log.debug("Ancestor folder {} is gone ({}); its descendants are unreachable this pass", uuid, e.statusCode)
                } else {
                    log.warn("Ancestor folder {} unfetchable ({}); dropping its descendants this pass", uuid, e.statusCode)
                }
                null
            } catch (e: InternxtUnreadableResponse) {
                log.warn("{} (server request id {}); dropping its descendants this pass", e.message, e.requestId ?: "not sent")
                null
            }
        }
        private var nameDecryptor: String? = null
        private var rowsSeen: Map<String, InternxtFolder> = emptyMap()
        private lateinit var trashed: TrashChains

        // Why each item left out was left out: a folder above it is gone for good (the item is unreachable), or one could
        // not be asked and the item may be placeable next pass (a drop that keeps the pass incomplete).
        private val unreachable = UnreachableItems()
        private var filesDropped = 0
        private var foldersDropped = 0
        private val outsideIds = HashSet<String>()
        private val censusFiles = HashMap<String, Int>()
        private val censusFolders = HashMap<String, Int>()
        private var inTrash = 0
        private var foldersInTrash = 0

        suspend fun prepare() {
            rootUuid = authService.getValidCredentials().rootFolderId
            // The folders this pass names were renamed, moved or trashed (or are new): what the folder cache holds for them is
            // from before. Another device's `mv docs old-docs; mkdir docs` must not keep sending uploads to the old folder.
            folderCache.invalidateFolders(allFolders.map { it.uuid })
            allFolders.filter { it.updatedAt != null && it.parentUuid == null && it.uuid != rootUuid }.mapTo(treeTops) { it.uuid }
            // Folders last so that path collisions in gatherRemoteChanges resolve in favour of folders.
            // toDeltaCloudItem returns null when an ancestor uuid is absent from folderMap AND
            // unfetchable (the /folders delta returned a changed leaf without its unchanged
            // ancestors and the ancestor is gone or could not be asked). A drop below a folder that is
            // gone is reported as unreachable; any other drop is counted so `complete` reflects
            // the residual gap and the engine skips detectMissingAfterFullSync + leaves the
            // cursor un-advanced.
            nameDecryptor = mnemonicOrNull()
            // The folders every path runs through, all found before the first path is built: the listing and the
            // ancestor-only rows answer most, the rest is fetched a few at a time instead of one request after the other
            // as the first item below each of them was met.
            // The folders above the items that the engine tracks and nothing in this pass contradicts are rows like the ones
            // the listing saw: they answer the paths and are not asked for.
            val trackedRows = tracked?.let { trackedAncestorRows(trackedFiles, allFolders, folderMap, ancestorRows, rootUuid, it) }.orEmpty()
            rowsSeen = if (trackedRows.isEmpty()) ancestorRows else trackedRows + ancestorRows
            val resolution =
                resolveAncestors(
                    parents = fileParents + allFolders.asSequence().map { it.parentUuid },
                    folderMap = folderMap,
                    ancestors = rowsSeen,
                    rootUuid = rootUuid,
                    unfetchable = unfetchableAncestors,
                    fetchFolder = fetchAncestor,
                ) { done, total ->
                    onProgress?.invoke(ScanProgress(items = gathered, listing = listing, pathsDone = done, pathsTotal = total))
                }
            if (resolution.needed > 0) {
                log.info(
                    "Resolved the paths of {} items: {} folders were not in the listing, {} came from rows it saw ({} of them rows state.db tracks), {} were fetched, {} could not be",
                    gathered,
                    resolution.needed,
                    resolution.fromRows,
                    trackedRows.keys.count { it in folderMap },
                    resolution.fetched,
                    resolution.unfetchable,
                )
            }
            trashed = TrashChains(folderMap, rootUuid)
        }

        /** The items of [files], each with its path; the ones whose folders cannot be resolved are left out and counted. */
        suspend fun placeFiles(files: Collection<InternxtFile>): List<CloudItem> {
            val raw = files.map { it.toDeltaCloudItem(folderMap, rootUuid, unfetchableAncestors, fetchAncestor, rowsSeen, nameDecryptor) }
            files.zip(raw).forEach { (file, item) ->
                if (item != null) return@forEach
                val gone = goneAncestorOf(file.folderUuid, folderMap, rowsSeen, rootUuid, unfetchableAncestors, goneAncestors)
                if (gone != null) unreachable.add(file.uuid, gone, isFolder = false) else filesDropped++
            }
            return keepInDrive(files.zip(raw).mapNotNull { (file, item) -> item?.markIfTrashed(trashed, file.folderUuid) })
        }

        // One more item below a tree of its own, counted under the top of that tree.
        private fun count(item: CloudItem) {
            val top = if (item.id in treeTops) item.id else treeTopOf(item.parentId, folderMap, rootUuid, treeTops) ?: return
            (if (item.isFolder) censusFolders else censusFiles).merge(top, 1, Int::plus)
        }

        /** The folders, then the trashed folders the placed items hang below, and what the whole pass left out. */
        suspend fun finish(): DeltaPage {
            val rawFolders =
                allFolders.map { it.toDeltaCloudItem(folderMap, rootUuid, unfetchableAncestors, fetchAncestor, rowsSeen, nameDecryptor) }
            allFolders.zip(rawFolders).forEach { (folder, item) ->
                if (item != null) return@forEach
                val gone = goneAncestorOf(folder.parentUuid, folderMap, rowsSeen, rootUuid, unfetchableAncestors, goneAncestors)
                if (gone != null) unreachable.add(folder.uuid, gone, isFolder = true) else foldersDropped++
            }
            val placed =
                allFolders.zip(rawFolders).mapNotNull { (folder, item) -> item?.markIfTrashed(trashed, folder.parentUuid) } +
                    trashedFolderItems(trashed, allFolders.mapTo(HashSet()) { it.uuid }) { folder ->
                        folder.toDeltaCloudItem(folderMap, rootUuid, unfetchableAncestors, fetchAncestor, rowsSeen, nameDecryptor)
                    }
            val items = keepInDrive(placed)
            if (treeTops.isNotEmpty()) {
                log.info(
                    "Internxt delta gather: {} item(s) sit below {} folder(s) outside the drive (trees of their own, such as device backups); " +
                        "they are left out and never written to",
                    outsideIds.size,
                    treeTops.size,
                )
            }
            if (fullGather) logTreeTops(censusFiles, censusFolders, treeTops)
            if (inTrash > 0) {
                log.info("{} items sit in the trash ({} of them trashed folders); they are reported as trashed", inTrash, foldersInTrash)
            }
            val skipped = foldersSkipped.get()
            if (skipped > 0) {
                log.warn(
                    "Internxt delta gather skipped {} folder(s) due to 500/503; " +
                        "returning DeltaPage(complete=false). detectMissingAfterFullSync will be suppressed.",
                    skipped,
                )
            }
            val ancestorDrops = filesDropped + foldersDropped
            if (ancestorDrops > 0) {
                log.warn(
                    "Internxt delta gather dropped {} item(s) ({} file(s), {} folder(s)) " +
                        "whose ancestor folder could not be resolved even after re-fetch " +
                        "(unavailable / unreadable); returning DeltaPage(complete=false). " +
                        "Dropped items recover when their ancestors answer again or via --reset.",
                    ancestorDrops,
                    filesDropped,
                    foldersDropped,
                )
            }
            // One line for the unreachable items, not one per gone folder. They are not deleted: the server still lists them,
            // under folders it no longer has. The pass is complete without them, and the engine leaves their rows as they are.
            if (unreachable.count > 0) {
                log.info(
                    "Internxt delta gather: {} item(s) ({} file(s), {} folder(s)) sit below {} folder(s) that no longer exist " +
                        "(HTTP 404 / 410); they are unreachable, not deleted, and the pass is complete without them.",
                    unreachable.count,
                    unreachable.files,
                    unreachable.folders,
                    unreachable.goneFolders.size,
                )
            }
            return DeltaPage(
                items = items,
                cursor = cursor,
                hasMore = false,
                complete = skipped == 0 && ancestorDrops == 0,
                unreachableIds = if (outsideIds.isEmpty()) unreachable.ids else unreachable.ids + outsideIds,
            )
        }

        // The items of a tree of its own got a path at the sync root (`/<device>/...`), where they would be ordinary
        // writable rows and merge by name into a real top-level folder. They are left out and named unreachable: the
        // engine keeps the rows it has, deletes nothing locally, and creates none.
        private fun keepInDrive(placed: List<CloudItem>): List<CloudItem> {
            val outside = outsideDriveItems(placed, treeTops, folderMap, rootUuid)
            if (outside.isNotEmpty()) {
                outsideIds.addAll(outside)
                if (fullGather) placed.filter { it.id in outside }.forEach(::count)
            }
            val items = if (outside.isEmpty()) placed else placed.filter { it.id !in outside }
            for (item in items) {
                if (!item.trashed) continue
                inTrash++
                if (item.isFolder) foldersInTrash++
            }
            return items
        }
    }

    // The ids of the [items] that are the top of a tree of its own ([treeTops]) or hang below one.
    private fun outsideDriveItems(
        items: List<CloudItem>,
        treeTops: Set<String>,
        folderMap: Map<String, InternxtFolder>,
        rootUuid: String,
    ): Set<String> {
        if (treeTops.isEmpty()) return emptySet()
        outsideDriveFolders.addAll(treeTops)
        return items.asSequence().filter { it.id in treeTops || treeTopOf(it.parentId, folderMap, rootUuid, treeTops) != null }.mapTo(HashSet()) { it.id }
    }

    // One line per full gather: how many of the items outside the drive hang below each tree of its own ([files] and
    // [folders], by the top folder's uuid; never a name or a path, the line ends up in logs that get shared), and which of
    // those tops are backup devices of the account (those uuids, the count, and how many devices the account lists).
    // Measured only: what the listing returns does not change. The device list is asked once, and only when there is a top
    // to compare; a failure to read it is reported in the line and never fails the gather.
    private suspend fun logTreeTops(
        files: Map<String, Int>,
        folders: Map<String, Int>,
        treeTops: Set<String>,
    ) {
        if (treeTops.isEmpty()) {
            log.info("Internxt full gather: no item hangs below a folder without a parent other than the drive root")
            return
        }
        val ordered =
            treeTops.sortedWith(compareByDescending<String> { (files[it] ?: 0) + (folders[it] ?: 0) }.thenBy { it })
        val tops = ordered.joinToString(", ") { "$it: ${files[it] ?: 0} files, ${folders[it] ?: 0} folders" }
        val devices =
            try {
                val deviceFolders = api.listBackupDeviceFolders()
                if (deviceFolders == null) {
                    "backups are not enabled for the account"
                } else {
                    val matched = ordered.filter { it in deviceFolders }
                    val summary =
                        "${matched.size} of the tops are among the ${deviceFolders.size} backup device folder(s) the account lists"
                    if (matched.isEmpty()) summary else "$summary: ${matched.joinToString(", ")}"
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                "the backup devices could not be listed (${(e as? InternxtApiException)?.statusCode?.let { "HTTP $it" } ?: e.javaClass.simpleName})"
            }
        log.info(
            "Internxt full gather: {} file(s) and {} folder(s) hang below {} folder(s) without a parent other than the drive root " +
                "[{}]; {}",
            files.values.sum(),
            folders.values.sum(),
            treeTops.size,
            tops,
            devices,
        )
    }

    // The items of a pass that sit below a folder which is gone for good: their remote ids, how many of them are files
    // and folders, and the distinct gone folders they hang below.
    private class UnreachableItems {
        val ids = HashSet<String>()
        val goneFolders = HashSet<String>()
        var files = 0
        var folders = 0
        val count: Int get() = files + folders

        fun add(
            uuid: String,
            goneFolder: String,
            isFolder: Boolean,
        ) {
            if (ids.add(uuid)) {
                if (isFolder) folders++ else files++
            }
            goneFolders.add(goneFolder)
        }
    }

    // A live item whose parent folder is in the trash (see [TrashChains]) keeps the path of the provider's own tree and is
    // marked as trashed; the engine puts it under the trash namespace. A tombstone stays one: a deleted item is gone, not
    // trashed.
    private fun CloudItem.markIfTrashed(
        chains: TrashChains,
        parentUuid: String?,
    ): CloudItem = if (!deleted && chains.contains(parentUuid)) copy(trashed = true) else this

    // The trashed folders that a live item hangs below, as folder items of the trash namespace: without them the way to
    // those items would not exist as folders, and a listing of the namespace could not reach them. A folder the page
    // already carries (the tombstone of an incremental delta, [inPage]) is not repeated: it stays the deletion it is.
    // [itemOf] builds the item of a folder, or null when its path cannot be resolved.
    private suspend fun trashedFolderItems(
        chains: TrashChains,
        inPage: Set<String>,
        itemOf: suspend (InternxtFolder) -> CloudItem?,
    ): List<CloudItem> =
        chains.trashedFolders
            .filter { it.uuid !in inPage }
            .mapNotNull { folder -> itemOf(folder)?.copy(deleted = false, trashed = true) }

    // An offset listing's marker or rows, a damaged marker, a cursor of another status, a marker whose rows are
    // gone: none of it is the cursor listing's to resume. The engine clears the staging when the enumeration
    // completes.
    private fun logFreshCursorStart(
        marker: String?,
        stagedRows: Int,
    ) {
        if (marker != null || stagedRows > 0) {
            log.info(
                "The stored scan (marker {}, {} staged rows) is not one the cursor listing can resume; listing from the start",
                marker?.take(60),
                stagedRows,
            )
        } else {
            log.info("Listing the whole drive by cursor: folders and files, one request at a time each")
        }
    }

    // ---- a full gather that hands its files over from the staging (gkrost/unidrive-cli#35) ----
    //
    // The cursor listing used to keep every file of the account in a map until the last page, and then built the item of every
    // file at once: on an account of 245k items the whole remote tree was in the heap twice over. When the engine gives the
    // gather a staging it can read back (ScanContext.stagedPage), the gather keeps only what the paths are built from: the
    // folders (a tenth of an account), the set of folders the files hang below, and the few rows a page told to forget. Each
    // file page is staged in its own transaction with the cursors of both streams, as before, and then released. Once both
    // streams are done, the files are read back from the staging a page at a time, given their paths from the folders in hand
    // and handed to the engine as pages of their own (hasMore, with a continuation cursor); the last page carries the folders.
    // A crash or a cancel at any point leaves the staged pages and the cursors that stand for them, so the next gather resumes
    // from the last page that was stored and hands the files over again; no row is skipped, and one that is staged twice
    // (it changed while the listing ran) is staged once, the later version winning, as the map did.

    /** What the cursor listing keeps of the files of a gather whose files wait in the staging. */
    private class GatherStream(
        val id: String,
        // True for a resumed listing: rows an earlier version staged carry no payload and belong to the gather they were
        // resumed into. Rows with a payload are taken when they name this gather.
        private val legacyRows: Boolean,
    ) {
        /** The folders the staged files hang below, as the listing named them (null: no folder named). */
        val parents = HashSet<String?>()

        // Files an unfiltered page delivered as no longer live: their staged rows are not handed over. A row that is live again later leaves.
        private val gone = HashSet<String>()

        /** The rows staged so far; a row delivered twice is counted twice, so it is a progress figure and no more. */
        var staged = 0
            private set

        fun pageIn(
            live: List<InternxtFile>,
            notLive: List<InternxtFile>,
        ) {
            for (f in notLive) gone.add(f.uuid)
            for (f in live) {
                gone.remove(f.uuid)
                parents.add(f.folderUuid)
            }
            staged += live.size
        }

        fun resumed(row: CloudItem) {
            parents.add(row.parentId)
            staged++
        }

        /** The file of [row] to hand over, or null for a row that is no file of this gather. */
        fun fileOf(row: CloudItem): InternxtFile? {
            if (row.isFolder || !isCursorStaged(row) || row.id in gone) return null
            val payload = stagedFilePayloadOf(row)
            return when {
                payload != null -> payload.file.takeIf { payload.gather == id }
                legacyRows -> row.toResumedFile()
                else -> null
            }
        }
    }

    /** A finished listing whose files are handed over in chunks: the paths it builds them with, and where the hand-over stands. */
    private inner class GatherHandover(
        val stream: GatherStream,
        val assembly: FullAssembly,
        val stagedPage: (afterId: String?, limit: Int) -> List<CloudItem>,
        val cutShort: Boolean,
    ) {
        val id: String = java.util.UUID.randomUUID().toString()
        var after: String? = null
    }

    // The rows of a resumed cursor listing back into the gather: the folders and the ancestor-only records into their maps, the
    // files into [files], or, for a gather that hands its files over from the staging, only as far as [stream] needs them.
    private fun resumeFrom(
        resume: CursorResume,
        stream: GatherStream?,
        folders: MutableMap<String, InternxtFolder>,
        files: MutableMap<String, InternxtFile>,
        ancestors: MutableMap<String, InternxtFolder>,
    ) {
        for (row in resume.rows) {
            when {
                row.isFolder -> folders[row.id] = row.toResumedFolder()
                stream != null -> stream.resumed(row)
                else -> files[row.id] = row.toResumedFile()
            }
        }
        for (row in resume.ancestors) ancestors[row.id] = row.toResumedAncestor()
    }

    // A page of the files stream into the gather: into [files], or, when the files are handed over from the staging, only as far as [stream] needs.
    private fun keepFilePage(
        stream: GatherStream?,
        files: MutableMap<String, InternxtFile>,
        live: List<InternxtFile>,
        gone: List<InternxtFile>,
    ) {
        if (stream != null) {
            stream.pageIn(live, gone)
            return
        }
        for (f in gone) files.remove(f.uuid)
        for (f in live) files[f.uuid] = f
    }

    // The files wait in the staging; their paths are built a chunk at a time, from the folders in hand.
    @Suppress("LongParameterList")
    private suspend fun startHandover(
        stream: GatherStream,
        readBack: (afterId: String?, limit: Int) -> List<CloudItem>,
        folders: List<InternxtFolder>,
        ancestors: Map<String, InternxtFolder>,
        startedAt: String,
        onProgress: ((ScanProgress) -> Unit)?,
        cutShort: Boolean,
    ): DeltaPage {
        val assembly =
            FullAssembly(
                allFolders = folders,
                fileParents = stream.parents.asSequence(),
                gathered = folders.size + stream.staged,
                trackedFiles = emptyList(),
                cursor = startedAt,
                ancestorRows = ancestors,
                onProgress = onProgress,
                listing = ScanProgress.LISTING_CURSOR,
                tracked = null,
                fullGather = true,
            )
        assembly.prepare()
        val handover = GatherHandover(stream, assembly, readBack, cutShort)
        gatherHandover.set(handover)
        return nextGatherChunk(handover)
    }

    // The staged row of a live file of the cursor listing; a gather that hands its files over from the staging adds the file.
    private fun InternxtFile.toStagedFileRow(
        rootUuid: String,
        stream: GatherStream?,
    ): CloudItem =
        toStagedCloudItem(rootUuid).copy(
            hash = stagedFileTag(CURSOR_FILE_PREFIX, this),
            version = stream?.let { stagedFilePayload(it.id, this) },
        )

    // The next chunk of the hand-over: the files of the next page of the staging, with their paths; the last chunk also
    // carries the folders and says what the whole gather left out. A page of the staging that holds no file (folder rows
    // sort among them by uuid) is passed over, so a chunk is never empty before the last.
    private suspend fun nextGatherChunk(handover: GatherHandover): DeltaPage {
        while (true) {
            val rows = withContext(Dispatchers.IO) { handover.stagedPage(handover.after, gatherChunkRows) }
            if (rows.isNotEmpty()) handover.after = rows.last().id
            val last = rows.size < gatherChunkRows
            val files = rows.mapNotNull(handover.stream::fileOf)
            if (files.isEmpty() && !last) continue
            if (files.size > gatherPeakFileRows) gatherPeakFileRows = files.size
            val items = handover.assembly.placeFiles(files)
            if (!last) return DeltaPage(items = items, cursor = GATHER_CURSOR_PREFIX + handover.id, hasMore = true)
            val rest = handover.assembly.finish()
            gatherHandover.compareAndSet(handover, null)
            val page = rest.copy(items = items + rest.items)
            if (!handover.cutShort) return page
            log.warn(
                "A cursor stream was cut short by a guard; returning DeltaPage(complete=false). " +
                    "detectMissingAfterFullSync will be suppressed.",
            )
            return page.copy(complete = false)
        }
    }

    // The engine asks for the next chunk of a hand-over by the cursor the last one named. A provider that does not hold that
    // hand-over (it was rebuilt, or another full gather took its place) cannot continue it: the drive is listed again, which
    // resumes from the stored cursors and takes the files from the staging as before.
    private suspend fun continueGather(
        cursor: String,
        onPageProgress: ((itemsSoFar: Int) -> Unit)?,
        scanContext: ScanContext?,
    ): DeltaPage {
        val handover = gatherHandover.get()
        if (handover != null && cursor == GATHER_CURSOR_PREFIX + handover.id) return nextGatherChunk(handover)
        log.warn("The continuation {} of a full gather is not one this provider holds; listing the drive again", cursor)
        return delta(null, onPageProgress, scanContext)
    }

    private val gatherHandover = java.util.concurrent.atomic.AtomicReference<GatherHandover?>(null)

    // How many staged rows are read back for one chunk of a hand-over.
    @Volatile
    internal var gatherChunkRows: Int = InternxtConfig.LISTING_PAGE_SIZE

    // The most files one chunk of a hand-over has held, since this provider was made: what the bound on the gather's memory is
    // measured by (a test reads it).
    @Volatile
    internal var gatherPeakFileRows: Int = 0

    // The full enumeration of the whole drive by the cursor listings. The folders and the files stream run
    // at once, each strictly one request at a time (a cursor names the page before it). Every page is staged
    // with a marker that holds both cursors, so a failed or killed attempt continues from the cursors and not
    // from the start. Items are deduplicated by uuid, the later version winning: the listing is a keyset on
    // (updatedAt, uuid), so an item that changes while the listing runs turns up again further on.
    //
    // Returns null when the server has no cursor endpoints (404, 405 or 501 on the first page of a stream):
    // the caller carries on with the offset listing. A server-side failure — the 5xx family (live, the
    // gateway answers Cloudflare 524 at its own cap of about 125 s when a cursor page runs long) or a
    // connection that failed or was cut (surfaced as status 0 / 503) — hands the gather to the folder tree
    // walk, the one listing that still finishes on a large drive, and skips the account-wide listings for
    // the R3 TTL: on the live account the folder stream hits the same wall at the same page every time,
    // and re-buying that attempt on every gather is minutes of load the walk replaces anyway. Both
    // handovers leave the marker and the staged rows in place, so the next gather resumes from the cursors.
    // A client error (a 404 on a later page, 401, 429) ends the attempt with an exception, marker intact:
    // it is a request-shape problem a walk cannot fix.
    //
    // The cursor of the page is the instant the enumeration began (kept in the marker across a resume), not
    // the newest updatedAt seen: the two streams end at different times, so the next incremental delta has to
    // ask for everything since the beginning. A stream that a guard cut short makes the page incomplete.
    //
    // A stream that failed twice in a row on the same cursor with the same timeout-like failure (HTTP 408 or
    // 5xx) does not resume that cursor a third time: it continues from an updatedAt a little before the cursor
    // without the status filter (see pageStreamWithFallback). Its pages hold rows of every status; the ones that
    // are not live are dropped here, and a row that turns up again as not live also leaves the rows an earlier
    // page stored. A staged copy of such a row only comes back after a restart, and the next incremental delta,
    // which asks for everything since the beginning of the enumeration, reports it as the tombstone it is.
    // The folder rows among them that are trashed are not thrown away: they are kept, and staged, as ancestor-only
    // records (never items) that answer the paths running through them, see assembleFullDelta.
    //
    // Scoped profiles do not come here: their cost is bounded by the scope (the walk), the cursor listing
    // always walks the whole account and cannot be narrowed to a subtree.
    private suspend fun cursorFullDelta(
        onPageProgress: ((itemsSoFar: Int) -> Unit)?,
        scanContext: ScanContext?,
        onProgress: ((ScanProgress) -> Unit)?,
    ): DeltaPage? {
        val begun = System.nanoTime()
        val rootUuid = authService.getValidCredentials().rootFolderId
        val staged = scanContext?.resumedItems.orEmpty()
        val resume = cursorResumeOf(scanContext?.resumeMarker, staged)
        val startedAt = resume?.marker?.startedAt ?: serverNow().toString()
        var foldersAt = resume?.marker?.folders ?: StreamPosition()
        var filesAt = resume?.marker?.files ?: StreamPosition()
        val folders = LinkedHashMap<String, InternxtFolder>()
        val files = LinkedHashMap<String, InternxtFile>()
        // With a staging to read back from the files are not held, see [GatherHandover]; without one they stay in [files].
        val readBack = scanContext?.stagedPage
        val stream = if (readBack != null) GatherStream(startedAt, legacyRows = resume != null) else null
        // The trashed folders an unfiltered page returned: no items, but live items can hang below them, so their rows
        // answer the paths that run through them. Staged with the live rows, so a restart keeps them.
        val ancestors = LinkedHashMap<String, InternxtFolder>()
        if (resume == null) {
            logFreshCursorStart(scanContext?.resumeMarker, staged.size)
        } else {
            resumeFrom(resume, stream, folders, files, ancestors)
            log.info(
                "Resuming the cursor listing begun {}: folders {}, files {}, {} staged rows, {} ancestor-only rows",
                startedAt,
                describeResume(foldersAt),
                describeResume(filesAt),
                folders.size + (stream?.staged ?: files.size),
                ancestors.size,
            )
        }

        val persist = scanContext?.persistPage
        val lock = Mutex()
        val heartbeat = onPageProgress?.let { cb -> ScanHeartbeat(cb) }

        // Read under the lock, after every page and at the start: the count rises with every page and the
        // reports arrive in the order of the counts. Since the fallback (archive#879) it can also drop, by the rare
        // row an unfiltered page delivers as no longer live against what the filtered pages stored — a few
        // rows against hundreds of thousands, and the heartbeat's wall-clock half keeps reporting through it.
        fun report() {
            val total = folders.size + (stream?.staged ?: files.size)
            heartbeat?.tick(total)
            onProgress?.invoke(ScanProgress(items = total, listing = ScanProgress.LISTING_CURSOR))
        }
        report()

        val cutShort =
            try {
                coroutineScope {
                    val foldersEnd =
                        async {
                            pageStreamWithFallback(
                                stream = "folders",
                                start = foldersAt,
                                fetch = { r -> api.getFoldersSync(updatedAt = r.updatedAt, cursor = r.cursor, status = r.status) },
                                onPage = { page, at ->
                                    lock.withLock {
                                        val rows = keepFolderPage(page, at.unfiltered, folders, ancestors, rootUuid)
                                        foldersAt = at
                                        persist?.invoke(rows, cursorMarker(startedAt, foldersAt, filesAt))
                                        report()
                                    }
                                },
                                onPosition = { at ->
                                    lock.withLock {
                                        foldersAt = at
                                        persist?.invoke(emptyList(), cursorMarker(startedAt, foldersAt, filesAt))
                                    }
                                },
                            )
                        }
                    val filesEnd =
                        async {
                            pageStreamWithFallback(
                                stream = "files",
                                start = filesAt,
                                fetch = { r -> api.getFilesSync(updatedAt = r.updatedAt, cursor = r.cursor, status = r.status) },
                                onPage = { page, at ->
                                    lock.withLock {
                                        val (live, gone) = page.partitionLive(at.unfiltered) { it.isLive() }
                                        keepFilePage(stream, files, live, gone)
                                        filesAt = at
                                        persist?.invoke(live.map { it.toStagedFileRow(rootUuid, stream) }, cursorMarker(startedAt, foldersAt, filesAt))
                                        report()
                                    }
                                },
                                onPosition = { at ->
                                    lock.withLock {
                                        filesAt = at
                                        persist?.invoke(emptyList(), cursorMarker(startedAt, foldersAt, filesAt))
                                    }
                                },
                            )
                        }
                    StreamEnd.CUT_SHORT in listOf(foldersEnd.await(), filesEnd.await())
                }
            } catch (e: CursorListingUnavailable) {
                log.warn("{}; falling back to the offset listing", e.message)
                return null
            } catch (e: InternxtApiException) {
                if (!isServerSideListingFailure(e.statusCode)) throw e
                heavyListingsUnavailableUntil.set(Instant.now().plus(LISTING_UNAVAILABLE_TTL))
                log.warn(
                    "The cursor listing failed server-side ({}: {}), falling back to the folder tree walk; " +
                        "skipping the account-wide listings until {}",
                    e.statusCode,
                    e.message,
                    heavyListingsUnavailableUntil.get(),
                )
                return scopedFullDelta(
                    scanContext?.scopeRoots.orEmpty().ifEmpty { listOf("/") },
                    onPageProgress,
                    onProgress,
                    folders.size + (stream?.staged ?: files.size),
                )
            }

        log.info(
            "Cursor listing finished in {} s: {} folders, {} files",
            (System.nanoTime() - begun) / 1_000_000_000L,
            folders.size,
            stream?.staged ?: files.size,
        )
        if (stream != null && readBack != null) {
            return startHandover(stream, readBack, folders.values.toList(), ancestors, startedAt, onProgress, cutShort)
        }
        val page = assembleFullDelta(files.values.toList(), folders.values.toList(), startedAt, ancestors, onProgress, ScanProgress.LISTING_CURSOR, fullGather = true)
        if (!cutShort) return page
        log.warn(
            "A cursor stream was cut short by a guard; returning DeltaPage(complete=false). " +
                "detectMissingAfterFullSync will be suppressed.",
        )
        return page.copy(complete = false)
    }

    // Cursor = the walk's start time, not the newest in-scope updatedAt: the next
    // incremental delta then asks for changes since the walk began. The newest
    // in-scope timestamp can be years old, which would make that first delta list
    // the whole account.
    //
    // [progressBaseline] seeds the heartbeat with the items the account-wide
    // listings had already counted before the walk took over, so the reported
    // total never jumps down mid-gather — the up-then-down movement that has
    // alarmed users before (208k → 261k → 218k). Zero on the direct scoped-full
    // path, where nothing was counted before the walk.
    //
    // [onProgress] is not seeded: it names the listing it reports on, and the items it
    // reports are the walk's own, the ones the gather keeps.
    // archive#523: the combined folder-content call dies server-side for a folder whose single body
    // the gateway cannot deliver (a 29k-file folder needs ~105 s in one 30 MB body; the gateway
    // cuts its origin at ~125 s, so beyond ~35k files the call never returns). On a server-side
    // failure (any 5xx; a gateway 524 arrives here as a synthetic 503) the folder is re-listed
    // through the paged per-folder endpoints (the cursor ones; the offset ones when those answer
    // 404/405, archive#647) instead of being skipped: every file the paged
    // listing returns is one the walk would otherwise have dropped, and an incremental poll
    // cannot pick them up later because their updatedAt is old. A fallback page that itself
    // fails propagates, so the callers' existing skip-on-500/503 still counts the folder and
    // keeps the gather incomplete — the honest signal is unchanged.
    private suspend fun folderContentsWithPerPageFallback(folderUuid: String): FolderContentResponse =
        try {
            api.getFolderContents(folderUuid)
        } catch (e: InternxtApiException) {
            if (e.statusCode < 500 && e.statusCode != 0) throw e
            log.warn(
                "Folder {} content call failed ({}); listing it through the paginated per-folder endpoints",
                folderUuid,
                e.statusCode,
            )
            api.getFolderContentsPaged(folderUuid)
        }

    private suspend fun scopedFullDelta(
        scopeRoots: List<String>,
        onPageProgress: ((itemsSoFar: Int) -> Unit)?,
        onProgress: ((ScanProgress) -> Unit)?,
        progressBaseline: Int = 0,
        // Only a walk that is the first strategy tried gets the scan context: a walk that took over from a
        // listing which already stored progress must leave that progress and its marker alone.
        scanContext: ScanContext? = null,
    ): DeltaPage {
        val resume = treeResumeOf(scanContext?.resumeMarker, scanContext?.resumedItems.orEmpty(), scopeRoots)
        // The cursor of the page is the instant the walk began; a resumed walk keeps the first attempt's instant,
        // so the next incremental delta asks for everything since the beginning, the outage included.
        val startedAt = resume?.marker?.startedAt?.let(Instant::parse) ?: serverNow()
        if (resume != null) {
            log.info(
                "Resuming the folder walk begun {}: {} folders listed, {} staged rows",
                startedAt,
                resume.listedFolders.size,
                resume.files.size + resume.folders.size,
            )
        }
        val heartbeat = onPageProgress?.let { cb -> ScanHeartbeat(cb) }
        val rootUuid = authService.getValidCredentials().rootFolderId
        val persist = scanContext?.persistPage
        val inventory =
            collectScopedInventoryImpl(
                getContents = ::folderContentsWithPerPageFallback,
                driveRootUuid = rootUuid,
                scopeRoots = scopeRoots,
                scanned = foldersScanned,
                skipped = foldersSkipped,
                log = log,
                onProgress = { progress ->
                    heartbeat?.tick(progress.items + progressBaseline)
                    onProgress?.invoke(progress)
                },
                resume = resume,
                tracked = scanContext?.trackedRow,
                persist =
                    persist?.let { store ->
                        { rows, startsDone -> store(rows, treeMarker(startedAt.toString(), scopeRoots, startsDone)) }
                    },
            )
        val skipped = foldersSkipped.get()
        if (skipped > 0) {
            log.warn(
                "Internxt scoped gather skipped {} folder(s) due to 500/503; returning DeltaPage(complete=false).",
                skipped,
            )
        }
        folderCache.invalidateFolders(inventory.folders.map { it.uuid }) // see assembleFullDelta
        return scopedDeltaPage(inventory, rootUuid, startedAt.toString(), skipped)
    }

    // Coalesces files + folders page arrivals into a single [persistPage] call
    // per page boundary so the checkpoint marker advances atomically and the
    // staged-row INSERTs ride a single SQLite transaction. Stream offsets are
    // tracked independently so a resume from the persisted marker lands each
    // stream at the right position.
    //
    // The class is stateful and the two notify methods are called from
    // concurrent coroutines, so writes go through a [kotlinx.coroutines.sync.Mutex]
    // to serialise the SQL transactions — concurrent persistPage calls on the
    // same DB connection would deadlock SQLite's per-connection serialised
    // write model.
    private class PageBoundaryPersister(
        private val persistPage: suspend (items: List<org.krost.unidrive.CloudItem>, marker: String) -> Unit,
        private val creds: org.krost.unidrive.internxt.model.InternxtCredentials,
        nextFilesOffset: Int,
        nextFoldersOffset: Int,
    ) {
        private val mutex = kotlinx.coroutines.sync.Mutex()

        @Volatile
        private var filesOffset: Int = nextFilesOffset

        @Volatile
        private var foldersOffset: Int = nextFoldersOffset

        suspend fun notifyFilesPage(
            page: List<InternxtFile>,
            newOffset: Int,
        ) {
            mutex.lock()
            try {
                filesOffset = newOffset
                val items = page.map { it.toStagedCloudItem(creds.rootFolderId).copy(hash = stagedFileTag(OFFSET_FILE_PREFIX, it)) }
                persistPage(items, buildMarker(filesOffset, foldersOffset))
            } finally {
                mutex.unlock()
            }
        }

        suspend fun notifyFoldersPage(
            page: List<InternxtFolder>,
            newOffset: Int,
        ) {
            mutex.lock()
            try {
                foldersOffset = newOffset
                val items = page.map { it.toStagedCloudItem(creds.rootFolderId) }
                persistPage(items, buildMarker(filesOffset, foldersOffset))
            } finally {
                mutex.unlock()
            }
        }
    }

    // Two-wide speculative page fetcher used by [delta]. Keeps up to 2 in-flight
    // [fetchPage] calls; the listing ends on an empty page, and any speculative
    // siblings still in flight are cancelled rather than waited on — a wasted
    // fetch costs a few hundred ms (measured p50 ~0.3s, archive#392 2026-09-29), far
    // cheaper than one extra round-trip's wall-clock at p99 (~2-4s). Pages are
    // appended in offset order so the resulting list matches the sequential walk.
    //
    // The next page begins where the last batch ended: the offset advances by
    // the rows actually served, not by the limit, so a server that serves fewer
    // rows than the limit asks (a clamp below [limit]) cannot open a gap. Only
    // an empty page ends the listing — a short one says nothing about the end.
    // The second seed and every top-up beyond a full page are speculative (they
    // assume the page before them was full); when a batch comes back short, the
    // boundary moves and the speculative page aimed past it is cancelled and
    // re-seeded from the real boundary (archive#312).
    //
    // [startOffset] supports the resumable-scan handshake: a resumed scan
    // begins paginating from the persisted boundary rather than offset 0.
    //
    // [onPage], when supplied, fires once per successfully-fetched page with
    // the page contents and the offset the NEXT page would start at. Resumable-
    // scan staging hooks here so each page is durably persisted before the
    // pipeline tops up.
    //
    // [runningCount] is incremented as pages arrive; [combinedTotal] reads it
    // alongside the sibling stream's counter so the heartbeat fires with a
    // monotonic file+folder total without cross-coroutine list synchronization
    // on the merge path. [reportProgress] fires after every page, unthrottled.
    private suspend fun <T> speculativeFetchPages(
        limit: Int,
        runningCount: AtomicInteger,
        heartbeat: ScanHeartbeat?,
        combinedTotal: () -> Int,
        reportProgress: (() -> Unit)?,
        label: String,
        startOffset: Int = 0,
        fetchPage: suspend (offset: Int) -> List<T>,
        onPage: (suspend (page: List<T>, nextOffset: Int) -> Unit)? = null,
    ): List<T> =
        coroutineScope {
            val accumulated = mutableListOf<T>()
            val pipeline: ArrayDeque<Pair<Int, Deferred<List<T>>>> = ArrayDeque()
            var nextOffset = startOffset
            // Seed the pipeline with up to 2 in-flight page fetches. The second
            // is speculative: it assumes a full first page.
            repeat(2) {
                val offset = nextOffset
                pipeline.addLast(offset to async { fetchPage(offset) })
                nextOffset += limit
            }
            while (pipeline.isNotEmpty()) {
                val (pageOffset, deferred) = pipeline.removeFirst()
                val batch = deferred.await()
                accumulated.addAll(batch)
                runningCount.addAndGet(batch.size)
                log.debug("Scanning {}: {}", label, runningCount.get())
                heartbeat?.tick(combinedTotal())
                reportProgress?.invoke()
                // Per-page persistence runs after we have a known-good batch but
                // before we top up the pipeline, so a transient failure on the
                // staging write rolls the page back without losing the offset
                // we'd otherwise have queued ahead.
                onPage?.invoke(batch, pageOffset + batch.size)
                if (batch.isEmpty()) {
                    // The listing ended. Cancel any speculative siblings still
                    // in flight.
                    pipeline.forEach { it.second.cancel() }
                    pipeline.clear()
                    break
                }
                // The boundary of the next page is the end of this batch.
                val boundary = pageOffset + batch.size
                val speculative = pipeline.firstOrNull()
                if (speculative != null && speculative.first != boundary) {
                    // A short batch moved the boundary: the speculative page
                    // beyond it is aimed at a gap. Cancel and re-seed from the
                    // real boundary.
                    pipeline.forEach { it.second.cancel() }
                    pipeline.clear()
                    pipeline.addLast(boundary to async { fetchPage(boundary) })
                }
                // Top up: keep two in flight. The speculative that already aims
                // at the boundary is that page; it is not asked for twice.
                if (pipeline.size < 2) {
                    val offset = pipeline.last().first + limit
                    pipeline.addLast(offset to async { fetchPage(offset) })
                }
            }
            accumulated
        }

    private fun buildFolderPath(
        uuid: String,
        folderMap: Map<String, InternxtFolder>,
        rootUuid: String,
    ): String? = Companion.buildFolderPath(uuid, folderMap, rootUuid)

    override suspend fun quota(): QuotaInfo = api.getQuota()

    // The uuid of the user of the stored credentials: one call, for a profile signed in before ids were kept (AccountOwner).
    override suspend fun currentAccountId(): String? = authService.currentUserUuid()

    private val foldersScanned =
        java.util.concurrent.atomic
            .AtomicInteger(0)

    // UD-361: count of subtrees skipped due to 500/503 during a tree walk (the
    // scoped bootstrap and the fallback of an unavailable account-wide listing).
    // Inspected at the end of the walk to refuse partial gathers.
    private val foldersSkipped =
        java.util.concurrent.atomic
            .AtomicInteger(0)

    // archive#517 R3: until when a full gather should skip the account-wide listings and walk
    // the folder tree straight away. Set for LISTING_UNAVAILABLE_TTL when a full gather
    // ends in an unavailable account-wide listing; only full gathers read it.
    private val heavyListingsUnavailableUntil =
        java.util.concurrent.atomic
            .AtomicReference(java.time.Instant.MIN)

    // archive#463: since when the incremental (cursor) listing keeps answering unavailable; null while it
    // answers. Read by delta() to decide when a failing poll must walk once instead of failing again.
    private val incrementalUnavailableSince =
        java.util.concurrent.atomic
            .AtomicReference<java.time.Instant?>(null)

    // Test seam for the incremental fallback's time bound.
    internal var clock: () -> java.time.Instant = { java.time.Instant.now() }

    private suspend fun resolveFolder(path: String): String {
        val creds = authService.getValidCredentials()
        val segments = pathSegments(path)
        var currentUuid = creds.rootFolderId

        for ((index, segment) in segments.withIndex()) {
            // UD-357: cache hit short-circuits Internxt's eventual-consistency
            // window. On the first resolveFolder right after createFolder of
            // a new subfolder, the API listing still doesn't show it; the
            // cache (populated by createFolder) does.
            val cached = folderCache.get(currentUuid, segment)
            if (cached != null) {
                // A folder renamed, moved or trashed elsewhere leaves the cache when a change feed names it
                // (assembleFullDelta); folderStateById drops a folder it finds gone.
                currentUuid = cached
                continue
            }
            val content = api.getFolderContents(currentUuid)
            // archive#402: fail closed on two live same-named child folders — a first-match
            // pick would be cached in folderCache and stick for the process lifetime,
            // pinning every operation under this path to an arbitrary twin. Non-live
            // folders do not count: a trashed twin must neither be picked nor make a
            // live folder ambiguous (the same rule as getMetadata and listChildren).
            val children = content.children.filter { it.isLive() && sanitizeName(it.plainName ?: it.name ?: "") == segment }
            val child =
                when (children.size) {
                    0 -> throw FolderNotFoundException(segment, path, "/" + segments.take(index + 1).joinToString("/"))
                    1 -> children[0]
                    else -> throwAmbiguous(children.size, segment, path)
                }
            // UD-357: cache the discovered UUID too — subsequent resolveFolder
            // calls on the same parent skip the round-trip.
            folderCache.put(currentUuid, segment, child.uuid)
            currentUuid = child.uuid
        }
        return currentUuid
    }

    // UD-317: instance wrappers; actual CloudItem construction (with name
    // sanitisation) lives as pure companion functions below so tests can
    // exercise the conversion directly without a real InternxtProvider.
    private suspend fun InternxtFile.toCloudItem(parentPath: String): CloudItem = fileToCloudItem(this, parentPath, mnemonicOrNull())

    private suspend fun InternxtFolder.toCloudItem(parentPath: String): CloudItem = folderToCloudItem(this, parentPath, mnemonicOrNull())

    /**
     * archive#314: the mnemonic for the legacy-name decryption fallback (`decryptName` needs
     * `<mnemonic>-<parentUuid>` as its password), resolved once per pass. Null when no
     * credentials are loaded — the converters then keep today's fallback.
     */
    private suspend fun mnemonicOrNull(): String? = runCatching { authService.getValidCredentials().mnemonic }.getOrNull()

    private suspend fun InternxtFile.toDeltaCloudItem(
        folderMap: MutableMap<String, InternxtFolder>,
        rootUuid: String,
        unfetchable: MutableSet<String>,
        fetchFolder: suspend (String) -> InternxtFolder?,
        ancestors: Map<String, InternxtFolder> = emptyMap(),
        mnemonic: String? = null,
    ): CloudItem? {
        val parentPath =
            when (val fUuid = folderUuid) {
                null -> ""
                else ->
                    Companion.buildFolderPathFetching(fUuid, folderMap, rootUuid, unfetchable, fetchFolder, ancestors)
                        ?: return null
            }
        return fileToDeltaCloudItem(this, parentPath, mnemonic)
    }

    private suspend fun InternxtFolder.toDeltaCloudItem(
        folderMap: MutableMap<String, InternxtFolder>,
        rootUuid: String,
        unfetchable: MutableSet<String>,
        fetchFolder: suspend (String) -> InternxtFolder?,
        ancestors: Map<String, InternxtFolder> = emptyMap(),
        mnemonic: String? = null,
    ): CloudItem? {
        val parentPath =
            when (val pUuid = parentUuid) {
                null -> ""
                else ->
                    Companion.buildFolderPathFetching(pUuid, folderMap, rootUuid, unfetchable, fetchFolder, ancestors)
                        ?: return null
            }
        return folderToDeltaCloudItem(this, parentPath, mnemonic)
    }

    companion object {
        // Upper bound of objects remembered by shardHashReported (archive#940).
        private const val MAX_SHARD_HASH_REPORTS = 4096

        // Largest file a create collision downloads to compare with the local one. Above it the compare is skipped and
        // both are kept, so a big collision never costs a big download.
        internal const val COLLISION_COMPARE_MAX_BYTES: Long = 256L * 1024 * 1024

        /** archive#493: statuses by which the server refuses an upload request as such; another attempt is refused again. */
        internal val PERMANENT_UPLOAD_STATUS = setOf(400, 413, 415, 422)

        // gkrost/unidrive-cli#145: Payment Required, the plan's refusal of an upload.
        internal const val PLAN_LIMIT_STATUS = 402

        private val SERVER_MESSAGE = Regex("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

        // The `message` string of the JSON body an API error quotes ("API error: 402 Payment Required - {"message":"…"}"), else null.
        internal fun serverSentence(errorMessage: String?): String? =
            errorMessage?.let { SERVER_MESSAGE.find(it)?.groupValues?.get(1) }?.replace("\\\"", "\"")?.takeIf { it.isNotBlank() }

        // How many per-folder requests are in flight at once when the paths are resolved: the same four as the folders
        // of the tree walk being listed (the default of collectScopedInventoryImpl's concurrency).
        internal const val PER_FOLDER_REQUESTS_IN_FLIGHT: Int = 4

        // Server-unavailable status codes that trigger the slower fallback
        // walk over the folder tree (line 518) or skip-this-folder (line 699).
        // Narrower than TRANSIENT_STATUSES: 429 (rate-limited) and 502/504
        // (gateway timing issues) should not trigger fallback — they should
        // honour Retry-After and retry the same call.
        private val SERVER_UNAVAILABLE_STATUSES = setOf(500, 503)

        // The statuses by which the server says a folder is gone for good (deleted or purged): asking again gets the
        // same answer, so the items below it are unreachable rather than the pass incomplete. 404 and 410 are never
        // synthesised by this client (a connection that failed is status 0, a cut one 503), so they are the server's.
        private val PERMANENT_ABSENCE_STATUSES = setOf(404, 410)

        // Whether a cursor listing's failure is the server's to answer for, and the tree walk — the one
        // listing that still finishes on a large drive — takes the gather over. The whole 5xx family: live,
        // the gateway answers Cloudflare 524 at its own cap (about 125 s) at the same deep page of the
        // folder stream on every run — a deterministic wall, not a blip the next gather dodges, which is
        // why this is wider than SERVER_UNAVAILABLE_STATUSES. A cut before the answer surfaces as 503.
        // Status 0 is a connection that never formed (archive#693): the enumeration has already waited out its
        // outage window, and a walk would meet the same dead network. It would also open the 30-minute window
        // and replace the cursor marker with a tree marker, so the next gather would start from item 0
        // instead of continuing the cursors. It ends the attempt with the marker intact, like a client error.
        // 429 stays out: the retry ladder honoured it already, and walking a throttled server is the wrong
        // move. A client error (401, a 404 on a later page) is a request-shape problem a walk cannot fix: it
        // ends the attempt with the marker intact.
        private fun isServerSideListingFailure(statusCode: Int): Boolean = statusCode >= 500

        // archive#517 R3: how long a full gather remembers an unavailable account-wide listing
        // before it pays one attempt to re-learn. The live account cycled the doomed
        // attempt every ~7 min for over 4 h; 30 min cuts that to ~8 attempts a day
        // while still re-trying often enough to notice a recovery.
        private val LISTING_UNAVAILABLE_TTL: java.time.Duration = java.time.Duration.ofMinutes(30)

        // archive#463: how long the incremental listing may keep failing before a poll walks the tree once.
        // Long enough that blips and short outages (the poller backs off up to 10 min) cost no walk;
        // short enough that a cursor the gateway keeps cutting does not freeze the view for good.
        internal val INCREMENTAL_WALK_AFTER: java.time.Duration = java.time.Duration.ofMinutes(30)

        // Cap on consecutive 409 collisions tolerated when the destructive-
        // overwrite guard tries to rename the prior cloud file to an archive
        // name. Two replaces of the same file inside one wall-clock second
        // collide on the timestamp suffix; the counter (`-2`, `-3`, ...)
        // disambiguates. After this many failed attempts, fall through to
        // the destructive `replaceFile` rather than block the local edit.
        internal const val MAX_KEEP_OVERWRITTEN_RETRIES: Int = 5

        // UD-361: testable recursion driver. Walks the folder tree rooted at
        // [folderUuid], accumulating files into [accumulator] and the folders
        // it descended through into [folderAccumulator]. On 500/503 from
        // [getContents], increments [skipped] and returns (the subtree is
        // silently dropped — caller is responsible for inspecting [skipped]
        // and rejecting partial gathers). Other exceptions propagate.
        // Increments [scanned] for every successfully walked folder, then
        // invokes [onProgress] so the caller can tick a heartbeat from
        // within the walk.
        //
        // Each child folder stamped into [folderAccumulator] has its
        // `parentUuid` set from the recursion context (the `folderUuid`
        // arg) when the listing omitted it. Measured on a live account
        // 2026-09-29 (archive#392): the /folders/:uuid/content listing DID carry
        // `parentUuid` on every child folder (and `folderUuid` on every
        // file), so the stamp is defensive and idempotent — it costs one
        // comparison and keeps the path resolution correct against an
        // endpoint that has been observed both ways. Threading these
        // folders into the caller's
        // `allFolders` is what closes the ancestor-uuid-drop gap when the
        // fallback fires (otherwise `folderMap` only contains the
        // cursor-filtered /folders delta and most files get dropped).
        internal suspend fun collectFilesFromFoldersImpl(
            getContents: suspend (String) -> FolderContentResponse,
            folderUuid: String,
            accumulator: MutableList<InternxtFile>,
            folderAccumulator: MutableList<InternxtFolder>,
            depth: Int,
            scanned: java.util.concurrent.atomic.AtomicInteger,
            skipped: java.util.concurrent.atomic.AtomicInteger,
            log: org.slf4j.Logger,
            onProgress: (() -> Unit)? = null,
        ) {
            val content =
                try {
                    getContents(folderUuid)
                } catch (e: InternxtApiException) {
                    if (e.statusCode in SERVER_UNAVAILABLE_STATUSES) {
                        log.warn("Skipping folder {} ({})", folderUuid, e.statusCode, e)
                        skipped.incrementAndGet()
                        return
                    }
                    throw e
                }
            accumulator.addAll(content.files)
            val total = scanned.incrementAndGet()
            log.debug("Scanning: {} files, {} folders scanned", accumulator.size, total)
            onProgress?.invoke()
            for (child in content.children) {
                if (child.status == "EXISTS" && !child.removed && !child.deleted) {
                    val stamped =
                        if (child.parentUuid != null) child else child.copy(parentUuid = folderUuid)
                    folderAccumulator.add(stamped)
                    collectFilesFromFoldersImpl(
                        getContents,
                        stamped.uuid,
                        accumulator,
                        folderAccumulator,
                        depth + 1,
                        scanned,
                        skipped,
                        log,
                        onProgress,
                    )
                }
            }
        }

        // Builds the single DeltaPage of a scoped bootstrap from its inventory. Items
        // whose ancestors cannot be resolved are dropped and make the page incomplete,
        // the same signal the account-wide gather uses.
        internal fun scopedDeltaPage(
            inventory: ScopedInventory,
            rootUuid: String,
            cursor: String,
            skipped: Int,
        ): DeltaPage {
            val folderMap = inventory.folders.associateBy { it.uuid }
            val files =
                inventory.files.map { f ->
                    val parent = f.folderUuid?.let { buildFolderPath(it, folderMap, rootUuid) ?: return@map null } ?: ""
                    fileToDeltaCloudItem(f, parent)
                }
            val folders =
                inventory.folders.map { d ->
                    val parent = d.parentUuid?.let { buildFolderPath(it, folderMap, rootUuid) ?: return@map null } ?: ""
                    folderToDeltaCloudItem(d, parent)
                }
            val dropped = files.count { it == null } + folders.count { it == null }
            return DeltaPage(
                items = files.filterNotNull() + folders.filterNotNull(),
                cursor = cursor,
                hasMore = false,
                complete = skipped == 0 && dropped == 0,
            )
        }

        // Enumerates only the given scope roots instead of the whole drive, so the
        // cost is bounded by the subtree size. Each root is resolved by walking its
        // path down from [driveRootUuid]; the folders met on the way are emitted so
        // paths resolve and the engine can track the ancestors. A root that does not
        // exist fails the gather. A 500/503 inside a subtree is skipped and counted in
        // [skipped] like the /files fallback does; a failure while resolving a root
        // propagates. Folders are listed with at most [concurrency] requests in flight.
        // Both folders and files are stamped from the walk (parentUuid/folderUuid).
        // Measured on a live account 2026-09-29 (archive#392): the listing carries both
        // fields on every child, so the stamp is defensive and idempotent, kept
        // because the endpoint has been observed without them.
        //
        // [onProgress] gets one report per folder whose listing finished or was skipped, from
        // whichever coroutine that was. A folder counts as known before the folder that holds it
        // counts as done, so known - done is the queue (listed, waiting or in flight) and is never
        // zero while the walk goes on.
        //
        // [tracked] is the engine's state.db (ScanContext.trackedRow): it decides between two live folders that share the
        // name of a scope root's segment (see [scopeSegmentWinner]).
        // The walk's collaborators and test seams are its parameters, and the walk, its resume and its persistence run in one
        // flow (its complexity was in the detekt baseline under the signature before [tracked]).
        @Suppress("LongParameterList", "CyclomaticComplexMethod")
        internal suspend fun collectScopedInventoryImpl(
            getContents: suspend (String) -> FolderContentResponse,
            driveRootUuid: String,
            scopeRoots: List<String>,
            scanned: java.util.concurrent.atomic.AtomicInteger,
            skipped: java.util.concurrent.atomic.AtomicInteger,
            log: org.slf4j.Logger,
            concurrency: Int = 4,
            onProgress: ((ScanProgress) -> Unit)? = null,
            resume: TreeResume? = null,
            persist: (suspend (rows: List<org.krost.unidrive.CloudItem>, startsDone: Set<String>) -> Unit)? = null,
            tracked: ((String) -> org.krost.unidrive.CloudItem?)? = null,
        ): ScopedInventory {
            val files = java.util.Collections.synchronizedList(mutableListOf<InternxtFile>())
            val folders = java.util.Collections.synchronizedMap(linkedMapOf<String, InternxtFolder>())
            val permits = kotlinx.coroutines.sync.Semaphore(concurrency)
            val known = AtomicInteger(0)
            val done = AtomicInteger(0)

            // A resumed walk starts from the rows the interrupted one stored: what they list is known, the
            // folders marked listed are done, and only the folders still outstanding are listed again.
            val listed: Set<String> = resume?.listedFolders.orEmpty()
            val seeded = HashSet<String>()
            val visited = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            val starts = LinkedHashSet<String>()
            val startsDone = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            val persistLock = kotlinx.coroutines.sync.Mutex()
            if (resume != null) {
                files.addAll(resume.files)
                for (f in resume.folders) {
                    folders[f.uuid] = f
                    seeded.add(f.uuid)
                }
                startsDone.addAll(resume.marker.startsDone)
                known.set(seeded.size)
                done.set(seeded.count { it in listed })
            }

            fun live(f: InternxtFolder) = f.status == "EXISTS" && !f.removed && !f.deleted

            // done is read before known: known only grows, so the pair never shows more done than known.
            fun report() {
                onProgress ?: return
                val foldersDone = done.get()
                val foldersKnown = known.get()
                onProgress(
                    ScanProgress(
                        items = files.size + folders.size,
                        foldersDone = foldersDone,
                        foldersKnown = foldersKnown,
                        foldersSkipped = skipped.get(),
                        listing = ScanProgress.LISTING_TREE,
                    ),
                )
            }

            suspend fun listing(uuid: String): FolderContentResponse = permits.withPermit { getContents(uuid) }

            suspend fun walk(folderUuid: String) {
                if (folderUuid in listed || !visited.add(folderUuid)) return
                val content =
                    try {
                        listing(folderUuid)
                    } catch (e: InternxtApiException) {
                        if (e.statusCode in SERVER_UNAVAILABLE_STATUSES) {
                            log.warn("Skipping folder {} ({})", folderUuid, e.statusCode, e)
                            skipped.incrementAndGet()
                            done.incrementAndGet()
                            report()
                            return
                        }
                        throw e
                    }
                val listedFiles =
                    content.files
                        .filter { it.status == "EXISTS" && !it.removed && !it.deleted }
                        .map { if (it.folderUuid != null) it else it.copy(folderUuid = folderUuid) }
                files.addAll(listedFiles)
                scanned.incrementAndGet()
                val children =
                    content.children.filter(::live).map { child ->
                        (if (child.parentUuid != null) child else child.copy(parentUuid = folderUuid)).also { folders[it.uuid] = it }
                    }
                known.addAndGet(children.size)
                // The listing, the rows of the children and the folder's own done mark are stored together: a
                // folder marked listed has all its children staged, and one that is not has none.
                persist?.let { store ->
                    persistLock.withLock {
                        val isStart = folderUuid in starts
                        if (isStart) startsDone.add(folderUuid)
                        val own =
                            if (isStart) {
                                emptyList()
                            } else {
                                listOfNotNull(folders[folderUuid]?.toStagedCloudItem(driveRootUuid)?.copy(hash = TREE_DONE_TAG))
                            }
                        val rows =
                            listedFiles.map { it.toStagedCloudItem(driveRootUuid).copy(hash = treeFileTag(it)) } +
                                children.map { it.toStagedCloudItem(driveRootUuid).copy(hash = TREE_STAGED_TAG) } +
                                own
                        store(rows, startsDone.toSet())
                    }
                }
                done.incrementAndGet()
                report()
                kotlinx.coroutines.coroutineScope {
                    for (child in children) launch { walk(child.uuid) }
                }
            }

            report()

            val warnedTwins = HashSet<String>()
            for (root in scopeRoots) {
                var current = driveRootUuid
                for (segment in pathSegments(root)) {
                    val matches = listing(current).children.filter { live(it) && sanitizeName(it.plainName ?: it.name ?: "") == segment }
                    val child =
                        scopeSegmentWinner(matches, segment, current, driveRootUuid, tracked)
                            ?: throw ProviderException("sync_path '$root' not found on the remote: no folder '$segment'")
                    if (matches.size > 1 && warnedTwins.add(child.uuid)) {
                        log.warn(
                            "sync_path '{}': {} live folders share the name of one segment ({}); walking {}, the one the gather keeps at that path",
                            root,
                            matches.size,
                            matches.map { it.uuid }.sorted().joinToString(", "),
                            child.uuid,
                        )
                    }
                    val stamped = if (child.parentUuid != null) child else child.copy(parentUuid = current)
                    folders[stamped.uuid] = stamped
                    current = stamped.uuid
                }
                if (starts.add(current)) {
                    if (current !in seeded) known.incrementAndGet()
                    if (current in listed && current !in seeded) done.incrementAndGet()
                }
            }

            kotlinx.coroutines.coroutineScope {
                for (f in resume?.folders.orEmpty()) if (f.uuid !in listed) launch { walk(f.uuid) }
            }
            for (start in starts) walk(start)
            return ScopedInventory(files.toList(), folders.values.toList())
        }

        // Internxt keeps two live folders of one name in one folder (archive#403). The segment of a scope root they share
        // resolves to the folder the engine's gather keeps at that path (RemoteGather's winner rule), never to the one the
        // listing happens to name first: the one state.db tracks at that path wins, one it keeps under another name (a
        // twin's decorated name) loses, and otherwise the smallest id. Walking another folder than the one state.db
        // tracks would make every row below the scope root vanish from a full listing.
        internal fun scopeSegmentWinner(
            matches: List<InternxtFolder>,
            segment: String,
            parentUuid: String,
            driveRootUuid: String,
            tracked: ((String) -> org.krost.unidrive.CloudItem?)?,
        ): InternxtFolder? {
            if (matches.size < 2) return matches.firstOrNull()
            fun rank(folder: InternxtFolder): Int {
                val row = tracked?.invoke(folder.uuid)?.takeIf { !it.trashed && (it.parentId ?: driveRootUuid) == parentUuid } ?: return 1
                return if (sanitizeName(row.name) == segment) 0 else 2
            }
            return matches.minWith(compareBy<InternxtFolder> { rank(it) }.thenBy { it.uuid })
        }

        /**
         * Look-back window applied to the persisted delta cursor before each
         * `/files` and `/folders` query, so items that mutated in the seam
         * between the prior gather's `max(updatedAt)` snapshot and the cursor
         * write are caught on the next pass.
         *
         * 2 minutes matches drive-desktop's `TWO_MINUTES_IN_MILLISECONDS`
         * (`src/apps/main/remote-sync/helpers/get-checkpoint.ts:19`). The
         * earlier 6-hour window was a defensive over-correction from when
         * cursor promotion was unreliable; with promote-always now in place
         * and `sort=updatedAt` on the delta path advancing the effective
         * cursor per page, 2 minutes is sufficient and turns "every restart
         * re-walks 6 h of activity" into "re-walks the last 120 s." On a hot
         * account this is the difference between a sub-second incremental
         * scan and a multi-minute one.
         */
        fun rewindCursor(cursor: String): String {
            val instant = Instant.parse(cursor)
            return instant.minus(REWIND_CURSOR_SECONDS, ChronoUnit.SECONDS).toString()
        }

        internal const val REWIND_CURSOR_SECONDS: Long = 120

        /**
         * The segments of [path], each in the one form a listing shows ([sanitizeName]: a control character at either end of a
         * name is gone, a space stays). Every name the provider sends, caches or compares comes from here, so a name that is
         * created under a path is found under that path (gkrost/unidrive-cli#180). A segment of control characters alone
         * keeps its raw form: it must not turn into an empty one and select the folder above it.
         */
        fun pathSegments(path: String): List<String> =
            path
                .removePrefix("/")
                .split("/")
                .filter { it.isNotEmpty() }
                .map { sanitizeName(it).ifEmpty { it } }

        // Build the archive plainName for the destructive-overwrite guard.
        // Returns `${plainName}.unidrive-prev-${yyyy-MM-dd'T'HH-mm-ss}` in UTC
        // (`-` instead of `:` for NTFS portability). [counter] > 1 appends a
        // `-N` suffix to break collisions when two replaces of the same file
        // fall inside the same wall-clock second. archive#1091: the plainName is cut (on a
        // code-point boundary) so that plainName + suffix + `.` + [type] stays within
        // 255 UTF-16 units; [type] is the file's extension, which is appended after.
        internal fun keepOverwrittenArchiveName(
            plainName: String,
            now: java.time.Instant,
            counter: Int = 1,
            type: String? = null,
        ): String {
            val stamp =
                java.time.format.DateTimeFormatter
                    .ofPattern("yyyy-MM-dd'T'HH-mm-ss")
                    .withZone(java.time.ZoneOffset.UTC)
                    .format(now)
            val tail = ".unidrive-prev-$stamp" + (if (counter <= 1) "" else "-$counter")
            return org.krost.unidrive.io.NameLimit.fitStem(plainName, tail.length + extensionUnits(type)) + tail
        }

        // The units `.type` adds to a plainName in the name a user sees.
        private fun extensionUnits(type: String?): Int = if (type.isNullOrEmpty()) 0 else type.length + 1

        // UD-366: build the conflict-copy plainName for the keep-both collision
        // handler.  Format: `$plainName (conflict $date)` for counter=1, then
        // `$plainName (conflict $date) (N)` for N ≥ 2.  [date] is a yyyy-MM-dd
        // string passed by the caller so it can be pinned in tests. archive#1091: the
        // plainName is cut (on a code-point boundary) so that plainName + suffix + `.` + [type]
        // stays within 255 UTF-16 units; [type] is the file's extension, appended after.
        internal fun conflictName(
            plainName: String,
            date: String,
            counter: Int = 1,
            type: String? = null,
        ): String {
            val tail = " (conflict $date)" + (if (counter <= 1) "" else " ($counter)")
            return org.krost.unidrive.io.NameLimit.fitStem(plainName, tail.length + extensionUnits(type)) + tail
        }

        /**
         * UD-369: split a leaf filename into (plainName, type). Returned type is the bare extension or null.
         *
         * The listing joins the two again as `plainName.type`, but strips control characters from both ends of the
         * plainName and does not repeat a type the plainName already ends in ([fileToCloudItem]). A name for which that
         * join is not the name itself is therefore kept whole in the plainName, with no type: `a.txt.txt` (split as
         * `a.txt` + `txt` it is listed as `a.txt`), `a.` (type empty, listed as `a`), and a dotfile (an empty plainName,
         * which the drive refuses). A name that is listed under another name is a rename of the user's file as far as the
         * engine can tell, and a path lookup of the uploaded name no longer finds it.
         *
         * A stem or type with whitespace at an end (`Scan 1 .pdf`, ` leading space.txt`) is also kept whole. Until #144 the
         * listing trimmed spaces too, and that is the shape such names have been uploaded in; keeping it means the fix
         * changes only how a name is listed, not what is sent.
         */
        fun splitLeaf(filename: String): Pair<String, String?> {
            val dot = filename.lastIndexOf('.')
            if (dot <= 0 || dot == filename.length - 1) return filename to null
            val stem = filename.substring(0, dot)
            val type = filename.substring(dot + 1)
            if (stem != stem.trim() || type != type.trim() || stem.endsWith(".$type")) return filename to null
            return stem to type
        }

        fun stripExtension(filename: String): String = splitLeaf(filename).first

        fun newFileType(filename: String): String? = splitLeaf(filename).second

        /**
         * UD-317: strip leading/trailing whitespace (incl. `\n`, `\r`, `\t`)
         * from names returned by internxt's listing APIs. Observed
         * 2026-04-19: a desktop-shortcut filename arrived as
         * `"\ninternxt-cli.desktop"`, which caused `SyncEngine` URI
         * construction to fail with `Illegal char <\n> at index 0`. We
         * `.trim()` defensively rather than reject, so the file still
         * syncs. Interior whitespace is preserved (legal filename char).
         *
         * #144: `.trim()` also took U+0020, and a space at either end is
         * a legal part of a name the user gave the file. The golden
         * unicode run of 2026-10-27 uploaded ` leading space.txt`, the
         * listing returned `leading space.txt`, and the engine followed
         * that as a rename of the user's file. Only control characters
         * (C0 `\u0000`..`\u001f` and DEL `\u007f`) in the whitespace at
         * either end are stripped; spaces, no-break spaces and other Unicode
         * whitespace stay. The `\n` of UD-317 is still stripped.
         */
        fun sanitizeName(raw: String): String {
            fun control(c: Char) = c < ' ' || c == '\u007f'
            val first = raw.indexOfFirst { !it.isWhitespace() && !control(it) }
            if (first < 0) return raw.filterNot(::control)
            val last = raw.indexOfLast { !it.isWhitespace() && !control(it) }
            return raw.substring(0, first).filterNot(::control) + raw.substring(first, last + 1) +
                raw.substring(last + 1).filterNot(::control)
        }

        internal fun internxtVersionToken(file: InternxtFile): String =
            "${file.uuid}|${file.modificationTime.orEmpty()}|${file.size}"

        /**
         * gkrost/unidrive-cli#164: the name of a file's bytes ([CloudItem.contentId]): its stored object (`fileId`, the bridge entry a
         * new upload replaces) and the size. Nothing a rename, a move or any other change of the drive entry touches, where the
         * [internxtVersionToken] carries the modification time those stamp. An empty file has no stored object (archive#485): the
         * size names its bytes alone. Null when the answer names no object for a file of some bytes, or no size.
         */
        internal fun internxtContentId(file: InternxtFile): String? {
            val size = file.size?.toLongOrNull() ?: return null
            val fileId = file.fileId?.takeIf { it.isNotBlank() }
            return when {
                fileId != null -> "$fileId|$size"
                size == 0L -> "|0"
                else -> null
            }
        }

        internal fun InternxtFile.isLive(): Boolean = status == "EXISTS" && !isTombstoned()

        // The rows of a page to keep and the ones to drop. A page asked for without the status filter holds rows of
        // every status; a page asked for with it is kept as the server filtered it.
        internal fun <T> List<T>.partitionLive(
            unfiltered: Boolean,
            live: (T) -> Boolean,
        ): Pair<List<T>, List<T>> = if (unfiltered) partition(live) else Pair(this, emptyList())

        private fun describeResume(position: StreamPosition): String =
            when {
                position.done -> "done"
                position.unfiltered && position.cursor == null -> "from ${position.since} without a status filter"
                position.unfiltered -> "from its cursor, without a status filter"
                position.cursor == null -> "not started"
                else -> "from its cursor"
            }

        internal fun InternxtFolder.isLive(): Boolean = status == "EXISTS" && !isTombstoned()

        /** The server's own word for a folder in the trash: its items are still there, below it. */
        internal fun InternxtFolder.isTrashed(): Boolean = status == "TRASHED"

        // A page of the folders stream into the listing: the live folders go into [folders], the rest leave it, and
        // the trashed ones among the rest become ancestor-only records in [ancestors] (a folder that is live again
        // leaves them). Returns the rows to stage with the page: the live ones, and the ancestors under their own tag.
        //
        // Only a trashed folder is kept as an ancestor: live items hang below trashed folders (5,536 files and 266
        // folders on the account this was measured on), and none of the 948 folders they needed was a deleted one,
        // of 421,897 deleted against 47,241 trashed. A deleted folder stays what it was: a path through one is asked for.
        internal fun keepFolderPage(
            page: List<InternxtFolder>,
            unfiltered: Boolean,
            folders: MutableMap<String, InternxtFolder>,
            ancestors: MutableMap<String, InternxtFolder>,
            rootUuid: String,
        ): List<CloudItem> {
            val (live, gone) = page.partitionLive(unfiltered) { it.isLive() }
            for (f in gone) {
                folders.remove(f.uuid)
                ancestors.remove(f.uuid)
            }
            for (f in live) {
                folders[f.uuid] = f
                ancestors.remove(f.uuid)
            }
            val trashed = gone.filter { it.isTrashed() }
            for (f in trashed) ancestors[f.uuid] = f
            return live.map { it.toStagedCloudItem(rootUuid).copy(hash = CURSOR_STAGED_TAG) } +
                trashed.map { it.toStagedCloudItem(rootUuid).copy(hash = CURSOR_ANCESTOR_TAG) }
        }

        internal fun filterLiveContent(content: FolderContentResponse): FolderContentResponse =
            FolderContentResponse(
                children = content.children.filter { it.isLive() },
                files = content.files.filter { it.isLive() },
            )

        private fun InternxtFile.isTombstoned(): Boolean =
            status == "TRASHED" || status == "DELETED" || removed || deleted

        private fun InternxtFolder.isTombstoned(): Boolean =
            status == "TRASHED" || status == "DELETED" || removed || deleted

        // UD-317: pure converter for `listChildren` / `getMetadata` file entries.
        // archive#314: a legacy row (older clients predating plainName) carries only the encrypted
        // name; attempt the client-side decryption against the item's parent scope before
        // falling back to the ciphertext blob. Never throws: an undecryptable name keeps
        // today's fallback.
        private val legacyNameLog = org.slf4j.LoggerFactory.getLogger(InternxtProvider::class.java)

        private fun resolvedBaseName(
            plainName: String?,
            encryptedName: String?,
            parentUuid: String?,
            mnemonic: String?,
        ): String {
            if (!plainName.isNullOrBlank() || encryptedName.isNullOrEmpty() || mnemonic.isNullOrEmpty() || parentUuid.isNullOrEmpty()) {
                return plainName ?: encryptedName ?: ""
            }
            return runCatching { InternxtCrypto().decryptName(encryptedName, "$mnemonic-$parentUuid") }
                .onFailure { legacyNameLog.debug("#314: could not decrypt a legacy name: {}", it.message) }
                .getOrNull() ?: encryptedName
        }

        internal fun fileToCloudItem(
            file: InternxtFile,
            parentPath: String,
            mnemonic: String? = null,
        ): CloudItem {
            val baseName = sanitizeName(resolvedBaseName(file.plainName, file.name, file.folderUuid, mnemonic))
            val cleanType = file.type?.let { sanitizeName(it) }
            val name =
                if (!cleanType.isNullOrEmpty() && !baseName.endsWith(".$cleanType")) {
                    "$baseName.$cleanType"
                } else {
                    baseName
                }
            val fullPath = if (parentPath == "/") "/$name" else "$parentPath/$name"
            return CloudItem(
                id = file.uuid,
                name = name,
                path = fullPath,
                size = file.size?.toLongOrNull() ?: 0,
                isFolder = false,
                modified = file.modificationInstant,
                created = file.creationInstant,
                hash = internxtVersionToken(file),
                // UD-352c: Internxt's API returns `file.type` as a file
                // EXTENSION (e.g. "heic", "jpg"), not a real MIME type
                // (e.g. "image/heic"). Setting mimeType = cleanType here
                // produced Android intent-resolver mismatches (no app
                // declares an intent-filter for MIME literally "heic").
                // Set mimeType = null so consumers (Android LocalFileSource,
                // MediaStoreBridge, etc.) derive the real MIME from the
                // filename extension via their platform-native lookup.
                // The extension itself is preserved in the `name` field
                // a few lines above (the `name = "$baseName.$cleanType"`
                // construction).
                mimeType = null,
                // UD-359: surface `removed`/`deleted` boolean flags alongside
                // the `status` enum. Mirrors the folder helper at line ~562.
                deleted = file.isTombstoned(),
                // Parent's folder UUID — populated so state.db can index alive
                // children of a folder via (parent_uuid, status). Internxt's
                // `folderUuid` is null only for items directly under the root
                // bucket; we collapse that to CloudItem.parentId=null too so
                // root rows store parent_uuid=NULL.
                parentId = file.folderUuid,
                contentId = internxtContentId(file),
            )
        }

        // UD-317: pure converter for `listChildren` folder entries.
        internal fun folderToCloudItem(
            folder: InternxtFolder,
            parentPath: String,
            mnemonic: String? = null,
        ): CloudItem {
            val name = sanitizeName(resolvedBaseName(folder.plainName, folder.name, folder.parentUuid, mnemonic))
            val fullPath = if (parentPath == "/") "/$name" else "$parentPath/$name"
            return CloudItem(
                id = folder.uuid,
                name = name,
                path = fullPath,
                size = 0,
                isFolder = true,
                modified = folder.modificationInstant,
                created = folder.creationInstant,
                hash = null,
                mimeType = null,
                deleted = folder.isTombstoned(),
                parentId = folder.parentUuid,
            )
        }

        // UD-317: pure converter for delta file entries (parentPath already resolved by caller).
        internal fun fileToDeltaCloudItem(
            file: InternxtFile,
            parentPath: String,
            mnemonic: String? = null,
        ): CloudItem {
            val baseName = sanitizeName(resolvedBaseName(file.plainName, file.name, file.folderUuid, mnemonic))
            val cleanType = file.type?.let { sanitizeName(it) }
            val name =
                if (!cleanType.isNullOrEmpty() && !baseName.endsWith(".$cleanType")) {
                    "$baseName.$cleanType"
                } else {
                    baseName
                }
            return CloudItem(
                id = file.uuid,
                name = name,
                path = "$parentPath/$name",
                size = file.size?.toLongOrNull() ?: 0,
                isFolder = false,
                modified = file.modificationInstant,
                created = file.creationInstant,
                hash = internxtVersionToken(file),
                // UD-352c: Internxt's API returns `file.type` as a file
                // EXTENSION (e.g. "heic", "jpg"), not a real MIME type
                // (e.g. "image/heic"). Setting mimeType = cleanType here
                // produced Android intent-resolver mismatches (no app
                // declares an intent-filter for MIME literally "heic").
                // Set mimeType = null so consumers (Android LocalFileSource,
                // MediaStoreBridge, etc.) derive the real MIME from the
                // filename extension via their platform-native lookup.
                // The extension itself is preserved in the `name` field
                // a few lines above (the `name = "$baseName.$cleanType"`
                // construction).
                mimeType = null,
                // UD-359: surface `removed`/`deleted` boolean flags alongside
                // the `status` enum. Mirrors the folder helper at line ~562.
                deleted = file.isTombstoned(),
                parentId = file.folderUuid,
                contentId = internxtContentId(file),
            )
        }

        // Resolves the absolute path of a folder uuid by walking ancestors
        // through [folderMap]. Returns the empty string for [rootUuid]
        // (parentPath sentinel) and `null` when any non-root ancestor is
        // absent from [folderMap].
        //
        // A null return signals that the caller cannot safely construct a
        // full path for an item under this uuid — typically because the
        // /folders delta page returned a changed leaf without its
        // unchanged ancestors. Callers must drop the item and signal
        // `complete = false` upstream rather than silently rooting it at
        // `/` (the prior silent-empty fallback produced ~84k duplicate
        // `remote_id` rows in user state.db). See
        // docs/audits/internxt-phantom-investigation.md.
        internal fun buildFolderPath(
            uuid: String,
            folderMap: Map<String, InternxtFolder>,
            rootUuid: String,
        ): String? {
            if (uuid == rootUuid) return ""
            val folder = folderMap[uuid] ?: return null
            val parentPath: String =
                when (val parentUuid = folder.parentUuid) {
                    null -> ""
                    else -> buildFolderPath(parentUuid, folderMap, rootUuid) ?: return null
                }
            val name = sanitizeName(folder.plainName ?: folder.name ?: "")
            return "$parentPath/$name"
        }

        // Self-healing variant of [buildFolderPath]: when an ancestor uuid is
        // absent from [folderMap] (typical when the /folders delta page returned
        // a changed leaf without its unchanged ancestors), fetch that folder's
        // metadata via [fetchFolder] and walk up, inserting each fetched
        // ancestor into [folderMap] before resolving the path. Without this an
        // unchanged remote keeps reproducing the same ancestor-uuid drop on
        // every pass — the gather never reaches `complete=true`, so the engine
        // suppresses deletes indefinitely.
        //
        // Memoisation, bounded to one fetch per missing uuid per pass:
        //  - a successful fetch is written into [folderMap], so any later item
        //    under the same ancestor finds it already present (no re-fetch);
        //  - an unfetchable uuid (404 / gone / unavailable → [fetchFolder] returns
        //    null) is recorded in [unfetchable], so a known-gone ancestor is
        //    never re-requested.
        // Both maps are shared across the whole delta() pass; at ~196k-file
        // scale this caps the re-fetch at the number of distinct missing
        // ancestors, not the number of orphaned leaves.
        //
        // Before any request, a folder is looked up in [ancestors]: the trashed folders the listing saw and did
        // not return as items. A row found there is spliced into [folderMap] like a fetched one, so it is read once.
        //
        // Returns null exactly as [buildFolderPath] does when an ancestor
        // cannot be resolved — the caller drops the item and counts it: below a
        // folder that is gone for good it is unreachable ([goneAncestorOf]),
        // otherwise `complete=false` stands for the pass.
        internal suspend fun buildFolderPathFetching(
            uuid: String,
            folderMap: MutableMap<String, InternxtFolder>,
            rootUuid: String,
            unfetchable: MutableSet<String>,
            fetchFolder: suspend (String) -> InternxtFolder?,
            ancestors: Map<String, InternxtFolder> = emptyMap(),
        ): String? {
            if (uuid == rootUuid) return ""
            if (uuid in unfetchable) return null
            val folder =
                knownFolder(uuid, folderMap, ancestors) ?: run {
                    val fetched = fetchFolder(uuid)
                    if (fetched == null) {
                        unfetchable.add(uuid)
                        return null
                    }
                    folderMap[uuid] = fetched
                    fetched
                }
            val parentPath: String =
                when (val parentUuid = folder.parentUuid) {
                    null -> ""
                    else ->
                        buildFolderPathFetching(parentUuid, folderMap, rootUuid, unfetchable, fetchFolder, ancestors)
                            ?: return null
                }
            val name = sanitizeName(folder.plainName ?: folder.name ?: "")
            return "$parentPath/$name"
        }

        // The folder that is gone for good and that stopped the path of an item whose parent is [parentUuid]: walking up from
        // the parent, the first folder the pass could not resolve ([unfetchable]) decides. It is returned when it is one of
        // [gone] (the server answered 404 / 410 for it); null when it was unavailable for another reason, which may be
        // over by the next pass, or when nothing on the way is unresolved. An item dropped below a gone folder is
        // unreachable; any other drop keeps the pass incomplete.
        internal fun goneAncestorOf(
            parentUuid: String?,
            folderMap: Map<String, InternxtFolder>,
            ancestors: Map<String, InternxtFolder>,
            rootUuid: String,
            unfetchable: Set<String>,
            gone: Set<String>,
        ): String? {
            var uuid = parentUuid
            // A parent chain that comes back to itself is damage; it is not walked twice.
            val seen = HashSet<String>()
            while (uuid != null && uuid != rootUuid && seen.add(uuid)) {
                if (uuid in unfetchable) return uuid.takeIf { it in gone }
                val folder = folderMap[uuid] ?: ancestors[uuid] ?: return null
                uuid = folder.parentUuid
            }
            return null
        }

        // The top of a tree of its own ([tops]) that an item whose parent is [parentUuid] hangs below, walking up through
        // [folderMap]; null when the walk reaches the drive root or a folder it does not know.
        internal fun treeTopOf(
            parentUuid: String?,
            folderMap: Map<String, InternxtFolder>,
            rootUuid: String,
            tops: Set<String>,
        ): String? {
            var uuid = parentUuid
            val seen = HashSet<String>()
            while (uuid != null && uuid != rootUuid && seen.add(uuid)) {
                if (uuid in tops) return uuid
                uuid = folderMap[uuid]?.parentUuid
            }
            return null
        }

        // The folder a path runs through, from what the listing already holds: the folders it returned, then the
        // ancestor-only rows (spliced into [folderMap] on the way). Null when only a request can tell.
        private fun knownFolder(
            uuid: String,
            folderMap: MutableMap<String, InternxtFolder>,
            ancestors: Map<String, InternxtFolder>,
        ): InternxtFolder? = folderMap[uuid] ?: ancestors[uuid]?.also { folderMap[uuid] = it }

        // The folders above the delivered items that state.db tracks, as rows for the pass to use in place of a request.
        // [files] and [folders] are the items the pass delivered, each with its parent folder; [folderMap] and [ancestors]
        // hold what the pass already knows for certain, and they win over a row. A row is taken only along an edge that
        // state.db itself confirms: the item is tracked under that very parent. An item the engine has not seen, or has
        // seen under another folder (it was moved), says nothing about the row of its new parent, and that folder is
        // asked for, as is a delivered folder's parent that differs from the tracked one. The same goes for each step up:
        // from a folder the pass delivered it is made only where the tracked parent is the delivered one. A folder that
        // is asked for stays out of the result even when another edge confirmed it, so what the remote says of it
        // (moved, gone) is never overruled by a row. A row that is not a folder, or that the engine cannot vouch for
        // (ScanContext.trackedRow answers null), ends the climb: that folder is asked for. Trashed folders are also
        // asked for: a purged folder can leave re-delivered children behind with no new child to challenge its row.
        internal fun trackedAncestorRows(
            files: Collection<InternxtFile>,
            folders: Collection<InternxtFolder>,
            folderMap: Map<String, InternxtFolder>,
            ancestors: Map<String, InternxtFolder>,
            rootUuid: String,
            tracked: (String) -> CloudItem?,
        ): Map<String, InternxtFolder> {
            val rows = HashMap<String, CloudItem?>()

            fun row(id: String): CloudItem? = if (id in rows) rows[id] else tracked(id).also { rows[id] = it }
            val found = LinkedHashMap<String, InternxtFolder>()
            val ask = HashSet<String>()
            val climbed = HashSet<String>()

            fun climb(
                item: String,
                parent: String?,
            ) {
                var below = item
                var uuid = parent
                while (uuid != null && uuid != rootUuid) {
                    val certain = folderMap[uuid] ?: ancestors[uuid]
                    val confirmed = certain != null || row(below)?.parentId == uuid
                    if (!confirmed) ask.add(uuid)
                    val folder =
                        certain
                            ?: found[uuid].takeIf { confirmed }
                            ?: row(uuid)?.takeIf { confirmed && it.isFolder && !it.trashed }?.toResumedFolder()?.also { found[uuid] = it }
                    if (folder == null || !climbed.add(uuid)) return
                    below = uuid
                    uuid = folder.parentUuid
                }
            }
            files.forEach { climb(it.uuid, it.folderUuid) }
            folders.forEach { climb(it.uuid, it.parentUuid) }
            found.keys.removeAll(ask)
            return found
        }

        /** How [resolveAncestors] found the folders that the listing did not return: [fromRows] of the ancestor-only rows, [fetched] by request, [unfetchable] not at all. */
        internal class AncestorResolution(
            val fromRows: Int,
            val fetched: Int,
            val unfetchable: Int,
        ) {
            val needed: Int get() = fromRows + fetched + unfetchable
        }

        // Finds, before any path is built, every folder that a path of the gathered items runs through and that the
        // listing did not return, and puts it into [folderMap]. [parents] are the parent folders of the items. A chain
        // is climbed from each of them up to the root or to a folder already visited, so each folder is looked at once.
        // A folder that the ancestor-only rows [ancestors] hold is read from there. The others are asked for through
        // [fetchFolder], with at most [PER_FOLDER_REQUESTS_IN_FLIGHT] requests in flight: they used to be asked for one after the other,
        // each when the first item below it was met, which on a large drive was 949 requests in a row.
        //
        // A folder that is fetched may hang below another that is missing: the next round asks for those, and the
        // total reported to [onProgress] (done, total) grows by them. An unfetchable folder is recorded in
        // [unfetchable], and the items below it are dropped by the path building as before.
        internal suspend fun resolveAncestors(
            parents: Sequence<String?>,
            folderMap: MutableMap<String, InternxtFolder>,
            ancestors: Map<String, InternxtFolder>,
            rootUuid: String,
            unfetchable: MutableSet<String>,
            fetchFolder: suspend (String) -> InternxtFolder?,
            onProgress: ((done: Int, total: Int) -> Unit)? = null,
        ): AncestorResolution {
            val visited = HashSet<String>()
            val missing = LinkedHashSet<String>()
            var fromRows = 0

            fun climb(start: String?) {
                var uuid = start
                while (uuid != null && uuid != rootUuid && visited.add(uuid)) {
                    val fromAncestors = uuid !in folderMap && uuid in ancestors
                    val folder = knownFolder(uuid, folderMap, ancestors)
                    if (folder == null) {
                        if (uuid !in unfetchable) missing.add(uuid)
                        return
                    }
                    if (fromAncestors) fromRows++
                    uuid = folder.parentUuid
                }
            }
            parents.forEach(::climb)

            val permits = kotlinx.coroutines.sync.Semaphore(PER_FOLDER_REQUESTS_IN_FLIGHT)
            val done = AtomicInteger(0)
            val total = AtomicInteger(0)
            var fetched = 0
            var gone = 0
            while (missing.isNotEmpty()) {
                val round = missing.toList()
                missing.clear()
                total.addAndGet(round.size)
                onProgress?.invoke(done.get(), total.get())
                val answers =
                    coroutineScope {
                        round
                            .map { uuid ->
                                async {
                                    val folder = permits.withPermit { fetchFolder(uuid) }
                                    onProgress?.invoke(done.incrementAndGet(), total.get())
                                    uuid to folder
                                }
                            }.awaitAll()
                    }
                for ((uuid, folder) in answers) {
                    if (folder == null) {
                        unfetchable.add(uuid)
                        gone++
                    } else {
                        folderMap[uuid] = folder
                        fetched++
                        climb(folder.parentUuid)
                    }
                }
            }
            return AncestorResolution(fromRows = fromRows, fetched = fetched, unfetchable = gone)
        }

        // UD-317: pure converter for delta folder entries (parentPath already resolved by caller).
        internal fun folderToDeltaCloudItem(
            folder: InternxtFolder,
            parentPath: String,
            mnemonic: String? = null,
        ): CloudItem {
            val name = sanitizeName(resolvedBaseName(folder.plainName, folder.name, folder.parentUuid, mnemonic))
            return CloudItem(
                id = folder.uuid,
                name = name,
                path = "$parentPath/$name",
                size = 0,
                isFolder = true,
                modified = folder.modificationInstant,
                created = folder.creationInstant,
                hash = null,
                mimeType = null,
                deleted = folder.isTombstoned(),
                parentId = folder.parentUuid,
            )
        }

        // ---- Resumable-scan helpers ----

        // Compute the cursor to return from a delta gather. Internxt cursors
        // are ISO-8601 timestamps that the gateway uses as a "items modified
        // since X" filter. Returns `max(seenMax, requestCursor)` so:
        //  - A gather that fetched items advances to the freshest updatedAt
        //    seen across all completed pages.
        //  - A gather that fetched no items (every page empty, or every
        //    folder hit the 503 fallback) leaves the cursor where it was
        //    rather than regressing to `Instant.now()` and skipping past
        //    items modified before now.
        //  - Both null (first scan, no items) falls back to the server's time
        //    (gkrost/unidrive-cli#181; the client's clock where none is known)
        //    so the next launch doesn't ask the gateway for "since epoch".
        // Lexicographic comparison is sound because the timestamps are
        // always ISO-8601 with the same timezone suffix (`Z` from the API).
        internal fun advanceCursor(
            seenMax: String?,
            requestCursor: String?,
            serverNow: Instant? = null,
        ): String =
            when {
                seenMax != null && requestCursor != null ->
                    if (seenMax > requestCursor) seenMax else requestCursor
                seenMax != null -> seenMax
                requestCursor != null -> requestCursor
                else -> (serverNow ?: Instant.now()).toString()
            }

        // Marker format used by the page-boundary persister. Two non-negative
        // integers separated by `|`: files offset first, folders offset
        // second. Stored verbatim in `sync_state.scan_in_progress_marker` and
        // parsed back via [parseResumeOffsets] on resume.
        internal fun buildMarker(
            filesOffset: Int,
            foldersOffset: Int,
        ): String = "$filesOffset|$foldersOffset"

        internal fun parseResumeOffsets(marker: String?): ResumeOffsets {
            if (marker.isNullOrEmpty()) return ResumeOffsets(0, 0)
            val parts = marker.split('|')
            if (parts.size != 2) return ResumeOffsets(0, 0)
            // Both tokens must parse to a non-negative integer; a half-corrupt
            // marker (one token good, one bad) is treated as a fresh start so
            // a corrupted persistence write doesn't silently skip a range.
            val filesOffset = parts[0].toIntOrNull() ?: return ResumeOffsets(0, 0)
            val foldersOffset = parts[1].toIntOrNull() ?: return ResumeOffsets(0, 0)
            if (filesOffset < 0 || foldersOffset < 0) return ResumeOffsets(0, 0)
            return ResumeOffsets(filesOffset, foldersOffset)
        }

        internal data class ResumeOffsets(val filesOffset: Int, val foldersOffset: Int)

        // Convert a freshly-fetched [InternxtFile] into the [CloudItem] shape
        // that the staging slice persists. The `path` field is left empty
        // because the folder graph isn't complete mid-scan — the resume path
        // re-resolves it from `parentId` against the rebuilt graph.
        internal fun InternxtFile.toStagedCloudItem(rootUuid: String): CloudItem {
            val baseName = sanitizeName(plainName ?: name ?: "")
            val cleanType = type?.let { sanitizeName(it) }
            val resolvedName =
                if (!cleanType.isNullOrEmpty() && !baseName.endsWith(".$cleanType")) {
                    "$baseName.$cleanType"
                } else {
                    baseName
                }
            // Root-level files have folderUuid == null; collapse to null
            // parentId so the staged row mirrors what a fully-resolved
            // CloudItem from this file would carry. Items whose parent is
            // the bucket root surface as parentId=null too.
            val pid = folderUuid?.takeIf { it != rootUuid }
            return CloudItem(
                id = uuid,
                name = resolvedName,
                path = "/$resolvedName",
                size = size?.toLongOrNull() ?: 0,
                isFolder = false,
                modified = modificationInstant,
                created = creationInstant,
                hash = null,
                mimeType = null,
                parentId = pid,
            )
        }

        // Folder equivalent of [toStagedCloudItem].
        internal fun InternxtFolder.toStagedCloudItem(rootUuid: String): CloudItem {
            val resolvedName = sanitizeName(plainName ?: name ?: "")
            val pid = parentUuid?.takeIf { it != rootUuid }
            return CloudItem(
                id = uuid,
                name = resolvedName,
                path = "/$resolvedName",
                size = 0,
                isFolder = true,
                modified = modificationInstant,
                created = creationInstant,
                hash = null,
                mimeType = null,
                parentId = pid,
            )
        }

        // Re-inflate a resumed-from-staging [CloudItem] back into the
        // Internxt model object that the gather loop consumes. The staged
        // row preserves cloud identity (uuid, plainName, parentUuid,
        // size, modified, hash) — everything the folder-graph reconstruction
        // needs. `updatedAt` is unknown post-staging (the engine strips it),
        // which is fine: the cursor-advance computation in delta() looks at
        // the freshly-fetched pages, which set the high-water mark.
        // The modification time is the string the server sent when the row carries it (archive#1039): the version
        // token of the file is built from it, and the Instant's toString() drops a zero fraction.
        internal fun CloudItem.toResumedFile(): InternxtFile {
            val raw = stagedModificationTime(hash)
            return InternxtFile(
                uuid = id,
                plainName = name.substringBeforeLast('.', name),
                name = name.substringBeforeLast('.', name),
                type = name.substringAfterLast('.', ""),
                folderUuid = parentId,
                size = size.toString(),
                modificationTime = if (raw == null) modified?.toString() else raw.takeIf { it.isNotEmpty() },
                updatedAt = null,
            )
        }

        internal fun CloudItem.toResumedFolder(): InternxtFolder =
            InternxtFolder(
                uuid = id,
                plainName = name,
                name = name,
                parentUuid = parentId,
                modificationTime = modified?.toString(),
                updatedAt = null,
            )

        // An ancestor-only record comes back from the staging as the trashed folder it was.
        internal fun CloudItem.toResumedAncestor(): InternxtFolder = toResumedFolder().copy(status = "TRASHED")
    }
}

// Internxt finishUpload idempotency: reconcile after HTTP-409 MissingUploadsError
// rather than re-finishing blindly. A network drop after server-side commit but
// before client reads the response will surface as 409 on retry; the original
// commit landed and a second finish would orphan the first BucketEntry. This
// helper detects that case, lists the parent folder, and matches by
// (plainName, type, size, recency) to recover the original fileId.
//
// Window is 5 minutes from [startedAt] (the upload's stage-1 timestamp), wide
// enough to absorb retry ladders without colliding with stale same-name entries.
// If no entry matches after the bounded reconcile, the session has no shards
// left to finish. Refuse the upload so hydration releases its slot and persists
// the current content's refusal stamp instead of replaying that session.
//
// Provider call site is responsible for fresh-indexBytes pipeline retry on hard
// failure (the IV-pinning constraint forbids holding stale indexBytes across a
// re-encrypt). This helper holds the bridge-side identity stable across the
// reconcile probe — same indexHex/hashHex/shardUuid means the reconciled
// BucketEntry decrypts with the same key/iv as the original attempt.
internal sealed class ReconcileResult {
    data class Found(val bucketEntry: org.krost.unidrive.internxt.model.BucketEntry) : ReconcileResult()

    data class FoundDisambiguated(
        val bucketEntry: org.krost.unidrive.internxt.model.BucketEntry,
        val siblingFileIds: List<String>,
    ) : ReconcileResult()

    data object NotFoundNoNameMatch : ReconcileResult()

    data object NotFoundWithNameMatch : ReconcileResult()
}

private val finishUploadLog = org.slf4j.LoggerFactory.getLogger("org.krost.unidrive.internxt.FinishUploadIdempotency")

internal const val RECONCILE_WINDOW_MS: Long = 5L * 60 * 1000

internal const val RECONCILE_RELIST_DELAY_MS: Long = 2_000L

// gkrost/unidrive-cli#134: the wait before the second look at an upload parent the first walk did not find.
internal const val PARENT_RELIST_DELAY_MS: Long = 2_000L

// True only for the IOException class that proves the request reached the server
// and a response began before the connection dropped — i.e. the body was lost in
// transit, not the request. Reconciling-by-name is sound here because the server
// may have committed our entry. A pre-commit failure (connect refused/reset,
// connect timeout) carries no such proof and must NOT adopt a same-name listing
// entry — a coincidental recent same-name/size remote file would be wrongly
// claimed as ours. Keyed on the truncation marker the bridge emits + the parse
// failure Ktor raises when a response is cut mid-body (verified against the live
// "server prematurely closed the connection" class this fix targets).
internal fun isLostResponse(e: java.io.IOException): Boolean {
    val msg = (e.message ?: "").lowercase()
    return msg.contains("prematurely closed") ||
        msg.contains("failed to parse http response") ||
        msg.contains("premature end") ||
        msg.contains("connection closed prematurely") ||
        msg.contains("unexpected end of stream")
}

// List [folderUuid] and match a just-committed bucket entry by
// (plainName, type, size, recency-window). Shared by both finishUpload recovery
// paths — a 409 MissingUploadsError and a lost/truncated response surface the
// same question ("did the commit land server-side?") and answer it identically.
// A NotFoundWithNameMatch (a stale same-name entry that fails size/window) gets
// one bounded re-list to absorb the bridge's eventual-consistency window.
// Listing failures propagate to the caller, which maps them to its own error.
internal suspend fun reconcileLandedEntry(
    api: InternxtApiService,
    folderUuid: String,
    plainName: String,
    ext: String?,
    fileSize: Long,
    startedAt: java.time.Instant,
): ReconcileResult {
    val window = startedAt.minusMillis(RECONCILE_WINDOW_MS)
    val listing = api.getFolderContents(folderUuid)
    val reconciled = pickReconcileCandidate(listing, plainName, ext, fileSize, window)
    if (reconciled !is ReconcileResult.NotFoundWithNameMatch) return reconciled
    kotlinx.coroutines.delay(RECONCILE_RELIST_DELAY_MS)
    val relisted = api.getFolderContents(folderUuid)
    return pickReconcileCandidate(relisted, plainName, ext, fileSize, window)
}

internal suspend fun commitWithRetry(
    api: InternxtApiService,
    bucket: String,
    folderUuid: String,
    plainName: String,
    ext: String?,
    fileSize: Long,
    @Suppress("UNUSED_PARAMETER") encryptedSize: Long,
    indexHex: String,
    hashHex: String,
    shardUuid: String,
    startedAt: java.time.Instant,
    clock: () -> java.time.Instant = { java.time.Instant.now() },
    // archive#578: the finish call itself, parameterized so the multipart session reuses the
    // same lost-response / 409 reconcile. Defaults to the single-shard finish.
    finishBlock: suspend () -> org.krost.unidrive.internxt.model.BucketEntry = {
        // archive#946: the shard hash on the wire is RIPEMD-160(SHA-256(ciphertext)) like the official clients' (40 hex); [hashHex], the SHA-256
        // digest pinned in the tombstone, stays what the encrypt pass produced.
        api.finishUpload(bucket, indexHex, ShardHashWire.forFinish(hashHex), shardUuid)
    },
): org.krost.unidrive.internxt.model.BucketEntry {
    val attemptStart = clock()
    val first =
        try {
            finishBlock()
        } catch (ioe: java.io.IOException) {
            // Only a LOST-RESPONSE IOException (truncated / "server prematurely
            // closed the connection") proves the request reached the server and a
            // response began: the bridge may have committed the bucket entry, but
            // we never received its fileId. retryShardCommit would re-throw this
            // after its attempt ladder, and the engine would record a synthetic
            // local: ghost row (remote_size=0) — the same data-loss class the 409
            // reconcile below guards against. A pre-commit failure (connect
            // refused/reset/timeout) carries no such proof, so it must NOT adopt a
            // listing entry by name+size — a coincidental recent same-name/size
            // remote file would be wrongly claimed as ours. Surface those verbatim
            // so the pipeline retries with pinned indexBytes.
            if (!isLostResponse(ioe)) throw ioe
            // Re-resolve the parent by (name, type, size, window); adopt the landed
            // bucket entry iff it provably matches what we just uploaded, else
            // surface the original failure (the pipeline retries).
            finishUploadLog.warn(
                "finishUpload response lost for {} under folder={} ({}); " +
                    "re-resolving parent to check for a landed commit",
                plainName,
                folderUuid,
                ioe.message,
            )
            val reconciled =
                try {
                    reconcileLandedEntry(api, folderUuid, plainName, ext, fileSize, startedAt)
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (listingErr: Exception) {
                    finishUploadLog.warn(
                        "finishUpload-lost-response reconcile listing failed for {}: {}; propagating original failure",
                        folderUuid,
                        listingErr.message,
                    )
                    throw ioe
                }
            when (reconciled) {
                is ReconcileResult.Found -> {
                    finishUploadLog.warn(
                        "finishUpload landed server-side despite the lost response for {} (fileId={}); adopting",
                        plainName,
                        reconciled.bucketEntry.id,
                    )
                    return reconciled.bucketEntry
                }
                is ReconcileResult.FoundDisambiguated -> {
                    finishUploadLog.warn(
                        "finishUpload-lost-response reconcile matched multiple candidates for {} under {}; " +
                            "selecting youngest fileId={}, others={}",
                        plainName,
                        folderUuid,
                        reconciled.bucketEntry.id,
                        reconciled.siblingFileIds,
                    )
                    return reconciled.bucketEntry
                }
                ReconcileResult.NotFoundNoNameMatch,
                ReconcileResult.NotFoundWithNameMatch,
                -> {
                    finishUploadLog.warn(
                        "no landed commit found after finishUpload lost its response for {} under {}; " +
                            "propagating original failure (pipeline retries with pinned indexBytes)",
                        plainName,
                        folderUuid,
                    )
                    throw ioe
                }
            }
        } catch (e: InternxtApiException) {
            if (!isMissingUploadsError(e)) throw e

            finishUploadLog.info(
                "finishUpload returned 409 MissingUploadsError for {} under folder={}; " +
                    "treating as probable prior commit, reconciling{}",
                plainName,
                folderUuid,
                e.requestId?.let { " requestId=$it" } ?: "",
            )

            val reconciled =
                try {
                    reconcileLandedEntry(api, folderUuid, plainName, ext, fileSize, startedAt)
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (listingErr: Exception) {
                    finishUploadLog.warn(
                        "finishUpload-409 reconcile listing failed for {}: {}; propagating original 409{}",
                        folderUuid,
                        listingErr.message,
                        e.requestId?.let { " requestId=$it" } ?: "",
                    )
                    throw InternxtApiException(
                        "finishUpload 409 MissingUploadsError, reconcile listing failed: ${listingErr.message}",
                        statusCode = 409,
                        requestId = e.requestId,
                        cause = listingErr,
                    )
                }

            when (reconciled) {
                is ReconcileResult.Found -> {
                    val elapsedMs = (clock().toEpochMilli() - attemptStart.toEpochMilli()).coerceAtLeast(0L)
                    finishUploadLog.warn(
                        "reconciled finishUpload-409 to fileId={} after {}ms (size={}, window=5m){}",
                        reconciled.bucketEntry.id,
                        elapsedMs,
                        fileSize,
                        e.requestId?.let { " requestId=$it" } ?: "",
                    )
                    return reconciled.bucketEntry
                }
                is ReconcileResult.FoundDisambiguated -> {
                    finishUploadLog.warn(
                        "reconcile matched multiple candidates for {} under {}; selecting youngest fileId={}, others={}{}",
                        plainName,
                        folderUuid,
                        reconciled.bucketEntry.id,
                        reconciled.siblingFileIds,
                        e.requestId?.let { " requestId=$it" } ?: "",
                    )
                    return reconciled.bucketEntry
                }
                ReconcileResult.NotFoundNoNameMatch,
                ReconcileResult.NotFoundWithNameMatch,
                -> {
                    finishUploadLog.warn(
                        "could not reconcile after finishUpload-409 for {} under {}; " +
                            "refusing exhausted upload session without another finish{}",
                        plainName,
                        folderUuid,
                        e.requestId?.let { " requestId=$it" } ?: "",
                    )
                    throw MissingUploadSessionException(
                        "finishUpload 409 MissingUploadsError after reconcile NotFound: ${e.message}",
                        cause = e,
                        requestId = e.requestId,
                    )
                }
            }
        }
    return first
}

/** A 409 with no landed Drive entry means this Bridge session is exhausted; the caller may start one fresh session. */
private class MissingUploadSessionException(
    message: String,
    cause: Throwable,
    requestId: String?,
) : org.krost.unidrive.PermanentUploadFailureException(message, cause, requestId)

/** A part PUT the storage layer answered 403: the session's presigned URLs are dead (expired signature). A fresh session can re-put. */
private class PresignedUrlsDeadException(
    message: String,
    cause: Throwable,
) : ProviderException(message, cause)

internal fun pickReconcileCandidate(
    listing: org.krost.unidrive.internxt.model.FolderContentResponse,
    plainName: String,
    ext: String?,
    fileSize: Long,
    windowLowerBound: java.time.Instant,
): ReconcileResult {
    val nameMatches = listing.files.filter { it.plainName == plainName && it.status == "EXISTS" }
    val candidates =
        nameMatches.filter { f ->
            f.type == ext &&
                f.size?.toLongOrNull() == fileSize &&
                f.creationInstant != null &&
                !f.creationInstant.isBefore(windowLowerBound) &&
                f.fileId != null
        }
    return when (candidates.size) {
        0 -> if (nameMatches.isEmpty()) ReconcileResult.NotFoundNoNameMatch else ReconcileResult.NotFoundWithNameMatch
        1 -> {
            val match = candidates.single()
            ReconcileResult.Found(
                bucketEntry =
                    org.krost.unidrive.internxt.model.BucketEntry(
                        id = match.fileId!!,
                        index = "",
                        bucket = match.bucket ?: "",
                        name = match.name ?: "",
                    ),
            )
        }
        else -> {
            val youngest = candidates.maxBy { it.creationInstant!! }
            val siblings = candidates.filter { it !== youngest }.mapNotNull { it.fileId }
            ReconcileResult.FoundDisambiguated(
                bucketEntry =
                    org.krost.unidrive.internxt.model.BucketEntry(
                        id = youngest.fileId!!,
                        index = "",
                        bucket = youngest.bucket ?: "",
                        name = youngest.name ?: "",
                    ),
                siblingFileIds = siblings,
            )
        }
    }
}

private fun isMissingUploadsError(e: InternxtApiException): Boolean =
    e.statusCode == 409 &&
        e.message?.let { it.contains("MissingUploadsError") || it.contains("Missing uploads") } == true

// Files and folders a scoped bootstrap gathered: the folders leading to each scope
// root, each root itself, and everything under the roots.
internal class ScopedInventory(
    val files: List<InternxtFile>,
    val folders: List<InternxtFolder>,
)

/**
 * The path walk of [InternxtProvider] listed a folder successfully and the next segment of the path was not among its
 * live child folders: a positive proof that the folder [missingFolder] (and everything below it) does not exist in the
 * cloud now. Never raised for a listing that failed. The message keeps the walk's long-standing wording
 * (`Folder not found: <segment> in <path>`), which callers outside this module still match.
 */
internal class FolderNotFoundException(
    segment: String,
    path: String,
    /** The path of the first folder of [path] that the walk did not find. */
    val missingFolder: String,
) : ProviderException("Folder not found: $segment in $path")
