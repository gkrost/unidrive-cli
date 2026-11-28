package org.krost.unidrive.xtra

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import org.krost.unidrive.testing.TestScratch

// All X-tra key files wrap under one shared passphrase, so rotating it must be possible for the Tink keyset too:
// without a re-wrap, a rotation leaves the keyset (the only key of every Tink file) under the old passphrase.
class XtraTinkKeyStoreTest {

    private val header = XtraHeader.serialize(3, ByteArray(256), ByteArray(0))

    private fun store(name: String) = XtraTinkKeyStore(TestScratch.directory("xtra-tink-store-$name").resolve("xtra-tink.key"))

    private fun strategy(
        store: XtraTinkKeyStore,
        passphrase: CharArray,
    ) = TinkStreamingStrategy(lazy { store }, { passphrase.copyOf() })

    private fun seal(
        strategy: TinkStreamingStrategy,
        plaintext: ByteArray,
    ): ByteArray = ByteArrayOutputStream().also { out ->
        strategy.encrypt(ByteArrayInputStream(plaintext), out, ByteArray(strategy.wrappedKeyLength), ByteArray(strategy.ivLength), header)
    }.toByteArray()

    private fun open(
        strategy: TinkStreamingStrategy,
        ciphertext: ByteArray,
    ): ByteArray = ByteArrayOutputStream().also { out ->
        strategy.decrypt(ByteArrayInputStream(ciphertext), out, ByteArray(strategy.wrappedKeyLength), ByteArray(strategy.ivLength), header)
    }.toByteArray()

    @Test
    fun `a second generate fails and leaves the first key file byte-identical`() {
        val store = store("generate-twice")
        store.generate("correct horse battery".toCharArray())
        val before = Files.readAllBytes(store.path)

        assertFailsWith<IllegalStateException> { store.generate("correct horse battery".toCharArray()) }

        assertContentEquals(before, Files.readAllBytes(store.path))
    }

    @Test
    fun `a passphrase change re-wraps the same keyset - the old passphrase is refused, files sealed before still open`() {
        val store = store("change")
        store.generate(PASSPHRASE)
        val plaintext = "sealed before the rotation".toByteArray()
        val sealed = seal(strategy(store, PASSPHRASE), plaintext)

        store.changePassphrase(PASSPHRASE, OTHER_PASSPHRASE)

        assertFailsWith<AEADBadTagException> { store.load(PASSPHRASE) }
        assertContentEquals(plaintext, open(strategy(store, OTHER_PASSPHRASE), sealed))
    }

    @Test
    fun `a passphrase change with the wrong current passphrase leaves the key file as it was`() {
        val store = store("wrong-current")
        store.generate(PASSPHRASE)
        val before = Files.readAllBytes(store.path)

        assertFailsWith<AEADBadTagException> { store.changePassphrase(OTHER_PASSPHRASE, OTHER_PASSPHRASE) }

        assertContentEquals(before, Files.readAllBytes(store.path))
    }

    @Test
    fun `a passphrase change refuses a short new passphrase and leaves the key file as it was`() {
        val store = store("short")
        store.generate(PASSPHRASE)
        val before = Files.readAllBytes(store.path)

        assertFailsWith<IllegalArgumentException> { store.changePassphrase(PASSPHRASE, "short".toCharArray()) }

        assertContentEquals(before, Files.readAllBytes(store.path))
    }

    private companion object {
        val PASSPHRASE = "tink-store-pass-1".toCharArray()
        val OTHER_PASSPHRASE = "tink-store-pass-2".toCharArray()
    }
}
