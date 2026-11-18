package org.krost.unidrive.internxt

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#747 finding 10: the upload's encrypt pass reads the source in large chunks (the download path and the
 * local content proof use 256 KiB), not in the 512-byte steps a CipherInputStream hands out. The ciphertext and its
 * digest stay exactly what one AES-CTR pass over the whole file gives.
 */
class InternxtEncryptPassTest {
    private val crypto = InternxtCrypto()
    private val key = ByteArray(32) { it.toByte() }
    private val iv = ByteArray(16) { (it * 3).toByte() }
    private val plain = ByteArray(4 * 1024 * 1024 + 17) { (it % 251).toByte() }

    private class CountingInput(
        source: InputStream,
    ) : FilterInputStream(source) {
        var reads = 0

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            reads++
            return super.read(b, off, len)
        }
    }

    @Test
    fun `the encrypt pass reads the source in chunks of at least 64 KiB`() {
        val source = CountingInput(plain.inputStream())

        InternxtProvider.encryptToStaging(source, crypto.createContentEncryptCipher(key, iv), ByteArrayOutputStream(), MessageDigest.getInstance("SHA-256"))

        assertTrue(source.reads <= plain.size / (64 * 1024) + 2, "${source.reads} reads for ${plain.size} bytes")
    }

    @Test
    fun `the staged ciphertext and its digest are those of one pass over the whole file`() {
        val out = ByteArrayOutputStream()
        val digest = MessageDigest.getInstance("SHA-256")

        InternxtProvider.encryptToStaging(plain.inputStream(), crypto.createContentEncryptCipher(key, iv), out, digest)

        val expected = crypto.createContentEncryptCipher(key, iv).doFinal(plain)
        assertContentEquals(expected, out.toByteArray())
        assertContentEquals(MessageDigest.getInstance("SHA-256").digest(expected), digest.digest())
    }
}
