package org.krost.unidrive.cli

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.krost.unidrive.io.Os
import org.krost.unidrive.io.VAULT_PASSPHRASE_ENV
import org.krost.unidrive.io.XTRA_PASSPHRASE_ENV
import org.krost.unidrive.sync.ProfileInfo
import org.krost.unidrive.sync.RawProvider
import org.krost.unidrive.sync.Vault
import org.krost.unidrive.testing.TestScratch
import org.krost.unidrive.xtra.XtraKeyManager

/**
 * The owner's decisions on #796 (review round 2): `xtra change-passphrase*` keeps the vaulted X-tra entry in step,
 * a one-shot CLI process resolves the X-tra passphrase through the daemon's chain (vault included), and only a vault
 * known to carry `.xtra` makes an xtra profile wait for `vault.unlock` (a non-secret marker beside vault.enc).
 */
class XtraVaultRound2Test {
    private val dir: Path = TestScratch.directory("xtra-vault-r2")
    private val vaultPass = "synthetic-vault-pass".toCharArray()
    private val oldXtra = "old-xtra-passphrase"
    private val newXtra = "new-xtra-passphrase"
    private val vault get() = Vault(dir.resolve("vault.enc"))
    private val marker get() = dir.resolve("vault.enc.xtra")

    private val envVault =
        PassphraseSources(env = { if (it == VAULT_PASSPHRASE_ENV) String(vaultPass) else null }, consolePresent = { false })
    private val noSource = PassphraseSources(env = { null }, consolePresent = { false })
    private val xtraEntry = mapOf(XTRA_VAULT_ENTRY to mapOf(XTRA_VAULT_FIELD to oldXtra))

    private fun main() = Main().apply { configDir = dir.toString() }

    private fun xtraProfile() = ProfileInfo("p", "localfs", dir, RawProvider(type = "localfs", xtra_encryption = true))

    private fun helper(script: String): Path {
        assumeFalse(Os.isWindows())
        assumeTrue(System.getenv(XTRA_PASSPHRASE_ENV) == null && System.getenv("CREDENTIALS_DIRECTORY") == null)
        assumeTrue(System.getenv(VAULT_PASSPHRASE_ENV) == null && System.console() == null)
        val file = dir.resolve("helper.sh")
        Files.writeString(file, "#!/bin/sh\nreq=\$(cat)\n$script\n")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"))
        Files.writeString(dir.resolve("config.toml"), "[general]\nunlock_helper = \"$file\"\n")
        return file
    }

    private fun b64(s: String) = java.util.Base64.getEncoder().encodeToString(s.toByteArray())

    // ── 1. change-passphrase keeps the vaulted entry in step ──

    @Test
    fun `change-passphrase rewrites the vault X-tra entry when the vault passphrase is available`() {
        vault.write(vaultPass, xtraEntry + ("work" to mapOf("password" to "p")))
        assertEquals(0, main().updateVaultXtraAfterChange(newXtra.toCharArray(), envVault))
        val data = vault.read(vaultPass)
        assertEquals(newXtra, data.getValue(XTRA_VAULT_ENTRY).getValue(XTRA_VAULT_FIELD))
        assertEquals(mapOf("password" to "p"), data["work"])
    }

    @Test
    fun `change-passphrase reports failure and leaves the vault alone when its passphrase is unavailable`() {
        vault.write(vaultPass, xtraEntry)
        assertEquals(1, main().updateVaultXtraAfterChange(newXtra.toCharArray(), noSource))
        assertEquals(oldXtra, vault.read(vaultPass).getValue(XTRA_VAULT_ENTRY).getValue(XTRA_VAULT_FIELD))
    }

    @Test
    fun `change-passphrase leaves a vault without an X-tra entry alone and succeeds`() {
        vault.write(vaultPass, mapOf("work" to mapOf("password" to "p")))
        assertEquals(0, main().updateVaultXtraAfterChange(newXtra.toCharArray(), envVault))
        assertEquals(setOf("work"), vault.read(vaultPass).keys)
    }

    @Test
    fun `change-passphrase with no vault file succeeds`() {
        assertEquals(0, main().updateVaultXtraAfterChange(newXtra.toCharArray(), noSource))
    }

