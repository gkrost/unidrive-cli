package org.krost.unidrive.sync

import org.krost.unidrive.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

// The in-memory provider the engine tests drive. A test fixture (java-test-fixtures) so the mount
// front-end's tests in :app:hydration build their SyncEngine host on the same fake as the mirror
// tests here, without a test dependency from this module on :app:hydration.
class FakeCloudProvider : CloudProvider {
    override val id = "fake"
    override val displayName = "Fake"
    override var isAuthenticated = true

    var supportsFastBootstrap = false
    var deltaFromLatestCalls = 0
    var deltaCalls = 0
    val deltaCursorArguments = mutableListOf<String?>()

    // archive#396: null = hashless provider (Internxt-style, the default); set before the
    // engine is constructed to stand in for a hash-capable one (OneDrive-style).
    var hashAlgorithmOverride: HashAlgorithm? = null

    override fun hashAlgorithm(): HashAlgorithm? = hashAlgorithmOverride

    // archive#309: the case policy the fake declares; the interface default (folding) unless a test sets another.
    override var caseSensitivity: CaseSensitivity = CaseSensitivity.INSENSITIVE_PRESERVING

    override fun capabilities(): Set<org.krost.unidrive.Capability> =
        buildSet {
            add(org.krost.unidrive.Capability.Delta)
            add(org.krost.unidrive.Capability.VerifyItem)
            if (supportsFastBootstrap) add(org.krost.unidrive.Capability.FastBootstrap)
        }

    override suspend fun deltaFromLatest(scanContext: org.krost.unidrive.ScanContext?): org.krost.unidrive.CapabilityResult<DeltaPage> {
        deltaFromLatestCalls++
        return if (supportsFastBootstrap) {
            org.krost.unidrive.CapabilityResult.Success(
                DeltaPage(items = emptyList(), cursor = deltaCursor, hasMore = false),
            )
        } else {
            org.krost.unidrive.CapabilityResult.Unsupported(
                org.krost.unidrive.Capability.FastBootstrap,
                "fake provider opts out",
            )
        }
    }

    var deltaItems = listOf<CloudItem>()
    var deltaCursor = "cursor-1"

    // archive#321: runs at the top of every delta() call, so a test can land a state.db write
    // mid-gather (a mount write-back completing while a long enumeration walks).
    var duringDelta: (() -> Unit)? = null

    // Multi-page delta driver: when non-empty, successive delta() calls
    // return successive entries (last page has hasMore=false). Lets a test
    // drive the streaming gather across ≥2 pages (the single-page deltaItems
    // path always returns hasMore=false). Each entry is (items, cursor).
    var deltaPages: List<Pair<List<CloudItem>, String>> = emptyList()
    private var deltaPageIndex = 0
    val uploadedPaths = mutableListOf<String>()
    val uploadAttempts = mutableListOf<String>()
    val uploadThrowByPath = mutableMapOf<String, Throwable>()
    val deletedPaths = mutableListOf<String>()
    val files = mutableMapOf<String, ByteArray>()

    // archive#123: created-folder order, captured under the bounded-concurrency
    // create-folder run. Thread-safe because createFolder runs from
    // concurrent coroutines; the synchronized list preserves completion
    // order for the parent-before-child ordering assertion.
    val createdFolders: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    // Paths whose createFolder must throw, simulating a single provider
    // mkdir failure mid-bulk-import (the rest of the plan must still apply).
    val createFolderFailPaths: MutableSet<String> = mutableSetOf()

    var downloadFailCount = 0
    var uploadFailCount = 0
    var deleteFailCount = 0
    var deltaFailCount = 0
    var authFailOnDownload = false
    var authFailOnUpload = false
    // archive#110: when true, delta() throws DeltaCursorExpiredException if called
    // with a non-null cursor (simulating an aged-out OneDrive delta cursor),
    // and clears itself after the first throw so the recovery full-enum
    // (cursor=null) proceeds normally.
    var deltaThrowExpiredOnResumedCursor = false

    // Paths for which downloadById / download should throw the
    // permanent-failure signal (e.g. Internxt "Bucket entry … not found").
    // Mirrors the live 1,248-retry incident shape — the engine must
    // quarantine the row instead of retrying forever.
    val permanentDownloadFailurePaths: MutableSet<String> = mutableSetOf()

    override suspend fun authenticate() {}

    override suspend fun logout() {}

