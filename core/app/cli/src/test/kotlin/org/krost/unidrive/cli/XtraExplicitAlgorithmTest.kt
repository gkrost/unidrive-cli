package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.io.XTRA_PASSPHRASE_ENV
import org.krost.unidrive.sync.SyncConfig
import org.krost.unidrive.testing.TestScratch
import org.krost.unidrive.xtra.XtraKeyManager
import org.krost.unidrive.xtra.XtraSymmetricKeyStore
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Owner decision on #1006 (review of the symmetric default): a profile's algorithm is explicit. Turning encryption on
 * for a new profile and `xtra init` write `xtra_algorithm = "aes-gcm-sym"` into the profile's section; a profile that
 * already runs encrypted without the key keeps reading as aes-gcm, the old default, and is never rewritten; and no key
 * file created later switches any profile.
 */
class XtraExplicitAlgorithmTest {
    private val pass = "explicit-pass-1234"
    private val dir: Path = TestScratch.directory("xtra-explicit")
    private val config: Path get() = dir.resolve("config.toml")
    private val out = ByteArrayOutputStream()
    private val originalOut = System.out

    @AfterTest
    fun tearDown() {
        System.setOut(originalOut)
        dir.toFile().deleteRecursively()
    }

    private fun section(extra: String = "") =
        "[providers.work]\ntype = \"localfs\"\nmode = \"mount\"\nroot_path = \"/tmp/work\"\n$extra"

    private fun run(vararg args: String): Int {
        out.reset()
        System.setOut(PrintStream(out, true, Charsets.UTF_8))
        val main = Main().also { m ->
            m.passphraseSourcesFactory = {
                PassphraseSources(env = { if (it == XTRA_PASSPHRASE_ENV) pass else null }, consolePresent = { false })
            }
        }
        val code = CommandLine(main).execute("-c", dir.toString(), *args)
        System.out.flush()
        System.setOut(originalOut)
        return code
    }

    private fun reply(): JsonObject = Json.parseToJsonElement(out.toString(Charsets.UTF_8).trim()).jsonObject

    private fun listedAlgorithm(): String {
        assertEquals(0, run("profile", "list", "--json"))
        return reply().getValue("profiles").jsonArray
            .first { it.jsonObject.getValue("name").jsonPrimitive.content == "work" }
            .jsonObject.getValue("xtra_algorithm").jsonPrimitive.content
    }

    private fun storedAlgorithm(): String? = SyncConfig.parseRaw(Files.readString(config)).providers.getValue("work").xtra_algorithm

    @Test
    fun `profile set xtra_encryption true on a new profile writes xtra_algorithm aes-gcm-sym`() {
        Files.writeString(config, section())
        XtraSymmetricKeyStore(dir.resolve("xtra-sym.key")).generate(pass.toCharArray())

        assertEquals(0, run("profile", "set", "work", "xtra_encryption", "true", "--json"), out.toString(Charsets.UTF_8))
        assertEquals(section("xtra_encryption = true\nxtra_algorithm = \"aes-gcm-sym\"\n"), Files.readString(config))
        assertEquals("aes-gcm-sym", listedAlgorithm())
    }

    @Test
    fun `turning encryption on for a new profile needs the symmetric key, the RSA key alone does not do`() {
        Files.writeString(config, section())
        Files.writeString(dir.resolve("xtra.key"), "k")

        assertEquals(1, run("profile", "set", "work", "xtra_encryption", "true", "--json"))
        assertEquals("key_missing", reply().getValue("error").jsonPrimitive.content)
        assertTrue(reply().getValue("message").jsonPrimitive.content.contains("xtra-sym.key"))
        assertEquals(section(), Files.readString(config), "a refused edit writes nothing")
    }

    @Test
    fun `creating xtra key after an aes-gcm-sym profile exists does not change that profile's algorithm`() {
        Files.writeString(config, section())
        XtraSymmetricKeyStore(dir.resolve("xtra-sym.key")).generate(pass.toCharArray())
        assertEquals(0, run("profile", "set", "work", "xtra_encryption", "true", "--json"))

        XtraKeyManager(dir.resolve("xtra.key")).generate(pass.toCharArray())
        assertEquals("aes-gcm-sym", listedAlgorithm())
        assertEquals("aes-gcm-sym", storedAlgorithm())
    }

