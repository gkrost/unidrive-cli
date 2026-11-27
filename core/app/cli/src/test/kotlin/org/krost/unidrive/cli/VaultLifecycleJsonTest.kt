package org.krost.unidrive.cli

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.krost.unidrive.sync.Vault
import picocli.CommandLine

/**
 * gkrost/unidrive-cli#973: `vault init`, `vault encrypt` and `vault change-passphrase` with `--json` answer one JSON
 * object with a stable `error` token, never prompt, and exit as the human mode does for the same condition.
 */
class VaultLifecycleJsonTest {
    private lateinit var dir: Path
    private val originalIn: InputStream = System.`in`
    private val originalOut: PrintStream = System.out
    private val originalErr: PrintStream = System.err
    private val out = ByteArrayOutputStream()
    private val pass = "vault-pass-123"

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("unidrive-vault-json-")
        System.setOut(PrintStream(out, true, "UTF-8"))
        System.setErr(PrintStream(ByteArrayOutputStream(), true, "UTF-8"))
    }

    @AfterTest
    fun tearDown() {
        System.setIn(originalIn)
        System.setOut(originalOut)
        System.setErr(originalErr)
        dir.toFile().deleteRecursively()
    }

    private class ForbiddenStdin : InputStream() {
        var reads = 0

        override fun read(): Int {
            reads++
            throw AssertionError("standard input was read although the request gave no stdin flag")
        }
    }

    private fun run(
        vararg args: String,
        stdin: String = "",
    ): Int {
        out.reset()
        System.setIn(ByteArrayInputStream(stdin.toByteArray(Charsets.UTF_8)))
        return CommandLine(Main()).execute("-c", dir.toString(), *args)
    }

    private fun runWithForbiddenStdin(vararg args: String): Pair<Int, ForbiddenStdin> {
        out.reset()
        val forbidden = ForbiddenStdin()
        System.setIn(forbidden)
        return CommandLine(Main()).execute("-c", dir.toString(), *args) to forbidden
    }

    private fun stdout(): JsonObject = Json.parseToJsonElement(out.toString(Charsets.UTF_8).trim()).jsonObject

    private fun assertOk() {
        assertEquals(1, out.toString(Charsets.UTF_8).trim().lines().size, "one line on stdout")
        assertEquals(JsonPrimitive(true), stdout()["ok"])
    }

    private fun assertError(token: String) {
        assertEquals(1, out.toString(Charsets.UTF_8).trim().lines().size, "one line on stdout: $out")
        assertEquals(JsonPrimitive(false), stdout()["ok"])
        assertEquals(JsonPrimitive(token), stdout()["error"])
    }

    private val vault get() = Vault(dir.resolve("vault.enc"))

    private fun initVault() = assertEquals(0, run("vault", "init", "--passphrase-stdin", stdin = "$pass\n"))

    private fun writeConfigWithSecret() =
        Files.writeString(
            dir.resolve("config.toml"),
            "[general]\n\n[providers.p1]\ntype = \"s3\"\nmode = \"mirror\"\nbucket = \"b\"\nregion = \"r\"\n" +
                "access_key_id = \"AKIA-PLAIN\"\nsecret_access_key = \"SECRET-PLAIN\"\n",
        )

    // ── init ──

    @Test
    fun `vault init --json succeeds with one JSON object`() {
        assertEquals(0, run("vault", "init", "--json", "--passphrase-stdin", stdin = "$pass\n"))
        assertOk()
        vault.read(pass.toCharArray())
        assertFalse(out.toString().contains(pass))
    }

    @Test
    fun `vault init --json on an existing vault answers vault_exists`() {
        initVault()
        assertEquals(1, run("vault", "init", "--json", "--passphrase-stdin", stdin = "another-pass-456\n"))
        assertError("vault_exists")
        vault.read(pass.toCharArray())
    }

    @Test
    fun `vault init --json with a too short passphrase answers passphrase_too_short`() {
        assertEquals(1, run("vault", "init", "--json", "--passphrase-stdin", stdin = "short\n"))
        assertError("passphrase_too_short")
        assertFalse(vault.exists())
    }

    @Test
    fun `vault init --json with missing stdin input answers invalid_request and never prompts`() {
        val (code, forbidden) = runWithForbiddenStdin("vault", "init", "--json")
        assertEquals(1, code)
        assertError("invalid_request")
        assertEquals(0, forbidden.reads)
        assertEquals(1, run("vault", "init", "--json", "--passphrase-stdin", stdin = ""))
        assertError("invalid_request")
        assertFalse(vault.exists())
    }

    // ── encrypt ──

    @Test
    fun `vault encrypt --json succeeds with one JSON object`() {
        initVault()
        writeConfigWithSecret()
        assertEquals(0, run("vault", "encrypt", "--json", "--passphrase-stdin", stdin = "$pass\n"))
        assertOk()
        assertEquals(JsonPrimitive(1), stdout()["profiles"])
        assertEquals("SECRET-PLAIN", vault.read(pass.toCharArray()).getValue("p1").getValue("secret_access_key"))
        assertFalse(Files.readString(dir.resolve("config.toml")).contains("SECRET-PLAIN"))
    }

    @Test
    fun `vault encrypt --json without a config file succeeds with no profiles like the human mode`() {
        initVault()
        assertEquals(0, run("vault", "encrypt", "--json", "--passphrase-stdin", stdin = "$pass\n"))
        assertOk()
        assertEquals(JsonPrimitive(0), stdout()["profiles"])
    }

    @Test
    fun `vault encrypt --json without a vault answers vault_absent`() {
        assertEquals(1, run("vault", "encrypt", "--json", "--passphrase-stdin", stdin = "$pass\n"))
        assertError("vault_absent")
        assertEquals(1, run("vault", "encrypt", "--json"))
        assertError("vault_absent")
    }

    @Test
    fun `vault encrypt --json with a wrong vault passphrase answers vault_bad_passphrase`() {
        initVault()
        writeConfigWithSecret()
        assertEquals(1, run("vault", "encrypt", "--json", "--passphrase-stdin", stdin = "not-the-vault-pass\n"))
        assertError("vault_bad_passphrase")
        assertTrue(Files.readString(dir.resolve("config.toml")).contains("SECRET-PLAIN"), "config untouched")
    }

    @Test
    fun `vault encrypt --json with missing stdin input answers invalid_request and never prompts`() {
        initVault()
        val (code, forbidden) = runWithForbiddenStdin("vault", "encrypt", "--json")
        assertEquals(1, code)
        assertError("invalid_request")
        assertEquals(0, forbidden.reads)
        assertEquals(1, run("vault", "encrypt", "--json", "--passphrase-stdin", stdin = ""))
        assertError("invalid_request")
    }

    // ── change-passphrase ──

    @Test
    fun `vault change-passphrase --json succeeds with one JSON object`() {
        initVault()
        assertEquals(
            0,
            run("vault", "change-passphrase", "--json", "--passphrase-stdin", "--new-passphrase-stdin", stdin = "$pass\nnew-pass-4567\n"),
        )
        assertOk()
        vault.read("new-pass-4567".toCharArray())
        assertFalse(out.toString().contains("new-pass-4567"))
    }

    @Test
    fun `vault change-passphrase --json without a vault answers vault_absent`() {
        assertEquals(
            1,
            run("vault", "change-passphrase", "--json", "--passphrase-stdin", "--new-passphrase-stdin", stdin = "$pass\nnew-pass-4567\n"),
        )
        assertError("vault_absent")
        assertEquals(1, run("vault", "change-passphrase", "--json"))
        assertError("vault_absent")
    }

    @Test
    fun `vault change-passphrase --json with a wrong vault passphrase answers vault_bad_passphrase`() {
        initVault()
        assertEquals(
            1,
            run("vault", "change-passphrase", "--json", "--passphrase-stdin", "--new-passphrase-stdin", stdin = "not-the-vault-pass\nnew-pass-4567\n"),
        )
        assertError("vault_bad_passphrase")
        vault.read(pass.toCharArray())
    }

    @Test
    fun `vault change-passphrase --json with a too short new passphrase answers passphrase_too_short`() {
        initVault()
        assertEquals(
            1,
            run("vault", "change-passphrase", "--json", "--passphrase-stdin", "--new-passphrase-stdin", stdin = "$pass\nshort\n"),
        )
        assertError("passphrase_too_short")
        vault.read(pass.toCharArray())
    }

    @Test
    fun `vault change-passphrase --json with missing stdin input answers invalid_request and never prompts`() {
        initVault()
        val (code, forbidden) = runWithForbiddenStdin("vault", "change-passphrase", "--json")
        assertEquals(1, code)
        assertError("invalid_request")
        assertEquals(0, forbidden.reads)
        val (code2, forbidden2) = runWithForbiddenStdin("vault", "change-passphrase", "--json", "--passphrase-stdin")
        assertEquals(1, code2)
        assertError("invalid_request")
        assertEquals(0, forbidden2.reads)
        assertEquals(
            1,
            run("vault", "change-passphrase", "--json", "--passphrase-stdin", "--new-passphrase-stdin", stdin = "$pass\n"),
        )
        assertError("invalid_request")
        vault.read(pass.toCharArray())
    }

    // #1038: a vault write that lands while change-passphrase waits for the new passphrase survives the re-key.
    @Test
    fun `vault change-passphrase --json keeps a vault write made while it read the new passphrase`() {
        initVault()
        out.reset()
        System.setIn(
            WriteBeforeSecondLine("$pass\nnew-pass-4567\n") {
                ConfigFileTransaction.withLock(dir.resolve("config.toml")) {
                    vault.write(pass.toCharArray(), vault.read(pass.toCharArray()) + ("written-meanwhile" to mapOf("k" to "v")))
                }
            },
        )
        val code = CommandLine(Main()).execute("-c", dir.toString(), "vault", "change-passphrase", "--json", "--passphrase-stdin", "--new-passphrase-stdin")
        assertEquals(0, code)
        assertOk()
        assertEquals(mapOf("k" to "v"), vault.read("new-pass-4567".toCharArray())["written-meanwhile"])
    }
}
