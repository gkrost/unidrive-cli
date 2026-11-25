package org.krost.unidrive.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.krost.unidrive.io.Os
import org.krost.unidrive.io.XTRA_PASSPHRASE_ENV
import org.krost.unidrive.sync.Vault
import org.krost.unidrive.testing.TestScratch

/**
 * The X-tra passphrase is a vault secret (issue#796): a daemon with no
 * environment variable, no systemd credential, no console and an unlock helper that holds no X-tra entry (the
 * Windows tray's case) opens an `xtra_encryption` profile once `vault.unlock` has delivered the vault passphrase.
 * The vault stores it as the reserved entry `.xtra` (not a valid profile name), field `passphrase`.
 */
class XtraPassphraseVaultTest {
    private val dir: Path = TestScratch.directory("xtra-vault")
    private val vaultPass = "synthetic-vault-pass".toCharArray()
    private val xtraPass = "synthetic-xtra-pass"

    private fun lockedMain(vaultData: Map<String, Map<String, String>>): Pair<Main, VaultRpcHandler> {
        assumeFalse(Os.isWindows())
        assumeTrue(System.getenv(XTRA_PASSPHRASE_ENV) == null && System.getenv("CREDENTIALS_DIRECTORY") == null)
        assumeTrue(System.console() == null)
        Vault(dir.resolve("vault.enc")).write(vaultPass, vaultData)
        // The fake helper answers `declined` for every secret kind: it has no xtra entry.
        val helper = dir.resolve("fake-unlock-helper.sh")
        Files.writeString(helper, "#!/bin/sh\ncat >/dev/null\necho '{\"error\":\"declined\"}'\n")
        Files.setPosixFilePermissions(helper, PosixFilePermissions.fromString("rwx------"))
        Files.writeString(dir.resolve("config.toml"), "[general]\nunlock_helper = \"$helper\"\n")
        val main = Main().apply { configDir = dir.toString() }
        main.daemonVaultLocked = true
        val handler = VaultRpcHandler(main.daemonVault(DaemonVaultStart.Locked("no passphrase source")), failureDelayMs = 0)
        return main to handler
    }

    private fun unlock(handler: VaultRpcHandler) =
        runBlocking { handler.unlockVerb("""{"passphrase":"${String(vaultPass)}"}""") }

    @Test
    fun `a locked daemon opens an xtra profile after vault unlock when the vault holds the X-tra passphrase`() {
        val (main, handler) = lockedMain(mapOf(".xtra" to mapOf("passphrase" to xtraPass)))
        assertFailsWith<IllegalStateException>("locked: no source answers") { main.xtraPassphrase() }
        assertEquals("""{"ok":true}""", unlock(handler))
        assertEquals(xtraPass, String(main.xtraPassphrase()))
    }

    @Test
    fun `a vault without an X-tra entry still refuses after vault unlock`() {
        val (main, handler) = lockedMain(emptyMap())
        assertEquals("""{"ok":true}""", unlock(handler))
        assertFailsWith<IllegalStateException> { main.xtraPassphrase() }
    }

    @Test
    fun `vault lock takes the X-tra passphrase out of reach again`() {
        val (main, handler) = lockedMain(mapOf(".xtra" to mapOf("passphrase" to xtraPass)))
        unlock(handler)
        assertEquals(xtraPass, String(main.xtraPassphrase()))
        runBlocking { handler.lockVerb() }
        assertFailsWith<IllegalStateException> { main.xtraPassphrase() }
        assertTrue(main.daemonVaultLocked)
    }

    @Test
    fun `xtra-set keeps the other entries and xtra-clear drops only the X-tra entry`() {
        val data = setVaultXtraPassphrase(mapOf("work" to mapOf("password" to "p")), xtraPass)
        assertEquals(mapOf("password" to "p"), data["work"])
        assertEquals(xtraPass, data[XTRA_VAULT_ENTRY]?.get(XTRA_VAULT_FIELD))
        assertFailsWith<IllegalArgumentException> { setVaultXtraPassphrase(emptyMap(), "") }
        val vault = Vault(dir.resolve("clear.enc"))
        vault.write(vaultPass, data)
        assertTrue(forgetVaultEntry(vault, vaultPass, XTRA_VAULT_ENTRY))
        assertEquals(setOf("work"), vault.read(vaultPass).keys)
    }

