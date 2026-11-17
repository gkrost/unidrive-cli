package org.krost.unidrive.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.krost.unidrive.testing.TestScratch

/**
 * The passphrase chain (issue#796, D1): env → systemd credential → unlock helper → console, shared
 * by the vault and the X-tra resolution. A helper soft code falls through to the console; a hard
 * code, a missing helper file and a non-absolute config value stop the chain with the reason named.
 */
class PassphraseChainTest {
    private val dir: Path = TestScratch.directory("passphrase-chain")

    private fun chain(
        env: Map<String, String> = emptyMap(),
        helperValue: String? = null,
        helper: (Path, String, Path) -> UnlockHelper.Result = { _, _, _ -> UnlockHelper.Result.Hard("unused") },
        consolePassword: CharArray? = "typed-pass".toCharArray(),
        consolePresent: Boolean = true,
    ): PassphraseResolution =
        resolvePassphrase(
            secretKind = "vault",
            envName = "UNIDRIVE_VAULT_PASS",
            credentialName = "unidrive-vault-pass",
            configDir = dir,
            helperConfig = helperValue,
            prompt = "Vault passphrase: ",
            sources = PassphraseSources(
                env = { env[it] },
                consolePresent = { consolePresent },
                consoleRead = { consolePassword },
                helper = helper,
            ),
        )

    @Test
    fun `the environment variable wins`() {
        val r = chain(env = mapOf("UNIDRIVE_VAULT_PASS" to "env-pass"))
        assertTrue(r is PassphraseResolution.Secret)
        assertEquals("env-pass", String((r as PassphraseResolution.Secret).chars))
    }

    @Test
    fun `the systemd credential file is the second source`() {
        val credDir = Files.createDirectories(dir.resolve("creds"))
        Files.writeString(credDir.resolve("unidrive-vault-pass"), "systemd-pass\n")
        val r =
            resolvePassphrase(
                secretKind = "vault",
                envName = "UNIDRIVE_VAULT_PASS",
                credentialName = "unidrive-vault-pass",
                configDir = dir,
                helperConfig = null,
                prompt = "Vault passphrase: ",
                sources = PassphraseSources(
                    env = { if (it == "CREDENTIALS_DIRECTORY") credDir.toString() else null },
                    consolePresent = { false },
                ),
            )
        assertTrue(r is PassphraseResolution.Secret)
        assertEquals("systemd-pass", String((r as PassphraseResolution.Secret).chars), "the line terminator is trimmed")
    }

    @Test
    fun `a helper secret is used before the console`() {
        val helperPath = dir.resolve("helper.exe")
        Files.writeString(helperPath, "binary")
        val keyringSeen = arrayOf<Path?>(null)
        val kindSeen = arrayOf<String?>(null)
        val r = chain(helperValue = helperPath.toString(), helper = { _, kind, keyring ->
            kindSeen[0] = kind
            keyringSeen[0] = keyring
            UnlockHelper.Result.Secret("helper-pass".toCharArray())
        })
        assertTrue(r is PassphraseResolution.Secret)
        assertEquals("helper-pass", String((r as PassphraseResolution.Secret).chars))
        assertEquals("vault", kindSeen[0])
        assertEquals(dir.resolve(KEYRING_FILE_NAME), keyringSeen[0], "the keyring path is the config dir's file")
    }

    @Test
    fun `a helper soft code falls through to the console`() {
        val helperPath = dir.resolve("helper.exe")
        Files.writeString(helperPath, "binary")
        val r = chain(helperValue = helperPath.toString(), helper = { _, _, _ -> UnlockHelper.Result.Soft("declined") })
        assertTrue(r is PassphraseResolution.Secret, "the console answers after the decline")
        assertEquals("typed-pass", String((r as PassphraseResolution.Secret).chars))
    }

    @Test
    fun `a helper soft code with no console is refused with the reason`() {
        val helperPath = dir.resolve("helper.exe")
        Files.writeString(helperPath, "binary")
        val r = chain(helperValue = helperPath.toString(), helper = { _, _, _ -> UnlockHelper.Result.Soft("locked_out") }, consolePresent = false)
        assertTrue(r is PassphraseResolution.Refused)
        assertTrue((r as PassphraseResolution.Refused).detail.contains("locked_out"), "the helper's reason is in the message")
        assertFalse(r.consoleWasPresent)
    }

    @Test
    fun `a helper hard code stops the chain even with a console`() {
        val helperPath = dir.resolve("helper.exe")
        Files.writeString(helperPath, "binary")
        val r = chain(helperValue = helperPath.toString(), helper = { _, _, _ -> UnlockHelper.Result.Hard("witness_mismatch") })
        assertTrue(r is PassphraseResolution.Refused)
        assertTrue((r as PassphraseResolution.Refused).detail.contains("witness_mismatch"))
    }

    @Test
    fun `a configured helper that is not a regular file is an error naming the path, not a skip`() {
        val helperPath = dir.resolve("gone").resolve("helper.exe")
        val r = chain(helperValue = helperPath.toString(), consolePresent = true)
        assertTrue(r is PassphraseResolution.Refused)
        assertTrue((r as PassphraseResolution.Refused).detail.contains(helperPath.toString()))
        assertTrue(r.detail.contains("missing"), "the message says what is wrong with the configured helper")
    }

    @Test
    fun `a relative helper path is refused, never resolved`() {
        val r = chain(helperValue = "helper.exe", consolePresent = true)
        assertTrue(r is PassphraseResolution.Refused)
        assertTrue((r as PassphraseResolution.Refused).detail.contains("absolute"))
    }

    @Test
    fun `no source at all is refused with what the operator can set`() {
        val r = chain(consolePresent = false)
        assertTrue(r is PassphraseResolution.Refused)
        val detail = (r as PassphraseResolution.Refused).detail
        assertTrue(detail.contains("unidrive-vault-pass"), detail)
        assertTrue(detail.contains("unlock_helper"), detail)
    }

    @Test
    fun `a console prompt that is cut off is consoleWasPresent`() {
        val r = chain(consolePassword = null)
        assertTrue(r is PassphraseResolution.Refused)
        assertTrue((r as PassphraseResolution.Refused).consoleWasPresent)
    }
}
