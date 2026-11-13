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

    // The constant the wrapper subtracts from the provider's size to answer the plaintext's (XtraEncryptedProvider):
    // the header [XtraHeader.write] lays down — magic(4) ‖ version(1) ‖ algorithmId(1) ‖ wrappedKeyLen(2) ‖
    // wrappedKey(256) ‖ ivLen(2) ‖ iv(12) — plus the 16-byte GCM tag. If this ever drifts from the header layout,
    // every listing size of an X-tra profile is wrong.
    @Test
    fun `the overhead is the header plus the tag`() {
        assertEquals(10 + 256 + 12 + 16, AesGcmStrategy().overheadBytes)
    }
}