    @Test
    fun `vault decrypt keeps the X-tra entry and restores the rest`() {
        Files.writeString(dir.resolve("config.toml"), "[general]\n\n[providers.work]\ntype = \"internxt\"\n")
        val vault = Vault(dir.resolve("vault.enc"))
        vault.write(vaultPass, mapOf("work" to mapOf("password" to "p"), XTRA_VAULT_ENTRY to mapOf(XTRA_VAULT_FIELD to xtraPass)))
        val warnings = mutableListOf<String>()
        decryptVault(dir, vaultPass) { warnings += it }
        assertTrue(warnings.isEmpty(), "the X-tra entry is not an orphan profile: $warnings")
        assertEquals(setOf(XTRA_VAULT_ENTRY), vault.read(vaultPass).keys)
        assertTrue(Files.readString(dir.resolve("config.toml")).contains("password = \"p\""))
    }

    @Test
    fun `without a vault file an xtra profile starts unlocked and resolves through the helper`() {
        assumeFalse(Os.isWindows())
        assumeTrue(System.getenv(XTRA_PASSPHRASE_ENV) == null && System.getenv("CREDENTIALS_DIRECTORY") == null)
        val helper = dir.resolve("answering-helper.sh")
        val b64 = java.util.Base64.getEncoder().encodeToString(xtraPass.toByteArray())
        Files.writeString(helper, "#!/bin/sh\ncat >/dev/null\necho '{\"secret\":\"$b64\"}'\n")
        Files.setPosixFilePermissions(helper, PosixFilePermissions.fromString("rwx------"))
        Files.writeString(dir.resolve("config.toml"), "[general]\nunlock_helper = \"$helper\"\n")
        val main = Main().apply { configDir = dir.toString() }
        val profile = org.krost.unidrive.sync.ProfileInfo("p", "localfs", dir, org.krost.unidrive.sync.RawProvider(type = "localfs", xtra_encryption = true))
        try {
            LifecycleHooks.env = { null }
            val start = main.resolveDaemonVaultStart(profile, main.rawGeneral())
            assertTrue(start is DaemonVaultStart.Ready, "no vault.enc: nothing can wait for vault.unlock, got $start")
            assertTrue(!main.daemonVaultLocked)
            assertEquals(null, main.daemonVault(start))
            assertEquals(xtraPass, String(main.xtraPassphrase()))
        } finally {
            LifecycleHooks.env = { System.getenv(it) }
        }
    }

    @Test
    fun `the vault-xtra capability is advertised with vault-unlock only`() {
        val on = daemonCapabilitiesJson(org.krost.unidrive.sync.ProfileMode.MOUNT, false, false, org.krost.unidrive.engine.ViewNamespaces.NONE, false, vaultUnlockable = true)
        val off = daemonCapabilitiesJson(org.krost.unidrive.sync.ProfileMode.MOUNT, false, false, org.krost.unidrive.engine.ViewNamespaces.NONE, false)
        assertTrue("\"$VAULT_XTRA_CAPABILITY\"" in on && "\"$VAULT_UNLOCK_CAPABILITY\"" in on)
        assertTrue(VAULT_XTRA_CAPABILITY !in off)
    }

    @Test
    fun `a locked start for an xtra profile is flagged and vault unlock then releases the X-tra passphrase`() {
        val (main, _) = lockedMain(mapOf(XTRA_VAULT_ENTRY to mapOf(XTRA_VAULT_FIELD to xtraPass)))
        main.daemonVaultLocked = false
        val profile = org.krost.unidrive.sync.ProfileInfo("p", "localfs", dir, org.krost.unidrive.sync.RawProvider(type = "localfs", xtra_encryption = true))
        try {
            LifecycleHooks.env = { null }
            val start = main.resolveDaemonVaultStart(profile, main.rawGeneral())
            assertTrue(start is DaemonVaultStart.Locked, "the helper declines the vault, no env source: $start")
            assertTrue(main.daemonVaultLocked)
            val handler = VaultRpcHandler(main.daemonVault(start), failureDelayMs = 0)
            assertFailsWith<IllegalStateException> { main.xtraPassphrase() }
            assertEquals("""{"ok":true}""", unlock(handler))
            assertEquals(xtraPass, String(main.xtraPassphrase()))
        } finally {
            LifecycleHooks.env = { System.getenv(it) }
        }
    }
}
