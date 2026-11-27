package org.krost.unidrive.xtra

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.sync.FakeCloudProvider
import org.krost.unidrive.testing.TestScratch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `aes-gcm-sym` (algorithm 4): algorithm 1's segment format, with the per-file key wrapped under the installation's
 * symmetric master key instead of RSA. These pin the properties the owner's decision on the symmetric default
 * names: a round trip, a wrong passphrase refused, a changed header refused, truncation at a segment boundary
 * refused, a key file that exports, imports and changes its passphrase, and a mismatch handled as for every other
 * algorithm.
 */
class AesGcmSymmetricStrategyTest {
    private val pass = "symmetric-pass-1234"
    private val dir: Path = TestScratch.directory("xtra-sym")

    private fun store(name: String = "xtra-sym.key") =
        XtraSymmetricKeyStore(dir.resolve(name)).also { it.generate(pass.toCharArray()) }

    private fun strategy(store: XtraSymmetricKeyStore) = AesGcmStrategy.symmetric(lazy { store })

    private fun chain(
        inner: FakeCloudProvider,
        strategy: XtraEncryptionStrategy,
    ) = XtraEncryptedProvider(XtraPlaintextListings(inner, strategy), strategy)

    /** The stored bytes for [plaintext] exactly as [XtraEncryptedProvider.upload] writes them. */
    private fun seal(
        strategy: XtraEncryptionStrategy,
        plaintext: ByteArray,
    ): ByteArray {
        val fileKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(strategy.ivLength).also { SecureRandom().nextBytes(it) }
        val header = XtraHeader.serialize(strategy.algorithmId, strategy.wrapFileKey(fileKey), iv)
        return header + ByteArrayOutputStream().also { strategy.encrypt(ByteArrayInputStream(plaintext), it, fileKey, iv, header) }.toByteArray()
    }

    /** The inverse of [seal], as the provider's download does it. */
    private fun open(
        strategy: XtraEncryptionStrategy,
        stored: ByteArray,
    ): ByteArray {
        val input = ByteArrayInputStream(stored)
        val header = assertIs<XtraHeader.ReadResult.Valid>(XtraHeader.read(input))
        assertEquals(strategy.algorithmId, header.algorithmId)
        val fileKey = strategy.unwrapFileKey(header.wrappedKey)
        return ByteArrayOutputStream().also { strategy.decrypt(input, it, fileKey, header.iv, header.bytes) }.toByteArray()
    }

    @Test
    fun `a file round-trips through the provider and its header names algorithm 4 with a 60-byte slot`() {
        val inner = FakeCloudProvider()
        val xtra = chain(inner, strategy(store()))
        val plaintext = ByteArray(3 * 65536 + 17).also { SecureRandom().nextBytes(it) }
        val local = dir.resolve("plain.bin").also { Files.write(it, plaintext) }

        runBlocking { xtra.upload(local, "/plain.bin") }
        val stored = inner.files.getValue("/plain.bin")
        val header = assertIs<XtraHeader.ReadResult.Valid>(XtraHeader.read(ByteArrayInputStream(stored)))
        assertEquals(4, header.algorithmId.toInt())
        assertEquals(60, header.wrappedKey.size)
        assertEquals(plaintext.size.toLong(), strategy(store("other.key")).plaintextSizeOf(stored.size.toLong()), "the listing size inverts")

        val back = dir.resolve("back.bin")
        runBlocking { xtra.download("/plain.bin", back) }
        assertContentEquals(plaintext, Files.readAllBytes(back))
    }

    @Test
    fun `a process that never generated loads the key file and opens the file`() {
        val sealed = seal(strategy(store()), "written by the first process".toByteArray())
        val later = XtraSymmetricKeyStore(dir.resolve("xtra-sym.key"))
        assertFalse(later.isLoaded)
        val opened = open(AesGcmStrategy.symmetric(lazy { later.also { it.load(pass.toCharArray()) } }), sealed)
        assertEquals("written by the first process", String(opened))
    }

    // A wrong passphrase is this installation's error, never the file's: it fails the key load, before any unwrap,
    // so it is not answered as a refused (quarantined) file key.
    @Test
    fun `a wrong passphrase is refused by the key file and never reads as a refused file key`() {
        val sealed = seal(strategy(store()), "secret".toByteArray())
        val wrong = XtraSymmetricKeyStore(dir.resolve("xtra-sym.key"))
        assertFailsWith<AEADBadTagException> { wrong.load("not-the-passphrase".toCharArray()) }
        assertFalse(wrong.opens("not-the-passphrase".toCharArray()))
        assertTrue(wrong.opens(pass.toCharArray()))

        val strategy = AesGcmStrategy.symmetric(lazy { wrong.also { it.load("not-the-passphrase".toCharArray()) } })
        val header = assertIs<XtraHeader.ReadResult.Valid>(XtraHeader.read(ByteArrayInputStream(sealed)))
        // The load's own AEADBadTagException comes through as is (XtraFileKeyRefusedException is not one).
        assertFailsWith<AEADBadTagException> { strategy.unwrapFileKey(header.wrappedKey) }
    }

