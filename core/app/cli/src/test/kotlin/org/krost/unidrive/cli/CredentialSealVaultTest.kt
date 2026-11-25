package org.krost.unidrive.cli

import org.krost.unidrive.auth.CredentialSeal
import org.krost.unidrive.auth.KeyedCredentialProtector
import org.krost.unidrive.sync.ProfileInfo
import org.krost.unidrive.sync.RawProvider
import org.krost.unidrive.sync.Vault
import picocli.CommandLine
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#797 (P3): the vault side of the credential seal. The seal key is a random secret kept in the
 * vault (its own section, never a profile), switched on and off with `vault seal-credentials`; `vault.unlock`
 * installs it and `vault.lock` removes it; a profile whose credentials.json is sealed stays locked until the unlock;
 * `vault decrypt` and `vault forget` never drop the key while sealed files may still need it. Temp dirs only.
 */
class CredentialSealVaultTest {
    private lateinit var dir: Path
    private val originalIn: java.io.InputStream = System.`in`
    private val pass = "seal-vault-pass-123"

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("unidrive-credential-seal-")
        CredentialSeal.clear()
    }

    @AfterTest
    fun tearDown() {
        System.setIn(originalIn)
        CredentialSeal.clear()
        dir.toFile().deleteRecursively()
    }

    private val vault get() = Vault(dir.resolve("vault.enc"))

    private fun run(
        vararg args: String,
        stdin: String = "",
    ): Int {
        System.setIn(ByteArrayInputStream(stdin.toByteArray(Charsets.UTF_8)))
        return CommandLine(Main()).execute("-c", dir.toString(), *args)
    }

    private fun main() = Main().apply { configDir = dir.toString() }

    private fun sealSection(): Map<String, String>? = vault.read(pass.toCharArray())[CREDENTIAL_SEAL_SECTION]

    @Test
    fun `vault seal-credentials on stores a seal key in the vault and off keeps the key`() {
        vault.init(pass.toCharArray())
        assertEquals(0, run("vault", "seal-credentials", "on", "--passphrase-stdin", stdin = "$pass\n"))
        val on = assertNotNull(sealSection(), "the vault holds the seal section")
        assertEquals("true", on["enabled"])
        assertEquals(32, Base64.getDecoder().decode(on.getValue("key")).size)
        assertEquals(0, run("vault", "seal-credentials", "off", "--passphrase-stdin", stdin = "$pass\n"))
        val off = assertNotNull(sealSection())
        assertEquals("false", off["enabled"])
        assertEquals(on["key"], off["key"], "turning off keeps the key: sealed files still need it to be rewritten")
    }

    @Test
    fun `vault unlock installs the seal key and vault lock removes it`() {
        vault.init(pass.toCharArray())
        assertEquals(0, run("vault", "seal-credentials", "on", "--passphrase-stdin", stdin = "$pass\n"))
        CredentialSeal.clear()
        val main = main()
        assertTrue(main.daemonUnlockVault(pass.toCharArray()) is VaultUnlockOutcome.Ok)
        assertEquals(true, CredentialSeal.current()?.enabled, "vault.unlock installed the seal key")
        main.dropVaultData()
        assertNull(CredentialSeal.current(), "vault.lock removed the seal key from memory")
    }

    @Test
    fun `a profile whose credentials file is sealed needs the vault`() {
        val profileDir = Files.createDirectories(dir.resolve("ix"))
        Files.writeString(profileDir.resolve("credentials.json"), KeyedCredentialProtector.PREFIX + "c2VhbGVk")
        val profile = ProfileInfo("ix", "internxt", dir, RawProvider(type = "internxt"))
        assertTrue(main().profileNeedsVaultedFields(profile), "a sealed credentials.json keeps the profile locked until vault.unlock")
    }

    @Test
    fun `a profile whose credentials file is not sealed does not need the vault`() {
        val profileDir = Files.createDirectories(dir.resolve("ix"))
        Files.writeString(profileDir.resolve("credentials.json"), "unidrive-dpapi-v1:AAAA")
        val profile = ProfileInfo("ix", "internxt", dir, RawProvider(type = "internxt"))
        assertFalse(main().profileNeedsVaultedFields(profile))
    }

    @Test
    fun `vault decrypt keeps the seal key and does not call it an orphan profile`() {
        Files.writeString(dir.resolve("config.toml"), "[general]\n\n[providers.p1]\ntype = \"localfs\"\nmode = \"mirror\"\n")
        val seal = mapOf("key" to Base64.getEncoder().encodeToString(ByteArray(32) { 7 }), "enabled" to "true")
        vault.write(pass.toCharArray(), mapOf("p1" to mapOf("password" to "p1-secret"), CREDENTIAL_SEAL_SECTION to seal))
        val warnings = mutableListOf<String>()
        decryptVault(dir, pass.toCharArray()) { warnings += it }
        assertEquals(emptyList(), warnings, "the seal section is not an orphan profile")
        assertEquals(mapOf(CREDENTIAL_SEAL_SECTION to seal), vault.read(pass.toCharArray()), "the vault keeps the seal key only")
    }

    @Test
    fun `vault forget never drops the seal key`() {
        val seal = mapOf("key" to Base64.getEncoder().encodeToString(ByteArray(32) { 7 }), "enabled" to "true")
        vault.write(pass.toCharArray(), mapOf(CREDENTIAL_SEAL_SECTION to seal))
        assertFalse(forgetVaultEntry(vault, pass.toCharArray(), CREDENTIAL_SEAL_SECTION))
        assertEquals(seal, sealSection())
    }

    @Test
    fun `a sealed credentials file of a provider that does not declare the seal does not lock the profile`() {
        val profileDir = Files.createDirectories(dir.resolve("lf"))
        Files.writeString(profileDir.resolve("credentials.json"), KeyedCredentialProtector.PREFIX + "c2VhbGVk")
        val profile = ProfileInfo("lf", "localfs", dir, RawProvider(type = "localfs"))
        assertFalse(main().profileNeedsVaultedFields(profile), "the provider's factory answers for its own files")
    }

    private fun stdoutOf(block: () -> Int): String {
        val original = System.out
        val buffer = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(buffer, true, Charsets.UTF_8))
        try {
            assertEquals(0, block())
        } finally {
            System.setOut(original)
        }
        return buffer.toString(Charsets.UTF_8)
    }

    @Test
    fun `seal-credentials says a running daemon picks the change up at its next vault unlock or restart`() {
        vault.init(pass.toCharArray())
        for (state in listOf("on", "off")) {
            val out = stdoutOf { run("vault", "seal-credentials", state, "--passphrase-stdin", stdin = "$pass\n") }
            assertTrue(out.contains("running daemon") && out.contains("vault.unlock") && out.contains("restart"), "$state: $out")
        }
    }

    @Test
    fun `an undecodable seal section fails the unlock naming the section instead of reading as locked`() {
        for (bad in listOf("!!not-base64!!", Base64.getEncoder().encodeToString(ByteArray(7)))) {
            vault.write(pass.toCharArray(), mapOf(CREDENTIAL_SEAL_SECTION to mapOf("key" to bad, "enabled" to "true")))
            val outcome = main().daemonUnlockVault(pass.toCharArray())
            val failed = assertIs<VaultUnlockOutcome.Failed>(outcome, "bad key '$bad'")
            assertTrue(failed.message.contains("credential seal"), "names the seal section: ${failed.message}")
            assertNull(CredentialSeal.current())
        }
    }

    private fun disabledSeal() = mapOf("key" to Base64.getEncoder().encodeToString(ByteArray(32) { 7 }), "enabled" to "false")

    @Test
    fun `vault decrypt drops a disabled seal key when no credentials file is sealed any more`() {
        Files.writeString(dir.resolve("config.toml"), "[general]\n")
        vault.write(pass.toCharArray(), mapOf(CREDENTIAL_SEAL_SECTION to disabledSeal()))
        val profileDir = Files.createDirectories(dir.resolve("ix"))
        Files.writeString(profileDir.resolve("credentials.json"), "unidrive-dpapi-v1:AAAA")
        decryptVault(dir, pass.toCharArray()) { }
        assertFalse(vault.exists(), "a vault that holds only a retired seal key can be deleted")
    }

    @Test
    fun `vault decrypt keeps a disabled seal key while a credentials file is still sealed and says why`() {
        Files.writeString(dir.resolve("config.toml"), "[general]\n")
        vault.write(pass.toCharArray(), mapOf(CREDENTIAL_SEAL_SECTION to disabledSeal()))
        val profileDir = Files.createDirectories(dir.resolve("ix"))
        Files.writeString(profileDir.resolve("credentials.json"), KeyedCredentialProtector.PREFIX + "c2VhbGVk")
        val line = decryptVault(dir, pass.toCharArray()) { }
        assertTrue(vault.exists())
        assertTrue(line.contains("still sealed"), line)
    }
}