    // archive#116: pre-existing remote tree the fast-bootstrap planner can't see.
    // Keyed by parent path; listChildren returns the configured children so a
    // test can stage top-level folders that already exist on the cloud.
    val childrenByParent = mutableMapOf<String, List<CloudItem>>()

    // archive#419: every listChildren() path, and per-path exceptions to throw from it, so a
    // reaper test can prove which directories were probed and simulate a 404 on one.
    val listChildrenCalls = mutableListOf<String>()
    val listChildrenThrow = mutableMapOf<String, Throwable>()

    override suspend fun listChildren(path: String): List<CloudItem> {
        listChildrenCalls.add(path)
        listChildrenThrow[path]?.let { throw it }
        return childrenByParent[path] ?: emptyList()
    }

    // archive#504 review: when set, getMetadata throws it (a remote item that is gone, an outage).
    var getMetadataError: Exception? = null

    // archive#531: folders that exist remotely but are invisible to the delta (the fast-bootstrap
    // adopted cursor never enumerated them) — getMetadata sees them, the delta does not.
    val remoteFoldersExisting = mutableSetOf<String>()

    // gkrost/unidrive-cli#57: what getMetadata answers for a path before the delta items: the provider's own current view,
    // which may be newer than the delta the engine gathered.
    val metadataOverride = mutableMapOf<String, CloudItem>()

    override suspend fun getMetadata(path: String): CloudItem {
        getMetadataError?.let { throw it }
        metadataOverride[path]?.let { return it }
        if (path in remoteFoldersExisting) {
            return CloudItem(
                id = "exists-" + path,
                name = path.substringAfterLast("/"),
                path = path,
                size = 0,
                isFolder = true,
                modified = Instant.now(),
                created = Instant.now(),
                hash = null,
                mimeType = null,
            )
        }
        return deltaItems.first { it.path == path }
    }

    // Runs at the top of every download, before anything is written. A test throws an Error here to stand in
    // for a process that dies mid-pass: none of the engine's catch clauses (they take Exception) runs for it.
    var beforeDownload: ((remotePath: String) -> Unit)? = null

    override suspend fun download(
        remotePath: String,
        destination: Path,
    ): Long {
        beforeDownload?.invoke(remotePath)
        if (authFailOnDownload) throw AuthenticationException("Token expired")
        if (remotePath in permanentDownloadFailurePaths) {
            throw org.krost.unidrive.PermanentDownloadFailureException(
                "Bucket entry for $remotePath not found",
            )
        }
        if (downloadFailCount > 0) {
            downloadFailCount--
            throw ProviderException("Network timeout on download")
        }
        downloadByPathCalls.add(remotePath)
        val content = files[remotePath] ?: throw ProviderException("Not found: $remotePath")
        Files.createDirectories(destination.parent)
        Files.write(destination, content)
        return content.size.toLong()
    }

    // UD-225b: track id-vs-path dispatch separately so tests can verify the
    // engine takes the fast/robust path when the action carries a remoteId.
    // Default base impl in CloudProvider delegates to download(); this
    // override lets us count both call paths distinctly.
    val downloadByIdCalls = mutableListOf<Pair<String, String>>() // (remoteId, remotePath)
    val downloadByPathCalls = mutableListOf<String>()
    var afterDownloadWrite: ((Path) -> Unit)? = null

    // C8 follow-up: observe per-path download concurrency so a test can prove
    // same-path transfers are serialized. downloadDelayMs opens an overlap window
    // (a suspension point) so a non-serialized race would surface peak concurrency 2.
    var downloadDelayMs: Long = 0L
    val maxConcurrentDownloadsByPath = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val activeDownloadsByPath = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private suspend fun trackDownloadConcurrency(remotePath: String) {
        val active = (activeDownloadsByPath[remotePath] ?: 0) + 1
        activeDownloadsByPath[remotePath] = active
        maxConcurrentDownloadsByPath[remotePath] =
            maxOf(maxConcurrentDownloadsByPath[remotePath] ?: 0, active)
        if (downloadDelayMs > 0) kotlinx.coroutines.delay(downloadDelayMs)
    }

    override suspend fun downloadById(
        remoteId: String,
        remotePath: String,
        destination: Path,
    ): Long = downloadByIdWithHash(remoteId, remotePath, destination).bytesWritten

