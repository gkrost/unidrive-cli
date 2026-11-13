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
