package org.krost.unidrive.xtra

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.krost.unidrive.testing.TestScratch

// The XTRA header the strategy is handed in these tests (the provider's own are in XtraEncryptedProviderTest).
private val TEST_HEADER = XtraHeader.serialize(TinkStreamingStrategy.PROTOTYPE_ALGORITHM_ID, ByteArray(256), ByteArray(0))

class TinkStreamingStrategyTest {

    private fun strategy(): TinkStreamingStrategy {
        val store = XtraTinkKeyStore(TestScratch.directory("xtra-tink-test").resolve("xtra-tink.key"))
        store.generate(PASSPHRASE)
        return TinkStreamingStrategy(lazy { store }, { PASSPHRASE.copyOf() })
    }

    private fun encryptOf(
        strategy: TinkStreamingStrategy,
        plaintext: ByteArray,
    ): ByteArray = ByteArrayOutputStream().also { out ->
        strategy.encrypt(ByteArrayInputStream(plaintext), out, ByteArray(strategy.wrappedKeyLength), ByteArray(strategy.ivLength), TEST_HEADER)
    }.toByteArray()

    private fun decryptOf(
        strategy: TinkStreamingStrategy,
        ciphertext: ByteArray,
    ): ByteArray = ByteArrayOutputStream().also { out ->
        strategy.decrypt(ByteArrayInputStream(ciphertext), out, ByteArray(strategy.wrappedKeyLength), ByteArray(strategy.ivLength), TEST_HEADER)
    }.toByteArray()

    @Test
    fun `round-trip restores the plaintext and the body is a Tink stream`() {
        val strategy = strategy()
        val plaintext = "Hello, X-tra!".toByteArray()

        val encrypted = encryptOf(strategy, plaintext)
        assertTrue(!plaintext.contentEquals(encrypted))

        assertContentEquals(plaintext, decryptOf(strategy, encrypted))
    }

    @Test
    fun `empty input round-trips and its size inverts`() {
        val strategy = strategy()

        val encrypted = encryptOf(strategy, ByteArray(0))
        // An empty payload is one empty final chunk (header + tag), the other algorithms' convention.
        assertEquals(0L, strategy.plaintextSizeOf(storedSizeOf(strategy, encrypted.size)))
        assertEquals(0, decryptOf(strategy, encrypted).size)
    }

    @Test
    fun `large input round-trips across the segment boundary`() {
        val strategy = strategy()
        val plaintext = ByteArray(200_000).also { SecureRandom().nextBytes(it) }

        val encrypted = encryptOf(strategy, plaintext)
        assertContentEquals(plaintext, decryptOf(strategy, encrypted))
    }

    // The corrected size law, verified against REAL ciphertexts at every interesting boundary: the header rides
    // inside the first segment, so the first segment carries 65 480 plaintext bytes and later ones 65 520 — the
    // inverse must answer the exact plaintext for the stored size each real encrypt produced (the listing's
    // size contract), and null for the unreachable gaps.
    @Test
    fun `the size law inverts real ciphertexts exactly at every boundary`() {
        val strategy = strategy()
        val sizes = intArrayOf(0, 1, 18, 65480, 65481, 65520, 65521, 65535, 65536, 65537, 131000, 131001, 131040, 131072, 500_000)
        for (n in sizes) {
            val encrypted = encryptOf(strategy, ByteArray(n))
            assertEquals(
                n.toLong(),
                strategy.plaintextSizeOf(storedSizeOf(strategy, encrypted.size)),
                "plaintext $n from its real stored size",
            )
        }
    }

    @Test
    fun `unreachable stored sizes are refused`() {
        val strategy = strategy()
        val fixed = HEADER_FIXED + strategy.wrappedKeyLength + strategy.ivLength
        // The gaps right after each segment jump: 65 537..65 552 map to no well-formed file (65 536 is the
        // 65 480-byte plaintext's stored body, 65 553 the 65 481-byte one); same shape after the second jump.
        for (gap in 65537L..65552L) {
            assertEquals(null, strategy.plaintextSizeOf(fixed + gap), "gap value $gap")
        }
        assertEquals(null, strategy.plaintextSizeOf(fixed + 131073L), "second jump's gap start")
        assertEquals(null, strategy.plaintextSizeOf(fixed + 131088L), "second jump's gap end")
        // The recovered boundaries themselves must invert exactly.
        assertEquals(65481L, strategy.plaintextSizeOf(fixed + 65553L), "65553 is the 65481-byte file")
        assertEquals(131001L, strategy.plaintextSizeOf(fixed + 131089L), "131089 is the 131001-byte file")
    }

    @Test
    fun `a tampered payload fails the decrypt as an authentication refusal`() {
        val strategy = strategy()
        val encrypted = encryptOf(strategy, "payload".toByteArray())
        encrypted[encrypted.size - 1] = (encrypted[encrypted.size - 1].toInt() xor 0x5A).toByte()

        assertFailsWith<AEADBadTagException> {
            decryptOf(strategy, encrypted)
        }
    }