    override suspend fun downloadByIdWithHash(
        remoteId: String,
        remotePath: String,
        destination: Path,
        targetMtimeMillis: Long?,
    ): DownloadReceipt {
        beforeDownload?.invoke(remotePath)
        if (authFailOnDownload) throw AuthenticationException("Token expired")
        if (remotePath in permanentDownloadFailurePaths) {
            throw org.krost.unidrive.PermanentDownloadFailureException(
                "Bucket entry for $remotePath not found",
            )
        }
        if (downloadFailCount > 0) {
            downloadFailCount--
            throw ProviderException("Network timeout on download")
        }
        downloadByIdCalls.add(remoteId to remotePath)
        trackDownloadConcurrency(remotePath)
        val content = files[remotePath] ?: throw ProviderException("Not found: $remotePath")
        Files.createDirectories(destination.parent)
        Files.write(destination, content)
        targetMtimeMillis?.let { Files.setLastModifiedTime(destination, java.nio.file.attribute.FileTime.fromMillis(it)) }
        val receipt = DownloadReceipt(
            bytesWritten = content.size.toLong(),
            plaintextSha256 = java.security.MessageDigest.getInstance("SHA-256").digest(content)
                .joinToString("") { "%02x".format(it) },
            stagedSize = Files.size(destination),
            stagedMtimeMillis = Files.getLastModifiedTime(destination).toMillis(),
            mtimeAppliedBeforePlacement = targetMtimeMillis != null,
        )
        afterDownloadWrite?.invoke(destination)
        activeDownloadsByPath[remotePath] = (activeDownloadsByPath[remotePath] ?: 1) - 1
        return receipt
    }

    var lastUploadExistingRemoteId: String? = null
        private set

    // gkrost/unidrive-cli#57: (remotePath, ifMatchETag) of every upload attempt, and a stand-in for a provider with conditional
    // writes: when it answers true for (remotePath, existingRemoteId, ifMatchETag) the upload is refused as a conflict.
    val uploadIfMatches = mutableListOf<Pair<String, String?>>()
    var refuseUpload: ((remotePath: String, existingRemoteId: String?, ifMatchETag: String?) -> Boolean)? = null

    // archive#417: when set, upload() reports this as the item's modified time (what a real
    // provider returns for the write: the time the server will list next).
    var uploadModified: Instant? = null

    // Runs after upload() has read the bytes it sends, to model a local edit that lands
    // while a long upload is still in flight.
    var duringUpload: ((Path) -> Unit)? = null

    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        existingRemoteId: String?,
        ifMatchETag: String?,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem {
        uploadAttempts.add(remotePath)
        uploadIfMatches.add(remotePath to ifMatchETag)
        uploadThrowByPath[remotePath]?.let { throw it }
        if (refuseUpload?.invoke(remotePath, existingRemoteId, ifMatchETag) == true) {
            throw RemoteConflictException("'$remotePath' changed since it was last synced")
        }
        lastUploadExistingRemoteId = existingRemoteId
        if (authFailOnUpload) throw AuthenticationException("Token expired")
        if (uploadFailCount > 0) {
            uploadFailCount--
            throw ProviderException("Network timeout on upload")
        }
        uploadedPaths.add(remotePath)
        val content = Files.readAllBytes(localPath)
        files[remotePath] = content
        duringUpload?.invoke(localPath)
        return CloudItem(
            id = "id-$remotePath",
            name = remotePath.substringAfterLast("/"),
            path = remotePath,
            size = content.size.toLong(),
            isFolder = false,
            modified = uploadModified ?: Instant.now(),
            created = Instant.now(),
            hash = "uploaded",
            mimeType = null,
        )
    }

    // When non-null, the NEXT delete() call throws this exception (single-use, cleared after throw).
    var deleteThrow: Throwable? = null

    // archive#419: per-path variant of deleteThrow (not single-use), for failing one specific delete.
    val deleteThrowByPath = mutableMapOf<String, Throwable>()

    override suspend fun delete(remotePath: String, ifMatchETag: String?) {
        deleteThrowByPath[remotePath]?.let { throw it }
        deleteThrow?.also { deleteThrow = null; throw it }
        if (deleteFailCount > 0) {
            deleteFailCount--
            throw ProviderException("Network timeout on delete")
        }
        deletedPaths.add(remotePath)
    }

