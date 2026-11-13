package org.krost.unidrive.xtra

import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ScanContext
import org.krost.unidrive.TrashItem

/**
 * The listing half of the X-tra layer (client-side encryption of a profile's file content, config key
 * `xtra_encryption`): every item this decorator hands up answers the plaintext size. What the provider stores is
 * the header plus the sealed content of [strategy], so its listed size is the ciphertext's — while the engines'
 * size contracts hold what a listing said against the bytes a download writes (the mount's #80 size confirmation,
 * the mirror's completeness check, the warm-cache size match and the incomplete-hydration guard), and those bytes
 * are the plaintext. [XtraEncryptionStrategy.plaintextSizeOf] inverts the strategy's fixed overhead; an item whose
 * size no well-formed file could have goes through unchanged, and its download fails closed on the header anyway.
 *
 * The content half (the cipher, the receipts, the hash answers) lives in [XtraEncryptedProvider]; the CLI wires
 * the listings decorator below the cipher, so both halves see the same provider.
 */
class XtraPlaintextListings(
    private val inner: CloudProvider,
    private val strategy: XtraEncryptionStrategy,
) : CloudProvider by inner {
    override suspend fun listChildren(path: String): List<CloudItem> = inner.listChildren(path).map { it.plaintextSized(strategy) }

    override suspend fun getMetadata(path: String): CloudItem = inner.getMetadata(path).plaintextSized(strategy)

    override suspend fun delta(
        cursor: String?,
        onPageProgress: ((Int) -> Unit)?,
        scanContext: ScanContext?,
    ): DeltaPage =
        inner.delta(cursor, onPageProgress, scanContext).let { page ->
            page.copy(items = page.items.map { it.plaintextSized(strategy) })
        }

    override suspend fun deltaWithShared(cursor: String?): CapabilityResult<DeltaPage> =
        when (val result = inner.deltaWithShared(cursor)) {
            is CapabilityResult.Success ->
                CapabilityResult.Success(result.value.copy(items = result.value.items.map { it.plaintextSized(strategy) }))
            is CapabilityResult.Unsupported -> result
        }

    override suspend fun deltaFromLatest(scanContext: ScanContext?): CapabilityResult<DeltaPage> =
        when (val result = inner.deltaFromLatest(scanContext)) {
            is CapabilityResult.Success ->
                CapabilityResult.Success(result.value.copy(items = result.value.items.map { it.plaintextSized(strategy) }))
            is CapabilityResult.Unsupported -> result
        }

    override suspend fun move(
        fromPath: String,
        toPath: String,
    ): CloudItem = inner.move(fromPath, toPath).plaintextSized(strategy)

    override suspend fun moveById(
        remoteId: String,
        fromPath: String,
        toPath: String,
    ): CloudItem = inner.moveById(remoteId, fromPath, toPath).plaintextSized(strategy)

    override suspend fun listTrash(): List<TrashItem> = inner.listTrash().map { it.plaintextSizedTrash(strategy) }
}

// What a listing may say about a file's size: the plaintext (see the class KDoc). A folder's size and one that
// could not be a well-formed X-tra file go through unchanged.
internal fun CloudItem.plaintextSized(strategy: XtraEncryptionStrategy): CloudItem =
    strategy.plaintextSizeOf(size)?.let { copy(size = it) } ?: this

internal fun TrashItem.plaintextSizedTrash(strategy: XtraEncryptionStrategy): TrashItem {
    val size = sizeBytes ?: return this
    if (isFolder) return this
    return copy(sizeBytes = strategy.plaintextSizeOf(size) ?: size)
}
