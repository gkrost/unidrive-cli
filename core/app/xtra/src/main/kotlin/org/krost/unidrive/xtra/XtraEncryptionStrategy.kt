package org.krost.unidrive.xtra

import java.io.InputStream
import java.io.OutputStream

/**
 * One content-encryption algorithm of the X-tra layer (client-side encryption of a profile's file content, config key
 * `xtra_encryption`): [encrypt] and [decrypt] a stream under a per-file key — and the strategy owns that key material
 * end-to-end. It answers the header's wrapped-key slot itself ([wrapFileKey] on the way in, [unwrapFileKey] on the
 * way back), so no key manager exists outside the strategy and an algorithm whose format carries no key through the
 * header (the age file wraps its own) demands no key store but its own. The properties describe the file-header
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

    /** The wrapped per-file key length, as [wrapFileKey] answers it. */
    val wrappedKeyLength: Int

    /**
     * The plaintext size a stored file of [ciphertextSize] bytes holds, or null when no well-formed file of this
     * algorithm has that size. The inverse of what [encrypt] writes for the same plaintext.
     */
    fun plaintextSizeOf(ciphertextSize: Long): Long?

    /**
     * What the header's wrapped-key slot holds for a file sealed under [fileKey]: the strategy's own key store does
     * the wrapping, and may load it here — the first content operation, so commands that only list never demand a
     * passphrase. An algorithm whose format carries no key through the header answers the slot's filler instead.
     */
    fun wrapFileKey(fileKey: ByteArray): ByteArray

    /**
     * The file key the header's wrapped-key slot carries for [decrypt]. Called only on a valid header of this
     * strategy's algorithm byte; an algorithm that ignores the slot answers a placeholder its decrypt ignores too.
     */
    fun unwrapFileKey(wrapped: ByteArray): ByteArray

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
