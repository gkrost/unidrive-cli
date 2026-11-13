package org.krost.unidrive.xtra

import java.io.InputStream
import java.io.OutputStream

/**
 * One content-encryption algorithm of the X-tra layer (client-side encryption of a profile's file content, config key
 * `xtra_encryption`): [encrypt] and [decrypt] a stream under a per-file key. The properties describe the file-header
 * fields the strategy needs, so [XtraHeader] reads variable-length headers by algorithm and a second strategy can
 * ship without a format change.
 *
 * The size contract joins the two halves of the decorator: what the provider stores is the header plus the
 * sealed content, and [plaintextSizeOf] inverts that for a well-formed file, so a listing can answer the
 * plaintext size the engines' size contracts hold downloads against. Null for a size no well-formed file of
 * this algorithm could have (the caller leaves such an item alone; its download fails closed on the header).
 */
interface XtraEncryptionStrategy {
    /** The header's algorithm byte; identifies the strategy that must decrypt the file. */
    val algorithmId: Byte

    /** The random IV length the strategy draws per file. */
    val ivLength: Int

    /** The wrapped per-file key length, as [XtraKeyManager.wrapKey] answers it. */
    val wrappedKeyLength: Int

    /**
     * The plaintext size a stored file of [ciphertextSize] bytes holds, or null when no well-formed file of this
     * algorithm has that size. The inverse of what [encrypt] writes for the same plaintext.
     */
    fun plaintextSizeOf(ciphertextSize: Long): Long?

    fun encrypt(
        input: InputStream,
        output: OutputStream,
        fileKey: ByteArray,
        iv: ByteArray,
    )

    fun decrypt(
        input: InputStream,
        output: OutputStream,
        fileKey: ByteArray,
        iv: ByteArray,
    )
}