    override suspend fun createFolder(path: String): CloudItem {
        if (path in createFolderFailPaths) {
            throw ProviderException("Simulated createFolder failure for $path")
        }
        if (path in remoteFoldersExisting) {
            // the live archive#531 shape: 409 nameAlreadyExists from OneDrive
            throw ProviderException("Create folder failed: 409 Conflict - nameAlreadyExists")
        }
        createdFolders.add(path)
        return CloudItem(
            id = "id-$path",
            name = path.substringAfterLast("/"),
            path = path,
            size = 0,
            isFolder = true,
            modified = Instant.now(),
            created = Instant.now(),
            hash = null,
            mimeType = null,
        )
    }

    val movedPaths = mutableListOf<Pair<String, String>>() // (fromPath, toPath)

    // gkrost/unidrive-cli#64 item 3: a move answers like a real provider's. A source that is not there and a
    // target that already exists are refused; a moved item keeps its id and, for a file, its bytes and size;
    // a moved folder takes the files below it along. A file the fake does not hold is not silently answered
    // with a folder-shaped item any more.
    private fun samePath(
        a: String,
        b: String,
    ): Boolean = if (caseSensitivity == CaseSensitivity.SENSITIVE) a == b else a.equals(b, ignoreCase = true)

    private fun samePathOrUnder(
        a: String,
        prefix: String,
    ): Boolean =
        samePath(a, prefix) ||
            (
                a.length > prefix.length &&
                    a.substring(0, prefix.length + 1).equals("$prefix/", ignoreCase = caseSensitivity != CaseSensitivity.SENSITIVE)
            )

    // Paths the fake models as alive: the files it holds, folders a test staged or the engine created, the
    // current delta listing, the metadata overrides — minus what the fake saw deleted.
    private fun alivePaths(): List<String> {
        val known = files.keys + remoteFoldersExisting + createdFolders + deltaItems.map { it.path } + metadataOverride.keys
        return known.filter { p -> deletedPaths.none { d -> p == d || p.startsWith("$d/") } }
    }

    private fun targetOccupied(
        fromPath: String,
        toPath: String,
    ): Boolean {
        if (samePath(fromPath, toPath)) return false // the item itself: a case-only rename
        return alivePaths().any { samePathOrUnder(it, toPath) }
    }

    private suspend fun doMove(
        fromPath: String,
        toPath: String,
        carriedId: String?,
    ): CloudItem {
        val content = files[fromPath]
        val subtree =
            if (content != null) {
                emptyMap()
            } else {
                files.filterKeys { it.startsWith("$fromPath/") }
            }
        val folderIsKnown =
            fromPath in remoteFoldersExisting ||
                createdFolders.contains(fromPath) ||
                deltaItems.any { it.path == fromPath && it.isFolder } ||
                metadataOverride[fromPath]?.isFolder == true
        if (content == null && subtree.isEmpty() && !folderIsKnown) {
            throw ProviderException("Not found: $fromPath")
        }
        if (targetOccupied(fromPath, toPath)) {
            throw ProviderException("Move target already exists: $toPath")
        }
        files.remove(fromPath)
        subtree.keys.forEach { files.remove(it) }
        subtree.forEach { (p, bytes) -> files[toPath + p.substring(fromPath.length)] = bytes }
        content?.let { files[toPath] = it }
        val movedFolderMarkers = remoteFoldersExisting.filter { it == fromPath || it.startsWith("$fromPath/") }
        remoteFoldersExisting.removeAll(movedFolderMarkers.toSet())
        movedFolderMarkers.forEach { remoteFoldersExisting.add(toPath + it.substring(fromPath.length)) }
        movedPaths.add(Pair(fromPath, toPath))
        val id = carriedId ?: "id-$fromPath"
        return if (content != null) {
            CloudItem(
                id = id,
                name = toPath.substringAfterLast("/"),
                path = toPath,
                size = content.size.toLong(),
                isFolder = false,
                modified = Instant.now(),
                created = Instant.now(),
                hash = "uploaded",
                mimeType = null,
            )
        } else {
            CloudItem(
                id = id,
                name = toPath.substringAfterLast("/"),
                path = toPath,
                size = 0,
                isFolder = true,
                modified = Instant.now(),
                created = Instant.now(),
                hash = null,
                mimeType = null,
            )
        }
    }

    override suspend fun move(
        fromPath: String,
        toPath: String,
    ): CloudItem = doMove(fromPath, toPath, carriedId = null)

