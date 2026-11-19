package org.krost.unidrive.xtra

import kotlinx.coroutines.CancellationException
import org.krost.unidrive.CloudItem

/**
 * The provider's memory of algorithm-mismatch refusals, one instance per provider stack — one daemon run, the
 * scope the issue names: the profile's `xtra_algorithm` is read only at start, so a switch always crosses a
 * restart, and a fresh instance starts empty. The first refusal of a stored object has to download it (the
 * provider SPI has no partial read, the header decides), but a later download of the same object version fails
 * fast with the same [XtraAlgorithmMismatchException] and transfers nothing: while the mismatch lasts, each sync
 * pass and each mount read would otherwise pull the whole ciphertext again only to refuse it.
 *
 * The key is the object the download call addresses — its remote id, or its path for a path-addressed call. The
 * version is not taken from the call: the mount read names none, and a listing's mtime can be the uploading
 * client's (OneDrive's `fileSystemInfo`), unchanged by a replacement that keeps the file's time. Instead each
 * refusal records a version token from one metadata read ([versionToken]: the provider's hash, else id, size and
 * modified time), and a remembered refusal is replayed only while a fresh metadata read answers the same token. A
 * replacement under the same id — another device re-saving the file under the profile's algorithm — answers a new
 * token and is downloaded again in the same run; a metadata read that fails replays nothing. A repeat costs one
 * metadata read instead of the transfer.
 *
 * Bounded: at most [capacity] objects, the least recently asked one forgotten first (an evicted refusal costs one
 * more download, nothing else). An upload through this provider clears what it replaces.
 */
internal class XtraAlgorithmMismatchMemory(
    private val configuredAlgorithmId: Byte,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    // Access-ordered, so the eldest entry is the least recently asked object; guarded by its own monitor.
    private val refused =
        object : LinkedHashMap<String, Refusal>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Refusal>?) = size > capacity
        }

    /**
     * Runs [download] unless this very object version was already refused in this run. A remembered refusal whose
     * object [metadata] still answers the recorded version token fails fast — the same exception, before any download
     * and any key demand; any other answer forgets it and downloads. A refusal [download] raises is remembered with
     * the token [metadata] answers then (none when that read fails: nothing to replay against).
     */
    suspend fun <R> rememberingRefusal(
        remoteId: String?,
        remotePath: String,
        metadata: suspend () -> CloudItem,
        download: suspend () -> R,
    ): R {
        val key = remoteId?.let { "id:$it" } ?: "path:$remotePath"
        synchronized(refused) { refused[key] }?.let { known ->
            if (tokenOrNull(metadata) == known.versionToken) {
                throw mismatchRefusal(known.storedAlgorithmId, configuredAlgorithmId, remotePath)
            }
            synchronized(refused) { refused.remove(key) }
        }
        return try {
            download()
        } catch (e: XtraAlgorithmMismatchException) {
            tokenOrNull(metadata)?.let { token ->
                synchronized(refused) { refused[key] = Refusal(e.storedAlgorithmId, token) }
            }
            throw e
        }
    }

    /** Clears what an upload through this provider can replace: the path's record and the existing remote id's. */
    fun forget(
        remoteId: String?,
        remotePath: String,
    ) {
        synchronized(refused) {
            refused.remove("path:$remotePath")
            remoteId?.let { refused.remove("id:$it") }
        }
    }

    /** How many objects the memory holds now (tests pin the bound). */
    internal fun size(): Int = synchronized(refused) { refused.size }

    // Any failed read — not found, an outage — is no proof the object is unchanged: it answers no token, so
    // nothing is replayed against it.
    private suspend fun tokenOrNull(metadata: suspend () -> CloudItem): String? =
        runCatching { versionToken(metadata()) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()

    private data class Refusal(
        val storedAlgorithmId: Byte,
        val versionToken: String,
    )

    internal companion object {
        const val DEFAULT_CAPACITY = 10_000
        private const val INITIAL_CAPACITY = 64
        private const val LOAD_FACTOR = 0.75f

        // The provider's content hash names the bytes; without one, the id with size and modified time stands in
        // (a replacement that keeps id, size and time is the one case that still replays until the restart).
        fun versionToken(item: CloudItem): String =
            item.hash?.let { "hash:$it" } ?: "meta:${item.id}|${item.size}|${item.modified?.toEpochMilli()}"
    }
}
