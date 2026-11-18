package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** An in-memory remote: a flat folder whose full listing is the delta. */
internal class MemProvider : CloudProvider {
    override val id = "mem"
    override val displayName = "Mem"
    override var isAuthenticated = true
    val remote = linkedMapOf<String, ByteArray>()
    val items = linkedMapOf<String, CloudItem>()
    val deleted = mutableListOf<String>()
    val downloads = mutableListOf<String>()
    private var version = 0

    // When set, upload() suspends here before storing anything: holds a background upload in its slot.
    @Volatile var uploadGate: CompletableDeferred<Unit>? = null

    // When set, delta() suspends here: a full scan that has not finished (the first scan of a big drive).
    @Volatile var deltaGate: CompletableDeferred<Unit>? = null

    // When set, download() signals downloadReached and suspends on downloadRelease before writing: holds a
    // hydration's download mid-transfer, with the path's hydration lock held.
    @Volatile var downloadReached: CompletableDeferred<Unit>? = null
    @Volatile var downloadRelease: CompletableDeferred<Unit>? = null

    // When set, runs on the written staging file before download() returns (e.g. to pin its modification time).
    @Volatile var downloadWritten: ((Path) -> Unit)? = null

    // When set, an upload into a folder the remote does not hold is refused as a gone parent, as a path-walking provider does.
    @Volatile var requireParents = false

    // When set, every upload attempt is counted and fails with it.
    @Volatile var uploadFailure: Exception? = null
    val uploadAttempts = java.util.concurrent.atomic.AtomicInteger()

    // The ifMatchETag of every upload, in order; with [enforceIfMatch] a replace whose token is not the item's hash is refused.
    val uploadIfMatch = java.util.concurrent.CopyOnWriteArrayList<String?>()
    @Volatile var enforceIfMatch = false

    // When set, an upload that creates a new item (no existing remote id) suspends here first.
    @Volatile var uploadGateForCreates: CompletableDeferred<Unit>? = null

    // When set, delete() signals deleteReached and suspends on deleteRelease before deleting: holds a provider delete.
    @Volatile var deleteReached: CompletableDeferred<Unit>? = null
    @Volatile var deleteRelease: CompletableDeferred<Unit>? = null

    // The folders listChildren was asked for, in order; and what it answers instead of the folder's children, or throws.
    val listCalls = java.util.concurrent.CopyOnWriteArrayList<String>()
    @Volatile var listFailure: Exception? = null
    @Volatile var listOverride: List<CloudItem>? = null

    /** A file that already exists on the remote with a fixed modified time (what an enumeration reports). */
    fun seed(
        path: String,
        bytes: ByteArray,
        modified: Instant = Instant.parse("2026-03-28T12:00:00Z"),
    ) {
        remote[path] = bytes
        items[path] =
            CloudItem(
                id = "id-$path",
                name = path.substringAfterLast("/"),
                path = path,
                size = bytes.size.toLong(),
                isFolder = false,
                modified = modified,
                created = modified,
                hash = "h-$path",
                mimeType = null,
            )
    }

    /** A folder that already exists on the remote. */
    fun seedFolder(path: String) {
        items[path] =
            CloudItem(
                id = "id-$path",
                name = path.substringAfterLast("/"),
                path = path,
                size = 0,
                isFolder = true,
                modified = Instant.parse("2026-03-28T12:00:00Z"),
                created = Instant.parse("2026-03-28T12:00:00Z"),
                hash = null,
                mimeType = null,
            )
    }

    override fun capabilities(): Set<Capability> = setOf(Capability.Delta)

    override suspend fun authenticate() {}

    // The direct children of [path]: an item whose parent folder is [path] (the fake used to answer every item for any path).
    override suspend fun listChildren(path: String): List<CloudItem> {
        listCalls += path
        listFailure?.let { throw it }
        listOverride?.let { return it }
        val parent = path.trimEnd('/')
        return items.values.filter { it.path.substringBeforeLast('/') == parent }
    }

    override suspend fun getMetadata(path: String): CloudItem = items[path] ?: throw org.krost.unidrive.ProviderException("Item not found: $path")

