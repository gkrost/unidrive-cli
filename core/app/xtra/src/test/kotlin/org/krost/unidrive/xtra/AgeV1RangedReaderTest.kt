package org.krost.unidrive.xtra

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.krost.unidrive.ProviderException
import org.krost.unidrive.testing.TestScratch

class AgeV1RangedReaderTest {

    private val chunk = 64 * 1024

    private fun strategy(): AgeV1Strategy {
        val store = XtraAgeKeyStore(TestScratch.directory("xtra-ranged-test").resolve("xtra-age.key"))
        store.generate(PASSPHRASE)
        return AgeV1Strategy(lazy { store }, { PASSPHRASE })
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }

    // The stored object exactly as the provider writes it: the X-tra header, then the age file.
    private fun storedOf(
        strategy: AgeV1Strategy,
        plaintext: ByteArray,
    ): ByteArray {
        val ageFile = ByteArrayOutputStream().also { out ->
            strategy.encrypt(ByteArrayInputStream(plaintext), out, ByteArray(strategy.wrappedKeyLength), ByteArray(strategy.ivLength), ByteArray(0))
        }.toByteArray()
        return ByteArrayOutputStream().also { out ->
            XtraHeader.write(out, strategy.algorithmId, strategy.wrapFileKey(ByteArray(32)), ByteArray(strategy.ivLength))
            out.write(ageFile)
        }.toByteArray()
    }

    // The whole-file decrypt of the same stored object: the ranged slices must be byte-exact against it.
    private fun fullDecryptOf(
        strategy: AgeV1Strategy,
        stored: ByteArray,
    ): ByteArray {
        val input = ByteArrayInputStream(stored)
        XtraHeader.read(input)
        return ByteArrayOutputStream().also { out ->
            strategy.decrypt(input, out, ByteArray(strategy.wrappedKeyLength), ByteArray(strategy.ivLength), ByteArray(0))
        }.toByteArray()
    }

    private fun sourceOver(bytes: ByteArray): RangedCiphertextSource =
        RangedCiphertextSource { position, buffer, offset, length ->
            if (position >= bytes.size) {
                -1
            } else {
                val n = minOf(length, (bytes.size - position).toInt())
                System.arraycopy(bytes, position.toInt(), buffer, offset, n)
                n
            }
        }

    private fun readerOver(
        strategy: AgeV1Strategy,
        stored: ByteArray,
    ): AgeV1RangedReader = strategy.rangedReader(sourceOver(stored), stored.size.toLong())

    private fun readSlice(
        reader: AgeV1RangedReader,
        offset: Long,
        length: Int,
    ): ByteArray {
        val out = ByteArray(length)
        val n = reader.readAt(out, offset)
        return out.copyOf(n)
    }

    // Where the encrypted payload sits in the stored object: past the X-tra header and the age file's own
    // header plus nonce; an empty plaintext's stored object is exactly that plus one empty final chunk's tag.
    private fun payloadStartOf(strategy: AgeV1Strategy): Int = storedOf(strategy, ByteArray(0)).size - 16

    private fun tamperChunk(
        strategy: AgeV1Strategy,
        stored: ByteArray,
        chunkIndex: Int,
    ) {
        val at = payloadStartOf(strategy) + chunkIndex * (chunk + 16) + 24
        stored[at] = (stored[at].toInt() xor 0x5A).toByte()
    }

    @Test
    fun `a read at a chunk boundary returns the exact plaintext`() {
        val strategy = strategy()
        val plaintext = randomBytes(3 * chunk + 5000)
        val reader = readerOver(strategy, storedOf(strategy, plaintext))

        assertContentEquals(plaintext.copyOfRange(chunk, chunk + 8192), readSlice(reader, chunk.toLong(), 8192))
        assertContentEquals(plaintext.copyOfRange(0, 8192), readSlice(reader, 0, 8192))
    }

    @Test
    fun `a range spanning several chunks is byte-exact against the full decrypt`() {
        val strategy = strategy()
        val plaintext = randomBytes(3 * chunk + 5000)
        val stored = storedOf(strategy, plaintext)
        val reader = readerOver(strategy, stored)

        val offset = 60_000
        val length = 2 * chunk
        assertContentEquals(
            fullDecryptOf(strategy, stored).copyOfRange(offset, offset + length),
            readSlice(reader, offset.toLong(), length),
        )
        assertContentEquals(plaintext.copyOfRange(offset, offset + length), readSlice(reader, offset.toLong(), length))
    }

