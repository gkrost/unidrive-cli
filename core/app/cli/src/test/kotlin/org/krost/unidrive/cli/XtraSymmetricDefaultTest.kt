package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.io.XTRA_PASSPHRASE_ENV
import org.krost.unidrive.sync.FakeCloudProvider
import org.krost.unidrive.sync.RawProvider
import org.krost.unidrive.testing.TestScratch
import org.krost.unidrive.xtra.XtraAgeKeyStore
import org.krost.unidrive.xtra.XtraHeader
import org.krost.unidrive.xtra.XtraKeyManager
import org.krost.unidrive.xtra.XtraSymmetricKeyStore
import picocli.CommandLine
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.Provider
import java.security.Security
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The symmetric default (owner decision on the X-tra key chain): a new installation's key is the aes-gcm-sym master
 * key, a profile without `xtra_algorithm` runs on it, and no RSA or X25519 operation runs for such a profile. An
 * installation that already holds the RSA key file keeps aes-gcm for its unset profiles.
 */
class XtraSymmetricDefaultTest {
    private val pass = "default-pass-1234"
    private val dir: Path = TestScratch.directory("xtra-sym-default")
    private val out = ByteArrayOutputStream()
    private val originalOut = System.out

    @AfterTest
    fun tearDown() {
        System.setOut(originalOut)
        Security.removeProvider(PublicKeyLookupSpy.NAME)
        dir.toFile().deleteRecursively()
    }

    private fun main() =
        Main().also { m ->
            m.passphraseSourcesFactory = {
                PassphraseSources(env = { if (it == XTRA_PASSPHRASE_ENV) pass else null }, consolePresent = { false })
            }
        }

    private fun wired(
        rp: RawProvider,
        inner: CloudProvider,
    ): CloudProvider = main().wrapWithXtra(rp, inner, dir.resolve("token.json"))

    private fun roundTrip(
        rp: RawProvider,
        inner: FakeCloudProvider = FakeCloudProvider(),
    ): Byte {
        val xtra = wired(rp, inner)
        val plaintext = ByteArray(70_000) { (it % 251).toByte() }
        val local = dir.resolve("up.bin").also { Files.write(it, plaintext) }
        runBlocking { xtra.upload(local, "/f.bin") }
        val back = dir.resolve("down.bin")
        runBlocking { xtra.download("/f.bin", back) }
        assertContentEquals(plaintext, Files.readAllBytes(back))
        return assertIs<XtraHeader.ReadResult.Valid>(XtraHeader.read(ByteArrayInputStream(inner.files.getValue("/f.bin")))).algorithmId
    }

    private fun profile(algorithm: String? = null) =
        RawProvider(type = "localfs", mode = "mount", xtra_encryption = true, xtra_algorithm = algorithm)

    private fun runCli(vararg args: String): Int {
        out.reset()
        System.setOut(PrintStream(out, true, Charsets.UTF_8))
        val code = CommandLine(main()).execute("-c", dir.toString(), *args)
        System.out.flush()
        System.setOut(originalOut)
        return code
    }

    @Test
    fun `xtra init creates the symmetric key and names the algorithm it chose`() {
        assertEquals(0, runCli("xtra", "init"))
        val text = out.toString(Charsets.UTF_8)
        assertTrue(text.contains("aes-gcm-sym") && text.contains("quantum-resistant"), text)
        assertTrue(Files.exists(dir.resolve("xtra-sym.key")))
        assertFalse(Files.exists(dir.resolve("xtra.key")), "no RSA pair is created")
        assertTrue(XtraSymmetricKeyStore(dir.resolve("xtra-sym.key")).opens(pass.toCharArray()), "under the chain's passphrase")
        assertEquals(XtraAlgorithm.AES_GCM_SYM, XtraAlgorithm.implicitDefault(dir))

        val again = dir.resolve("xtra-sym.key").let { Files.readAllBytes(it) }
        assertEquals(0, runCli("xtra", "init"))
        assertContentEquals(again, Files.readAllBytes(dir.resolve("xtra-sym.key")), "a second init never replaces the key")
    }

