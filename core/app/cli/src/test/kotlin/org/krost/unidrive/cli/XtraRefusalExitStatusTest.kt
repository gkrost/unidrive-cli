package org.krost.unidrive.cli

import org.krost.unidrive.io.XTRA_PASSPHRASE_ENV
import org.krost.unidrive.testing.TestScratch
import org.krost.unidrive.xtra.XtraAgeKeyStore
import org.krost.unidrive.xtra.XtraSymmetricKeyStore
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A refusal of an X-tra key verb ends non-zero, like the vault verbs: `xtra export-key --output X && echo ok` must not
 * report a backup that was never written (the export is the one file that decrypts everything).
 */
class XtraRefusalExitStatusTest {
    private val pass = "refusal-pass-1234"
    private val dir: Path = TestScratch.directory("xtra-refusal")
    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()
    private val originalOut = System.out
    private val originalErr = System.err

    @AfterTest
    fun tearDown() {
        System.setOut(originalOut)
        System.setErr(originalErr)
        dir.toFile().deleteRecursively()
    }

    private fun run(vararg args: String): Int {
        out.reset()
        err.reset()
        System.setOut(PrintStream(out, true, Charsets.UTF_8))
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        val main = Main().also { m ->
            m.passphraseSourcesFactory = {
                PassphraseSources(env = { if (it == XTRA_PASSPHRASE_ENV) pass else null }, consolePresent = { false })
            }
        }
        val code = CommandLine(main).execute("-c", dir.toString(), *args)
        System.out.flush()
        System.err.flush()
        System.setOut(originalOut)
        System.setErr(originalErr)
        return code
    }

    private fun errText(): String = err.toString(Charsets.UTF_8)

    private fun backupDir(): Path = TestScratch.directory("xtra-refusal-backup")

    @Test
    fun `export-key with no key exits non-zero and writes nothing`() {
        val backup = backupDir()
        assertEquals(1, run("xtra", "export-key", "--output", backup.toString()), errText())
        assertTrue(errText().contains("No key found"), errText())
        assertTrue(!Files.exists(backup.resolve("xtra-sym.key")))
    }

    @Test
    fun `export-key onto an existing file exits non-zero and leaves that file`() {
        XtraSymmetricKeyStore(dir.resolve("xtra-sym.key")).generate(pass.toCharArray())
        val backup = backupDir()
        Files.writeString(backup.resolve("xtra-sym.key"), "stale")

        assertEquals(1, run("xtra", "export-key", "--output", backup.toString()), errText())
        assertTrue(errText().contains("already exists"), errText())
        assertEquals("stale", Files.readString(backup.resolve("xtra-sym.key")))
    }

    @Test
    fun `export-age with no identity exits non-zero and writes nothing`() {
        val backup = backupDir()
        assertEquals(1, run("xtra", "export-age", "--output", backup.toString()), errText())
        assertTrue(errText().contains("No age identity"), errText())
        assertTrue(!Files.exists(backup.resolve("xtra-age-identity.txt")))
    }

    @Test
    fun `export-age onto an existing file exits non-zero and leaves that file`() {
        XtraAgeKeyStore(dir.resolve("xtra-age.key")).generate(pass.toCharArray())
        val backup = backupDir()
        Files.writeString(backup.resolve("xtra-age-identity.txt"), "stale")

        assertEquals(1, run("xtra", "export-age", "--output", backup.toString()), errText())
        assertTrue(errText().contains("already exists"), errText())
        assertEquals("stale", Files.readString(backup.resolve("xtra-age-identity.txt")))
    }

    @Test
    fun `export-key and export-age succeed with exit 0 when nothing is refused`() {
        XtraSymmetricKeyStore(dir.resolve("xtra-sym.key")).generate(pass.toCharArray())
        XtraAgeKeyStore(dir.resolve("xtra-age.key")).generate(pass.toCharArray())
        assertEquals(0, run("xtra", "export-key", "--output", backupDir().toString()), errText())
        assertEquals(0, run("xtra", "export-age", "--output", backupDir().toString()), errText())
    }

    @Test
    fun `init-age and init-tink over an existing key exit non-zero`() {
        XtraAgeKeyStore(dir.resolve("xtra-age.key")).generate(pass.toCharArray())
        assertEquals(1, run("xtra", "init-age"), errText())
        Files.writeString(dir.resolve(XtraAlgorithm.TINK_AES_GCM.keyFileName), "k")
        assertEquals(1, run("xtra", "init-tink"), errText())
    }

    @Test
    fun `import-age with no identity file exits non-zero`() {
        assertEquals(1, run("xtra", "import-age", "--input", backupDir().toString()), errText())
    }

    @Test
    fun `change-passphrase verbs with no key exit non-zero`() {
        assertEquals(1, run("xtra", "change-passphrase"), errText())
        assertEquals(1, run("xtra", "change-passphrase-age"), errText())
        assertEquals(1, run("xtra", "change-passphrase-tink"), errText())
    }

    @Test
    fun `change-passphrase verbs with a key but no usable console exit non-zero`() {
        // The test JVM has no terminal: the console is absent, or reads nothing. Either refusal must end non-zero.
        XtraSymmetricKeyStore(dir.resolve("xtra-sym.key")).generate(pass.toCharArray())
        XtraAgeKeyStore(dir.resolve("xtra-age.key")).generate(pass.toCharArray())
        Files.writeString(dir.resolve(XtraAlgorithm.TINK_AES_GCM.keyFileName), "k")
        assertEquals(1, run("xtra", "change-passphrase", "--algorithm", "aes-gcm-sym"), errText())
        assertEquals(1, run("xtra", "change-passphrase-age"), errText())
        assertEquals(1, run("xtra", "change-passphrase-tink"), errText())
    }
}
