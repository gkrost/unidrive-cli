package org.krost.unidrive.cli

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.util.LogbackMDCAdapter
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.krost.unidrive.testing.TestScratch

/**
 * Several unidrive processes log to one directory. A logback RollingFileAppender is not multi-process safe: the
 * shipped pre-#897 config rolled the shared file by renaming it (a size-and-time policy over a named file), and a
 * rename onto a name another process had already rolled to replaced that file, so its lines were in no file at all.
 * The shipped config now is prudent mode (file locking on every write) with a time-only policy and no named file:
 * the live file already carries its final dated name, so a rollover is a close-and-open, not a rename, and any
 * number of processes may write it. Each test builds two logger contexts in one JVM, which stand in for two
 * processes: separate appenders, separate file handles, no shared state but the directory.
 */
class SharedLogRotationTest {
    private fun shippedContext(logDir: Path): LoggerContext {
        val shipped =
            javaClass.classLoader
                .getResourceAsStream("logback.xml")!!
                .use { String(it.readBytes(), Charsets.UTF_8) }
        val config =
            shipped
                .replace(
                    Regex("""<property name="LOG_DIR"[^>]*/>"""),
                    """<property name="LOG_DIR" value="${logDir.toString().replace('\\', '/')}"/>""",
                )
                // The shipped policy rolls by day; the test accelerates it to a millisecond pattern, so the write
                // loop below crosses rollover boundaries constantly. The mechanics under test are the same: a
                // rollover opens the next dated file and abandons the old one, whatever the period is.
                .replace(Regex("""%d\{yyyy-MM-dd\}"""), "%d{yyyy-MM-dd_HH-mm-ss.SSS}")
                // logback's archive cleaner runs asynchronously and, rolling several times within milliseconds, deletes
                // today's earlier archives as "historically stale": retention is not what is under test here.
                .replace(Regex("""\s*<(maxHistory|totalSizeCap)>[^<]*</\1>"""), "")
        assertNotEquals(shipped, config, "precondition: the LOG_DIR property was found")
        assertTrue(
            "%d{yyyy-MM-dd_HH-mm-ss.SSS}" in config && "%d{yyyy-MM-dd}." !in config,
            "precondition: the day pattern of the shipped XML was found and replaced",
        )
        val context = LoggerContext().also { it.setMDCAdapter(LogbackMDCAdapter()) }
        JoranConfigurator().also { it.context = context }.doConfigure(ByteArrayInputStream(config.toByteArray(Charsets.UTF_8)))
        return context
    }

    private fun linesOf(dir: Path): List<String> =
        Files.list(dir).use { s ->
            s
                .filter { it.fileName.toString().endsWith(".log") }
                .toList()
                .flatMap { Files.readAllLines(it, Charsets.UTF_8) }
        }

    @Test
    fun `no line of two processes is lost when both roll the shared log`() {
        val dir = TestScratch.directory("unidrive-sharedlog")
        try {
            val a = shippedContext(dir)
            val b = shippedContext(dir)
            val la = a.getLogger("org.krost.unidrive.test.a")
            val lb = b.getLogger("org.krost.unidrive.test.b")
            repeat(20) {
                la.info("A-$it " + "x".repeat(80))
                lb.info("B-$it " + "y".repeat(80))
            }
            a.stop()
            b.stop()

            val files = Files.list(dir).use { s -> s.filter { it.fileName.toString().endsWith(".log") }.toList() }
            assertTrue(files.size > 2, "the accelerated pattern really rolled: ${files.map { it.fileName }}")
            val all = linesOf(dir).joinToString("\n")
            val missing = (0 until 20).flatMap { listOf("A-$it ", "B-$it ") }.filter { "$it" !in all }
            assertEquals(emptyList(), missing, "every line of both processes is in some file under $dir")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
