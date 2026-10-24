package org.krost.unidrive.sync

import org.krost.unidrive.ContentProof
import org.krost.unidrive.HashAlgorithm
import java.nio.file.Path
import java.util.Base64

/**
 * The proof that a local file is the cloud copy, from the content hash the cloud row carries (archive#1219): the file is hashed
 * locally with the row's algorithm and compared with the recorded value, so nothing is downloaded.
 *
 * Unlike [HashVerifier.verify] ("true when unverifiable") and [HashVerifier.matches] ("false when unverifiable"), this answers three ways, because
 * its caller has a fallback that is expensive and a wrong "different" is only wasteful while a wrong "same" is data loss:
 *  - a [ContentProof] with `same = true` or `false` when the recorded hash is in the shape the row's algorithm produces and was compared;
 *  - null when it cannot tell: no hash on the row, no algorithm, an algorithm this proof does not cover, or a recorded value of another
 *    shape than the algorithm's (a OneDrive item that carries only a SHA-256 is handled; a value that is neither is not guessed at).
 *
 * Covered: OneDrive's quickXorHash (base64 of 20 bytes) and, where the Graph item had only that, its sha256Hash (64 hex). Nothing else is
 * wired: a provider with another algorithm answers null here and keeps its behaviour.
 */
object RowHashProof {
    const val METHOD_QUICKXOR = "quickxor"
    const val METHOD_SHA256 = "sha256"

    private const val QUICKXOR_BYTES = 20
    private const val SHA256_HEX_LENGTH = 64

    fun prove(
        local: Path,
        remoteHash: String?,
        algorithm: HashAlgorithm?,
    ): ContentProof? {
        if (remoteHash.isNullOrBlank() || algorithm != HashAlgorithm.QuickXor) return null
        return when {
            isQuickXor(remoteHash) -> ContentProof(HashVerifier.computeQuickXorHash(local) == remoteHash, METHOD_QUICKXOR)
            isSha256Hex(remoteHash) -> ContentProof(HashVerifier.computeSha256Hex(local).equals(remoteHash, ignoreCase = true), METHOD_SHA256)
            else -> null
        }
    }

    // The base64 text of a 20-byte value. QuickXorHash.of writes the standard alphabet with padding, so equal bytes are equal text.
    private fun isQuickXor(value: String): Boolean =
        value.length == BASE64_QUICKXOR_LENGTH &&
            runCatching { Base64.getDecoder().decode(value).size == QUICKXOR_BYTES }.getOrDefault(false)

    private fun isSha256Hex(value: String): Boolean =
        value.length == SHA256_HEX_LENGTH && value.all { it.code < ASCII_LIMIT && Character.digit(it, HEX_RADIX) >= 0 }

    private const val BASE64_QUICKXOR_LENGTH = 28
    private const val ASCII_LIMIT = 128
    private const val HEX_RADIX = 16
}
