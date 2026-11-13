package org.krost.unidrive.xtra

import java.io.InputStream
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The default strategy: AES-256-GCM over the whole stream, a 128-bit tag, streamed in 64 KB steps. */
class AesGcmStrategy : XtraEncryptionStrategy {
    override val algorithmId: Byte = 1
    override val ivLength: Int = 12
    override val wrappedKeyLength: Int = 256

    // The header's fixed part: magic(4) ‖ version(1) ‖ algorithmId(1) ‖ wrappedKeyLen(2) ‖ ivLen(2), the lengths
    // [XtraHeader.write] lays down before the variable fields; with the wrapped key, the IV and the GCM tag this
    // is the constant difference between a stored file's size and its plaintext's.
    override val overheadBytes: Int = HEADER_FIXED_BYTES + wrappedKeyLength + ivLength + TAG_BITS / 8

    private companion object {
        const val BUFFER_SIZE = 65536
        const val TAG_BITS = 128
        const val HEADER_FIXED_BYTES = 10
    }

    override fun encrypt(
        input: InputStream,
        output: OutputStream,
        fileKey: ByteArray,
        iv: ByteArray,
    ) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(fileKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
        val buf = ByteArray(BUFFER_SIZE)
        var n: Int
        while (input.read(buf).also { n = it } != -1) {
            val chunk = cipher.update(buf, 0, n)
            if (chunk != null) output.write(chunk)
        }
        output.write(cipher.doFinal())
    }

    override fun decrypt(
        input: InputStream,
        output: OutputStream,
        fileKey: ByteArray,
        iv: ByteArray,
    ) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(fileKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
        val buf = ByteArray(BUFFER_SIZE)
        var n: Int
        while (input.read(buf).also { n = it } != -1) {
            val chunk = cipher.update(buf, 0, n)
            if (chunk != null) output.write(chunk)
        }
        output.write(cipher.doFinal())
    }
}
