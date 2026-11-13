package org.krost.unidrive.xtra

import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DownloadReceipt
import org.krost.unidrive.ProviderException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat

/**
 * The X-tra layer (client-side encryption of a profile's file content, config key `xtra_encryption`): a
 * [CloudProvider] decorator that encrypts every file on its way to the inner provider and decrypts it on the way
 * back. The inner provider — and through it the cloud — sees only opaque ciphertext with a self-describing
 * [XtraHeader]; paths, names, sizes and the tree stay as they are, so listings, moves and the delta work unchanged.
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
            return inner.upload(tmp, remotePath, existingRemoteId, ifMatchETag, onProgress)
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
            return decryptToDestination(tmp, destination)
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
            return decryptToDestination(tmp, destination)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /**
     * The hash variants answer about the plaintext that lands in [destination]: the receipt keeps the inner
     * provider's identity fields (they name the ciphertext object the row stands for) and replaces the byte count
     * and the digest with what the decryption wrote.
     */
    override suspend fun downloadWithHash(
        remotePath: String,
        destination: Path,
        targetMtimeMillis: Long?,
    ): DownloadReceipt {
        val tmp = Files.createTempFile("xtra-", ".tmp")
        try {
            val receipt = inner.downloadWithHash(remotePath, tmp, targetMtimeMillis)
            return decryptToDestination(tmp, destination, receipt)
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
            return decryptToDestination(tmp, destination, receipt)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun decryptToDestination(
        encryptedFile: Path,
        destination: Path,
    ): Long {
        decrypt(encryptedFile, destination)
        return Files.size(destination)
    }

    private fun decryptToDestination(
        encryptedFile: Path,
        destination: Path,
        receipt: DownloadReceipt,
    ): DownloadReceipt {
        decrypt(encryptedFile, destination)
        return receipt.copy(bytesWritten = Files.size(destination), plaintextSha256 = sha256Of(destination))
    }

    private fun decrypt(
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
}