    @Test
    fun `another installation's master key refuses the file key as content`() {
        val sealed = seal(strategy(store()), "secret".toByteArray())
        val other = strategy(store("elsewhere.key"))
        assertFailsWith<XtraFileKeyRefusedException> { open(other, sealed) }
    }

    // Every header byte is the seal's associated data, and the slot is additionally the wrap's ciphertext: a change
    // anywhere fails, the slot at the unwrap, every other byte at the first segment's tag.
    @Test
    fun `a changed header byte is refused wherever it is`() {
        val strategy = strategy(store())
        val sealed = seal(strategy, "header-bound".toByteArray())
        val headerLength = 4 + 1 + 1 + 2 + 60 + 2 + 12
        val slot = 8 until 8 + 60
        for (offset in listOf(4 + 1 + 1 + 2, 8 + 30, 8 + 59, 8 + 60 + 2, headerLength - 6, headerLength - 1)) {
            val changed = sealed.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            val failure = assertFailsWith<Exception>("header byte $offset") { open(strategy, changed) }
            if (offset in slot) {
                assertIs<XtraFileKeyRefusedException>(failure, "slot byte $offset")
            } else {
                assertIs<AEADBadTagException>(failure, "header byte $offset")
            }
        }
        // The decrypt under the stored header still opens the untouched file.
        assertEquals("header-bound", String(open(strategy, sealed)))
    }

    @Test
    fun `a stream truncated at a segment boundary is refused`() {
        val strategy = strategy(store())
        val sealed = seal(strategy, ByteArray(3 * 65536).also { SecureRandom().nextBytes(it) })
        val headerLength = 4 + 1 + 1 + 2 + 60 + 2 + 12
        assertEquals(headerLength + 3 * (65536 + 16), sealed.size)
        for (segments in 1..2) {
            val cut = sealed.copyOfRange(0, headerLength + segments * (65536 + 16))
            assertFailsWith<AEADBadTagException>("cut after $segments segments") { open(strategy, cut) }
        }
        assertFailsWith<AEADBadTagException>("the header alone") { open(strategy, sealed.copyOfRange(0, headerLength)) }
    }

    @Test
    fun `a passphrase change keeps the master key - the old passphrase is refused, files sealed before still open`() {
        val first = store()
        val sealed = seal(strategy(first), "before the change".toByteArray())
        first.changePassphrase("the-new-passphrase".toCharArray())

        val reloaded = XtraSymmetricKeyStore(dir.resolve("xtra-sym.key"))
        assertFalse(reloaded.opens(pass.toCharArray()))
        reloaded.load("the-new-passphrase".toCharArray())
        assertEquals("before the change", String(open(AesGcmStrategy.symmetric(lazy { reloaded }), sealed)))
        assertFailsWith<IllegalArgumentException> { reloaded.changePassphrase("short".toCharArray()) }
        assertTrue(reloaded.opens("the-new-passphrase".toCharArray()), "a refused change leaves the key file as it was")
    }

    @Test
    fun `export and import carry the master key to a second machine`() {
        val first = store()
        val sealed = seal(strategy(first), "travels".toByteArray())

        val secondDir = TestScratch.directory("xtra-sym-second")
        val second = XtraSymmetricKeyStore(secondDir.resolve("xtra-sym.key"))
        second.importKey(first.exportKey())
        second.load(pass.toCharArray())
        assertEquals("travels", String(open(AesGcmStrategy.symmetric(lazy { second }), sealed)))
    }

    @Test
    fun `import refuses what is not a key file, and never replaces a key without force`() {
        val existing = store().exportKey()
        val target = XtraSymmetricKeyStore(dir.resolve("xtra-sym.key"))
        assertFailsWith<IllegalArgumentException> { target.importKey(ByteArray(86)) }
        assertFailsWith<IllegalArgumentException> { target.importKey(Files.readAllBytes(dir.resolve("xtra-sym.key")).copyOf(85)) }

        val other = store("other.key")
        assertFailsWith<IllegalStateException> { target.importKey(other.exportKey()) }
        assertContentEquals(existing, target.exportKey(), "the refused import left the key file alone")

        target.importKey(other.exportKey(), force = true)
        assertContentEquals(other.exportKey(), target.exportKey())
        val retired = Files.list(dir).use { s -> s.filter { it.fileName.toString().startsWith("xtra-sym.key.bak-") }.toList() }
        assertEquals(1, retired.size, "a forced import keeps the key it replaced")
        assertContentEquals(existing, Files.readAllBytes(retired.single()))
    }

