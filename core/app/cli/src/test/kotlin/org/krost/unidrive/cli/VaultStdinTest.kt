package org.krost.unidrive.cli

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.krost.unidrive.sync.Vault
import picocli.CommandLine

/**
 * The stdin variants of the vault commands (issue#796): `vault init`, `vault encrypt` and
 * `vault change-passphrase` take `--passphrase-stdin` (and `--new-passphrase-stdin`), so a client
 * sets up and re-keys the vault without a console. The passphrase is never an argument.
 */
class VaultStdinTest {
    private lateinit var dir: Path
    private val originalIn: java.io.InputStream = System.`in`

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("unidrive-vault-stdin-")
    }

    @AfterTest
    fun tearDown() {
        System.setIn(originalIn)
        dir.toFile().deleteRecursively()
    }

    private fun run(
        vararg args: String,
        stdin: String = "",
    ): Int {
        System.setIn(ByteArrayInputStream(stdin.toByteArray(Charsets.UTF_8)))
        return CommandLine(Main()).execute("-c", dir.toString(), *args)
    }

    private val vault get() = Vault(dir.resolve("vault.enc"))

    @Test
    fun `vault init reads the passphrase from standard input with no console`() {
        assertEquals(0, run("vault", "init", "--passphrase-stdin", stdin = "first-vault-pass\nsecond line stays unread\n"))
        assertTrue(vault.exists())
        // The vault is readable with the piped passphrase (and nothing else was consumed).
        vault.read("first-vault-pass".toCharArray())
    }

    @Test
    fun `vault encrypt reads the passphrase from standard input and moves the config secrets`() {
        assertEquals(0, run("vault", "init", "--passphrase-stdin", stdin = "encrypt-pass\n"))
        Files.writeString(
            dir.resolve("config.toml"),
            """
            [general]

            [providers.p1]
            type = "s3"
            mode = "mirror"
            bucket = "b"
            region = "r"
            access_key_id = "AKIA-PLAIN"
            secret_access_key = "SECRET-PLAIN"
            """.trimIndent(),
        )
        assertEquals(0, run("vault", "encrypt", "--passphrase-stdin", stdin = "encrypt-pass\n"))
        val config = Files.readString(dir.resolve("config.toml"))
        assertFalse(config.contains("SECRET-PLAIN"), "the secret left config.toml")
        assertFalse(config.contains("AKIA-PLAIN"), "the access key moved too")
        val data = vault.read("encrypt-pass".toCharArray())
        assertEquals("SECRET-PLAIN", data.getValue("p1").getValue("secret_access_key"))
    }

    @Test
    fun `vault change-passphrase reads both passphrases from standard input`() {
        assertEquals(0, run("vault", "init", "--passphrase-stdin", stdin = "old-pass-123\n"))
        assertEquals(0, run("vault", "change-passphrase", "--passphrase-stdin", "--new-passphrase-stdin", stdin = "old-pass-123\nnew-pass-456\n"))
        vault.read("new-pass-456".toCharArray())
        try {
            vault.read("old-pass-123".toCharArray())
            error("the old passphrase must no longer open the vault")
        } catch (_: javax.crypto.AEADBadTagException) {
            // expected: the vault was re-keyed
        }
    }

    // #1038: a vault write that lands while change-passphrase waits for the new passphrase survives the re-key.
    @Test
    fun `vault change-passphrase keeps a vault write made while it read the new passphrase`() {
        assertEquals(0, run("vault", "init", "--passphrase-stdin", stdin = "old-pass-123\n"))
        System.setIn(
            WriteBeforeSecondLine("old-pass-123\nnew-pass-456\n") {
                ConfigFileTransaction.withLock(dir.resolve("config.toml")) {
                    vault.write("old-pass-123".toCharArray(), mapOf("written-meanwhile" to mapOf("k" to "v")))
                }
            },
        )
        assertEquals(0, CommandLine(Main()).execute("-c", dir.toString(), "vault", "change-passphrase", "--passphrase-stdin", "--new-passphrase-stdin"))
        assertEquals(mapOf("k" to "v"), vault.read("new-pass-456".toCharArray())["written-meanwhile"])
    }

    @Test
    fun `vault xtra-set reads both passphrases from standard input and xtra-clear drops the entry`() {
        assertEquals(0, run("vault", "init", "--passphrase-stdin", stdin = "vault-pass-123\n"))
        assertEquals(
            0,
            run("vault", "xtra-set", "--passphrase-stdin", "--xtra-passphrase-stdin", stdin = "vault-pass-123\nxtra-pass-456\n"),
        )
        assertEquals(
            "xtra-pass-456",
            vault.read("vault-pass-123".toCharArray()).getValue(XTRA_VAULT_ENTRY).getValue(XTRA_VAULT_FIELD),
        )
        assertEquals(0, run("vault", "xtra-clear", "--passphrase-stdin", stdin = "vault-pass-123\n"))
        assertEquals(emptyMap<String, Map<String, String>>(), vault.read("vault-pass-123".toCharArray()))
    }

    @Test
    fun `vault xtra-set refuses a passphrase that opens none of the X-tra key files and stores nothing`() {
        org.krost.unidrive.xtra.XtraKeyManager(dir.resolve("xtra.key")).generate("right-xtra-pass".toCharArray())
        assertEquals(0, run("vault", "init", "--passphrase-stdin", stdin = "vault-pass-123\n"))
        assertEquals(
            1,
            run("vault", "xtra-set", "--passphrase-stdin", "--xtra-passphrase-stdin", stdin = "vault-pass-123\nwrong-xtra-pass\n"),
        )
        assertEquals(emptyMap<String, Map<String, String>>(), vault.read("vault-pass-123".toCharArray()))
        assertEquals(
            0,
            run("vault", "xtra-set", "--passphrase-stdin", "--xtra-passphrase-stdin", stdin = "vault-pass-123\nright-xtra-pass\n"),
        )
        assertEquals(
            "right-xtra-pass",
            vault.read("vault-pass-123".toCharArray()).getValue(XTRA_VAULT_ENTRY).getValue(XTRA_VAULT_FIELD),
        )
    }
}
