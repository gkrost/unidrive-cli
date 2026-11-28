package org.krost.unidrive.sync

import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * A provider with a change feed and a full listing, for the tombstone-id tests (gkrost/unidrive-cli#743): a null cursor
 * lists [live]; any other cursor walks the incremental feed, which is [pages] (one page after the other, `hasMore` until
 * the last) or the single page [feed]. A provider built with [fullListing] returns [live] plus [feed] on every call, as
 * S3 and LocalFs do. [fullListings] counts the null-cursor listings, [deleted] what the engine deleted in the cloud.
 */
class ListingFeedProvider(
    private val fullListing: Boolean = false,
) : CloudProvider {
    // A drive of unique names (#268: the interface default is now twins possible).
    override val allowsDuplicateNames = false

    override val id = "listing-feed-fake"
    override val displayName = "Listing Feed Fake"
    override var isAuthenticated = true
    override val deltaIsFullListing: Boolean get() = fullListing

    override fun capabilities(): Set<Capability> = setOf(Capability.Delta)

    var live: List<CloudItem> = emptyList()
    var feed: List<CloudItem> = emptyList()
    var pages: List<List<CloudItem>>? = null
    var complete = true
    var fullListings = 0
    val deleted = mutableListOf<String>()

    private fun known(): List<CloudItem> = live + feed + pages.orEmpty().flatten()

    override suspend fun authenticate() {}

    override suspend fun listChildren(path: String) = live.filter { it.path.substringBeforeLast('/', "").ifEmpty { "/" } == path.trimEnd('/').ifEmpty { "/" } }

    override suspend fun getMetadata(path: String): CloudItem = live.lastOrNull { it.path == path } ?: throw ProviderException("Item not found: $path", httpStatus = 404)

    override suspend fun downloadById(
        remoteId: String,
        remotePath: String,
        destination: Path,
    ): Long {
        val item = known().firstOrNull { it.id == remoteId && !it.deleted } ?: throw ProviderException("Item not found: $remoteId", httpStatus = 404)
        return write(destination, item.size)
    }

    override suspend fun download(
        remotePath: String,
        destination: Path,
    ): Long = write(destination, getMetadata(remotePath).size)

    private fun write(
        destination: Path,
        size: Long,
    ): Long {
        Files.createDirectories(destination.parent)
        Files.write(destination, ByteArray(size.toInt()))
        return size
    }

    override suspend fun deleteById(
        remoteId: String,
        remotePath: String,
    ) {
        deleted += remoteId
    }

    override suspend fun delete(
        remotePath: String,
        ifMatchETag: String?,
    ) {
        deleted += remotePath
    }

    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        existingRemoteId: String?,
        ifMatchETag: String?,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem = error("not expected in this test")

    override suspend fun createFolder(path: String): CloudItem = error("not expected in this test")

    override suspend fun move(
        fromPath: String,
        toPath: String,
    ): CloudItem = error("not expected in this test")

    override suspend fun delta(
        cursor: String?,
        onPageProgress: ((itemsSoFar: Int) -> Unit)?,
        scanContext: ScanContext?,
    ): DeltaPage {
        if (fullListing) return DeltaPage(items = live + feed, cursor = "c-full", hasMore = false, complete = true)
        if (cursor == null) {
            fullListings++
            return DeltaPage(items = live, cursor = "c-full", hasMore = false, complete = true)
        }
        val chain = pages ?: listOf(feed)
        val index = cursor.removePrefix("p").toIntOrNull() ?: 0
        val last = index >= chain.lastIndex
        return DeltaPage(items = chain[index], cursor = if (last) "c-inc" else "p${index + 1}", hasMore = !last, complete = complete)
    }

    override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0, used = 0, remaining = 0)
}