    @Test
    fun `a tag failure is recognized by its cause rather than provider wording`() {
        val failure = IOException("provider-specific wording", AEADBadTagException("tag"))
        assertTrue(TinkStreamingStrategy.isIntegrityFailure(failure))
        assertEquals(false, TinkStreamingStrategy.isIntegrityFailure(IOException("Tag mismatch without a cause")))
    }

    // The XTRA header is the stream's associated data: a decrypt under any other header bytes fails the seal.
    @Test
    fun `a decrypt under a changed XTRA header fails as an authentication refusal`() {
        val strategy = strategy()
        val encrypted = encryptOf(strategy, "payload".toByteArray())
        val otherHeader = TEST_HEADER.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 0x01).toByte() }

        assertFailsWith<AEADBadTagException> {
            strategy.decrypt(ByteArrayInputStream(encrypted), ByteArrayOutputStream(), ByteArray(0), ByteArray(0), otherHeader)
        }
    }

    @Test
    fun `a tampered header fails the decrypt as an authentication refusal`() {
        val strategy = strategy()
        val encrypted = encryptOf(strategy, "payload".toByteArray())
        encrypted[0] = (encrypted[0].toInt() xor 0x5A).toByte()

        assertFailsWith<AEADBadTagException> {
            decryptOf(strategy, encrypted)
        }
    }

    @Test
    fun `a truncated stream fails the decrypt as an authentication refusal`() {
        val strategy = strategy()
        val plaintext = ByteArray(200_000).also { SecureRandom().nextBytes(it) }
        val encrypted = encryptOf(strategy, plaintext)

        val cut = encrypted.copyOfRange(0, encrypted.size - 40_000)
        assertFailsWith<AEADBadTagException> {
            decryptOf(strategy, cut)
        }
    }

    @Test
    fun `another installation's keyset does not decrypt the file`() {
        val encrypted = encryptOf(strategy(), "for one keyset only".toByteArray())

        assertFailsWith<AEADBadTagException> {
            decryptOf(strategy(), encrypted)
        }
    }

    @Test
    fun `the header slot names the sealing keyset and only that keyset accepts it`() {
        val writer = strategy()
        val slot = writer.wrapFileKey(ByteArray(32))
        assertEquals(writer.wrappedKeyLength, slot.size)
        writer.unwrapFileKey(slot)

        assertFailsWith<XtraKeysetMismatchException> { strategy().unwrapFileKey(slot) }
    }

    @Test
    fun `generate refuses to replace an existing keyset`() {
        val path = TestScratch.directory("xtra-tink-test").resolve("xtra-tink.key")
        val store = XtraTinkKeyStore(path)
        store.generate(PASSPHRASE)
        val before = java.nio.file.Files.readAllBytes(path)

        assertFailsWith<IllegalStateException> { store.generate(PASSPHRASE) }
        assertContentEquals(before, java.nio.file.Files.readAllBytes(path))
    }

    // Tink's registry is per process. Every test above generates its keyset in this JVM first, which registers
    // Tink as a side effect; the daemon after setup never generates. A child JVM that only loads the keyset must
    // still answer listings, decrypt what the setup process wrote, and encrypt. If this test is removed, a
    // registration that lives only in generate() regresses silently.
    @Test
    fun `a process that never generated loads, lists, decrypts and encrypts`() {
        val dir = TestScratch.directory("xtra-tink-fresh-jvm")
        val keyPath = dir.resolve("xtra-tink.key")
        val store = XtraTinkKeyStore(keyPath)
        store.generate(PASSPHRASE)
        val writer = TinkStreamingStrategy(lazy { store }, { PASSPHRASE.copyOf() })
        val plaintext = ByteArray(200_000) { (it * 31 + 7).toByte() }
        val bodyPath = dir.resolve("body.bin")
        val plainPath = dir.resolve("plain.bin")
        java.nio.file.Files.write(bodyPath, encryptOf(writer, plaintext))
        java.nio.file.Files.write(plainPath, plaintext)

        val java = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(
            java, "-cp", System.getProperty("java.class.path"),
            TinkFreshJvmProbe::class.java.name, keyPath.toString(), bodyPath.toString(), plainPath.toString(),
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        assertEquals("ok", output.trim().lines().last(), output)
    }

    // A local disk error must NOT surface as an authentication refusal: the provider maps AEADBadTagException
    // to the engines' permanent content refusal, and a staged-file read error is retryable local I/O. Tink
    // propagates foreign IOExceptions with their original message (probed) — the translation must preserve it.
    @Test
    fun `a failing source read propagates as the local error, not an authentication refusal`() {
        val strategy = strategy()
        val plaintext = ByteArray(200_000).also { SecureRandom().nextBytes(it) }
        val encrypted = encryptOf(strategy, plaintext)

        val failingSource = object : FilterInputStream(ByteArrayInputStream(encrypted)) {
            var reads = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (++reads == 3) throw IOException("disk read error on the staged ciphertext")
                return super.read(b, off, len)
            }
        }
        val failure = assertFailsWith<IOException> {
            strategy.decrypt(failingSource, ByteArrayOutputStream(), ByteArray(strategy.wrappedKeyLength), ByteArray(strategy.ivLength), TEST_HEADER)
        }
        assertEquals("disk read error on the staged ciphertext", failure.message)
    }

    @Test
    fun `a failing destination write propagates as the local error, not an authentication refusal`() {
        val strategy = strategy()
        val encrypted = encryptOf(strategy, ByteArray(200_000))

        val failingSink = object : OutputStream() {
            var written = 0L
            override fun write(b: Int) {
                written += 1
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                written += len
                if (written > 100_000) throw IOException("disk full on the staging file")
            }
        }
        val failure = assertFailsWith<IOException> {
            strategy.decrypt(ByteArrayInputStream(encrypted), failingSink, ByteArray(strategy.wrappedKeyLength), ByteArray(strategy.ivLength), TEST_HEADER)
        }
        assertEquals("disk full on the staging file", failure.message)
    }

    // The provider syncs the staging file's fd after the strategy returns, so the caller's output must still be
    // open on both directions — and the encrypt must have finalized (its ciphertext decrypts back).
    @Test
    fun `the caller's output stays open after encrypt and decrypt return`() {
        val strategy = strategy()

        val encOut = TrackingOutput()
        strategy.encrypt(ByteArrayInputStream("payload".toByteArray()), encOut, ByteArray(strategy.wrappedKeyLength), ByteArray(strategy.ivLength), TEST_HEADER)
        assertEquals(false, encOut.closed, "encrypt must not close the caller's output")
        assertContentEquals("payload".toByteArray(), decryptOf(strategy, encOut.bytes), "encrypt must have finalized")

        val decOut = TrackingOutput()
        strategy.decrypt(ByteArrayInputStream(encryptOf(strategy, "payload".toByteArray())), decOut, ByteArray(strategy.wrappedKeyLength), ByteArray(strategy.ivLength), TEST_HEADER)
        assertEquals(false, decOut.closed, "decrypt must not close the caller's output")
        assertContentEquals("payload".toByteArray(), decOut.bytes)
    }

    private fun storedSizeOf(
        strategy: TinkStreamingStrategy,
        ageFileSize: Int,
    ): Long = HEADER_FIXED + strategy.wrappedKeyLength + strategy.ivLength + ageFileSize

    companion object {
        private val PASSPHRASE = "testpass1234".toCharArray()
        private const val HEADER_FIXED = 10L
    }
}

