package org.krost.unidrive.sync

import org.junit.Assume.assumeTrue
import org.krost.unidrive.auth.CredentialData
import org.krost.unidrive.auth.CredentialProtectionException
import org.krost.unidrive.auth.CredentialProtector
import org.krost.unidrive.auth.WindowsDpapiCredentialProtector
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The at-rest codec for config.toml's provider secret fields (issue#786): decode tolerates legacy
 * plaintext and reports an undecodable protected value naming profile and field; protectText rewrites
 * only what it must, byte for byte, and migrateText is idempotent with a round-trip backstop.
 * A fake protector keeps these portable; the real DPAPI round-trip is gated to Windows at the bottom.
 */
class ConfigSecretsTest {
    private lateinit var dir: Path
    private lateinit var configPath: Path

    /** In-memory stand-in for the native protectors: references are bound to the identity (the config path). */
    private class FakeProtector : CredentialProtector {
        private val items = mutableMapOf<String, MutableMap<String, String>>()
        private val undecodable = mutableSetOf<String>()
        private var counter = 0

        override fun protect(id: String, plaintext: String): String {
            val reference = "fakeref-${++counter}"
            items.getOrPut(id) { mutableMapOf() }[reference] = plaintext
            return reference
        }

        override fun decode(id: String, stored: String): CredentialData {
            if (!stored.startsWith("fakeref-")) return CredentialData(stored, false)
            if (stored in undecodable) throw CredentialProtectionException("test: reference is undecodable")
            return CredentialData(
                items[id]?.get(stored) ?: throw CredentialProtectionException("test: reference belongs to another config"),
                true,
            )
        }

        override fun delete(id: String) {
            items.remove(id)
        }

        fun makeUndecodable(reference: String) {
            undecodable += reference
        }
    }

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("unidrive-config-secrets-")
        configPath = dir.resolve("config.toml")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private val plaintextConfig: String =
        """
        [general]
        default_profile = "bench"

        [providers.bench]
        type = "s3"
        mode = "mount"
        bucket = "unidrive-cli-bench"
        # a comment between the fields must survive
        access_key_id = "AKIDEXAMPLE123456"
        secret_access_key = "round-trip-secret"
        label = "Bench"

        [providers.web]
        type = "webdav"
        mode = "mirror"
        url = "https://example.com/dav"
        password = ""
        """.trimIndent() + "\n"

    // ── decode ───────────────────────────────────────────────────────────────

    private fun rawWith(secret: String) =
        RawProvider(type = "s3", access_key_id = "plain-key-id", secret_access_key = secret)

    private fun assertForeignFailsClosed(reference: String, protector: CredentialProtector?) {
        val e =
            assertFailsWith<CredentialProtectionException> {
                ConfigSecrets.decode(rawWith(reference), "bench", configPath, protector)
            }
        assertContains(e.message!!, "bench")
        assertContains(e.message!!, "secret_access_key")
        assertContains(e.message!!, "another computer")
        assertContains(e.message!!, "re-enter")
        assertFalse(e.message!!.contains(reference), "the error must never contain the value")
        assertFalse(e.message!!.contains("AAAA"), "the error must never contain the value")
    }

    @Test
    fun `a DPAPI reference on a host without a protector fails closed`() =
        assertForeignFailsClosed("unidrive-dpapi-v1:AAAAsecretblob", null)

    @Test
    fun `a Keychain reference on a host without a protector fails closed`() =
        assertForeignFailsClosed("unidrive-keychain-v1:AAAArev", null)

    @Test
    fun `a Secret Service reference on a host without a protector fails closed`() =
        assertForeignFailsClosed("unidrive-secret-service-v1:AAAArev", null)

    @Test
    fun `another platform's reference on a host with a protector that does not recognize it fails closed`() =
        assertForeignFailsClosed("unidrive-dpapi-v1:AAAAsecretblob", FakeProtector())

    @Test
    fun `plain values pass through decode on a host without a protector`() {
        val rp = rawWith("plain-secret")
        assertEquals(rp, ConfigSecrets.decode(rp, "bench", configPath, null))
    }

