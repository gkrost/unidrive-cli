package org.krost.unidrive.cli

import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `trash purge` and `versions purge` use the profile's configured retention (`trash_retention_days`,
 * `version_retention_days`) unless `--retention-days` overrides it, and print the value they used.
 */
class PurgeRetentionTest {
    private class Run(val exit: Int, val out: String, val err: String)

    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    private fun daysAgo(days: Long): String = stamp.format(Instant.now().minus(days, ChronoUnit.DAYS))

    private fun withProfile(
        general: String,
        body: (syncRoot: Path, run: (Array<String>) -> Run) -> Unit,
    ) {
        val configDir = Files.createTempDirectory("purge-retention")
        try {
            val syncRoot = Files.createDirectories(configDir.resolve("local"))
            val remoteRoot = Files.createDirectories(configDir.resolve("remote"))
            Files.writeString(
                configDir.resolve("config.toml"),
                (if (general.isEmpty()) "" else "[general]\n$general\n\n") +
                    """
                    [providers.test]
                    type = "localfs"
                    sync_root = "${syncRoot.toString().replace('\\', '/')}"
                    root_path = "${remoteRoot.toString().replace('\\', '/')}"
                    """.trimIndent() + "\n",
            )
            body(syncRoot) { args ->
                val out = ByteArrayOutputStream()
                val err = ByteArrayOutputStream()
                val originalOut = System.out
                val originalErr = System.err
                try {
                    System.setOut(PrintStream(out, true, Charsets.UTF_8))
                    System.setErr(PrintStream(err, true, Charsets.UTF_8))
                    val exit = CommandLine(Main()).execute("-c", configDir.toString(), "-p", "test", *args)
                    Run(exit, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
                } finally {
                    System.setOut(originalOut)
                    System.setErr(originalErr)
                }
            }
        } finally {
            configDir.toFile().deleteRecursively()
        }
    }

    private fun trashEntry(syncRoot: Path, days: Long): Path {
        val file = syncRoot.resolve(".unidrive-trash").resolve(daysAgo(days)).resolve("report.txt")
        Files.createDirectories(file.parent)
        Files.writeString(file, "x")
        return file
    }

    private fun versionEntry(syncRoot: Path, days: Long): Path {
        val file = syncRoot.resolve(".unidrive-versions").resolve("report.txt").resolve(daysAgo(days))
        Files.createDirectories(file.parent)
        Files.writeString(file, "x")
        return file
    }

    @Test
    fun `trash purge keeps an entry younger than the configured trash_retention_days`() {
        withProfile("trash_retention_days = 365") { syncRoot, run ->
            val hundred = trashEntry(syncRoot, 100)
            val old = trashEntry(syncRoot, 400)
            val r = run(arrayOf("trash", "purge"))
            assertEquals(0, r.exit, r.err)
            assertTrue(Files.exists(hundred), "a 100-day-old entry is inside the configured 365 days")
            assertFalse(Files.exists(old), "a 400-day-old entry is past the configured 365 days")
            assertTrue(r.out.contains("365"), "the output names the effective retention: ${r.out}")
        }
    }

    @Test
    fun `trash purge without configuration uses the default of 30 days and says so`() {
        withProfile("") { syncRoot, run ->
            val young = trashEntry(syncRoot, 10)
            val old = trashEntry(syncRoot, 40)
            val r = run(arrayOf("trash", "purge"))
            assertEquals(0, r.exit, r.err)
            assertTrue(Files.exists(young))
            assertFalse(Files.exists(old))
            assertTrue(r.out.contains("30"), r.out)
        }
    }

    @Test
    fun `trash purge retention-days overrides the configured value`() {
        withProfile("trash_retention_days = 365") { syncRoot, run ->
            val hundred = trashEntry(syncRoot, 100)
            val r = run(arrayOf("trash", "purge", "--retention-days", "30"))
            assertEquals(0, r.exit, r.err)
            assertFalse(Files.exists(hundred), "the explicit 30 days wins over the configured 365")
            assertTrue(r.out.contains("30"), r.out)
        }
    }

    @Test
    fun `versions purge keeps a snapshot younger than the configured version_retention_days`() {
        withProfile("version_retention_days = 365") { syncRoot, run ->
            val hundred = versionEntry(syncRoot, 100)
            val old = versionEntry(syncRoot, 400)
            val r = run(arrayOf("versions", "purge"))
            assertEquals(0, r.exit, r.err)
            assertTrue(Files.exists(hundred), "a 100-day-old snapshot is inside the configured 365 days")
            assertFalse(Files.exists(old), "a 400-day-old snapshot is past the configured 365 days")
            assertTrue(r.out.contains("365"), "the output names the effective retention: ${r.out}")
        }
    }

    @Test
    fun `versions purge without configuration uses the default of 90 days and says so`() {
        withProfile("") { syncRoot, run ->
            val young = versionEntry(syncRoot, 60)
            val old = versionEntry(syncRoot, 120)
            val r = run(arrayOf("versions", "purge"))
            assertEquals(0, r.exit, r.err)
            assertTrue(Files.exists(young))
            assertFalse(Files.exists(old))
            assertTrue(r.out.contains("90"), r.out)
        }
    }

    @Test
    fun `versions purge retention-days overrides the configured value`() {
        withProfile("version_retention_days = 365") { syncRoot, run ->
            val hundred = versionEntry(syncRoot, 100)
            val r = run(arrayOf("versions", "purge", "--retention-days", "30"))
            assertEquals(0, r.exit, r.err)
            assertFalse(Files.exists(hundred), "the explicit 30 days wins over the configured 365")
            assertTrue(r.out.contains("30"), r.out)
        }
    }
}