/** Records whether it was closed, and keeps the bytes for assertions. */
private class TrackingOutput : OutputStream() {
    private val buffer = ByteArrayOutputStream()
    val bytes: ByteArray
        get() = buffer.toByteArray()
    var closed = false

    override fun write(b: Int) {
        if (closed) throw IOException("write after close")
        buffer.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (closed) throw IOException("write after close")
        buffer.write(b, off, len)
    }

    override fun close() {
        closed = true
    }
}

/**
 * The child process of the fresh-JVM test: it only loads the keyset (never generates), so Tink is registered
 * here by the classes themselves or not at all. The listing answer comes first, before any keyset is loaded.
 */
object TinkFreshJvmProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val (keyPath, bodyPath, plainPath) = args.map { java.nio.file.Path.of(it) }
        val passphrase = { "testpass1234".toCharArray() }
        val strategy = TinkStreamingStrategy(lazy { XtraTinkKeyStore(keyPath) }, passphrase)
        val body = java.nio.file.Files.readAllBytes(bodyPath)
        val plaintext = java.nio.file.Files.readAllBytes(plainPath)

        val stored = 10 + strategy.wrappedKeyLength + strategy.ivLength + body.size
        check(strategy.plaintextSizeOf(stored.toLong()) == plaintext.size.toLong()) { "listing size" }

        val decrypted = ByteArrayOutputStream()
        strategy.decrypt(ByteArrayInputStream(body), decrypted, ByteArray(0), ByteArray(0), TEST_HEADER)
        check(decrypted.toByteArray().contentEquals(plaintext)) { "decrypt" }

        val sealed = ByteArrayOutputStream()
        strategy.encrypt(ByteArrayInputStream(plaintext), sealed, ByteArray(0), ByteArray(0), TEST_HEADER)
        val again = ByteArrayOutputStream()
        strategy.decrypt(ByteArrayInputStream(sealed.toByteArray()), again, ByteArray(0), ByteArray(0), TEST_HEADER)
        check(again.toByteArray().contentEquals(plaintext)) { "encrypt round-trip" }
        println("ok")
    }
}
