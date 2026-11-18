package org.krost.unidrive.cli

import org.krost.unidrive.io.Os
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.testing.TestScratch
import picocli.CommandLine
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#706: sqlite-jdbc extracts its native library into `org.sqlite.tmpdir` (default: the system temp dir)
 * on every JVM start, lists that folder to clean up old copies, and leaks a copy whenever the JVM is halted. Every
 * unidrive JVM pins the folder to `<config dir>/.sqlite-native` before any state.db is opened. Temp dirs only.
 */
class SqliteNativeDirTest {
    private lateinit var dir: Path
    private var saved: String? = null

    @BeforeTest
    fun setUp() {
        dir = TestScratch.directory("unidrive-sqlite-native")
        saved = System.getProperty(SQLITE_TMPDIR_PROPERTY)
        System.clearProperty(SQLITE_TMPDIR_PROPERTY)
    }

    @AfterTest
    fun tearDown() {
        if (saved == null) System.clearProperty(SQLITE_TMPDIR_PROPERTY) else System.setProperty(SQLITE_TMPDIR_PROPERTY, saved!!)
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `the native library folder is pinned under the config dir`() {
        pinSqliteNativeDir(dir)

        val pinned = dir.resolve(".sqlite-native")
        assertEquals(pinned.toAbsolutePath().toString(), System.getProperty(SQLITE_TMPDIR_PROPERTY))
        assertTrue(Files.isDirectory(pinned))
    }

    @Test
    fun `a folder the operator chose is never overridden`() {
        System.setProperty(SQLITE_TMPDIR_PROPERTY, "/operator/choice")

        pinSqliteNativeDir(dir)

        assertEquals("/operator/choice", System.getProperty(SQLITE_TMPDIR_PROPERTY))
    }

    @Test
    fun `a config dir where the folder cannot be made leaves the driver default`() {
        val notADir = Files.writeString(dir.resolve("file"), "x")

        pinSqliteNativeDir(notADir)

        assertNull(System.getProperty(SQLITE_TMPDIR_PROPERTY))
    }

    @Test
    fun `the command line pins the folder from -c before any state db opens`() {
        // A fresh JVM: the driver loads its native library once per JVM, so only a fork shows where it lands.
        val javaBin = Path.of(System.getProperty("java.home"), "bin", if (Os.isWindows()) "java.exe" else "java")
        val process =
            ProcessBuilder(
                javaBin.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                SqliteNativeDirProbe::class.java.name,
                dir.toString(),
            ).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        assertEquals(0, process.waitFor(), output)
        assertTrue(output.lines().any { it.startsWith("native: sqlite-") }, "the library was extracted into the pinned folder:\n$output")
    }
}

/** Runs the real command line with `-c <dir> --version`, then opens a state.db and lists the pinned folder. */
object SqliteNativeDirProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val dir = Path.of(args[0])
        val main = Main()
        CommandLine(main).apply { executionStrategy = pinSqliteNativeDirStrategy(main) }.execute("-c", dir.toString(), "--version")
        val db = StateDatabase(dir.resolve("probe-state.db"))
        db.initialize()
        Files.list(dir.resolve(".sqlite-native")).use { entries -> entries.forEach { println("native: ${it.fileName}") } }
        db.close()
    }
}