    @Test
    fun `the last chunk serves and the plaintext end short-reads`() {
        val strategy = strategy()
        val tail = 1234
        val plaintext = randomBytes(3 * chunk + tail)
        val reader = readerOver(strategy, storedOf(strategy, plaintext))

        val out = ByteArray(chunk)
        val n = reader.readAt(out, (3 * chunk).toLong())
        assertEquals(tail, n)
        assertContentEquals(plaintext.copyOfRange(3 * chunk, plaintext.size), out.copyOf(n))
        assertEquals(0, reader.readAt(ByteArray(16), reader.plaintextSize))
    }

    @Test
    fun `the reader answers the size the listings answer`() {
        val strategy = strategy()
        val plaintext = randomBytes(2 * chunk + 777)
        val stored = storedOf(strategy, plaintext)
        val reader = readerOver(strategy, stored)

        assertEquals(plaintext.size.toLong(), strategy.plaintextSizeOf(stored.size.toLong()))
        assertEquals(plaintext.size.toLong(), reader.plaintextSize)
    }

    @Test
    fun `a corrupt chunk inside the range fails the read`() {
        val strategy = strategy()
        val plaintext = randomBytes(4 * chunk)
        val stored = storedOf(strategy, plaintext)
        tamperChunk(strategy, stored, chunkIndex = 1)
        val reader = readerOver(strategy, stored)

        assertFailsWith<AEADBadTagException> {
            readSlice(reader, chunk.toLong(), 8192)
        }
    }

    // The documented property: only the chunks a range covers are read, so damage elsewhere does not fail a
    // read that never touches it (the payload's last chunk is the exception — authenticated at the open).
    @Test
    fun `a read whose range avoids the damaged chunk still serves`() {
        val strategy = strategy()
        val plaintext = randomBytes(4 * chunk)
        val stored = storedOf(strategy, plaintext)
        tamperChunk(strategy, stored, chunkIndex = 1)
        val reader = readerOver(strategy, stored)

        assertContentEquals(plaintext.copyOfRange(0, chunk), readSlice(reader, 0, chunk))
    }

    @Test
    fun `a tampered final chunk fails the open itself`() {
        val strategy = strategy()
        val stored = storedOf(strategy, randomBytes(4 * chunk))
        stored[stored.size - 1] = (stored[stored.size - 1].toInt() xor 0x5A).toByte()

        assertFailsWith<AEADBadTagException> {
            readerOver(strategy, stored)
        }
    }

    // Truncation is a property of the stored bytes: another attempt reads the same object, so it is the same
    // permanent refusal as the whole-file decrypt's. If these tests are loosened to "any exception", a truncated
    // object silently becomes a retry-forever failure.
    @Test
    fun `an object cut at a chunk boundary refuses the open as an authentication failure`() {
        val strategy = strategy()
        val stored = storedOf(strategy, randomBytes(4 * chunk))
        val cut = stored.copyOf(stored.size - (chunk + 16))

        assertFailsWith<AEADBadTagException> { readerOver(strategy, cut) }
    }

    @Test
    fun `an object cut inside a chunk refuses the open as an authentication failure`() {
        val strategy = strategy()
        val stored = storedOf(strategy, randomBytes(4 * chunk))
        val cut = stored.copyOf(stored.size - 100_000)

        assertFailsWith<AEADBadTagException> { readerOver(strategy, cut) }
    }

    // A listing larger than the object (a stale size) is not damaged content: the source ends early, kage answers
    // with its own StreamException, and the reader hands out a ProviderException, never a kage type.
    @Test
    fun `a source that ends before the listed size fails as a provider error, not a refusal`() {
        val strategy = strategy()
        val stored = storedOf(strategy, randomBytes(2 * chunk))
        val listedSize = storedOf(strategy, randomBytes(3 * chunk)).size.toLong()

        val failure = assertFailsWith<ProviderException> { strategy.rangedReader(sourceOver(stored), listedSize) }
        assertEquals(ProviderException::class, failure::class, "a subtype would be a classification: $failure")
        assertTrue(failure.cause != null && failure.cause!!::class.qualifiedName!!.startsWith("kage."), "$failure")
    }

