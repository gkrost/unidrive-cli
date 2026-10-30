package org.krost.unidrive.sync

import org.krost.unidrive.io.fileDigestHex
import org.krost.unidrive.io.fileSha256Hex
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Verifies file integrity after transfer using provider-specific hash algorithms.
 * Supports QuickXorHash (OneDrive), MD5/ETag (S3), and returns true for providers without hashes.
 */
object HashVerifier {
    /**
     * Verify that the downloaded file matches the remote hash.
     *
     * Returns true if hash matches, OR if the algorithm is null
     * (provider has no verifiable hash), OR if the remoteHash is
     * null/empty. Returns false only on actual mismatch.
     */
    fun verify(
        localPath: java.nio.file.Path,
        remoteHash: String?,
        algorithm: org.krost.unidrive.HashAlgorithm?,
    ): Boolean {
        if (remoteHash.isNullOrEmpty()) return true
        if (algorithm == null) return true

        return when (algorithm) {
            org.krost.unidrive.HashAlgorithm.QuickXor -> verifyQuickXorHash(localPath, remoteHash)
            org.krost.unidrive.HashAlgorithm.Md5Hex -> verifyS3ETag(localPath, remoteHash)
            org.krost.unidrive.HashAlgorithm.Sha256Hex -> computeSha256Hex(localPath).equals(remoteHash, ignoreCase = true)
            org.krost.unidrive.HashAlgorithm.DropboxContentHash -> computeDropboxContentHash(localPath).equals(remoteHash, ignoreCase = true)
        }
    }

    /**
     * Strict content equality — the counterpart [verify] needs for conflict resolution:
     * false whenever either side cannot be compared (no remote hash, no provider algorithm),
     * where [verify]'s "true when unverifiable" contract would silently adopt. The engine
     * wires this into the reconciler's identical-edits branch (archive#532); the legacy-profile inventory
     * (archive#560) uses it to tell a clean copy from an unknown one.
     */
    fun matches(
        localPath: java.nio.file.Path,
        remoteHash: String?,
        algorithm: org.krost.unidrive.HashAlgorithm?,
    ): Boolean = compare(localPath, remoteHash, algorithm) == true

    /**
     * gkrost/unidrive-cli#97: content equality with "cannot tell" kept apart from "different": true when the hashes
     * match, false when they prove the bytes differ, null when there is nothing to compare (no remote hash, no
     * provider algorithm, or an S3 multipart ETag, which is no MD5 of the bytes).
     */
    fun compare(
        localPath: java.nio.file.Path,
        remoteHash: String?,
        algorithm: org.krost.unidrive.HashAlgorithm?,
    ): Boolean? {
        if (remoteHash.isNullOrEmpty() || algorithm == null) return null

        return when (algorithm) {
            org.krost.unidrive.HashAlgorithm.QuickXor -> computeQuickXorHash(localPath) == remoteHash
            org.krost.unidrive.HashAlgorithm.Md5Hex ->
                if (remoteHash.contains("-")) null else computeMd5Hex(localPath).equals(remoteHash, ignoreCase = true)
            org.krost.unidrive.HashAlgorithm.Sha256Hex -> computeSha256Hex(localPath).equals(remoteHash, ignoreCase = true)
            org.krost.unidrive.HashAlgorithm.DropboxContentHash -> computeDropboxContentHash(localPath).equals(remoteHash, ignoreCase = true)
        }
    }

    private fun verifyQuickXorHash(
        localPath: Path,
        expectedBase64: String,
    ): Boolean {
        val computed = computeQuickXorHash(localPath)
        return computed == expectedBase64
    }

    private fun verifyS3ETag(
        localPath: Path,
        expectedETag: String,
    ): Boolean {
        // Multipart ETags contain "-" (e.g. "abc123-3"), skip verification
        if (expectedETag.contains("-")) return true

        val computed = computeMd5Hex(localPath)
        return computed.equals(expectedETag, ignoreCase = true)
    }

    /**
     * Compute the content hash for [path] using [algorithm].
     * Returns null when [algorithm] is null (provider has no verifiable hash).
     */
    fun computeHash(
        path: Path,
        algorithm: org.krost.unidrive.HashAlgorithm?,
    ): String? =
        when (algorithm) {
            org.krost.unidrive.HashAlgorithm.QuickXor -> computeQuickXorHash(path)
            org.krost.unidrive.HashAlgorithm.Md5Hex -> computeMd5Hex(path)
            org.krost.unidrive.HashAlgorithm.Sha256Hex -> computeSha256Hex(path)
            org.krost.unidrive.HashAlgorithm.DropboxContentHash -> computeDropboxContentHash(path)
            null -> null
        }

    fun computeSha256Hex(path: Path): String = fileSha256Hex(path)

    /** Dropbox's `content_hash` of [path] (see [org.krost.unidrive.HashAlgorithm.DropboxContentHash]). */
    fun computeDropboxContentHash(path: Path): String {
        val hasher = DropboxContentHasher()
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                hasher.update(buffer, read)
            }
        }
        return hasher.digestHex()
    }

    /** SHA-256 per 4 MiB block, then SHA-256 over the block digests in order. */
    private class DropboxContentHasher {
        private val overall = MessageDigest.getInstance("SHA-256")
        private val block = MessageDigest.getInstance("SHA-256")
        private var inBlock = 0

        fun update(
            buffer: ByteArray,
            length: Int,
        ) {
            var offset = 0
            while (offset < length) {
                val take = minOf(length - offset, BLOCK_BYTES - inBlock)
                block.update(buffer, offset, take)
                inBlock += take
                offset += take
                if (inBlock == BLOCK_BYTES) closeBlock()
            }
        }

        fun digestHex(): String {
            if (inBlock > 0) closeBlock()
            return overall.digest().joinToString("") { "%02x".format(it) }
        }

        private fun closeBlock() {
            overall.update(block.digest())
            inBlock = 0
        }

        companion object {
            private const val BLOCK_BYTES = 4 * 1024 * 1024
        }
    }

    fun computeMd5Hex(path: Path): String = fileDigestHex(path, "MD5")

    fun computeQuickXorHash(path: Path): String = org.krost.unidrive.QuickXorHash.of(path)
}
