package org.krost.unidrive.xtra

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AesGcmStrategyTest {

    private fun fileKey() = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private fun iv(strategy: AesGcmStrategy) = ByteArray(strategy.ivLength).also { SecureRandom().nextBytes(it) }

    @Test
    fun `encrypt then decrypt round-trips`() {
        val strategy = AesGcmStrategy()
        val key = fileKey()
        val iv = iv(strategy)
        val plaintext = "Hello, X-tra!".toByteArray()

        val encrypted = ByteArrayOutputStream().also { out ->
            strategy.encrypt(ByteArrayInputStream(plaintext), out, key, iv)
        }.toByteArray()
        kotlin.test.assertNotEquals(plaintext.toList(), encrypted.toList())

        val decrypted = ByteArrayOutputStream().also { out ->
            strategy.decrypt(ByteArrayInputStream(encrypted), out, key, iv)
        }.toByteArray()
        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun `empty input round-trips`() {
        val strategy = AesGcmStrategy()
        val key = fileKey()
        val iv = iv(strategy)

        val encrypted = ByteArrayOutputStream().also { out ->
            strategy.encrypt(ByteArrayInputStream(ByteArray(0)), out, key, iv)
        }.toByteArray()
        assertEquals(16, encrypted.size) // the GCM tag of an empty plaintext

        val decrypted = ByteArrayOutputStream().also { out ->
            strategy.decrypt(ByteArrayInputStream(encrypted), out, key, iv)
        }.toByteArray()
        assertEquals(0, decrypted.size)
    }

    @Test
    fun `large input round-trips across the buffer boundary`() {
        val strategy = AesGcmStrategy()
        val key = fileKey()
        val iv = iv(strategy)
        val plaintext = ByteArray(200_000).also { SecureRandom().nextBytes(it) }

        val encrypted = ByteArrayOutputStream().also { out ->
            strategy.encrypt(ByteArrayInputStream(plaintext), out, key, iv)
        }.toByteArray()
        val decrypted = ByteArrayOutputStream().also { out ->
            strategy.decrypt(ByteArrayInputStream(encrypted), out, key, iv)
        }.toByteArray()
        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun `a tampered ciphertext fails the decrypt`() {
        val strategy = AesGcmStrategy()
        val key = fileKey()
        val iv = iv(strategy)
        val encrypted = ByteArrayOutputStream().also { out ->
            strategy.encrypt(ByteArrayInputStream("payload".toByteArray()), out, key, iv)
        }.toByteArray()
        encrypted[encrypted.size - 1] = (encrypted[encrypted.size - 1].toInt() xor 1).toByte()

        assertFailsWith<Exception> {
            strategy.decrypt(ByteArrayInputStream(encrypted), ByteArrayOutputStream(), key, iv)
        }
    }

    @Test
    fun `a wrong key fails the decrypt`() {
        val strategy = AesGcmStrategy()
        val iv = iv(strategy)
        val encrypted = ByteArrayOutputStream().also { out ->
            strategy.encrypt(ByteArrayInputStream("payload".toByteArray()), out, fileKey(), iv)
        }.toByteArray()

        assertFailsWith<Exception> {
            strategy.decrypt(ByteArrayInputStream(encrypted), ByteArrayOutputStream(), fileKey(), iv)
        }
    }

    // The size math the listings decorator answers from (XtraPlaintextListings): a stored file is the header —
    // magic(4) ‖ version(1) ‖ algorithmId(1) ‖ wrappedKeyLen(2) ‖ wrappedKey(256) ‖ ivLen(2) ‖ iv(12) — plus the
    // plaintext plus one 16-byte tag per 64 KiB segment (an empty file is one final empty segment). The inverse
    // must be exact on every image and null on every size no well-formed file can have, or a listing of an
    // X-tra profile would answer a wrong size.
    @Test
    fun `the size math inverts exactly and refuses foreign sizes`() {
        val strategy = AesGcmStrategy()
        val fixed = 10L + 256 + 12
        val seg = 65536L
        val tag = 16L
        for (p in listOf(0L, 1L, seg - 1, seg, seg + 1, 2 * seg, 2 * seg + 7, 500_000L)) {
            val stored = fixed + p + tag * maxOf(1L, (p + seg - 1) / seg)
            assertEquals(p, strategy.plaintextSizeOf(stored), "plaintext $p from stored $stored")
        }
        assertEquals(null, strategy.plaintextSizeOf(fixed), "a header without a tag is not a file")
        assertEquals(null, strategy.plaintextSizeOf(fixed + 5), "below the empty file's single tag")
        // In the gap the segment jump leaves at a boundary: the sizes a well-formed file never has.
        assertEquals(null, strategy.plaintextSizeOf(fixed + seg + tag + 5))
    }

    // The whole point of the segments: the decrypt holds two segments, not the file, and a stream cut at a
    // segment boundary fails the final segment's tag instead of yielding short plaintext.
    @Test
    fun `a stream truncated at a segment boundary fails the decrypt`() {
        val strategy = AesGcmStrategy()
        val key = fileKey()
        val iv = iv(strategy)
        val plaintext = ByteArray(3 * 65536).also { SecureRandom().nextBytes(it) }
        val encrypted = ByteArrayOutputStream().also { out ->
            strategy.encrypt(ByteArrayInputStream(plaintext), out, key, iv)
        }.toByteArray()
        assertEquals(3 * 65536 + 3 * 16, encrypted.size)

        val cut = encrypted.copyOfRange(0, 2 * (65536 + 16))
        assertFailsWith<Exception> {
            strategy.decrypt(ByteArrayInputStream(cut), ByteArrayOutputStream(), key, iv)
        }
    }

    @Test
    fun `a spliced stream fails the decrypt`() {
        val strategy = AesGcmStrategy()
        val key = fileKey()
        val iv = iv(strategy)
        val encryptedOfA = ByteArrayOutputStream().also { out ->
            strategy.encrypt(ByteArrayInputStream(ByteArray(2 * 65536)), out, key, iv)
        }.toByteArray()
        // Segment 2 of another file with the same IV, spliced in: its nonce names segment 2, so the splice is a
        // legal-looking frame — the header IV's random prefix is what makes such a frame never verify.
        val otherIv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val other = ByteArrayOutputStream().also { out ->
            strategy.encrypt(ByteArrayInputStream(ByteArray(65536)), out, key, otherIv)
        }.toByteArray()
        val spliced = encryptedOfA.copyOfRange(0, 65536 + 16) + other.copyOfRange(65536, 65536 + 16)

        assertFailsWith<Exception> {
            strategy.decrypt(ByteArrayInputStream(spliced), ByteArrayOutputStream(), key, iv)
        }
    }
}
