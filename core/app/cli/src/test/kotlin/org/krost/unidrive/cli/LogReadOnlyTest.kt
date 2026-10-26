package org.krost.unidrive.cli

import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * `log` only shows history (gkrost/unidrive-cli#55 X6): it builds `StateDatabase(path)` and calls `initialize()`, the
 * read-write open, which creates the folder and the file when they are missing. `verify` opens the same way
 * (VerifyCommand, after its own existence check), `StatusCommand` and `PendingCommand` open `readOnly = true`.
 */
class LogReadOnlyTest {
    private lateinit var configDir: Path
    private val originalOut = System.out

    @BeforeTest
    fun setUp() {
        configDir = Files.createTempDirectory("ud-log-ro-cfg")
        val syncRoot = Files.createTempDirectory("ud-log-ro-local")
        val remoteRoot = Files.createTempDirectory("ud-log-ro-remote")
        Files.writeString(
            configDir.resolve("config.toml"),
            """
            |[providers.localtest]
            |type = "localfs"
            |sync_root = "${syncRoot.toString().replace('\\', '/')}"
            |root_path = "${remoteRoot.toString().replace('\\', '/')}"
            """.trimMargin(),
        )
        System.setOut(PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8))
    }

    @AfterTest
    fun tearDown() {
        System.setOut(originalOut)
    }

    @Test
    fun `log on a profile without a state db does not create one`() {
        CommandLine(Main()).execute("-c", configDir.toString(), "-p", "localtest", "log")
        assertFalse(Files.exists(configDir.resolve("localtest").resolve("state.db")), "log created a state.db")
    }
}