    override suspend fun download(remotePath: String, destination: Path): Long {
        downloads += remotePath
        downloadReached?.complete(Unit)
        downloadRelease?.await()
        Files.createDirectories(destination.parent)
        Files.write(destination, remote.getValue(remotePath))
        downloadWritten?.invoke(destination)
        return remote.getValue(remotePath).size.toLong()
    }

    override suspend fun downloadById(remoteId: String, remotePath: String, destination: Path): Long = download(remotePath, destination)

    // gkrost/unidrive-cli#392: when set, a download answers this receipt for the file it staged (a provider that hashes the
    // plaintext while it writes it); unset, the plain receipt of the CloudProvider default.
    @Volatile var receiptFor: ((remotePath: String, staged: Path) -> org.krost.unidrive.DownloadReceipt)? = null

    override suspend fun downloadWithHash(
        remotePath: String,
        destination: Path,
        targetMtimeMillis: Long?,
    ): org.krost.unidrive.DownloadReceipt {
        val bytes = download(remotePath, destination)
        return receiptFor?.invoke(remotePath, destination) ?: org.krost.unidrive.DownloadReceipt(bytes)
    }

    override suspend fun downloadByIdWithHash(
        remoteId: String,
        remotePath: String,
        destination: Path,
        targetMtimeMillis: Long?,
    ): org.krost.unidrive.DownloadReceipt = downloadWithHash(remotePath, destination, targetMtimeMillis)

    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        existingRemoteId: String?,
        ifMatchETag: String?,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem {
        uploadGate?.await()
        if (existingRemoteId == null) uploadGateForCreates?.await()
        uploadAttempts.incrementAndGet()
        uploadFailure?.let { throw it }
        uploadIfMatch += ifMatchETag
        val stale = ifMatchETag != null && items[remotePath]?.hash != ifMatchETag
        if (enforceIfMatch && existingRemoteId != null && stale) {
            throw org.krost.unidrive.RemoteConflictException("precondition failed: $remotePath moved on")
        }
        val parent = remotePath.substringBeforeLast('/')
        if (requireParents && parent.isNotEmpty() && items[parent]?.isFolder != true) {
            throw org.krost.unidrive.ParentGoneException("parent folder not found: $parent", parent)
        }
        val bytes = Files.readAllBytes(localPath)
        remote[remotePath] = bytes
        version++
        val at = Instant.parse("2026-03-28T12:00:00Z").plusSeconds(version.toLong())
        return CloudItem(
            id = "id-$remotePath",
            name = remotePath.substringAfterLast("/"),
            path = remotePath,
            size = bytes.size.toLong(),
            isFolder = false,
            modified = at,
            created = at,
            hash = "v$version",
            mimeType = null,
        ).also { items[remotePath] = it }
    }

    override suspend fun delete(remotePath: String, ifMatchETag: String?) {
        deleteReached?.complete(Unit)
        deleteRelease?.await()
        deleted += remotePath
        remote.remove(remotePath)
        items.remove(remotePath)
    }

    // When set, createFolder throws it.
    @Volatile var createFolderFailure: Exception? = null

    override suspend fun createFolder(path: String): CloudItem {
        createFolderFailure?.let { throw it }
        val at = java.time.Instant.parse("2026-03-28T12:00:00Z")
        val item =
            CloudItem(
                id = "id-$path",
                name = path.substringAfterLast('/'),
                path = path,
                size = 0,
                isFolder = true,
                modified = at,
                created = at,
                hash = null,
                mimeType = null,
            )
        items[path] = item
        return item
    }

    override suspend fun move(fromPath: String, toPath: String): CloudItem {
        val bytes = remote.remove(fromPath) ?: error("not found: $fromPath")
        val item = items.remove(fromPath) ?: error("not found: $fromPath")
        remote[toPath] = bytes
        version++
        return item
            .copy(
                id = "id-$toPath",
                name = toPath.substringAfterLast("/"),
                path = toPath,
            ).also { items[toPath] = it }
    }

    override suspend fun delta(cursor: String?, onPageProgress: ((Int) -> Unit)?, scanContext: org.krost.unidrive.ScanContext?): DeltaPage {
        deltaGate?.await()
        return DeltaPage(items = items.values.toList(), cursor = "cursor", hasMore = false)
    }

    override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
}