    @Test
    fun `xtra init refuses an algorithm that has its own verb`() {
        runCli("xtra", "init", "--algorithm", "age-v1")
        assertFalse(Files.exists(dir.resolve("xtra-sym.key")))
        assertFalse(Files.exists(dir.resolve("xtra-age.key")))
    }

    @Test
    fun `export-key and import-key carry the symmetric key to a second installation`() {
        assertEquals(0, runCli("xtra", "init"))
        val backup = TestScratch.directory("xtra-sym-backup")
        assertEquals(0, runCli("xtra", "export-key", "--output", backup.toString()))
        assertTrue(out.toString(Charsets.UTF_8).contains("aes-gcm-sym"))
        assertContentEquals(Files.readAllBytes(dir.resolve("xtra-sym.key")), Files.readAllBytes(backup.resolve("xtra-sym.key")))

        val second = TestScratch.directory("xtra-sym-second-install")
        val code = CommandLine(main()).execute("-c", second.toString(), "xtra", "import-key", "--input", backup.toString())
        assertEquals(0, code)
        assertContentEquals(Files.readAllBytes(dir.resolve("xtra-sym.key")), Files.readAllBytes(second.resolve("xtra-sym.key")))
    }

    @Test
    fun `import-key refuses a key file the passphrase does not open and leaves the local key alone`() {
        val foreign = TestScratch.directory("xtra-sym-foreign")
        XtraSymmetricKeyStore(foreign.resolve("xtra-sym.key")).generate("another-passphrase".toCharArray())
        assertEquals(0, runCli("xtra", "import-key", "--input", foreign.toString()))
        assertFalse(Files.exists(dir.resolve("xtra-sym.key")))
    }

    @Test
    fun `a profile without xtra_algorithm on a new installation runs aes-gcm-sym and no RSA or X25519 lookup happens`() {
        XtraSymmetricKeyStore(dir.resolve("xtra-sym.key")).generate(pass.toCharArray())
        val spy = PublicKeyLookupSpy.install()

        assertEquals(4, roundTrip(profile()).toInt(), "the header names aes-gcm-sym")
        assertEquals(4, roundTrip(profile("aes-gcm-sym")).toInt())

        assertEquals(emptyList(), spy.lookups.toList(), "no RSA, OAEP, X25519 or XDH algorithm was looked up")
    }

    // The positive control: the spy sees the RSA chain, so its silence above is evidence and not blindness.
    @Test
    fun `the lookup spy sees the RSA wrap of an aes-gcm profile`() {
        XtraKeyManager(dir.resolve("xtra.key")).generate(pass.toCharArray())
        val spy = PublicKeyLookupSpy.install()

        assertEquals(1, roundTrip(profile()).toInt(), "an installation with xtra.key keeps aes-gcm for an unset profile")
        assertTrue(spy.lookups.any { it.contains("RSA", ignoreCase = true) }, "lookups: ${spy.lookups}")
    }

    // age-v1's X25519 runs inside its own strategy and key store, which the symmetric wiring never builds: a profile
    // on aes-gcm-sym succeeds with an age identity file beside it that no passphrase opens.
    @Test
    fun `an aes-gcm-sym profile never touches the age identity or the RSA key file beside it`() {
        XtraSymmetricKeyStore(dir.resolve("xtra-sym.key")).generate(pass.toCharArray())
        XtraAgeKeyStore(dir.resolve("xtra-age.key")).generate("not-the-chain-passphrase".toCharArray())
        Files.write(dir.resolve("xtra.key"), ByteArray(64))
        val spy = PublicKeyLookupSpy.install()

        assertEquals(4, roundTrip(profile("aes-gcm-sym")).toInt())
        assertEquals(emptyList(), spy.lookups.toList())
    }

    private fun profileJson(vararg args: String): Pair<Int, kotlinx.serialization.json.JsonObject> {
        val code = runCli("profile", *args, "--json")
        return code to kotlinx.serialization.json.Json.parseToJsonElement(out.toString(Charsets.UTF_8).trim()).jsonObject
    }