    @Test
    fun `generate never replaces a key file and refuses a short passphrase`() {
        store()
        assertFailsWith<IllegalStateException> { XtraSymmetricKeyStore(dir.resolve("xtra-sym.key")).generate(pass.toCharArray()) }
        assertFailsWith<IllegalArgumentException> { XtraSymmetricKeyStore(dir.resolve("short.key")).generate("short".toCharArray()) }
    }

    // The KDF parameters are the key file's associated data: a lowered iteration count fails like a wrong passphrase,
    // and a count beyond the bound is refused before any derivation runs.
    @Test
    fun `a changed iteration count in the key file is refused`() {
        store()
        val path = dir.resolve("xtra-sym.key")
        val original = Files.readAllBytes(path)
        assertEquals(86, original.size)
        assertEquals("XSYM", String(original.copyOfRange(0, 4)))

        Files.write(path, original.copyOf().also { java.nio.ByteBuffer.wrap(it).putInt(6, 600_001) })
        assertFailsWith<AEADBadTagException> { XtraSymmetricKeyStore(path).load(pass.toCharArray()) }
        Files.write(path, original.copyOf().also { java.nio.ByteBuffer.wrap(it).putInt(6, 1_000) })
        assertFailsWith<IllegalStateException> { XtraSymmetricKeyStore(path).load(pass.toCharArray()) }
        Files.write(path, original.copyOf().also { java.nio.ByteBuffer.wrap(it).putInt(6, Int.MAX_VALUE) })
        assertFailsWith<IllegalStateException> { XtraSymmetricKeyStore(path).load(pass.toCharArray()) }
    }

    // As for every algorithm: a file another algorithm wrote is refused by its header byte before any key demand,
    // with the retryable mismatch, never as damaged content; and the symmetric file is refused the same way by aes-gcm.
    @Test
    fun `a file of the other aes-gcm algorithm is a mismatch in both directions, and no key is demanded`() {
        val inner = FakeCloudProvider()
        inner.files["/rsa.xtra"] = fixture("aes-gcm.xtra")
        inner.files["/sym.xtra"] = seal(strategy(store()), "symmetric".toByteArray())
        val symOnly = AesGcmStrategy.symmetric(lazy { error("the symmetric key was demanded for a file of another algorithm") })
        val rsaOnly = AesGcmStrategy(lazy { error("the RSA key was demanded for a file of another algorithm") })

        val toSym = assertFailsWith<XtraAlgorithmMismatchException> { runBlocking { chain(inner, symOnly).download("/rsa.xtra", dir.resolve("a")) } }
        assertEquals(1, toSym.storedAlgorithmId.toInt())
        val toRsa = assertFailsWith<XtraAlgorithmMismatchException> { runBlocking { chain(inner, rsaOnly).download("/sym.xtra", dir.resolve("b")) } }
        assertEquals(4, toRsa.storedAlgorithmId.toInt())
    }

    // Files and key files written before the symmetric default (frozen in src/test/resources/xtra-legacy-fixtures by
    // the unchanged code of main, passphrase below) still decrypt with their own algorithm: nothing about aes-gcm,
    // age-v1 or tink-aes-gcm changed.
    @Test
    fun `files written by the earlier algorithms still decrypt unchanged`() {
        val fixturePass = "fixture-pass-1234"
        val plaintext = fixture("plaintext.txt")
        val legacy = TestScratch.directory("xtra-legacy")
        for (name in listOf("xtra.key", "xtra-age.key", "xtra-tink.key")) Files.write(legacy.resolve(name), fixture(name))

        val rsa = XtraKeyManager(legacy.resolve("xtra.key")).also { it.load(fixturePass.toCharArray()) }
        assertContentEquals(plaintext, open(AesGcmStrategy(lazy { rsa }), fixture("aes-gcm.xtra")))
        val age = AgeV1Strategy(lazy { XtraAgeKeyStore(legacy.resolve("xtra-age.key")) }, { fixturePass.toCharArray() })
        assertContentEquals(plaintext, open(age, fixture("age-v1.xtra")))
        val tink = TinkStreamingStrategy(lazy { XtraTinkKeyStore(legacy.resolve("xtra-tink.key")) }, { fixturePass.toCharArray() })
        assertContentEquals(plaintext, open(tink, fixture("tink-aes-gcm.xtra")))

        // The RSA algorithm still writes what it always wrote: algorithm 1, a 256-byte slot, the same segment format.
        val fresh = open(AesGcmStrategy(lazy { rsa }), seal(AesGcmStrategy(lazy { rsa }), plaintext))
        assertContentEquals(plaintext, fresh)
        assertEquals(1, AesGcmStrategy(lazy { rsa }).algorithmId.toInt())
        assertEquals(256, AesGcmStrategy(lazy { rsa }).wrappedKeyLength)
    }

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/xtra-legacy-fixtures/$name")) { "missing fixture $name" }.use { it.readBytes() }
}