    // A file the fake holds moves with its bytes and keeps the id the caller tracks; a folder moves its
    // subtree and keeps the id it was created or listed with.
    override suspend fun moveById(
        remoteId: String,
        fromPath: String,
        toPath: String,
    ): CloudItem = doMove(fromPath, toPath, carriedId = remoteId)

    // UD-360: when set, delta() returns DeltaPage(complete=false) so that
    // tests can exercise the engine's partial-gather suppression path.
    var deltaComplete = true

    // The remote ids delta() reports as unreachable (DeltaPage.unreachableIds): listed by the
    // server, but with no place in the tree because a folder above them is gone for good.
    var deltaUnreachableIds: Set<String> = emptySet()

    // The entries delta() could not read (DeltaPage.skippedPaths, archive#1234): named in the page, so a
    // test can pin that the engine persists them for the status.
    var deltaSkippedPaths: List<String> = emptyList()

    // archive#422: stands in for a provider whose delta() lists the whole tree on every call and
    // never sends tombstones (localfs).
    var deltaFullListing = false
    override val deltaIsFullListing: Boolean get() = deltaFullListing

    // Resumable-scan instrumentation: captures the ScanContext the engine
    // passed in (resume_marker, # of resumedItems) so tests can verify
    // start-from-scratch vs resume behavior.
    var lastScanContext: org.krost.unidrive.ScanContext? = null
        private set

    // Optional per-page persistence simulation: if set, delta() pushes
    // each "page" through the staging callback before returning, so a
    // test can assert what landed in the staging slice.
    var stagedPages: List<Pair<List<CloudItem>, String>> = emptyList()

    // Mid-scan-crash simulator: run the stagedPages persistPage loop,
    // THEN throw, mimicking a daemon that persisted partial pages before
    // the next API call failed.
    var persistThenFail: Boolean = false

    override suspend fun delta(
        cursor: String?,
        onPageProgress: ((itemsSoFar: Int) -> Unit)?,
        scanContext: org.krost.unidrive.ScanContext?,
    ): DeltaPage {
        deltaCalls++
        deltaCursorArguments.add(cursor)
        lastScanContext = scanContext
        duringDelta?.invoke()
        if (deltaThrowExpiredOnResumedCursor && cursor != null) {
            deltaThrowExpiredOnResumedCursor = false // self-clearing; recovery pass uses cursor=null
            throw org.krost.unidrive.DeltaCursorExpiredException("410 Gone — delta token is too old (test)")
        }
        if (deltaFailCount > 0) {
            deltaFailCount--
            throw ProviderException("Network timeout on delta")
        }
        // Drive the engine's persistPage callback so a test can pin that
        // staged rows land in scan_staging.
        if (scanContext != null) {
            for ((page, marker) in stagedPages) {
                scanContext.persistPage(page, marker)
            }
        }
        if (persistThenFail) {
            throw ProviderException("Simulated mid-scan crash after partial persistence")
        }
        if (deltaPages.isNotEmpty()) {
            val idx = deltaPageIndex.coerceAtMost(deltaPages.size - 1)
            deltaPageIndex = (deltaPageIndex + 1).coerceAtMost(deltaPages.size)
            val (items, pageCursor) = deltaPages[idx]
            return DeltaPage(
                items = items,
                cursor = pageCursor,
                hasMore = idx < deltaPages.size - 1,
                complete = deltaComplete,
                unreachableIds = deltaUnreachableIds,
                skippedPaths = deltaSkippedPaths,
            )
        }
        return DeltaPage(
            items = deltaItems,
            cursor = deltaCursor,
            hasMore = false,
            complete = deltaComplete,
            unreachableIds = deltaUnreachableIds,
            skippedPaths = deltaSkippedPaths,
        )
    }

    override suspend fun quota() = QuotaInfo(total = 1000, used = 100, remaining = 900)

    val remoteIdExists = mutableMapOf<String, Boolean>()

    override suspend fun verifyItemExists(remoteId: String): org.krost.unidrive.CapabilityResult<Boolean> =
        org.krost.unidrive.CapabilityResult
            .Success(remoteIdExists[remoteId] ?: true)

    // Captured callback from registerRemoteWakeListener — tests fire it
    // directly to simulate the provider observing remote events.
    var registeredRemoteChangeCallback: (() -> Unit)? = null

    override fun onRemoteChangeHint(callback: () -> Unit) {
        registeredRemoteChangeCallback = callback
    }
}