    private fun listedAlgorithm(): String =
        profileJson("list").second.getValue("profiles").jsonArray
            .first { it.jsonObject.getValue("name").jsonPrimitive.content == "work" }
            .jsonObject.getValue("xtra_algorithm").jsonPrimitive.content

    // `profile list` and `profile set` answer the algorithm the daemon will run, so a front-end shows what the
    // wiring does: aes-gcm-sym on a new installation, aes-gcm where the RSA key file exists.
    @Test
    fun `profile list and set answer the installation's default, and xtra_encryption on it needs xtra-sym key`() {
        val config = dir.resolve("config.toml")
        Files.writeString(config, "[providers.work]\ntype = \"localfs\"\nmode = \"mount\"\nroot_path = \"/tmp/work\"\n")
        assertEquals("aes-gcm-sym", listedAlgorithm())

        val (refused, reply) = profileJson("set", "work", "xtra_encryption", "true")
        assertEquals(1, refused)
        assertEquals("key_missing", reply.getValue("error").jsonPrimitive.content)
        assertTrue(reply.getValue("message").jsonPrimitive.content.let { it.contains("xtra-sym.key") && it.contains("unidrive xtra init") })

        XtraSymmetricKeyStore(dir.resolve("xtra-sym.key")).generate(pass.toCharArray())
        assertEquals(0, profileJson("set", "work", "xtra_encryption", "true").first)
        val (named, set) = profileJson("set", "work", "xtra_algorithm", " AES-GCM-SYM ")
        assertEquals(0, named)
        assertEquals("aes-gcm-sym", set.getValue("previous").jsonPrimitive.content)
        assertTrue(Files.readString(config).contains("xtra_algorithm = \"aes-gcm-sym\""))
        assertEquals(0, profileJson("set", "work", "xtra_algorithm", "--unset").first)

        Files.write(dir.resolve("xtra.key"), ByteArray(1))
        assertEquals("aes-gcm", listedAlgorithm(), "an installation with the RSA key file keeps aes-gcm")
    }

    @Test
    fun `the implicit default is aes-gcm exactly where the RSA key file exists`() {
        assertEquals(XtraAlgorithm.AES_GCM_SYM, XtraAlgorithm.implicitDefault(dir))
        assertEquals("aes-gcm-sym", ProfileSettings.effectiveXtraAlgorithm(profile(), dir))
        Files.write(dir.resolve("xtra.key"), ByteArray(1))
        assertEquals(XtraAlgorithm.AES_GCM, XtraAlgorithm.implicitDefault(dir))
        assertEquals("aes-gcm", ProfileSettings.effectiveXtraAlgorithm(profile(), dir))
        assertEquals("aes-gcm-sym", ProfileSettings.effectiveXtraAlgorithm(profile(" AES-GCM-SYM "), dir), "a named algorithm wins")
    }
}

/**
 * A JCA provider at the head of the provider list that answers no service and records every lookup of a public-key
 * algorithm: the JDK asks each provider in order, so any `Cipher`, `KeyFactory`, `KeyPairGenerator`, `KeyAgreement` or
 * `Signature` instance for RSA, OAEP, X25519 or XDH passes through [getService] first.
 */
private class PublicKeyLookupSpy : Provider(NAME, "1", "records public-key algorithm lookups") {
    val lookups: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun getService(
        type: String,
        algorithm: String,
    ): Service? {
        val name = algorithm.uppercase()
        if (PUBLIC_KEY_MARKERS.any { name.contains(it) }) lookups += "$type.$algorithm"
        return null
    }

    companion object {
        const val NAME = "UnidrivePublicKeyLookupSpy"
        private val PUBLIC_KEY_MARKERS = listOf("RSA", "OAEP", "X25519", "XDH", "X448", "ED25519")

        fun install(): PublicKeyLookupSpy =
            PublicKeyLookupSpy().also {
                Security.removeProvider(NAME)
                Security.insertProviderAt(it, 1)
            }
    }
}
