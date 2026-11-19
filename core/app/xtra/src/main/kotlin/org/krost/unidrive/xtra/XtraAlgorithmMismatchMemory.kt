package org.krost.unidrive.xtra

import java.util.concurrent.ConcurrentHashMap

/**
 * The provider's memory of algorithm-mismatch refusals, one instance per provider stack — one daemon run, the
 * scope the issue names: the profile's `xtra_algorithm` is read only at start, so a switch always crosses a
 * restart, and a fresh instance starts empty. The first refusal of a stored object has to download it (the
 * provider SPI has no partial read, the header decides), but every later download of the same object version
 * fails fast with the same [XtraAlgorithmMismatchException] and transfers nothing: while the mismatch lasts,
 * each sync pass and each mount read would otherwise pull the whole ciphertext again only to refuse it.
 *
 * The key is the identity the download call itself presents — its remote id (or the path, for a path-addressed
 * call) plus the target mtime the hash variants carry, the listing's word on the object's version — so a changed
 * remote object misses and is downloaded again: a new mtime the listing reports, or another id at the path. A
 * call that names no version (the mount read, the rule-file download) is keyed without one; for those, a
 * same-id replacement comes back at the restart the switch-back needs anyway. An upload through this provider
 * clears what it can replace.
 */
internal class XtraAlgorithmMismatchMemory(private val configuredAlgorithmId: Byte) {
    private val refused = ConcurrentHashMap<RefusedObject, Byte>()

    /**
     * Runs [download] unless this very object version was already refused in this run: a remembered refusal
     * fails fast — the same exception, before any download and any key demand — and a refusal [download] raises
     * is remembered for the next call of the same identity.
     */
    suspend fun <R> rememberingRefusal(
        remoteId: String?,
        remotePath: String,
        targetMtimeMillis: Long?,
        download: suspend () -> R,
    ): R {
        val objectVersion = RefusedObject(remoteId, remotePath, targetMtimeMillis)
        refused[objectVersion]?.let { throw mismatchRefusal(it, configuredAlgorithmId, remotePath) }
        return try {
            download()
        } catch (e: XtraAlgorithmMismatchException) {
            refused[objectVersion] = e.storedAlgorithmId
            throw e
        }
    }

    /** Clears what an upload through this provider can replace: the path's records and the existing remote id's. */
    fun forget(
        remoteId: String?,
        remotePath: String,
    ) {
        refused.keys.removeIf { it.remotePath == remotePath || (remoteId != null && it.remoteId == remoteId) }
    }

    // The identity a download call presents: its remote id (null when it addresses by path) with the path, plus
    // the target mtime the hash variants carry — null when the call names no version of the object.
    private data class RefusedObject(
        val remoteId: String?,
        val remotePath: String,
        val targetMtimeMillis: Long?,
    )
}