    @Test
    fun `legacy plaintext values pass through decode unchanged`() {
        val rp = SyncConfig.parseRaw(plaintextConfig).providers.getValue("bench")!!
        val decoded = ConfigSecrets.decode(rp, "bench", configPath, FakeProtector())!!
        assertEquals("AKIDEXAMPLE123456", decoded.access_key_id)
        assertEquals("round-trip-secret", decoded.secret_access_key)
    }

    @Test
    fun `protected values decode to their plaintext`() {
        val protector = FakeProtector()
        val reference = protector.protect(ConfigSecrets.identity(configPath), "round-trip-secret")
        val rp = SyncConfig.parseRaw(plaintextConfig).providers.getValue("bench")!!.copy(secret_access_key = reference)

        val decoded = ConfigSecrets.decode(rp, "bench", configPath, protector)!!

        assertEquals(reference, rp.secret_access_key)
        assertEquals("round-trip-secret", decoded.secret_access_key)
        assertEquals("AKIDEXAMPLE123456", decoded.access_key_id)
    }

    @Test
    fun `an undecodable protected value throws naming profile and field, never as a missing credential`() {
        val protector = FakeProtector()
        val reference = protector.protect(ConfigSecrets.identity(configPath), "round-trip-secret")
        protector.makeUndecodable(reference)
        val rp = SyncConfig.parseRaw(plaintextConfig).providers.getValue("bench")!!.copy(secret_access_key = reference)

        val e = assertFailsWith<CredentialProtectionException> { ConfigSecrets.decode(rp, "bench", configPath, protector) }
        assertContains(e.message!!, "secret_access_key")
        assertContains(e.message!!, "bench")
        assertFalse(e.message!!.contains("round-trip-secret"), "the error must not quote the plaintext")
    }

    @Test
    fun `a reference of another config does not decode`() {
        val otherConfig = dir.resolve("other").also { Files.createDirectories(it) }.resolve("config.toml")
        val protector = FakeProtector()
        val reference = protector.protect(ConfigSecrets.identity(otherConfig), "someone-elses-secret")
        val rp = SyncConfig.parseRaw(plaintextConfig).providers.getValue("bench")!!.copy(secret_access_key = reference)

        assertFailsWith<CredentialProtectionException> { ConfigSecrets.decode(rp, "bench", configPath, protector) }
    }

    @Test
    fun `decode without a protector is the identity, and absent fields stay absent`() {
        val rp = SyncConfig.parseRaw(plaintextConfig).providers.getValue("web")!!
        assertEquals("", ConfigSecrets.decode(rp, "web", configPath, null)?.password, "an empty value passes through as itself")
        assertNull(ConfigSecrets.decode(rp, "web", configPath, null)?.secret_access_key, "an absent field stays absent")
    }

    // ── protectText ──────────────────────────────────────────────────────────

    @Test
    fun `protectText rewrites only the plaintext sensitive fields and preserves everything else`() {
        val protector = FakeProtector()
        val protected = ConfigSecrets.protectText(plaintextConfig, configPath, protector)

        // The sensitive values are gone from the text...
        assertFalse("round-trip-secret" in protected)
        assertFalse("AKIDEXAMPLE123456" in protected)
        // ...and decode back to them, line by line preserved elsewhere.
        val parsed = SyncConfig.parseRaw(protected)
        assertEquals("round-trip-secret", ConfigSecrets.decode(parsed.providers.getValue("bench")!!, "bench", configPath, protector)!!.secret_access_key)
        assertEquals("s3", parsed.providers.getValue("bench")!!.type)
        assertEquals("unidrive-cli-bench", parsed.providers.getValue("bench")!!.bucket)
        assertEquals("Bench", parsed.providers.getValue("bench")!!.label)
        assertContains(protected.lines(), "# a comment between the fields must survive")
        // The empty password is not protected (nothing to protect).
        assertTrue("password = \"\"" in protected)
        assertEquals("bench", parsed.general.default_profile)
    }

