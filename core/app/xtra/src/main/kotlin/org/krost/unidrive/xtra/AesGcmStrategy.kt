package org.krost.unidrive.xtra

import java.io.InputStream
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The default strategy: AES-256-GCM over the stream in independent [SEGMENT_SIZE]-byte segments, a 128-bit tag per
 * segment. Segmented because the JDK's GCM decrypt cannot release unauthenticated plaintext — a whole-stream
 * decrypt holds the entire file in one array, so a 2 GB backup would need 2 GB of daemon heap; per-segment frames
 * bound the memory to two segments and let a truncated or spliced stream fail its tag (the segment index and the
 * last-segment flag ride in the nonce, so segments cannot be reordered, dropped or truncated undetected). This is
 * the on-disk format of algorithm 1; changing it needs a new algorithm id.
 *
 * The nonce is the header's IV with the low bytes meaning counter and flag: bytes 4..10 hold the segment index
 * big-endian (7 bytes, 2^56 segments), byte 11 is 0 for a normal segment and 1 for the last one. The header's
 * fixed part (magic, version, algorithm id, the two length prefixes, the wrapped key and the IV) plus the tags
 * are the difference between a stored file's size and its plaintext's; [plaintextSizeOf] inverts it.
 */
class AesGcmStrategy : XtraEncryptionStrategy {
    override val algorithmId: Byte = 1
    override val ivLength: Int = 12
    override val wrappedKeyLength: Int = 256

    override fun plaintextSizeOf(ciphertextSize: Long): Long? {
        val fixed = headerFixedBytes() + wrappedKeyLength + ivLength
        val body = ciphertextSize - fixed
        if (body < TAG_BYTES) return null
        // A well-formed file holds body = plaintext + TAG_BYTES * segments, segments = max(1, ceil(plaintext /
        // SEGMENT_SIZE)). Two segment counts cannot both fit (the larger count's plaintext is smaller, so its
        // required count would be the smaller one), so the count is unique and, with the plaintext bounded by its
        // count's segment, lies in a one-or-two window around body / (SEGMENT_SIZE + TAG_BYTES). Every candidate
        // is checked against the segment rule, so a size no well-formed file could have is answered null rather
        // than rewritten wrong.
        val guess = (body + SEGMENT_SIZE + TAG_BYTES - 1) / (SEGMENT_SIZE + TAG_BYTES)
        for (segments in longArrayOf(guess - 1, guess, guess + 1)) {
            if (segments < 1L) continue
            val plaintext = body - segments * TAG_BYTES
            if (plaintext < 0L) continue
            val required = if (plaintext == 0L) 1L else (plaintext + SEGMENT_SIZE - 1) / SEGMENT_SIZE
            if (segments == required) return plaintext
        }
        return null
    }

    override fun encrypt(
        input: InputStream,
        output: OutputStream,
        fileKey: ByteArray,
        iv: ByteArray,
    ) {
        var segment = input.readNBytes(SEGMENT_SIZE)
        var index = 0L
        while (segment.isNotEmpty()) {
            val next = input.readNBytes(SEGMENT_SIZE)
            output.write(cipher(fileKey, nonce(iv, index, last = next.isEmpty()), Cipher.ENCRYPT_MODE).doFinal(segment))
            index++
            segment = next
        }
        // An empty plaintext is one final empty segment, so a zero-byte file is a header and a tag.
        if (index == 0L) output.write(cipher(fileKey, nonce(iv, 0, last = true), Cipher.ENCRYPT_MODE).doFinal(ByteArray(0)))
    }

    override fun decrypt(
        input: InputStream,
        output: OutputStream,
        fileKey: ByteArray,
        iv: ByteArray,
    ) {
        var segment = input.readNBytes(SEGMENT_SIZE + TAG_BYTES)
        var index = 0L
        while (segment.isNotEmpty()) {
            val next = input.readNBytes(SEGMENT_SIZE + TAG_BYTES)
            // One segment ahead, so a full-size final segment is decrypted with the last-segment nonce it was
            // written under; a stream that stops early fails its tag instead of yielding short plaintext.
            val plaintext = cipher(fileKey, nonce(iv, index, last = next.isEmpty()), Cipher.DECRYPT_MODE).doFinal(segment)
            output.write(plaintext)
            index++
            segment = next
        }
        if (index == 0L) throw javax.crypto.AEADBadTagException("no segment: the stream holds no sealed content")
    }

    private fun cipher(
        fileKey: ByteArray,
        nonce: ByteArray,
        mode: Int,
    ): Cipher = Cipher.getInstance("AES/GCM/NoPadding").also { it.init(mode, SecretKeySpec(fileKey, "AES"), GCMParameterSpec(TAG_BITS, nonce)) }

    // The IV doubles as the nonce template: bytes 4..10 the big-endian segment index, byte 11 the last-segment
    // flag. The first 4 bytes stay the random per-file prefix, so no two files share a nonce and no two segments
    // of one file do.
    private fun nonce(
        iv: ByteArray,
        index: Long,
        last: Boolean,
    ): ByteArray {
        require(iv.size == ivLength) { "the strategy's IV length is $ivLength, got ${iv.size}" }
        require(index in 0 until (1L shl 56)) { "segment index out of range: $index" }
        return iv.copyOf().also {
            for (i in 0 until 7) it[4 + i] = (index ushr ((6 - i) * 8)).toByte()
            it[ivLength - 1] = if (last) 1 else 0
        }
    }

    private fun headerFixedBytes(): Int = 4 + 1 + 1 + 2 + 2

    private companion object {
        const val TAG_BITS = 128
        const val TAG_BYTES = TAG_BITS / 8
        const val SEGMENT_SIZE = 64 * 1024
    }
}
