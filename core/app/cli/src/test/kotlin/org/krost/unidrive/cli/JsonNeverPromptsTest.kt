package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.krost.unidrive.sync.Vault
import org.krost.unidrive.testing.TestScratch
import picocli.CommandLine
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #845 (item #749-6): a `--json` command never reads the console and always answers with one JSON object.
 * One test per invariant: (1) no console read in `--json` mode, (2) a JSON object whatever escapes the command,
 * (3) the vault refuses typed when no passphrase source answers, (4) human mode still prompts.
 */
class JsonNeverPromptsTest {
    private lateinit var dir: Path
    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()
    private val originalOut = System.out
    private val originalErr = System.err
    private val originalIn = System.`in`
    private val vaultPass = "synthetic-vault-pass"
    private var consoleReads = 0
    private var stdinReads = 0

    @BeforeTest
    fun setUp() {
        dir = TestScratch.directory("json-never-prompts")
        val remote = Files.createDirectories(dir.resolve("remote"))
        val local = Files.createDirectories(dir.resolve("local"))
        Files.writeString(
            dir.resolve("config.toml"),
            """
            [providers.test]
            type = "localfs"
            sync_root = "${local.toString().replace('\\', '/')}"
            root_path = "${remote.toString().replace('\\', '/')}"
            """.trimIndent() + "\n",
        )
        Vault(dir.resolve("vault.enc")).write(vaultPass.toCharArray(), mapOf("test" to mapOf("password" to "synthetic")))
        System.setOut(PrintStream(out, true, Charsets.UTF_8))
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        System.setIn(
            object : InputStream() {
                override fun read(): Int {
                    stdinReads++
                    return -1
                }
            },
        )
    }

    @AfterTest
    fun tearDown() {
        System.setOut(originalOut)
        System.setErr(originalErr)
        System.setIn(originalIn)
        dir.toFile().deleteRecursively()
    }

    /** A console that is present and answers [answer]; every read is counted. */
    private fun fakeConsole(answer: String): () -> PassphraseSources =
        {
            PassphraseSources(
                env = { null },
                consolePresent = { true },
                consoleRead = {
                    consoleReads++
                    answer.toCharArray()
                },
            )
        }

    private fun newMain(console: String = vaultPass) =
        Main().apply {
            configDir = dir.toString()
            provider = "test"
            passphraseSourcesFactory = fakeConsole(console)
        }

    private fun reply(): JsonObject {
        val lines = out.toString(Charsets.UTF_8).trim().lines()
        assertEquals(1, lines.size, "stdout carries one line: ${out.toString(Charsets.UTF_8)}")
        return Json.parseToJsonElement(lines.single()).jsonObject
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    @Test
    fun `json_command_whose_provider_construction_would_prompt_prints_json_error_without_reading_console`() {
        val main = newMain()
        val exit =
            CommandLine(main)
                .also { it.executionExceptionHandler = cliExecutionExceptionHandler }
                .execute("-c", dir.toString(), "-p", "test", "trash", "list", "--remote", "--json")
        assertEquals(0, consoleReads, "a --json run never reads the console")
        assertEquals(0, stdinReads, "a --json run never reads stdin")
        assertEquals(1, exit)
        val json = reply()
        assertEquals("false", json.str("ok"))
        assertEquals("vault_passphrase_required", json.str("error"))
    }

    @Test
    fun `json_command_hitting_an_unexpected_exception_still_prints_a_json_error_object`() {
        val cmd = CommandLine(Main())
        val parsed = cmd.parseArgs("trash", "list", "--remote", "--json")
        val exit = cliExecutionExceptionHandler.handleExecutionException(IllegalStateException("boom: secret-detail"), cmd, parsed)
        assertEquals(1, exit)
        val json = reply()
        assertEquals("false", json.str("ok"))
        assertEquals("internal_error", json.str("error"))
        assertFalse(json.toString().contains("secret-detail"), "the message of an unexpected exception is never echoed")
    }

    @Test
    fun `vault_backed_profile_with_json_and_no_passphrase_source_fails_typed_without_prompting`() {
        val main = newMain().apply { nonInteractive = true }
        val e = assertFailsWith<LifecycleError> { main.createProvider() }
        assertEquals("vault_passphrase_required", e.token)
        assertEquals(0, consoleReads)
    }

    @Test
    fun `human_mode_still_prompts_for_the_vault_passphrase`() {
        val main = newMain().apply { nonInteractive = false }
        main.createProvider()
        assertEquals(1, consoleReads, "without --json the passphrase is asked for on the console")
        assertTrue(err.toByteArray().isEmpty() || !err.toString(Charsets.UTF_8).contains("Error"))
    }

    @Test
    fun `human_mode_error_path_is_unchanged_for_an_unexpected_exception`() {
        val cmd = CommandLine(Main())
        val parsed = cmd.parseArgs("trash", "list", "--remote")
        val exit = cliExecutionExceptionHandler.handleExecutionException(IllegalStateException("boom"), cmd, parsed)
        assertEquals(1, exit)
        assertEquals("", out.toString(Charsets.UTF_8))
        assertTrue(err.toString(Charsets.UTF_8).contains("Error: boom"))
    }
}