    @Test
    fun `protectText is idempotent - protected values are left alone`() {
        val protector = FakeProtector()
        val once = ConfigSecrets.protectText(plaintextConfig, configPath, protector)
        val twice = ConfigSecrets.protectText(once, configPath, protector)
        assertEquals(once, twice)
    }

    @Test
    fun `protectText leaves an undecodable protected value untouched instead of discarding it`() {
        val protector = FakeProtector()
        val once = ConfigSecrets.protectText(plaintextConfig, configPath, protector)
        val references = Regex("\"(fakeref-[^\"]+)\"").findAll(once).map { it.groupValues[1] }.toList()
        assertTrue(references.isNotEmpty())
        references.forEach(protector::makeUndecodable)

        assertEquals(once, ConfigSecrets.protectText(once, configPath, protector), "nothing plaintext is left, and the undecodable references stay")
    }

    @Test
    fun `protectText without a protector is the identity`() {
        assertEquals(plaintextConfig, ConfigSecrets.protectText(plaintextConfig, configPath, null))
    }

    @Test
    fun `protectText touches nothing outside provider sections`() {
        val protector = FakeProtector()
        val text = "[general]\npassword = \"not-a-provider-secret\"\n\n" + plaintextConfig
        val protected = ConfigSecrets.protectText(text, configPath, protector)
        assertTrue("password = \"not-a-provider-secret\"" in protected)
    }

    // ── migrateText ──────────────────────────────────────────────────────────

    @Test
    fun `migrateText protects in place and the second start is a no-op`() {
        val protector = FakeProtector()
        val migrated = ConfigSecrets.migrateText(plaintextConfig, configPath, protector)!!
        assertEquals(2, migrated.count, "access_key_id + secret_access_key of 'bench'")
        assertFalse("round-trip-secret" in migrated.text)

        assertNull(ConfigSecrets.migrateText(migrated.text, configPath, protector), "second start: nothing left to protect")
    }

    @Test
    fun `migrateText is null without a protector, and null on an already-protected config`() {
        assertNull(ConfigSecrets.migrateText(plaintextConfig, configPath, null))

        val protector = FakeProtector()
        val migrated = ConfigSecrets.migrateText(plaintextConfig, configPath, protector)!!
        assertNull(ConfigSecrets.migrateText(migrated.text, configPath, protector), "nothing plaintext left to protect")
    }

    @Test
    fun `migrateText refuses text that would not round-trip`() {
        // A protector whose decode does not return what protect stored: only the round-trip backstop
        // can catch this, and it must refuse the rewrite (naming profile and field, never the value).
        // require() backs the round-trip check, so the refusal is an IllegalArgumentException.
        assertFailsWith<IllegalArgumentException> { ConfigSecrets.migrateText(plaintextConfig, configPath, LyingProtector()) }
    }

    /** Stores one value, hands back another: only migrateText's round-trip backstop can catch this. */
    private class LyingProtector : CredentialProtector {
        private val real = FakeProtector()

        override fun protect(id: String, plaintext: String): String = real.protect(id, "$plaintext-lying")

        override fun decode(id: String, stored: String): CredentialData = real.decode(id, stored)

        override fun delete(id: String) = real.delete(id)
    }

    // ── the real protector on Windows ────────────────────────────────────────

    @Test
    fun `a full config round-trips through real DPAPI on Windows`() {
        assumeTrue("DPAPI integration requires Windows", System.getProperty("os.name").lowercase().contains("windows"))

        val protector = WindowsDpapiCredentialProtector()
        val migrated = ConfigSecrets.migrateText(plaintextConfig, configPath, protector)!!
        assertFalse("round-trip-secret" in migrated.text)
        assertTrue(migrated.text.contains("unidrive-dpapi-v1:"))

        val parsed = SyncConfig.parseRaw(migrated.text)
        assertEquals("round-trip-secret", ConfigSecrets.decode(parsed.providers.getValue("bench")!!, "bench", configPath, protector)!!.secret_access_key)
        assertEquals(migrated.text, ConfigSecrets.protectText(migrated.text, configPath, protector), "second pass must not change protected values")
    }
}
