package org.krost.unidrive.cli

import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `backup add` writes the name as a TOML table header and says truthfully what it did to config.toml. */
class BackupAddValidationTest {
    private class Run(val exit: Int, val out: String, val err: String)

    private fun run(
        dir: Path,
        vararg args: String,
    ): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        try {
            System.setOut(PrintStream(out, true, Charsets.UTF_8))
            System.setErr(PrintStream(err, true, Charsets.UTF_8))
            val exit = CommandLine(Main()).execute("-c", dir.toString(), "backup", "add", "--provider", "localfs", "--target", "/b", *args)
            return Run(exit, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }

    private fun withConfig(body: (dir: Path, config: Path) -> Unit) {
        val dir = Files.createTempDirectory("backup-add-validation")
        try {
            val config = dir.resolve("config.toml")
            Files.writeString(config, "[general]\n")
            body(dir, config)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a name that is no valid profile name is refused and the config is left alone`() =
        withConfig { dir, config ->
            for (name in listOf("a b", "a]b", "a\"b", "a.b")) {
                val r = run(dir, "--name", name)
                assertEquals(2, r.exit, "name '$name': ${r.err}")
                assertEquals("[general]\n", Files.readString(config), "name '$name' must not reach config.toml")
            }
        }

    @Test
    fun `adding a profile that exists says the config was left unchanged and does not claim to have created it`() =
        withConfig { dir, config ->
            val first = run(dir, "--name", "nightly")
            assertEquals(0, first.exit, first.err)
            assertTrue("created" in first.out, first.out)
            val written = Files.readString(config)

            val second = run(dir, "--name", "nightly")
            assertEquals(0, second.exit, second.err)
            assertTrue("left unchanged" in second.err, second.err)
            assertFalse("created" in second.out, second.out)
            assertEquals(written, Files.readString(config))
        }
}
