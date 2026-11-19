package org.krost.unidrive.sync

import kotlinx.coroutines.CancellationException
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DownloadReceipt
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("org.krost.unidrive.sync.DownloadSizeConfirmation")

/**
 * The mirror's answer to a download whose byte count differs from the item's size, the way the mount answers it (#80):
 * the count is taken only when the provider confirms it as the file's size now. The item may come from a row whose size
 * went stale with no delivery to correct it — an X-tra file sized under another algorithm's overhead while the profile
 * named that one (#710) — and without the confirmation the recovery loop would re-download that file on every pass and
 * never place it. A count the provider does not confirm stays the transient short body it is (#282).
 */
@Suppress("TooGenericExceptionCaught") // whatever the lookup throws, the count stays unconfirmed and the download fails as before
internal suspend fun confirmsDownloadedSize(
    provider: CloudProvider,
    remotePath: String,
    bytesWritten: Long,
): Boolean {
    val live =
        try {
            provider.getMetadata(remotePath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug("Cannot confirm the size of {} after a download of {} bytes: {}", remotePath, bytesWritten, e.javaClass.simpleName)
            return false
        }
    return !live.isFolder && !live.deleted && live.size == bytesWritten
}

/** The row of a placed download records the size [confirmsDownloadedSize] accepted, not the stale one it replaced. */
internal fun SyncEntry.sizedByDownload(receipt: DownloadReceipt): SyncEntry =
    if (!isFolder && remoteSize > 0L && receipt.bytesWritten != remoteSize) copy(remoteSize = receipt.bytesWritten) else this
