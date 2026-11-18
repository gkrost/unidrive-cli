package org.krost.unidrive.cli

import org.krost.unidrive.sync.Vault
import picocli.CommandLine
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `vault forget <profile>` (#749 finding 7): drops the vault entry a removed profile left behind (for example when
 * `profile remove` found the vault locked). It never drops the entry of a profile that is still configured: that
 * entry is the only copy of the profile's credentials. Runs against a temp config directory only.
 */
class VaultForgetTest {
    private lateinit var dir: Path
    private val originalIn: java.io.InputStream = System.`in`
    private val originalErr = System.err
    private val err = ByteArrayOutputStream()
    private val pass = "forget-pass-123"

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("unidrive-vault-forget-")
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        Files.writeString(dir.resolve("config.toml"), "[general]\n\n[providers.keep]\ntype = \"localfs\"\nmode = \"mirror\"\n")
        vault.write(pass.toCharArray(), mapOf("gone" to mapOf("password" to "old-secret"), "keep" to mapOf("password" to "keep-secret")))
    }

    @AfterTest
    fun tearDown() {
        System.setIn(originalIn)
        System.setErr(originalErr)
        dir.toFile().deleteRecursively()
    }

    private val vault get() = Vault(dir.resolve("vault.enc"))

    private fun run(vararg args: String): Int {
        System.setIn(ByteArrayInputStream("$pass\n".toByteArray(Charsets.UTF_8)))
        return CommandLine(Main()).execute("-c", dir.toString(), *args)
    }

    @Test
    fun `vault forget drops the entry of a profile that is no longer configured`() {
        assertEquals(0, run("vault", "forget", "gone", "--passphrase-stdin"))

        assertEquals(mapOf("keep" to mapOf("password" to "keep-secret")), vault.read(pass.toCharArray()))
    }

    @Test
    fun `vault forget refuses a profile that is still configured and changes nothing`() {
        assertEquals(1, run("vault", "forget", "keep", "--passphrase-stdin"))

        assertTrue("still configured" in err.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
        assertEquals(setOf("gone", "keep"), vault.read(pass.toCharArray()).keys)
    }

    @Test
    fun `vault forget of a name the vault does not hold changes nothing and succeeds`() {
        assertEquals(0, run("vault", "forget", "never-there", "--passphrase-stdin"))

        assertEquals(setOf("gone", "keep"), vault.read(pass.toCharArray()).keys)
    }
}
