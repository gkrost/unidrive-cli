package org.krost.unidrive.engine

import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DownloadReceipt
import org.krost.unidrive.HashAlgorithm
import org.krost.unidrive.sync.HashVerifier
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path

/**
 * archive#560 U3: the transfer bookkeeping the mirror pass (`SyncEngine`) and the mount operations
 * (`MountEngine`) share: how a download is addressed, which stats an upload records, and when a
 * recorded local hash (archive#396) still describes the bytes. Moved out of `SyncEngine` unchanged; the
 * callers pass their logger so the log lines keep their logger name.
 */
object Transfers {
    /**
     * gkrost/unidrive-cli#414: hands the provider what the row kept of the item's stored object, before an id-addressed
     * download. The row's hint survives a daemon restart (state.db), the provider's in-memory one does not, so this is
     * what lets a post-restart download skip the metadata call it used to pay per file. The provider ignores it when an
     * answer of its own run already describes the id, and the download still falls back to that call when the hinted
     * object is gone.
     */
    private fun seedStoredObject(
        provider: CloudProvider,
        item: CloudItem,
    ) {
        if (item.id.isNotEmpty()) provider.seedStoredObject(item.id, item.storedObject)
    }

    /** Download [item] to [destination]: by id when the item has one (the fast path), else by [remotePath]. */
    suspend fun downloadByIdOrPath(
        provider: CloudProvider,
        item: CloudItem,
        remotePath: String,
        destination: Path,
    ): Long =
        if (item.id.isNotEmpty()) {
            seedStoredObject(provider, item)
            provider.downloadById(item.id, remotePath, destination)
        } else {
            provider.download(remotePath, destination)
        }

    /** Download while allowing the provider to return a hash of the plaintext it staged. */
    @Suppress("TooGenericExceptionCaught") // Record every failed provider attempt, then rethrow unchanged.
    suspend fun downloadByIdOrPathWithHash(
        provider: CloudProvider,
        item: CloudItem,
        remotePath: String,
        destination: Path,
        targetMtimeMillis: Long? = null,
        tally: FileEventTally? = null,
        retryHistory: FileEventTally? = tally,
    ): DownloadReceipt {
        val eventPath = item.path
        val started = System.nanoTime()
        FileEvents.event(FileEvents.Phase.DOWNLOAD_START, eventPath, bytes = item.size, tally = tally)
        try {
            val receipt = if (item.id.isNotEmpty()) {
                seedStoredObject(provider, item)
                provider.downloadByIdWithHash(item.id, remotePath, destination, targetMtimeMillis)
            } else {
                provider.downloadWithHash(remotePath, destination, targetMtimeMillis)
            }
            FileEvents.event(FileEvents.Phase.DOWNLOAD_DONE, eventPath, bytes = receipt.bytesWritten,
                ms = (System.nanoTime() - started) / 1_000_000, retries = retryHistory?.attemptsFailed(eventPath), tally = tally)
            retryHistory?.noteSuccess(eventPath)
            return receipt
        } catch (e: Exception) {
            retryHistory?.noteFailure(eventPath)
            FileEvents.event(FileEvents.Phase.DOWNLOAD_FAILED, eventPath, ms = (System.nanoTime() - started) / 1_000_000,
                retries = retryHistory?.attemptsFailed(eventPath), tally = tally, extra = listOf("error" to e.javaClass.simpleName))
            throw e
        }
    }

    /** mtime and size of a file about to be uploaded, or null when it cannot be read. */
    fun statBeforeUpload(local: Path): Pair<Long, Long>? =
        try {
            Files.getLastModifiedTime(local).toMillis() to Files.size(local)
        } catch (e: java.io.IOException) {
            null
        }

    /**
     * archive#396: [entry] with the SHA-256 of [local] as its local hash, for a provider without a content
     * hash (either [algorithm] is null or this item has no hash). The hash is kept only if the file still has the mtime and size in [stats], so a
     * write that lands while it is hashed cannot turn the new bytes into the tracked baseline. The
     * callers pass a row that already carries the new mtime/size but may still hold the hash of the
     * previous contents, so a failed hash clears it rather than keeping it.
     *
     * archive#439 residual: on NTFS an mtime update can be deferred until the last handle closes, so a
     * same-size rewrite that overlaps the hash can land hash(new) under the old baseline and later
     * read as touch-only. The window is sub-second; the same race exists in the archive#112 remote-hash
     * compare, which relies on the file's stats the same way.
     *
     * gkrost/unidrive-cli#393: [precomputedSha256] is the hash of the bytes that were just sent, when the provider hashed
     * them in the pass that sent them ([org.krost.unidrive.CloudItem.sentSha256]). It stands in for the read of the file,
     * and the same stat guard decides whether it still describes the file.
     */
    fun withLocalHash(
        entry: SyncEntry,
        local: Path,
        stats: Pair<Long, Long>,
        algorithm: HashAlgorithm?,
        log: Logger,
        precomputedSha256: String? = null,
        // gkrost/unidrive-cli#394: told when a hash was recorded: whether it came from the provider (true) or from a read of the
        // file (false), and the milliseconds the read took (0 for the provider's).
        onHashed: ((fromProvider: Boolean, ms: Long) -> Unit)? = null,
    ): SyncEntry {
        if ((algorithm != null && !entry.remoteHash.isNullOrEmpty()) || entry.isFolder) return entry
        val (mtime, size) = stats
        return try {
            val startedNs = System.nanoTime()
            val hash = precomputedSha256 ?: HashVerifier.computeSha256Hex(local)
            val readMs = if (precomputedSha256 == null) (System.nanoTime() - startedNs) / 1_000_000 else 0L
            if (Files.getLastModifiedTime(local).toMillis() == mtime && Files.size(local) == size) {
                onHashed?.invoke(precomputedSha256 != null, readMs)
                entry.copy(localHash = hash)
            } else {
                entry.copy(localHash = null)
            }
        } catch (e: java.io.IOException) {
            log.debug("#396: cannot hash {} for the local-hash column: {}", entry.path, e.message)
            entry.copy(localHash = null)
        }
    }

    /**
     * An upload row records the file's stats as they were BEFORE the transfer (archive#337/#148), so a
     * write landing during a long upload keeps a newer mtime than the recorded baseline and the next
     * scan re-uploads it. Hashing the bytes that were sent is therefore only safe while the file still
     * matches those pre-upload stats ([sent]): if the file changed while it was being sent, it gets no
     * hash, otherwise the edit could be absorbed as an unchanged touch.
     */
    fun withSentHash(
        entry: SyncEntry,
        local: Path,
        sent: Pair<Long, Long>?,
        algorithm: HashAlgorithm?,
        log: Logger,
        // gkrost/unidrive-cli#393: see [withLocalHash]; the file is not read again when the provider hashed it while sending.
        sentSha256: String? = null,
        onHashed: ((fromProvider: Boolean, ms: Long) -> Unit)? = null,
    ): SyncEntry {
        if (sent == null) return entry.copy(localHash = null)
        return withLocalHash(entry, local, sent, algorithm, log, sentSha256, onHashed)
    }
}
