package org.krost.unidrive.sync

import org.krost.unidrive.auth.CredentialData
import org.krost.unidrive.auth.CredentialLockedException
import org.krost.unidrive.auth.CredentialProtectionException
import org.krost.unidrive.auth.CredentialProtector
import org.krost.unidrive.auth.CredentialSealKey
import org.krost.unidrive.auth.KeyedCredentialProtector
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#859 / #797: config.toml's provider secret fields (S3 keys, `password`, `client_id` /
 * `client_secret`) under the vault-key seal, inside the platform layer, as the credential files. One test per invariant.
 */
class ConfigSecretsSealTest {
    /** The platform layer: a reversible wrapping under its own prefix, anything else is legacy plaintext. */
    private class FakePlatform : CredentialProtector {
        override fun protect(id: String, plaintext: String): String = PREFIX + Base64.getEncoder().encodeToString(plaintext.toByteArray())

        override fun decode(id: String, stored: String): CredentialData =
            if (stored.startsWith(PREFIX)) CredentialData(String(Base64.getDecoder().decode(stored.removePrefix(PREFIX))), true) else CredentialData(stored, false)

        override fun delete(id: String) = Unit

        companion object {
            const val PREFIX = "fake-platform-v1:"
        }
    }

    private lateinit var dir: Path
    private lateinit var configPath: Path
    private val platform = FakePlatform()
    private var current: CredentialSealKey? = null
    private val keyed = KeyedCredentialProtector(platform) { current }
    private val secret = CredentialSealKey.generateSecret()
    private val s3Secret = "s3-secret-access-key-value"

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("config-secrets-seal")
        configPath = dir.resolve("config.toml")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun config(secretValue: String) =
        "[providers.s3a]\ntype = \"s3\"\nbucket = \"b\"\naccess_key_id = \"AKIAEXAMPLE\"\nsecret_access_key = \"$secretValue\"\n"

    private fun writePlatformOnly() {
        Files.writeString(configPath, ConfigSecrets.protectText(config(s3Secret), configPath, platform))
    }

    private fun stored(field: String): String = assertNotNull(SyncConfig.parseRaw(Files.readString(configPath)).providers["s3a"]).let {
        if (field == "secret_access_key") it.secret_access_key!! else it.access_key_id!!
    }

    private fun decoded(): RawProvider? =
        ConfigSecrets.decode(SyncConfig.parseRaw(Files.readString(configPath)).providers["s3a"], "s3a", configPath, keyed)

    private fun migrate() {
        val migrated = ConfigSecrets.migrateText(Files.readString(configPath), configPath, keyed)
        if (migrated != null) Files.writeString(configPath, migrated.text)
    }

    private fun sealAll() {
        writePlatformOnly()
        current = CredentialSealKey(secret, enabled = true)
        migrate()
        assertTrue(stored("secret_access_key").startsWith(KeyedCredentialProtector.PREFIX))
    }

    @Test
    fun `a platform-only S3 secret is re-sealed by the migration with sealing on`() {
        writePlatformOnly()
        current = CredentialSealKey(secret, enabled = true)

        migrate()

        val onDisk = stored("secret_access_key")
        assertTrue(onDisk.startsWith(KeyedCredentialProtector.PREFIX), "sealed: ${onDisk.take(24)}")
        val platformOnly = platform.decode("x", onDisk.removePrefix(KeyedCredentialProtector.PREFIX)).plaintext
        assertFalse(s3Secret in platformOnly, "the platform layer alone holds only ciphertext")
        assertEquals(s3Secret, decoded()?.secret_access_key)
        assertEquals("AKIAEXAMPLE", decoded()?.access_key_id)
    }

    @Test
    fun `with sealing on a platform-only field still reads before the migration`() {
        writePlatformOnly()
        current = CredentialSealKey(secret, enabled = true)

        assertEquals(s3Secret, decoded()?.secret_access_key)
    }

    @Test
    fun `a sealed config secret read locked is refused by name as locked`() {
        sealAll()
        current = null

        val e = assertFailsWith<CredentialLockedException> { decoded() }

        assertTrue("config.toml" in e.message.orEmpty() && "s3a" in e.message.orEmpty() && "vault.unlock" in e.message.orEmpty(), e.message)
    }

    @Test
    fun `a config rewrite in a locked process never downgrades a sealed field`() {
        sealAll()
        val before = stored("secret_access_key")
        current = null

        val text = ConfigSecrets.protectText(Files.readString(configPath) + "\n[providers.other]\ntype = \"localfs\"\n", configPath, keyed)
        Files.writeString(configPath, text)

        assertEquals(before, stored("secret_access_key"))
        assertEquals(null, ConfigSecrets.migrateText(Files.readString(configPath), configPath, keyed), "nothing to migrate while locked")
    }

    @Test
    fun `with sealing off a sealed field is rewritten platform-only`() {
        sealAll()
        current = CredentialSealKey(secret, enabled = false)

        migrate()

        assertTrue(stored("secret_access_key").startsWith(FakePlatform.PREFIX))
        assertEquals(s3Secret, decoded()?.secret_access_key)
    }

    @Test
    fun `on a host without a platform store a foreign platform reference is still refused`() {
        val noStore = ConfigSecretSeal.sealableProtector(null)
        Files.writeString(configPath, config("unidrive-dpapi-v1:AAAA"))

        val e =
            assertFailsWith<CredentialProtectionException> {
                ConfigSecrets.decode(SyncConfig.parseRaw(Files.readString(configPath)).providers["s3a"], "s3a", configPath, noStore)
            }
        assertTrue("another computer" in e.message.orEmpty(), e.message)
    }

    @Test
    fun `on a host without a platform store an unsealed field is plaintext as before`() {
        val noStore = ConfigSecretSeal.sealableProtector(null)
        Files.writeString(configPath, config(s3Secret))

        assertEquals(null, ConfigSecrets.migrateText(Files.readString(configPath), configPath, noStore))
        assertEquals(config(s3Secret), ConfigSecrets.protectText(config(s3Secret), configPath, noStore))
    }

    @Test
    fun `the default config protector is the keyed seal`() {
        assertIs<KeyedCredentialProtector>(ConfigSecretSeal.defaultProtector())
    }

    @Test
    fun `a config that holds a sealed field is reported as sealed`() {
        writePlatformOnly()
        assertFalse(ConfigSecretSeal.holdsSealedField(configPath))
        sealAll()
        assertTrue(ConfigSecretSeal.holdsSealedField(configPath))
    }
}
