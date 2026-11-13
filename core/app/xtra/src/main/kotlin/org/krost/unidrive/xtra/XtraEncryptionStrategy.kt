package org.krost.unidrive.xtra

import java.io.InputStream
import java.io.OutputStream

/**
 * One content-encryption algorithm of the X-tra layer (client-side encryption of a profile's file content, config key
 * `xtra_encryption`): [encrypt] and [decrypt] a stream under a per-file key. The properties describe the file-header
 * fields the strategy needs, so [XtraHeader] reads variable-length headers by algorithm and a second strategy can
 * ship without a format change.
 */
interface XtraEncryptionStrategy {
    /** The header's algorithm byte; identifies the strategy that must decrypt the file. */
    val algorithmId: Byte

    /** The random IV length the strategy draws per file. */
    val ivLength: Int

    /** The wrapped per-file key length, as [XtraKeyManager.wrapKey] answers it. */
    val wrappedKeyLength: Int

    /**
     * The bytes the wrapper adds around a file's content: the header (magic, version, algorithm id, the two length
     * prefixes, the wrapped key and the IV) plus the AEAD tag. Constant per algorithm, so the provider's size for a
     * well-formed file names the plaintext size too: the engines' size contracts hold a listing's size against the
     * bytes a download writes (the #80 confirmation, the mirror's completeness check, the warm-cache size match),
     * and the wrapper keeps both on the plaintext side of this difference.
     */
    val overheadBytes: Int

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
