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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.krost.unidrive.sync.Vault
import org.krost.unidrive.xtra.XtraKeyManager
import picocli.CommandLine

/**
 * gkrost/unidrive-cli#973: `vault xtra-set --json` and `vault xtra-clear --json` answer one JSON object on stdout
 * with a stable `error` token, never prompt, and exit as the human mode does for the same condition.
 */
class VaultXtraJsonTest {
    private lateinit var dir: Path
    private val originalIn: InputStream = System.`in`
    private val originalOut: PrintStream = System.out
    private val originalErr: PrintStream = System.err
    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()
    private val vaultPass = "vault-pass-123"

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("unidrive-xtra-json-")
        System.setOut(PrintStream(out, true, "UTF-8"))
        System.setErr(PrintStream(err, true, "UTF-8"))
    }

    @AfterTest
    fun tearDown() {
        System.setIn(originalIn)
        System.setOut(originalOut)
        System.setErr(originalErr)
        dir.toFile().deleteRecursively()
    }

    /** Standard input that fails the test when anything reads it: a prompt or an unrequested read. */
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
        err.reset()
        System.setIn(ByteArrayInputStream(stdin.toByteArray(Charsets.UTF_8)))
        return CommandLine(Main()).execute("-c", dir.toString(), *args)
    }

    private fun stdout(): JsonObject = Json.parseToJsonElement(out.toString(Charsets.UTF_8).trim()).jsonObject

    private fun assertSingleObject() {
        val text = out.toString(Charsets.UTF_8)
        assertEquals(1, text.trim().lines().size, "exactly one line on stdout: $text")
        assertTrue(text.trim().startsWith("{") && text.trim().endsWith("}"), text)
    }

    private fun initVault() = assertEquals(0, run("vault", "init", "--passphrase-stdin", stdin = "$vaultPass\n"))

    private val vault get() = Vault(dir.resolve("vault.enc"))

    private fun assertError(token: String) {
        assertSingleObject()
        val obj = stdout()
        assertEquals(JsonPrimitive(false), obj["ok"])
        assertEquals(JsonPrimitive(token), obj["error"])
    }

    @Test
    fun `vault xtra-set --json succeeds with one JSON object`() {
        initVault()
        assertEquals(
            0,
            run("vault", "xtra-set", "--json", "--passphrase-stdin", "--xtra-passphrase-stdin", stdin = "$vaultPass\nxtra-pass-456\n"),
        )
        assertSingleObject()
        val obj = stdout()
        assertEquals(JsonPrimitive(true), obj["ok"])
        assertEquals(JsonArray(emptyList()), obj["key_files_not_under_passphrase"])
        assertEquals("xtra-pass-456", vault.read(vaultPass.toCharArray()).getValue(XTRA_VAULT_ENTRY).getValue(XTRA_VAULT_FIELD))
        assertFalse(out.toString().contains("xtra-pass-456"), "the passphrase is never echoed")
    }

    @Test
    fun `vault xtra-set --json with a passphrase that opens no key file answers xtra_passphrase_mismatch`() {
        XtraKeyManager(dir.resolve("xtra.key")).generate("right-xtra-pass".toCharArray())
        initVault()
        assertEquals(
            1,
            run("vault", "xtra-set", "--json", "--passphrase-stdin", "--xtra-passphrase-stdin", stdin = "$vaultPass\nwrong-xtra-pass\n"),
        )
        assertError("xtra_passphrase_mismatch")
        assertEquals(emptyMap<String, Map<String, String>>(), vault.read(vaultPass.toCharArray()), "nothing was stored")
    }

    @Test
    fun `vault xtra-set --json with a wrong vault passphrase answers vault_bad_passphrase`() {
        initVault()
        assertEquals(
            1,
            run("vault", "xtra-set", "--json", "--passphrase-stdin", "--xtra-passphrase-stdin", stdin = "not-the-vault-pass\nxtra-pass-456\n"),
        )
        assertError("vault_bad_passphrase")
        assertEquals(emptyMap<String, Map<String, String>>(), vault.read(vaultPass.toCharArray()))
    }

    @Test
    fun `vault xtra-set --json without a vault answers vault_absent`() {
        assertEquals(
            1,
            run("vault", "xtra-set", "--json", "--passphrase-stdin", "--xtra-passphrase-stdin", stdin = "$vaultPass\nxtra-pass-456\n"),
        )
        assertError("vault_absent")
        assertFalse(Files.exists(dir.resolve("vault.enc")))
    }

    @Test
    fun `vault xtra-set --json with missing stdin input answers invalid_request and never prompts`() {
        initVault()
        // No stdin flags at all: the request is refused before anything is read.
        val forbidden = ForbiddenStdin()
        out.reset()
        System.setIn(forbidden)
        assertEquals(1, CommandLine(Main()).execute("-c", dir.toString(), "vault", "xtra-set", "--json"))
        assertError("invalid_request")
        assertEquals(0, forbidden.reads)
        // Only the vault passphrase flag: the X-tra passphrase has no source.
        assertEquals(1, run("vault", "xtra-set", "--json", "--passphrase-stdin", stdin = "$vaultPass\n"))
        assertError("invalid_request")
        // Both flags, but standard input is empty or holds only the first line.
        assertEquals(1, run("vault", "xtra-set", "--json", "--passphrase-stdin", "--xtra-passphrase-stdin", stdin = ""))
        assertError("invalid_request")
        assertEquals(1, run("vault", "xtra-set", "--json", "--passphrase-stdin", "--xtra-passphrase-stdin", stdin = "$vaultPass\n"))
        assertError("invalid_request")
        assertEquals(emptyMap<String, Map<String, String>>(), vault.read(vaultPass.toCharArray()))
    }

    @Test
    fun `vault xtra-clear --json succeeds with one JSON object and says whether there was an entry`() {
        initVault()
        assertEquals(0, run("vault", "xtra-set", "--passphrase-stdin", "--xtra-passphrase-stdin", stdin = "$vaultPass\nxtra-pass-456\n"))
        assertEquals(0, run("vault", "xtra-clear", "--json", "--passphrase-stdin", stdin = "$vaultPass\n"))
        assertSingleObject()
        assertEquals(JsonPrimitive(true), stdout()["ok"])
        assertEquals(JsonPrimitive(true), stdout()["removed"])
        assertEquals(emptyMap<String, Map<String, String>>(), vault.read(vaultPass.toCharArray()))
        assertEquals(0, run("vault", "xtra-clear", "--json", "--passphrase-stdin", stdin = "$vaultPass\n"))
        assertEquals(JsonPrimitive(false), stdout()["removed"])
    }

    @Test
    fun `vault xtra-clear --json with a wrong vault passphrase answers vault_bad_passphrase`() {
        initVault()
        assertEquals(1, run("vault", "xtra-clear", "--json", "--passphrase-stdin", stdin = "not-the-vault-pass\n"))
        assertError("vault_bad_passphrase")
    }

    @Test
    fun `vault xtra-clear --json without a vault answers vault_absent`() {
        assertEquals(1, run("vault", "xtra-clear", "--json", "--passphrase-stdin", stdin = "$vaultPass\n"))
        assertError("vault_absent")
    }

    @Test
    fun `vault xtra-clear --json with missing stdin input answers invalid_request and never prompts`() {
        initVault()
        val forbidden = ForbiddenStdin()
        out.reset()
        System.setIn(forbidden)
        assertEquals(1, CommandLine(Main()).execute("-c", dir.toString(), "vault", "xtra-clear", "--json"))
        assertError("invalid_request")
        assertEquals(0, forbidden.reads)
        assertEquals(1, run("vault", "xtra-clear", "--json", "--passphrase-stdin", stdin = ""))
        assertError("invalid_request")
    }

    // Human-mode order (what a person would see): no vault, then the key files, then the vault passphrase at the write.

    @Test
    fun `vault xtra-set --json without a vault and without stdin flags answers vault_absent like the human mode`() {
        assertEquals(1, run("vault", "xtra-set", "--json"))
        assertError("vault_absent")
    }

    @Test
    fun `vault xtra-clear --json without a vault and without stdin flags answers vault_absent like the human mode`() {
        assertEquals(1, run("vault", "xtra-clear", "--json"))
        assertError("vault_absent")
    }

    @Test
    fun `vault xtra-set --json with a wrong vault passphrase and a mismatching X-tra passphrase answers xtra_passphrase_mismatch like the human mode`() {
        XtraKeyManager(dir.resolve("xtra.key")).generate("right-xtra-pass".toCharArray())
        initVault()
        assertEquals(
            1,
            run("vault", "xtra-set", "--json", "--passphrase-stdin", "--xtra-passphrase-stdin", stdin = "not-the-vault-pass\nwrong-xtra-pass\n"),
        )
        assertError("xtra_passphrase_mismatch")
    }
}
