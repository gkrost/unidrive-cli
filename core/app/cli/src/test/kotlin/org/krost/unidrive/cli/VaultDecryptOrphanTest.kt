package org.krost.unidrive.cli

import org.krost.unidrive.auth.CredentialProtectors
import org.krost.unidrive.sync.ConfigSecrets
import org.krost.unidrive.sync.SyncConfig
import org.krost.unidrive.sync.Vault
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `vault decrypt` with an orphan entry (#749 finding 7): an entry whose profile is no longer configured (a removal that
 * found the vault locked) used to fail the whole decrypt. It is skipped with one warning naming `vault forget`, and
 * stays in the vault, which is therefore not deleted. Temp config directories only.
 */
class VaultDecryptOrphanTest {
    private lateinit var dir: Path
    private val pass = "decrypt-pass-123"
    private val warnings = mutableListOf<String>()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("unidrive-vault-orphan-")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private val vault get() = Vault(dir.resolve("vault.enc"))

    private fun config(vararg profiles: String) =
        Files.writeString(
            dir.resolve("config.toml"),
            "[general]\n" + profiles.joinToString("") { "\n[providers.$it]\ntype = \"localfs\"\nmode = \"mirror\"\n" },
        )

    private fun decrypt() = decryptVault(dir, pass.toCharArray()) { warnings += it }

    @Test
    fun `an orphan entry is skipped with a warning naming vault forget and the configured ones are restored`() {
        config("keep")
        vault.write(pass.toCharArray(), mapOf("keep" to mapOf("password" to "keep-secret"), "gone" to mapOf("password" to "old-secret")))

        decrypt()

        val configPath = dir.resolve("config.toml")
        val keep = SyncConfig.parseRaw(Files.readString(configPath)).providers.getValue("keep")
        assertEquals("keep-secret", ConfigSecrets.decode(keep, "keep", configPath)?.password)
        if (CredentialProtectors.atRestDefaultOrNull() != null) {
            assertNotEquals("keep-secret", keep.password, "a protector exists: the restored password must not be plaintext at rest")
        }
        assertEquals(1, warnings.size, warnings.toString())
        assertTrue("unidrive vault forget gone" in warnings.single(), warnings.single())
        assertFalse("old-secret" in warnings.single(), "a warning never carries a secret")
    }

    @Test
    fun `the vault keeps only the orphan entries and is not deleted while it holds one`() {
        config("keep")
        vault.write(pass.toCharArray(), mapOf("keep" to mapOf("password" to "keep-secret"), "gone" to mapOf("password" to "old-secret")))

        decrypt()

        assertEquals(mapOf("gone" to mapOf("password" to "old-secret")), vault.read(pass.toCharArray()))
    }

    @Test
    fun `with no orphan the vault is deleted as before and nothing is warned`() {
        config("keep")
        vault.write(pass.toCharArray(), mapOf("keep" to mapOf("password" to "keep-secret")))

        decrypt()

        assertFalse(vault.exists())
        assertTrue(warnings.isEmpty(), warnings.toString())
    }

    @Test
    fun `with only orphans the config is left as it was`() {
        config("keep")
        val before = Files.readString(dir.resolve("config.toml"))
        vault.write(pass.toCharArray(), mapOf("gone" to mapOf("password" to "old-secret")))

        decrypt()

        assertEquals(before, Files.readString(dir.resolve("config.toml")))
        assertEquals(setOf("gone"), vault.read(pass.toCharArray()).keys)
        assertEquals(1, warnings.size)
    }

    // #1038: decrypt reads the vault under the config lock, so an entry written while it waited for the lock is kept.
    @Test
    fun `an entry written while decrypt waits for the config lock stays in the vault`() {
        config("keep")
        vault.write(pass.toCharArray(), mapOf("keep" to mapOf("password" to "keep-secret")))
        var failure: Throwable? = null
        val decrypting = Thread { runCatching { decrypt() }.onFailure { failure = it } }

        ConfigFileTransaction.withLock(dir.resolve("config.toml")) {
            decrypting.start()
            val deadline = System.nanoTime() + 10_000_000_000L
            while (decrypting.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(Thread.State.BLOCKED, decrypting.state, "decrypt never waited for the config lock")
            vault.write(pass.toCharArray(), vault.read(pass.toCharArray()) + ("gone" to mapOf("password" to "old-secret")))
        }
        decrypting.join(10_000)

        assertEquals(null, failure)
        assertEquals(mapOf("gone" to mapOf("password" to "old-secret")), vault.read(pass.toCharArray()))
    }
}
