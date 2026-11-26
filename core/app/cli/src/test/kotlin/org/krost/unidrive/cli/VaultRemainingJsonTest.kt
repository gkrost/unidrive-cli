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
import picocli.CommandLine

/**
 * gkrost/unidrive-cli#973, owner decision 2026-11-26 ("--json for all vault subcommands"): `vault decrypt`,
 * `vault forget` and `vault seal-credentials` with `--json` answer one JSON object with a stable `error` token, never
 * prompt, never print a secret, and exit as the human mode does for the same condition.
 */
class VaultRemainingJsonTest {
    private lateinit var dir: Path
    private val originalIn: InputStream = System.`in`
    private val originalOut: PrintStream = System.out
    private val originalErr: PrintStream = System.err
    private val out = ByteArrayOutputStream()
    private val pass = "vault-pass-123"
    private val xtra = "xtra-pass-456"

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("unidrive-vault-rest-json-")
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
        assertEquals(1, out.toString(Charsets.UTF_8).trim().lines().size, "one line on stdout: $out")
        assertEquals(JsonPrimitive(true), stdout()["ok"])
    }

    private fun assertError(token: String) {
        assertEquals(1, out.toString(Charsets.UTF_8).trim().lines().size, "one line on stdout: $out")
        assertEquals(JsonPrimitive(false), stdout()["ok"])
        assertEquals(JsonPrimitive(token), stdout()["error"])
    }

    private val vault get() = Vault(dir.resolve("vault.enc"))

    private fun write(data: Map<String, Map<String, String>>) = vault.write(pass.toCharArray(), data)

    private val orphan = mapOf("gone" to mapOf("password" to "gone-secret"))

    private fun configWith(vararg profiles: String) =
        Files.writeString(
            dir.resolve("config.toml"),
            "[general]\n\n" + profiles.joinToString("\n") { "[providers.$it]\ntype = \"s3\"\nmode = \"mirror\"\nbucket = \"b\"\nregion = \"r\"\n" },
        )

    // ── decrypt ──

    @Test
    fun `vault decrypt --json succeeds with one JSON object and lists the orphan entries`() {
        configWith("work")
        write(mapOf("work" to mapOf("password" to "work-secret")) + orphan)
        assertEquals(0, run("vault", "decrypt", "--json", "--passphrase-stdin", stdin = "$pass\n"))
        assertOk()
        assertEquals(JsonPrimitive(1), stdout()["restored_profiles"])
        assertEquals(JsonArray(listOf(JsonPrimitive("gone"))), stdout()["orphans"])
        assertEquals(JsonPrimitive(false), stdout()["vault_deleted"])
        assertTrue(Files.readString(dir.resolve("config.toml")).contains("work-secret"))
        assertEquals(setOf("gone"), vault.read(pass.toCharArray()).keys)
    }

    @Test
    fun `vault decrypt --json that empties the vault reports vault_deleted`() {
        configWith("work")
        write(mapOf("work" to mapOf("password" to "work-secret")))
        assertEquals(0, run("vault", "decrypt", "--json", "--passphrase-stdin", stdin = "$pass\n"))
        assertOk()
        assertEquals(JsonPrimitive(true), stdout()["vault_deleted"])
        assertFalse(vault.exists())
    }

    @Test
    fun `vault decrypt --json without a vault answers vault_absent`() {
        assertEquals(1, run("vault", "decrypt", "--json", "--passphrase-stdin", stdin = "$pass\n"))
        assertError("vault_absent")
        assertEquals(1, run("vault", "decrypt", "--json"))
        assertError("vault_absent")
    }

    @Test
    fun `vault decrypt --json with a wrong vault passphrase answers vault_bad_passphrase`() {
        configWith("work")
        write(mapOf("work" to mapOf("password" to "work-secret")))
        assertEquals(1, run("vault", "decrypt", "--json", "--passphrase-stdin", stdin = "not-the-vault-pass\n"))
        assertError("vault_bad_passphrase")
        assertTrue(vault.exists())
    }

    @Test
    fun `vault decrypt --json with missing stdin input answers invalid_request and never prompts`() {
        write(emptyMap())
        val (code, forbidden) = runWithForbiddenStdin("vault", "decrypt", "--json")
        assertEquals(1, code)
        assertError("invalid_request")
        assertEquals(0, forbidden.reads)
        assertEquals(1, run("vault", "decrypt", "--json", "--passphrase-stdin", stdin = ""))
        assertError("invalid_request")
        assertTrue(vault.exists())
    }

    // ── forget ──

    @Test
    fun `vault forget --json reports what forget did and changes nothing else`() {
        configWith("work")
        write(orphan + mapOf("work" to mapOf("password" to "w"), XTRA_VAULT_ENTRY to mapOf(XTRA_VAULT_FIELD to xtra)))
        assertEquals(0, run("vault", "forget", "gone", "--json", "--passphrase-stdin", stdin = "$pass\n"))
        assertOk()
        assertEquals(JsonPrimitive("gone"), stdout()["profile"])
        assertEquals(JsonPrimitive(true), stdout()["removed"])
        assertEquals(setOf("work", XTRA_VAULT_ENTRY), vault.read(pass.toCharArray()).keys, "only the named entry went")
        assertEquals(0, run("vault", "forget", "gone", "--json", "--passphrase-stdin", stdin = "$pass\n"))
        assertEquals(JsonPrimitive(false), stdout()["removed"])
        assertEquals(setOf("work", XTRA_VAULT_ENTRY), vault.read(pass.toCharArray()).keys)
    }

    @Test
    fun `vault forget --json of a configured profile answers profile_configured and drops nothing`() {
        configWith("work")
        write(mapOf("work" to mapOf("password" to "w")))
        assertEquals(1, run("vault", "forget", "work", "--json", "--passphrase-stdin", stdin = "$pass\n"))
        assertError("profile_configured")
        assertEquals(setOf("work"), vault.read(pass.toCharArray()).keys)
    }

    @Test
    fun `vault forget --json of a reserved entry answers reserved_entry and drops nothing`() {
        write(mapOf(XTRA_VAULT_ENTRY to mapOf(XTRA_VAULT_FIELD to xtra)))
        assertEquals(1, run("vault", "forget", XTRA_VAULT_ENTRY, "--json", "--passphrase-stdin", stdin = "$pass\n"))
        assertError("reserved_entry")
        assertEquals(setOf(XTRA_VAULT_ENTRY), vault.read(pass.toCharArray()).keys)
    }

    @Test
    fun `vault forget --json without a vault answers vault_absent`() {
        assertEquals(1, run("vault", "forget", "gone", "--json", "--passphrase-stdin", stdin = "$pass\n"))
        assertError("vault_absent")
    }

    @Test
    fun `vault forget --json with a wrong vault passphrase answers vault_bad_passphrase`() {
        write(orphan)
        assertEquals(1, run("vault", "forget", "gone", "--json", "--passphrase-stdin", stdin = "not-the-vault-pass\n"))
        assertError("vault_bad_passphrase")
        assertEquals(setOf("gone"), vault.read(pass.toCharArray()).keys)
    }

    @Test
    fun `vault forget --json with missing stdin input answers invalid_request and never prompts`() {
        write(orphan)
        val (code, forbidden) = runWithForbiddenStdin("vault", "forget", "gone", "--json")
        assertEquals(1, code)
        assertError("invalid_request")
        assertEquals(0, forbidden.reads)
        assertEquals(1, run("vault", "forget", "gone", "--json", "--passphrase-stdin", stdin = ""))
        assertError("invalid_request")
        assertEquals(setOf("gone"), vault.read(pass.toCharArray()).keys)
    }

    // ── seal-credentials ──

    @Test
    fun `vault seal-credentials --json succeeds with one JSON object`() {
        write(emptyMap())
        assertEquals(0, run("vault", "seal-credentials", "on", "--json", "--passphrase-stdin", stdin = "$pass\n"))
        assertOk()
        assertEquals(JsonPrimitive("on"), stdout()["sealing"])
        assertEquals("true", vault.read(pass.toCharArray()).getValue(CREDENTIAL_SEAL_SECTION)["enabled"])
        assertEquals(0, run("vault", "seal-credentials", "off", "--json", "--passphrase-stdin", stdin = "$pass\n"))
        assertEquals(JsonPrimitive("off"), stdout()["sealing"])
        assertEquals("false", vault.read(pass.toCharArray()).getValue(CREDENTIAL_SEAL_SECTION)["enabled"])
    }

    @Test
    fun `vault seal-credentials --json with a state other than on or off answers invalid_request with the human exit code 2`() {
        write(emptyMap())
        assertEquals(2, run("vault", "seal-credentials", "maybe", "--json", "--passphrase-stdin", stdin = "$pass\n"))
        assertError("invalid_request")
    }

    @Test
    fun `vault seal-credentials --json without a vault answers vault_absent`() {
        assertEquals(1, run("vault", "seal-credentials", "on", "--json", "--passphrase-stdin", stdin = "$pass\n"))
        assertError("vault_absent")
    }

    @Test
    fun `vault seal-credentials --json with a wrong vault passphrase answers vault_bad_passphrase`() {
        write(emptyMap())
        assertEquals(1, run("vault", "seal-credentials", "on", "--json", "--passphrase-stdin", stdin = "not-the-vault-pass\n"))
        assertError("vault_bad_passphrase")
        assertEquals(emptyMap<String, Map<String, String>>(), vault.read(pass.toCharArray()))
    }

    @Test
    fun `vault seal-credentials --json with missing stdin input answers invalid_request and never prompts`() {
        write(emptyMap())
        val (code, forbidden) = runWithForbiddenStdin("vault", "seal-credentials", "on", "--json")
        assertEquals(1, code)
        assertError("invalid_request")
        assertEquals(0, forbidden.reads)
        assertEquals(1, run("vault", "seal-credentials", "on", "--json", "--passphrase-stdin", stdin = ""))
        assertError("invalid_request")
    }

    // ── no secret in any answer ──

    @Test
    fun `no vault --json answer ever carries a passphrase or a credential`() {
        configWith("work")
        val secrets = listOf(pass, "not-the-vault-pass", "new-pass-4567", xtra, "work-secret", "gone-secret")
        val answers = mutableListOf<String>()

        fun record(
            vararg args: String,
            stdin: String,
        ) {
            run(*args, stdin = stdin)
            answers += out.toString(Charsets.UTF_8)
        }
        write(mapOf("work" to mapOf("password" to "work-secret")) + orphan + mapOf(XTRA_VAULT_ENTRY to mapOf(XTRA_VAULT_FIELD to xtra)))
        record("vault", "decrypt", "--json", "--passphrase-stdin", stdin = "not-the-vault-pass\n")
        record("vault", "forget", "gone", "--json", "--passphrase-stdin", stdin = "not-the-vault-pass\n")
        record("vault", "seal-credentials", "on", "--json", "--passphrase-stdin", stdin = "not-the-vault-pass\n")
        record("vault", "change-passphrase", "--json", "--passphrase-stdin", "--new-passphrase-stdin", stdin = "$pass\nnew-pass-4567\n")
        record("vault", "change-passphrase", "--json", "--passphrase-stdin", "--new-passphrase-stdin", stdin = "new-pass-4567\n$pass\n")
        record("vault", "xtra-set", "--json", "--passphrase-stdin", "--xtra-passphrase-stdin", stdin = "new-pass-4567\n$xtra\n")
        record("vault", "xtra-clear", "--json", "--passphrase-stdin", stdin = "new-pass-4567\n")
        record("vault", "forget", "gone", "--json", "--passphrase-stdin", stdin = "new-pass-4567\n")
        record("vault", "seal-credentials", "off", "--json", "--passphrase-stdin", stdin = "new-pass-4567\n")
        record("vault", "decrypt", "--json", "--passphrase-stdin", stdin = "new-pass-4567\n")
        assertTrue(answers.all { it.trim().startsWith("{") }, answers.toString())
        for (answer in answers) for (secret in secrets) assertFalse(answer.contains(secret), "'$secret' leaked into: $answer")
    }
}
