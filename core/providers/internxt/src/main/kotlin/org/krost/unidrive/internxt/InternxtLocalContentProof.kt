package org.krost.unidrive.internxt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.krost.unidrive.ContentProof
import org.krost.unidrive.internxt.model.BridgeFileInfo
import org.krost.unidrive.internxt.model.InternxtFile
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Proves, without downloading the stored object, that a local file holds the bytes of an Internxt file (archive#1219).
 *
 * The content is encrypted with AES-256-CTR under a key derived from the account's bucket key and the file's own 32-byte `index`
 * (its first 16 bytes are the IV), and the network records a hash of the CIPHERTEXT as the shard's `hash`: RIPEMD-160(SHA-256(ciphertext))
 * for every object an official client stored, SHA-256(ciphertext) for the ones this engine stored before it adopted the official shape
 * (see [ShardHashExpectation]). CTR without padding is deterministic and keeps the length, so encrypting the local file with the file's key
 * and IV and hashing the result gives the recorded hash exactly when the plaintext is the stored plaintext. Both sides of the check are
 * data the engine already reads for a download: the drive metadata (bucket, file id, declared size) and the bridge info (index, shard hash).
 * What is saved is the download: a local read and AES at disk speed instead of the object over the wire.
 *
 * Answers: true (the ciphertext hash is the recorded one), false (the declared size differs, or the hash is a checkable one and differs),
 * null (no proof can be made: the object is stored in several parts or has a hash in no known shape, the index is malformed, the
 * encryption version is another one, the bridge names another object). A null never means "different"; the caller falls back to a
 * download. Nothing is written anywhere, and no hash, key or index reaches a log or a message.
 *
 * The lookups are function parameters, like [InternxtRemoteObjectAuditor], so that a test needs no network and no account.
 */
internal class InternxtLocalContentProof(
    private val getFileMeta: suspend (String) -> InternxtFile,
    private val getBridgeFileInfo: suspend (String, String) -> BridgeFileInfo,
    private val fileKeyFor: suspend (bucket: String, index: ByteArray) -> ByteArray,
    private val crypto: InternxtCrypto,
) {
    suspend fun prove(
        fileUuid: String,
        local: Path,
    ): ContentProof? {
        val meta = getFileMeta(fileUuid)
        val declared = meta.size?.toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val localSize = withContext(Dispatchers.IO) { Files.size(local) }
        val bucket = meta.bucket
        val fileId = meta.fileId
        if (bucket.isNullOrBlank() || fileId.isNullOrBlank()) {
            // An empty file is a drive entry of size 0 with no stored object (archive#485): there is nothing to hash, the sizes decide. A drive
            // entry that declares bytes and has no object is broken, not a file to judge.
            if (declared != 0L) return null
            return ContentProof(same = localSize == 0L, method = METHOD_EMPTY)
        }
        // A declared size of 0 WITH a file id is the older "size unknown" shape (archive#485): it is no evidence of a difference.
        if (declared > 0L && localSize != declared) return ContentProof(same = false, method = METHOD_SIZE)
        if ((meta.encryptVersion ?: SUPPORTED_ENCRYPT_VERSION) != SUPPORTED_ENCRYPT_VERSION) return null

        val info = getBridgeFileInfo(bucket, fileId)
        if (info.bucket != bucket || info.id != fileId) return null
        val index =
            try {
                InternxtCrypto.hexToBytes(info.index)
            } catch (_: IllegalArgumentException) {
                return null
            }
        if (index.size < IV_BYTES) return null
        // One hash is recorded per shard. A multi-shard object is not read by this client either (archive#335), and a hash of one part says
        // nothing about the whole.
        val shard = info.shards.singleOrNull() ?: return null
        val expectation = ShardHashExpectation.parse(shard.hash) as? ShardHashExpectation.Checkable ?: return null

        val fileKey = fileKeyFor(bucket, index)
        val sha256 = ciphertextSha256(local, fileKey, index.copyOfRange(0, IV_BYTES), crypto)
        return ContentProof(same = expectation.matches(sha256), method = METHOD_SHARD_HASH)
    }

    companion object {
        const val METHOD_SIZE = "size"
        const val METHOD_EMPTY = "empty"
        const val METHOD_SHARD_HASH = "internxt-shard-hash"

        private const val SUPPORTED_ENCRYPT_VERSION = "03-aes"
        private const val IV_BYTES = 16
        private const val CHUNK_BYTES = 256 * 1024

        /**
         * SHA-256 of the AES-256-CTR ciphertext of [local] under [fileKey] and [iv], streamed in chunks (a 4 GiB file needs no more memory
         * than a small one) and cancellable between chunks.
         */
        internal suspend fun ciphertextSha256(
            local: Path,
            fileKey: ByteArray,
            iv: ByteArray,
            crypto: InternxtCrypto,
        ): ByteArray =
            withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                val cipher = crypto.createContentEncryptCipher(fileKey, iv)
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(CHUNK_BYTES)
                Files.newInputStream(local).use { input ->
                    while (true) {
                        context.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        cipher.update(buffer, 0, read)?.let { digest.update(it) }
                    }
                }
                cipher.doFinal()?.let { digest.update(it) }
                digest.digest()
            }
    }
}
