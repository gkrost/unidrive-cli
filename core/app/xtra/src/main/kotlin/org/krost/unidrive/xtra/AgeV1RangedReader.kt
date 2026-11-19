package org.krost.unidrive.xtra

import javax.crypto.AEADBadTagException
import kage.crypto.stream.SeekableDecrypt

/**
 * Random access to the stored bytes of one X-tra-encrypted object: the seam the age-v1 ranged reader runs over —
 * a local file in the tests, the inner provider's range answers in the future ranged hydration (per-provider
 * work, outside this module; kage's own source type stays inside the module). The contract mirrors a positional
 * [java.nio.channels.FileChannel.read]: read up to [length] bytes at absolute [position] into [buffer] at
 * [offset] and answer the count. A short read is allowed anywhere, but for `length > 0` the answer is at least one
 * byte, or -1 at or past the end of the stored object — never 0: kage's seekable decrypt takes an answer below one
 * as the end and fails the read. Implementations must be safe to call concurrently.
 */
fun interface RangedCiphertextSource {
    fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int
}

/**
 * Plaintext random access into one stored age-v1 file, opened by [AgeV1Strategy.rangedReader]: the ranged
 * groundwork the hydration feature builds on, without touching the whole-file paths. Every 64 KiB chunk a range
 * covers is decrypted with its Poly1305 tag verified, so a read over a damaged chunk fails —
 * [AEADBadTagException], the same permanent refusal the whole-file decrypt answers — instead of serving
 * garbage, while a read whose range avoids the damage still serves. The payload's last chunk is the one
 * exception to that laziness: kage authenticates it when the reader opens, so a truncated or tail-tampered
 * stored object fails the open itself, whatever range was going to be asked. [readAt] fills [buffer] from
 * plaintext [offset] and answers the count read, short only at the end of the plaintext; an offset outside
 * `[0, plaintextSize]` is refused. Any other failure is a [org.krost.unidrive.ProviderException]; no kage type
 * leaves the module. Safe to call concurrently.
 */
class AgeV1RangedReader internal constructor(private val delegate: SeekableDecrypt) {
    /** The plaintext size the stored object holds — the same size the listings answer for it. */
    val plaintextSize: Long get() = delegate.plaintextSize

    /**
     * Reads up to [buffer.size] plaintext bytes at [offset]: the count answered is short only at the end of
     * the plaintext, and every chunk the span covers has its tag verified along the way.
     */
    fun readAt(
        buffer: ByteArray,
        offset: Long,
    ): Int = readAt(buffer, 0, buffer.size, offset)

    /** Reads up to [length] plaintext bytes at [offset] into [buffer] from [bufferOffset] — a read into part of a buffer. */
    @Suppress("TooGenericExceptionCaught") // kage throws unchecked exceptions across its layers; the cause chain decides
    fun readAt(
        buffer: ByteArray,
        bufferOffset: Int,
        length: Int,
        offset: Long,
    ): Int {
        require(offset in 0..plaintextSize) { "offset out of range [0:$plaintextSize]: $offset" }
        require(bufferOffset >= 0 && length >= 0 && bufferOffset <= buffer.size - length) {
            "buffer range out of bounds: $bufferOffset+$length of ${buffer.size}"
        }
        val whole = bufferOffset == 0 && length == buffer.size
        val target = if (whole) buffer else ByteArray(length)
        val n =
            try {
                delegate.readAt(target, offset)
            } catch (e: Exception) {
                throw rangedFailure(e)
            }
        if (!whole && n > 0) System.arraycopy(target, 0, buffer, bufferOffset, n)
        return n
    }
}
