package org.krost.unidrive

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * Microsoft's QuickXorHash (the "QuickXorHash algorithm" document of the OneDrive developer docs).
 *
 * A circular 160-bit register: byte k of the input is XORed in at bit offset `(11 * k) mod 160`, bits that
 * pass bit 159 wrap to bit 0. At the end the 64-bit little-endian input length is XORed into bytes 12..19.
 * The register is kept as 20 bytes in little-endian bit order, which is the byte layout of the hash OneDrive
 * reports, so [digest] needs no rotation or reordering.
 *
 * Lives here rather than in the engine so the OneDrive provider can compare a local file with the
 * `quickXorHash` of a remote item without depending on the engine.
 */
class QuickXorHash {
    private val register = ByteArray(WIDTH_BYTES)

    // (11 * bytes so far) mod 160; kept reduced so the input length can never overflow it.
    private var bitPosition = 0
    private var lengthSoFar = 0L

    fun update(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) {
        var position = bitPosition
        for (i in offset until offset + length) {
            val low = position ushr 3
            val high = if (low + 1 == WIDTH_BYTES) 0 else low + 1
            // The byte shifted to its bit offset spans at most two register bytes.
            val shifted = (buffer[i].toInt() and 0xFF) shl (position and 7)
            register[low] = (register[low].toInt() xor shifted).toByte()
            register[high] = (register[high].toInt() xor (shifted ushr 8)).toByte()
            position += SHIFT
            if (position >= WIDTH_BITS) position -= WIDTH_BITS
        }
        bitPosition = position
        lengthSoFar += length
    }

    /** The 20-byte hash of what was fed so far; does not change the state, so more input may follow. */
    fun digest(): ByteArray {
        val result = register.copyOf()
        var remaining = lengthSoFar
        for (i in 0 until Long.SIZE_BYTES) {
            val index = WIDTH_BYTES - Long.SIZE_BYTES + i
            result[index] = (result[index].toInt() xor (remaining and 0xFF).toInt()).toByte()
            remaining = remaining ushr 8
        }
        return result
    }

    companion object {
        private const val WIDTH_BITS = 160
        private const val WIDTH_BYTES = WIDTH_BITS / 8
        private const val SHIFT = 11

        /** The QuickXorHash of the file at [path], Base64 as OneDrive reports it. */
        fun of(path: Path): String = Files.newInputStream(path).use { of(it) }

        /** The QuickXorHash of everything [input] yields, Base64 as OneDrive reports it. Does not close [input]. */
        fun of(input: InputStream): String {
            val qxh = QuickXorHash()
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                qxh.update(buffer, 0, read)
            }
            return Base64.getEncoder().encodeToString(qxh.digest())
        }
    }
}
