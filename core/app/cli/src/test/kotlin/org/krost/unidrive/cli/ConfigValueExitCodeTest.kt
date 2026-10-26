package org.krost.unidrive.cli

import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A config value the schema refuses ends a command with exit 78 (configuration problem) and a line that names the key. */
class ConfigValueExitCodeTest {
    private fun run(general: String): Pair<Int, String> {
        val dir = Files.createTempDirectory("config-value-exit")
        try {
            val root = Files.createDirectories(dir.resolve("local")).toString().replace('\\', '/')
            val remote = Files.createDirectories(dir.resolve("remote")).toString().replace('\\', '/')
            Files.writeString(
                dir.resolve("config.toml"),
                "[general]\n$general\n\n[providers.test]\ntype = \"localfs\"\nsync_root = \"$root\"\nroot_path = \"$remote\"\n",
            )
            val err = ByteArrayOutputStream()
            val originalErr = System.err
            val exit =
                try {
                    System.setErr(PrintStream(err, true, Charsets.UTF_8))
                    val cmd = CommandLine(Main())
                    cmd.executionExceptionHandler = cliExecutionExceptionHandler
                    cmd.execute("-c", dir.toString(), "-p", "test", "trash", "list")
                } finally {
                    System.setErr(originalErr)
                }
            return exit to err.toString(Charsets.UTF_8)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `poll_interval below the floor exits 78 and names the key`() {
        val (exit, err) = run("poll_interval = 3")
        assertEquals(78, exit, err)
        assertTrue("poll_interval" in err, err)
    }

    @Test
    fun `an unknown sync_direction exits 78 and names the key`() {
        val (exit, err) = run("sync_direction = \"uplaod\"")
        assertEquals(78, exit, err)
        assertTrue("sync_direction" in err && "uplaod" in err, err)
    }
}
