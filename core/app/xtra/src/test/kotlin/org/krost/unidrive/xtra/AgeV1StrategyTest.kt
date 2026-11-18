package org.krost.unidrive.xtra

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.krost.unidrive.testing.TestScratch

class AgeV1StrategyTest {

    private fun strategy(): AgeV1Strategy {
        val store = XtraAgeKeyStore(TestScratch.directory("xtra-age-test").resolve("xtra-age.key"))
        store.generate(PASSPHRASE)
        return AgeV1Strategy(lazy { store }, { PASSPHRASE })
    }

    // The stored layout the size math answers from: the XTRA header (fixed part + RSA-wrapped key slot, which
    // this algorithm does not consume) plus the age file, whose overhead is the strategy's own measured one.
    private fun storedSizeOf(
        strategy: AgeV1Strategy,
        ageFileSize: Int,
    ): Long = 10L + strategy.wrappedKeyLength + strategy.ivLength + ageFileSize

    private fun encryptOf(
        strategy: AgeV1Strategy,
        plaintext: ByteArray,
    ): ByteArray = ByteArrayOutputStream().also { out ->
        strategy.encrypt(ByteArrayInputStream(plaintext), out, ByteArray(strategy.wrappedKeyLength), ByteArray(strategy.ivLength))
    }.toByteArray()

    private fun decryptOf(
        strategy: AgeV1Strategy,
        ciphertext: ByteArray,
    ): ByteArray = ByteArrayOutputStream().also { out ->
        strategy.decrypt(ByteArrayInputStream(ciphertext), out, ByteArray(strategy.wrappedKeyLength), ByteArray(strategy.ivLength))
    }.toByteArray()

    @Test
    fun `metadata sizing does not load the identity or request its passphrase`() {
        val strategy = AgeV1Strategy(
            lazy { error("metadata requested the identity store") },
            { error("metadata requested a passphrase") },
        )

        assertNotNull(strategy.plaintextSizeOf(1024L))
    }
    @Test
    fun `encrypt writes a standard age file and the round-trip restores the plaintext`() {
        val strategy = strategy()
        val plaintext = "Hello, X-tra!".toByteArray()

        val encrypted = encryptOf(strategy, plaintext)
        assertTrue(
            String(encrypted.copyOfRange(0, AGE_VERSION_LINE.length)).startsWith(AGE_VERSION_LINE),
            "the body must be a standard age file the `age` tool can read",
        )
        assertContentEquals(plaintext, decryptOf(strategy, encrypted))
    }

    @Test
    fun `empty input round-trips and its size inverts`() {
        val strategy = strategy()

        val encrypted = encryptOf(strategy, ByteArray(0))
        // kage seals an empty payload as one empty final chunk (the AES-GCM strategy's convention): the size
        // math must answer 0 for it.
        assertEquals(0L, strategy.plaintextSizeOf(storedSizeOf(strategy, encrypted.size)))
        assertEquals(0, decryptOf(strategy, encrypted).size)
    }

    @Test
    fun `large input round-trips across the chunk boundary`() {
        val strategy = strategy()
        val plaintext = ByteArray(200_000).also { SecureRandom().nextBytes(it) }

        val encrypted = encryptOf(strategy, plaintext)
        assertContentEquals(plaintext, decryptOf(strategy, encrypted))
    }

    @Test
    fun `the size math inverts exactly and refuses foreign sizes`() {
        val strategy = strategy()
        val fixed = 10L + strategy.wrappedKeyLength + strategy.ivLength
        val seg = 65536L
        val tag = 16L
        val overhead = encryptOf(strategy, ByteArray(0)).size.toLong() - tag
        // Every boundary image: the stored size plus the age file's chunk tags must invert to the plaintext,
        // exactly, the way the listings decorator answers sizes from. An empty payload is one empty final chunk.
        for (p in listOf(0L, 1L, seg - 1, seg, seg + 1, 2 * seg, 2 * seg + 7, 500_000L)) {
            val tags = maxOf(1L, (p + seg - 1) / seg)
            val stored = fixed + overhead + p + tag * tags
            assertEquals(p, strategy.plaintextSizeOf(stored), "plaintext $p from stored $stored")
        }
        // The sizes no well-formed file can have.
        assertEquals(null, strategy.plaintextSizeOf(fixed + overhead), "a body without a tag")
        assertEquals(null, strategy.plaintextSizeOf(fixed + overhead + 5), "between the nonce and one tag")
        assertEquals(null, strategy.plaintextSizeOf(fixed + overhead + seg + tag + 5), "in the boundary gap")
    }

    @Test
    fun `a tampered ciphertext fails the decrypt as an authentication refusal`() {
        val strategy = strategy()
        val encrypted = encryptOf(strategy, "payload".toByteArray())
        // The payload's last byte (a header byte would fail earlier, in the stanza decode).
        encrypted[encrypted.size - 1] = (encrypted[encrypted.size - 1].toInt() xor 0x5A).toByte()

        // The provider maps AEADBadTagException to the engines' permanent content refusal; the translation is
        // the strategy's job, because kage reports the failure in its own exception types.
        assertFailsWith<AEADBadTagException> {
            decryptOf(strategy, encrypted)
        }
    }

    @Test
    fun `a truncated stream fails the decrypt`() {
        val strategy = strategy()
        val plaintext = ByteArray(3 * 65536).also { SecureRandom().nextBytes(it) }
        val encrypted = encryptOf(strategy, plaintext)

        val cut = encrypted.copyOfRange(0, encrypted.size - 40_000)
        assertFailsWith<Exception> {
            decryptOf(strategy, cut)
        }
    }

    @Test
    fun `another installation's identity does not decrypt the file`() {
        val encrypted = encryptOf(strategy(), "for one identity only".toByteArray())

        assertFailsWith<Exception> {
            decryptOf(strategy(), encrypted)
        }
    }

    companion object {
        private val PASSPHRASE = "testpass1234".toCharArray()
        private const val AGE_VERSION_LINE = "age-encryption.org/v1"
    }
}
