package org.krost.unidrive.xtra

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * The self-describing header every X-tra-encrypted file starts with:
 * `XTRA(4) ‖ version(1) ‖ algorithmId(1) ‖ wrappedKeyLen(2) ‖ wrappedKey(N) ‖ ivLen(2) ‖ iv(N) ‖ ciphertext`.
 * Lengths are uint16 big-endian, so a later strategy with other key or IV sizes needs no format change.
 *
 * The header's own bytes ([serialize], [ReadResult.Valid.bytes]) are what a strategy binds as associated data of its
 * seal where its format can carry it (see [XtraEncryptionStrategy.encrypt]): the format is deterministic, so the bytes
 * a reader rebuilds from the fields it parsed are the bytes the writer wrote.
 */
object XtraHeader {
    private val MAGIC = "XTRA".toByteArray()
    private const val VERSION: Byte = 0x01

    sealed class ReadResult {
        data class Valid(
            val algorithmId: Byte,
            val wrappedKey: ByteArray,
            val iv: ByteArray,
        ) : ReadResult() {
            /** The header exactly as stored, magic through IV: the associated data the strategy's seal is bound to. */
            val bytes: ByteArray get() = serialize(algorithmId, wrappedKey, iv)
        }

        /** No XTRA magic: the stream is not an X-tra-encrypted file. */
        data object NotEncrypted : ReadResult()

        data class Corrupted(val reason: String) : ReadResult()
    }

    fun write(
        output: OutputStream,
        algorithmId: Byte,
        wrappedKey: ByteArray,
        iv: ByteArray,
    ) {
        output.write(serialize(algorithmId, wrappedKey, iv))
    }

    /** The header's bytes as [write] stores them: magic, version, algorithm id, both length-prefixed fields. */
    fun serialize(
        algorithmId: Byte,
        wrappedKey: ByteArray,
        iv: ByteArray,
    ): ByteArray =
        ByteBuffer
            .allocate(MAGIC.size + 2 + 2 + wrappedKey.size + 2 + iv.size)
            .put(MAGIC)
            .put(VERSION)
            .put(algorithmId)
            .putShort(wrappedKey.size.toShort())
            .put(wrappedKey)
            .putShort(iv.size.toShort())
            .put(iv)
            .array()

    fun read(input: InputStream): ReadResult {
        val magic = input.readNBytes(4)
        if (magic.size < 4 || !magic.contentEquals(MAGIC)) return ReadResult.NotEncrypted
        val va = input.readNBytes(2)
        if (va.size < 2) return ReadResult.Corrupted("Truncated after magic")
        if (va[0] != VERSION) return ReadResult.Corrupted("Unknown version: ${va[0]}")
        val algorithmId = va[1]
        val wkLenBytes = input.readNBytes(2)
        if (wkLenBytes.size < 2) return ReadResult.Corrupted("Missing wrapped key length")
        val wkLen = ByteBuffer.wrap(wkLenBytes).short.toInt() and 0xFFFF
        val wrappedKey = input.readNBytes(wkLen)
        if (wrappedKey.size < wkLen) return ReadResult.Corrupted("Wrapped key truncated")
        val ivLenBytes = input.readNBytes(2)
        if (ivLenBytes.size < 2) return ReadResult.Corrupted("Missing IV length")
        val ivLen = ByteBuffer.wrap(ivLenBytes).short.toInt() and 0xFFFF
        val iv = input.readNBytes(ivLen)
        if (iv.size < ivLen) return ReadResult.Corrupted("IV truncated")
        return ReadResult.Valid(algorithmId, wrappedKey, iv)
    }
}