    @Test
    fun `an existing encrypted profile without xtra_algorithm still reads as aes-gcm and is never rewritten`() {
        val existing = section("xtra_encryption = true\n")
        Files.writeString(config, existing)
        // Whatever key files lie beside it, the unset profile is aes-gcm.
        XtraSymmetricKeyStore(dir.resolve("xtra-sym.key")).generate(pass.toCharArray())
        assertEquals("aes-gcm", listedAlgorithm())

        Files.writeString(dir.resolve("xtra.key"), "k")
        assertEquals(0, run("profile", "set", "work", "xtra_encryption", "true", "--json"))
        assertEquals(existing, Files.readString(config), "setting the value in force writes no algorithm")
        Files.delete(dir.resolve("xtra-sym.key"))
        assertEquals(0, run("xtra", "init"), "a new key file for the installation")
        assertEquals(existing, Files.readString(config), "xtra init never rewrites an encrypted profile")
        assertEquals("aes-gcm", listedAlgorithm())
    }

    @Test
    fun `xtra init writes the algorithm it created into the profile's section`() {
        Files.writeString(config, section("label = \"Work\"\n"))

        assertEquals(0, run("xtra", "init"))
        assertTrue(out.toString(Charsets.UTF_8).contains("xtra_algorithm = \"aes-gcm-sym\""), out.toString(Charsets.UTF_8))
        assertEquals(section("label = \"Work\"\nxtra_algorithm = \"aes-gcm-sym\"\n"), Files.readString(config))
        assertEquals(0, run("profile", "set", "work", "xtra_encryption", "true", "--json"))
        assertEquals("aes-gcm-sym", storedAlgorithm())
    }

    @Test
    fun `xtra init leaves a profile that names its algorithm alone`() {
        Files.writeString(config, section("xtra_algorithm = \"tink-aes-gcm\"\n"))
        assertEquals(0, run("xtra", "init"))
        assertEquals("tink-aes-gcm", storedAlgorithm())
        assertFalse(out.toString(Charsets.UTF_8).contains("xtra_algorithm = \"aes-gcm-sym\" on profile"))
    }

    @Test
    fun `an unset profile is aes-gcm whatever key files exist`() {
        Files.writeString(config, section())
        assertEquals("aes-gcm", listedAlgorithm())
        XtraSymmetricKeyStore(dir.resolve("xtra-sym.key")).generate(pass.toCharArray())
        assertEquals("aes-gcm", listedAlgorithm())
    }

    // ── Review round ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `import-key on a clean installation restores an RSA backup or fails, never exit 0 with nothing imported`() {
        val backup = TestScratch.directory("xtra-explicit-rsa-backup")
        XtraKeyManager(backup.resolve("xtra.key")).generate(pass.toCharArray())

        val code = run("xtra", "import-key", "--input", backup.toString())
        assertEquals(0, code, "a valid RSA backup restores")
        assertTrue(Files.exists(dir.resolve("xtra.key")), "the RSA key file was restored")
        assertFalse(Files.exists(dir.resolve("xtra-sym.key")))
    }

    @Test
    fun `import-key with nothing to import exits non-zero`() {
        val empty = TestScratch.directory("xtra-explicit-empty-backup")
        assertEquals(1, run("xtra", "import-key", "--input", empty.toString()))
    }

    @Test
    fun `xtra init with -p writes the named profile and never the default one`() {
        Files.writeString(
            config,
            "[general]\ndefault_profile = \"work\"\n\n" + section() + "\n[providers.home]\ntype = \"localfs\"\nroot_path = \"/tmp/home\"\n",
        )
        assertEquals(0, run("-p", "home", "xtra", "init"))
        val raw = SyncConfig.parseRaw(Files.readString(config)).providers
        assertEquals("aes-gcm-sym", raw.getValue("home").xtra_algorithm)
        assertEquals(null, raw.getValue("work").xtra_algorithm, "the default profile is not the one -p named")
    }

    @Test
    fun `xtra init with a -p that names no profile writes no profile`() {
        Files.writeString(config, "[general]\ndefault_profile = \"work\"\n\n" + section())
        run("-p", "nosuch", "xtra", "init")
        assertEquals(null, storedAlgorithm(), "a -p that names no profile must not fall back to the default profile")
    }

    @Test
    fun `xtra init refuses to name the algorithm of a profile with sync state unless forced`() {
        Files.writeString(config, section())
        Files.createDirectories(dir.resolve("work"))
        Files.write(dir.resolve("work").resolve("state.db"), byteArrayOf(1, 2, 3))

        assertEquals(1, run("xtra", "init"))
        assertEquals(null, storedAlgorithm(), "refused: the profile's algorithm is unchanged")
        assertFalse(Files.exists(dir.resolve("xtra-sym.key")), "a refused init creates no key")

        assertEquals(0, run("xtra", "init", "--force"))
        assertEquals("aes-gcm-sym", storedAlgorithm())
    }
}
