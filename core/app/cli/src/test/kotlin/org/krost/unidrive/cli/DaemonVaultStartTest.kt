package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.krost.unidrive.sync.Vault
import java.nio.file.Files
import java.nio.file.Path
import java.security.GeneralSecurityException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * #1046 item 4: a truncated vault.enc is a failed vault read for the daemon's vault verbs, answered as
 * `vault_unlock_failed`, not an exception out of the handler. Temp dirs only.
 */
class DaemonVaultStartTest {
    private lateinit var dir: Path
    private val pass = "truncated-vault-pass-123"

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("unidrive-daemon-vault-")
        Vault(dir.resolve("vault.enc")).init(pass.toCharArray())
        // Shorter than salt, IV and GCM tag together: the vault cannot even be split into its parts.
        Files.write(dir.resolve("vault.enc"), Files.readAllBytes(dir.resolve("vault.enc")).copyOf(10))
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun main() = Main().apply { configDir = dir.toString() }

    @Test
    fun `vault unlock of a truncated vault answers vault_unlock_failed`() = runBlocking {
        val main = main()
        assertIs<VaultUnlockOutcome.Failed>(main.daemonUnlockVault(pass.toCharArray()))
        val vault = assertNotNull(main.daemonVault(DaemonVaultStart.Locked("test")))
        val reply = VaultRpcHandler(vault, failureDelayMs = 0, delayFn = { }).unlockVerb("""{"verb":"vault.unlock","passphrase":"$pass"}""")
        assertEquals(JsonPrimitive("vault_unlock_failed"), (Json.parseToJsonElement(reply) as JsonObject)["error"])
        assertFalse(reply.contains(pass), "the reply must not carry the passphrase")
    }

    @Test
    fun `a rewrite or a passphrase check of a truncated vault is a failure, not an exception`() {
        val main = main()
        assertIs<VaultUnlockOutcome.Failed>(main.rewriteDaemonVault(pass.toCharArray()) { it })
        assertFalse(main.vaultPassphraseMatches(pass.toCharArray()))
    }

    // Only the truncated read is a failed read: an IllegalStateException of the change itself is not swallowed as one.
    @Test
    fun `a rewrite whose change throws IllegalStateException passes it on`() {
        Vault(dir.resolve("vault.enc")).init(pass.toCharArray())
        val thrown = assertFailsWith<IllegalStateException> { main().rewriteDaemonVault(pass.toCharArray()) { error("change refused") } }
        assertEquals("change refused", thrown.message)
    }

    // The daemon start (readVaultForDaemon, which exits the process) relies on this conversion for its decryption-failed line.
    @Test
    fun `the truncated read is a GeneralSecurityException naming the cause`() {
        val thrown = assertFailsWith<GeneralSecurityException> { Vault(dir.resolve("vault.enc")).readTruncatedAsFailed(pass.toCharArray()) }
        assertIs<IllegalStateException>(thrown.cause)
    }
}