    @Test
    fun `rotation leaves an X-tra entry cleared before it acquires the config lock`() {
        vault.write(vaultPass, xtraEntry)
        val cli = main()
        val rotation = FutureTask { cli.updateVaultXtraAfterChange(newXtra.toCharArray(), envVault) }
        ConfigFileTransaction.withLock(dir.resolve("config.toml")) {
            val thread = Thread.ofPlatform().daemon().start(rotation)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (thread.state != Thread.State.BLOCKED && !rotation.isDone && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(Thread.State.BLOCKED, thread.state, "rotation reached the config lock")
            assertTrue(forgetVaultEntry(vault, vaultPass, XTRA_VAULT_ENTRY))
        }
        assertEquals(0, rotation.get(10, TimeUnit.SECONDS))
        assertFalse(XTRA_VAULT_ENTRY in vault.read(vaultPass))
        assertFalse(vault.carriesXtra)
    }

    @Test
    fun `a vault replacement failure after rotating the key returns a recovery message and exit status`() {
        vault.write(vaultPass, xtraEntry)
        val keyPath = dir.resolve("xtra.key")
        val keys = XtraKeyManager(keyPath)
        keys.generate(oldXtra.toCharArray())
        keys.changePassphrase(newXtra.toCharArray())
        val failingVault = Vault(dir.resolve("vault.enc")) { throw IOException("simulated replacement failure") }
        val stderr = ByteArrayOutputStream()
        val original = System.err
        try {
            System.setErr(PrintStream(stderr, true, Charsets.UTF_8))
            assertEquals(1, main().updateVaultXtraAfterChange(newXtra.toCharArray(), envVault, failingVault))
        } finally {
            System.setErr(original)
        }
        val message = stderr.toString(Charsets.UTF_8)
        assertContains(message, "key file already changed")
        assertContains(message, "unidrive vault xtra-set")
        XtraKeyManager(keyPath).load(newXtra.toCharArray())
        assertEquals(xtraEntry, vault.read(vaultPass))
    }

    // ── 2. the CLI resolves the X-tra passphrase through the full chain ──

    @Test
    fun `X-tra environment and credential sources skip the configured vault helper`() {
        vault.write(vaultPass, xtraEntry)
        val helperPath = Files.createFile(dir.resolve("recording-helper"))
        Files.writeString(dir.resolve("config.toml"), "[general]\nunlock_helper = '$helperPath'\n")
        val credentials = Files.createDirectory(dir.resolve("credentials"))
        Files.writeString(credentials.resolve("unidrive-xtra-pass"), "from-credential\n")
        val calls = mutableListOf<String>()
        val recordingHelper: (Path, String, Path) -> UnlockHelper.Result = { _, kind, _ ->
            calls += kind
            UnlockHelper.Result.Secret(vaultPass.copyOf())
        }

        val env = PassphraseSources(
            env = { if (it == XTRA_PASSPHRASE_ENV) "from-environment" else null },
            helper = recordingHelper,
        )
        val credential = PassphraseSources(
            env = { if (it == "CREDENTIALS_DIRECTORY") credentials.toString() else null },
            helper = recordingHelper,
        )
        assertEquals("from-environment", String(main().xtraPassphrase(env)))
        assertEquals("from-credential", String(main().xtraPassphrase(credential)))
        assertTrue(calls.isEmpty(), "higher-priority X-tra sources never invoke a helper")
    }

    @Test
    fun `a one-shot CLI process resolves the vaulted X-tra passphrase when the vault passphrase is available`() {
        helper("case \"\$req\" in *'\"vault\"'*) echo '{\"secret\":\"${b64(String(vaultPass))}\"}';; *) echo '{\"error\":\"declined\"}';; esac")
        vault.write(vaultPass, xtraEntry)
        val main = main()
        assertEquals(oldXtra, String(main.xtraPassphrase()))
    }

    // ── 3. the marker ──

    @Test
    fun `the vault keeps a marker beside vault enc exactly while it holds an X-tra entry`() {
        vault.write(vaultPass, mapOf("work" to mapOf("password" to "p")))
        assertFalse(Files.exists(marker))
        vault.write(vaultPass, xtraEntry)
        assertTrue(Files.exists(marker))
        assertEquals(0L, Files.size(marker), "the marker is not a secret and carries no content")
        vault.write(vaultPass, emptyMap())
        assertFalse(Files.exists(marker))
        vault.write(vaultPass, xtraEntry)
        vault.delete()
        assertFalse(Files.exists(marker))
    }

    @Test
    fun `reading a vault brings a missing or stale marker in line with what it holds`() {
        vault.write(vaultPass, xtraEntry)
        Files.delete(marker)
        vault.read(vaultPass)
        assertTrue(Files.exists(marker), "a vault written before the marker existed gets it on its first unlock")
        vault.write(vaultPass, emptyMap())
        Files.writeString(marker, "")
        vault.read(vaultPass)
        assertFalse(Files.exists(marker))
    }

    @Test
    fun `an xtra profile waits for the vault unlock only when the vault is known to carry the X-tra passphrase`() {
        val main = main()
        vault.write(vaultPass, mapOf("work" to mapOf("password" to "p")))
        try {
            LifecycleHooks.env = { null }
            assertFalse(main.profileNeedsVaultedFields(xtraProfile()), "no .xtra known: a helper-only install must not wait")
            vault.write(vaultPass, xtraEntry)
            assertTrue(main.profileNeedsVaultedFields(xtraProfile()))
            LifecycleHooks.env = { if (it == XTRA_PASSPHRASE_ENV) "from-env" else null }
            assertFalse(main.profileNeedsVaultedFields(xtraProfile()), "the environment supplies the X-tra passphrase")
        } finally {
            LifecycleHooks.env = { System.getenv(it) }
        }
    }

    @Test
    fun `a helper-only install with a vault that holds no X-tra entry starts unlocked and asks the helper`() {
        helper("case \"\$req\" in *'\"xtra\"'*) echo '{\"secret\":\"${b64(oldXtra)}\"}';; *) echo '{\"error\":\"declined\"}';; esac")
        vault.write(vaultPass, mapOf("work" to mapOf("password" to "p")))
        val main = main()
        try {
            LifecycleHooks.env = { null }
            val start = main.resolveDaemonVaultStart(xtraProfile(), main.rawGeneral())
            assertTrue(start is DaemonVaultStart.Ready, "got $start")
            assertFalse(main.daemonVaultLocked)
            assertEquals(oldXtra, String(main.xtraPassphrase()))
        } finally {
            LifecycleHooks.env = { System.getenv(it) }
        }
    }
}