    // The source contract allows short reads anywhere: one byte per call must serve the open and every read.
    @Test
    fun `a source answering one byte per call serves the open and a multi-chunk range`() {
        val strategy = strategy()
        val plaintext = randomBytes(3 * chunk + 123)
        val stored = storedOf(strategy, plaintext)
        val trickle = RangedCiphertextSource { position, buffer, offset, length ->
            if (position >= stored.size) {
                -1
            } else {
                buffer[offset] = stored[position.toInt()]
                if (length == 0) 0 else 1
            }
        }
        val reader = strategy.rangedReader(trickle, stored.size.toLong())

        assertContentEquals(plaintext.copyOfRange(chunk - 7, 2 * chunk + 9), readSlice(reader, (chunk - 7).toLong(), chunk + 16))
    }

    @Test
    fun `a read into part of a buffer fills only that part`() {
        val strategy = strategy()
        val plaintext = randomBytes(2 * chunk)
        val reader = readerOver(strategy, storedOf(strategy, plaintext))
        val buffer = ByteArray(64) { 0x11 }

        assertEquals(32, reader.readAt(buffer, 16, 32, (chunk - 10).toLong()))
        assertContentEquals(ByteArray(16) { 0x11 }, buffer.copyOfRange(0, 16))
        assertContentEquals(plaintext.copyOfRange(chunk - 10, chunk + 22), buffer.copyOfRange(16, 48))
        assertContentEquals(ByteArray(16) { 0x11 }, buffer.copyOfRange(48, 64))
        assertFailsWith<IllegalArgumentException> { reader.readAt(buffer, 60, 8, 0) }
    }

    @Test
    fun `an out-of-bounds offset is refused`() {
        val strategy = strategy()
        val reader = readerOver(strategy, storedOf(strategy, randomBytes(chunk / 2)))

        assertFailsWith<IllegalArgumentException> {
            reader.readAt(ByteArray(16), reader.plaintextSize + 1)
        }
        assertFailsWith<IllegalArgumentException> {
            reader.readAt(ByteArray(16), -1)
        }
    }

    @Test
    fun `an object without the X-tra header refuses the open`() {
        val strategy = strategy()
        val plain = "plain bytes, not xtra".toByteArray()

        assertFailsWith<ProviderException> {
            strategy.rangedReader(sourceOver(plain), plain.size.toLong())
        }
    }

    // The same answers as the whole-file download (validHeaderFor): a file of another algorithm is the retryable
    // mismatch that comes back after a switch back, and no identity is demanded for it.
    @Test
    fun `a stored file of another algorithm refuses the open as an algorithm mismatch without a key demand`() {
        val noKey = AgeV1Strategy(lazy { error("the identity must not be demanded") }, { error("no passphrase") })
        val other = ByteArrayOutputStream().also { out ->
            XtraHeader.write(out, 1, ByteArray(noKey.wrappedKeyLength), ByteArray(0))
            out.write(ByteArray(64))
        }.toByteArray()

        assertFailsWith<XtraAlgorithmMismatchException> {
            noKey.rangedReader(sourceOver(other), other.size.toLong())
        }
    }

    @Test
    fun `a damaged length prefix refuses the open as content`() {
        val strategy = strategy()
        val stored = storedOf(strategy, randomBytes(chunk))
        stored[7] = (stored[7].toInt() xor 0x01).toByte() // the wrapped-key length prefix's low byte

        val refused = assertFailsWith<org.krost.unidrive.RemoteContentRefusedDownloadException> { readerOver(strategy, stored) }
        assertEquals("header corrupted", refused.reason)
    }

    @Test
    fun `an empty plaintext serves an empty range`() {
        val strategy = strategy()
        val reader = readerOver(strategy, storedOf(strategy, ByteArray(0)))

        assertEquals(0L, reader.plaintextSize)
        assertEquals(0, reader.readAt(ByteArray(16), 0))
    }

    companion object {
        private val PASSPHRASE = "testpass1234".toCharArray()
    }
}
