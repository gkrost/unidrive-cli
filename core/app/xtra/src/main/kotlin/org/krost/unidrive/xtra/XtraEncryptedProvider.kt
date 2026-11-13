package org.krost.unidrive.xtra

import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DownloadReceipt
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ProviderException
import org.krost.unidrive.ScanContext
import org.krost.unidrive.TrashItem
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat

/**
 * The X-tra layer (client-side encryption of a profile's file content, config key `xtra_encryption`): a
 * [CloudProvider] decorator that encrypts every file on its way to the inner provider and decrypts it on the way
 * back. The inner provider — and through it the cloud — sees only opaque ciphertext with a self-describing
 * [XtraHeader]; paths, names and the tree stay as they are, so listings, moves and the delta work unchanged.
 *
 * Sizes cross this boundary on the plaintext side: the engines' size contracts hold what a listing said against
 * the bytes a download writes (the #80 confirmation, the mirror's completeness check, the warm-cache size match
 * and the incomplete-hydration guard), so every item that crosses up answers the plaintext size — the provider's
 * ciphertext size minus the strategy's [XtraEncryptionStrategy.overheadBytes] — and a download receipt names the
 * bytes the decryption wrote. The provider still sees the ciphertext's size, which is the metadata leak the
 * design accepts.
 *
 * [keys] is lazy on purpose: the wrap happens for every provider of an opted-in profile, including commands that
 * never touch content (auth, status, quota, listings), and only those that upload or download may demand the
 * passphrase. A file without the XTRA header fails its download instead of passing plaintext through silently: a
 * download that answers what the caller believes encrypted, but is not, is the one outcome this layer must never
 * produce.
 *
 * Both directions stage through a temp file, so a transfer peaks at twice the file's size; streaming without the
 * temp file needs the encrypted size upfront and stays out (see the design note in the module docs).
 */
