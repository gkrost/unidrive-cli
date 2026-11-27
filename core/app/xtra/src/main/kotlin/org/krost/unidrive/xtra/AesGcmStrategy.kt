package org.krost.unidrive.xtra

import java.io.InputStream
import java.io.OutputStream
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The default strategy: AES-256-GCM over the stream in independent [SEGMENT_SIZE]-byte segments, a 128-bit tag per
 * segment. Segmented because the JDK's GCM decrypt cannot release unauthenticated plaintext — a whole-stream
 * decrypt holds the entire file in one array, so a 2 GB backup would need 2 GB of daemon heap; per-segment frames
 * bound the memory to two segments and let a truncated or spliced stream fail its tag (the segment index and the
 * last-segment flag ride in the nonce, so segments cannot be reordered, dropped or truncated undetected). This is
 * the on-disk format of algorithms 1 and 4; changing it needs a new algorithm id (the header binding below changed it
 * in place once, while the layer was unreleased and no stored file had to survive).
 *
 * The strategy owns its key material, and the two algorithms on this segment format differ only there:
 * - algorithm 1, `aes-gcm` (the public constructor): the installation's RSA-2048 pair ([XtraKeyManager]) wraps each
 *   per-file key with RSA-OAEP into a 256-byte slot.
 * - algorithm 4, `aes-gcm-sym` ([symmetric]): the installation's symmetric master key ([XtraSymmetricKeyStore]) wraps
 *   it with AES-256-GCM into a 60-byte slot. No public-key operation runs anywhere in that chain, so nothing in a
 *   stored file falls to a quantum attacker (Shor breaks RSA and X25519; AES-256 keeps its margin under Grover).
 * Either key store is handed in lazy, so the passphrase demand waits for the first content operation.
 *
 * Every segment binds the serialized [XtraHeader] (magic through IV) as its associated data, so a header byte changed
 * after the write fails the first segment's tag like a changed body byte — the IV's counter bytes, which the nonce
 * overwrites, and the wrapped-key slot included. (A wrapped-key slot changed in place mostly fails earlier, in the
 * key unwrap, which cannot tell a changed slot from another installation's key; both are refused as content.)
 *
 * The nonce is the header's IV with the low bytes meaning counter and flag: bytes 4..10 hold the segment index
 * big-endian (7 bytes, 2^56 segments), byte 11 is 0 for a normal segment and 1 for the last one. The index is a
 * 64-bit counter checked against 2^56 before every segment, so it can never wrap into a nonce already used (the
 * bound is 2^56 x 64 KiB, far beyond any file). The header's
 * fixed part (magic, version, algorithm id, the two length prefixes, the wrapped key and the IV) plus the tags
 * are the difference between a stored file's size and its plaintext's; [plaintextSizeOf] inverts it.
 */
class AesGcmStrategy private constructor(
    override val algorithmId: Byte,
    override val wrappedKeyLength: Int,
    private val wrap: (ByteArray) -> ByteArray,
    // Loads the key store (the passphrase demand) and answers its unwrap; the load runs outside the refusal below.
    private val unwrapper: () -> (ByteArray) -> ByteArray,
) : XtraEncryptionStrategy {
    /** Algorithm 1, `aes-gcm`: the per-file key is wrapped with RSA-OAEP under the installation's RSA pair. */
    constructor(keys: Lazy<XtraKeyManager>) : this(
        ALGORITHM_RSA,
        RSA_WRAPPED_KEY_LENGTH,
        { keys.value.wrapKey(it) },
        { keys.value.let { manager -> manager::unwrapKey } },
    )

    override val ivLength: Int = 12

    // The header slot's key journey is this strategy's: the load behind the lazy is what demands the passphrase, and
    // it fires on the first content operation only.
    override fun wrapFileKey(fileKey: ByteArray): ByteArray = wrap(fileKey)

    // A slot that does not unwrap is refused like a failed seal (owner decision, #821): neither RSA-OAEP nor the
    // AES-GCM wrap can tell a changed slot from another installation's key, and both are answered as damaged content.
    // The load stays outside the translation — a wrong passphrase or a missing key file is this installation's
    // error, never the file's. (AEADBadTagException, the GCM wrap's refusal, is a BadPaddingException.)
    override fun unwrapFileKey(wrapped: ByteArray): ByteArray {
        val unwrap = unwrapper()
        try {
            val fileKey = unwrap(wrapped)
            // The writer only makes 32-byte file keys; any other length would pick AES-128 or -192 (or fail late).
            // Refused like a slot that does not unwrap, but named for what it is: the slot did unwrap.
            if (fileKey.size != FILE_KEY_BYTES) {
                val length = fileKey.size
                fileKey.fill(0)
                throw XtraFileKeyRefusedException("X-tra file key is $length bytes, not $FILE_KEY_BYTES")
            }
            return fileKey
        } catch (e: BadPaddingException) {
            throw XtraFileKeyRefusedException("X-tra file key does not unwrap: ${e.message}", e)
        } catch (e: IllegalBlockSizeException) {
            throw XtraFileKeyRefusedException("X-tra file key does not unwrap: ${e.message}", e)
        }
    }

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
        header: ByteArray,
    ) {
        var segment = input.readNBytes(SEGMENT_SIZE)
        var index = 0L
        while (segment.isNotEmpty()) {
            val next = input.readNBytes(SEGMENT_SIZE)
            output.write(cipher(fileKey, nonce(iv, index, last = next.isEmpty()), header, Cipher.ENCRYPT_MODE).doFinal(segment))
            index++
            segment = next
        }
        // An empty plaintext is one final empty segment, so a zero-byte file is a header and a tag.
        if (index == 0L) output.write(cipher(fileKey, nonce(iv, 0, last = true), header, Cipher.ENCRYPT_MODE).doFinal(ByteArray(0)))
    }

    override fun decrypt(
        input: InputStream,
        output: OutputStream,
        fileKey: ByteArray,
        iv: ByteArray,
        header: ByteArray,
    ) {
        var segment = input.readNBytes(SEGMENT_SIZE + TAG_BYTES)
        var index = 0L
        while (segment.isNotEmpty()) {
            val next = input.readNBytes(SEGMENT_SIZE + TAG_BYTES)
            // One segment ahead, so a full-size final segment is decrypted with the last-segment nonce it was
            // written under; a stream that stops early fails its tag instead of yielding short plaintext.
            val plaintext = cipher(fileKey, nonce(iv, index, last = next.isEmpty()), header, Cipher.DECRYPT_MODE).doFinal(segment)
            output.write(plaintext)
            index++
            segment = next
        }
        if (index == 0L) throw javax.crypto.AEADBadTagException("no segment: the stream holds no sealed content")
    }

    private fun cipher(
        fileKey: ByteArray,
        nonce: ByteArray,
        header: ByteArray,
        mode: Int,
    ): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").also {
            it.init(mode, SecretKeySpec(fileKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            it.updateAAD(header)
        }

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

    companion object {
        /** The header's algorithm byte of `aes-gcm` (RSA-wrapped file keys). */
        const val ALGORITHM_RSA: Byte = 1

        /** The header's algorithm byte of `aes-gcm-sym` (file keys wrapped under the symmetric master key). */
        const val ALGORITHM_SYMMETRIC: Byte = 4

        private const val RSA_WRAPPED_KEY_LENGTH = 256

        private const val FILE_KEY_BYTES = 32

        /**
         * Algorithm 4, `aes-gcm-sym`: algorithm 1's segment format with the per-file key wrapped under the installation's
         * symmetric master key ([XtraSymmetricKeyStore.wrapKey]). No RSA or X25519 operation runs for it.
         */
        fun symmetric(keys: Lazy<XtraSymmetricKeyStore>): AesGcmStrategy =
            AesGcmStrategy(
                ALGORITHM_SYMMETRIC,
                XtraSymmetricKeyStore.WRAPPED_KEY_LENGTH,
                { keys.value.wrapKey(it) },
                { keys.value.let { store -> store::unwrapKey } },
            )

        private const val TAG_BITS = 128
        private const val TAG_BYTES = TAG_BITS / 8
        private const val SEGMENT_SIZE = 64 * 1024
    }
}