class XtraEncryptedProvider(
    private val inner: CloudProvider,
    private val keys: Lazy<XtraKeyManager>,
    private val strategy: XtraEncryptionStrategy = AesGcmStrategy(),
) : CloudProvider by inner {

    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        existingRemoteId: String?,
        ifMatchETag: String?,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem {
        val km = keys.value
        val fileKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(strategy.ivLength).also { SecureRandom().nextBytes(it) }
        val wrappedKey = km.wrapKey(fileKey)

        val tmp = Files.createTempFile("xtra-", ".tmp")
        try {
            FileOutputStream(tmp.toFile()).use { out ->
                XtraHeader.write(out, strategy.algorithmId, wrappedKey, iv)
                FileInputStream(localPath.toFile()).use { input ->
                    strategy.encrypt(input, out, fileKey, iv)
                }
            }
            return plaintextSized(strategy, inner.upload(tmp, remotePath, existingRemoteId, ifMatchETag, onProgress))
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    override suspend fun download(
        remotePath: String,
        destination: Path,
    ): Long {
        val tmp = Files.createTempFile("xtra-", ".tmp")
        try {
            inner.download(remotePath, tmp)
            decrypt(strategy, keys, tmp, destination)
            return Files.size(destination)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    override suspend fun downloadById(
        remoteId: String,
        remotePath: String,
        destination: Path,
    ): Long {
        val tmp = Files.createTempFile("xtra-", ".tmp")
        try {
            inner.downloadById(remoteId, remotePath, tmp)
            decrypt(strategy, keys, tmp, destination)
            return Files.size(destination)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    // What the engines' size contracts need (see the class KDoc): every item crossing up answers the plaintext
    // size, the provider's ciphertext size minus the strategy's fixed overhead. The overrides live on the class;
    // their helpers are file-level functions (they take the strategy and the lazy keys), so the class stays
    // under detekt's function count.

    override suspend fun listChildren(path: String): List<CloudItem> = inner.listChildren(path).map { plaintextSized(strategy, it) }

    override suspend fun getMetadata(path: String): CloudItem = plaintextSized(strategy, inner.getMetadata(path))

    override suspend fun delta(
        cursor: String?,
        onPageProgress: ((Int) -> Unit)?,
        scanContext: ScanContext?,
    ): DeltaPage =
        inner.delta(cursor, onPageProgress, scanContext).let { page ->
            page.copy(items = page.items.map { plaintextSized(strategy, it) })
        }

    override suspend fun deltaWithShared(cursor: String?): CapabilityResult<DeltaPage> =
        when (val result = inner.deltaWithShared(cursor)) {
            is CapabilityResult.Success ->
                CapabilityResult.Success(result.value.copy(items = result.value.items.map { plaintextSized(strategy, it) }))
            is CapabilityResult.Unsupported -> result
        }

    override suspend fun deltaFromLatest(scanContext: ScanContext?): CapabilityResult<DeltaPage> =
        when (val result = inner.deltaFromLatest(scanContext)) {
            is CapabilityResult.Success ->
                CapabilityResult.Success(result.value.copy(items = result.value.items.map { plaintextSized(strategy, it) }))
            is CapabilityResult.Unsupported -> result
        }

    override suspend fun move(
        fromPath: String,
        toPath: String,
    ): CloudItem = plaintextSized(strategy, inner.move(fromPath, toPath))

    override suspend fun moveById(
        remoteId: String,
        fromPath: String,
        toPath: String,
    ): CloudItem = plaintextSized(strategy, inner.moveById(remoteId, fromPath, toPath))

    override suspend fun listTrash(): List<TrashItem> = inner.listTrash().map { plaintextSizedTrash(strategy, it) }

    /**
     * The hash variants answer about the plaintext that lands in [destination]: the receipt keeps the inner
     * provider's identity fields (they name the ciphertext object the row stands for) and replaces the byte count,
     * the staged stats and the digest with what the decryption wrote — including the target mtime, which the
     * decryption of a fresh file would otherwise lose.
     */
    override suspend fun downloadWithHash(
        remotePath: String,
        destination: Path,
        targetMtimeMillis: Long?,
    ): DownloadReceipt {
        val tmp = Files.createTempFile("xtra-", ".tmp")
        try {
            val receipt = inner.downloadWithHash(remotePath, tmp, targetMtimeMillis)
            return decryptToDestination(strategy, keys, tmp, destination, receipt, targetMtimeMillis)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    override suspend fun downloadByIdWithHash(
        remoteId: String,
        remotePath: String,
        destination: Path,
        targetMtimeMillis: Long?,
    ): DownloadReceipt {
        val tmp = Files.createTempFile("xtra-", ".tmp")
        try {
            val receipt = inner.downloadByIdWithHash(remoteId, remotePath, tmp, targetMtimeMillis)
            return decryptToDestination(strategy, keys, tmp, destination, receipt, targetMtimeMillis)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}

// The helpers of [XtraEncryptedProvider], file-level so the class stays under detekt's function count. They take
// the class's private strategy and lazy keys as parameters.

// What a listing may say about a file's size: the plaintext (see the class KDoc). A folder's size and one that
// could not be a well-formed X-tra file — smaller than the fixed overhead — go through unchanged; the download
// of such a file fails closed on the missing header anyway.
private fun plaintextSized(
    strategy: XtraEncryptionStrategy,
    item: CloudItem,
): CloudItem =
    if (item.isFolder || item.size < strategy.overheadBytes) item else item.copy(size = item.size - strategy.overheadBytes)

private fun plaintextSizedTrash(
    strategy: XtraEncryptionStrategy,
    item: TrashItem,
): TrashItem =
    when {
        item.isFolder || (item.sizeBytes ?: 0L) < strategy.overheadBytes -> item
        else -> item.copy(sizeBytes = item.sizeBytes!! - strategy.overheadBytes)
    }

private fun decryptToDestination(
    strategy: XtraEncryptionStrategy,
    keys: Lazy<XtraKeyManager>,
    encryptedFile: Path,
    destination: Path,
    receipt: DownloadReceipt,
    targetMtimeMillis: Long?,
): DownloadReceipt {
    decrypt(strategy, keys, encryptedFile, destination)
    // The mtime the inner provider applied to its staged ciphertext does not survive the decryption (the
    // plaintext lands in a fresh file), so the wrapper applies the target itself — what the hash variants
    // answer for (the mirror restores the mtime itself only when the receipt says it was not applied).
    if (targetMtimeMillis != null) Files.setLastModifiedTime(destination, FileTime.fromMillis(targetMtimeMillis))
    val size = Files.size(destination)
    // The staged stats the engines compare the placed copy against (the streamed-hash guard, withLocalHash)
    // describe the decrypted file now, not the ciphertext the inner provider staged.
    return receipt.copy(
        bytesWritten = size,
        plaintextSha256 = sha256Of(destination),
        stagedSize = size,
        stagedMtimeMillis = Files.getLastModifiedTime(destination).toMillis(),
        mtimeAppliedBeforePlacement = true,
    )
}

private fun decrypt(
    strategy: XtraEncryptionStrategy,
    keys: Lazy<XtraKeyManager>,
    encryptedFile: Path,
    destination: Path,
) {
    val km = keys.value
    FileInputStream(encryptedFile.toFile()).use { input ->
        when (val result = XtraHeader.read(input)) {
            is XtraHeader.ReadResult.NotEncrypted ->
                throw ProviderException("File is not XTRA-encrypted: $encryptedFile")
            is XtraHeader.ReadResult.Corrupted ->
                throw ProviderException("XTRA header corrupted: ${result.reason}")
            is XtraHeader.ReadResult.Valid -> {
                val fileKey = km.unwrapKey(result.wrappedKey)
                FileOutputStream(destination.toFile()).use { out ->
                    strategy.decrypt(input, out, fileKey, result.iv)
                }
            }
        }
    }
}

private fun sha256Of(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file.toFile()).use { input ->
        val buf = ByteArray(65536)
        var n: Int
        while (input.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
    }
    return HexFormat.of().formatHex(digest.digest())
}
