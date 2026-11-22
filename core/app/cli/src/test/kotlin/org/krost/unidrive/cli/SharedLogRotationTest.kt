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
 * Several unidrive processes log to one directory. A logback RollingFileAppender is not multi-process safe: each
 * process names the rolled file itself, and a rename onto a name another process already rolled to replaces it, so
 * the lines of the replaced file are in no file at all. Each test builds two logger contexts in one JVM, which
 * stand in for two processes: separate appenders, separate file handles, no shared state but the directory.
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
                ).replace("<maxFileSize>10MB</maxFileSize>", "<maxFileSize>2KB</maxFileSize>")
                // logback's archive cleaner runs asynchronously and, rolling several times within milliseconds, deletes
                // today's earlier archives as "historically stale": retention is not what is under test here.
                .replace(Regex("""\s*<(maxHistory|totalSizeCap)>[^<]*</\1>"""), "")
        assertNotEquals(shipped, config, "precondition: the LOG_DIR property was found")
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
            // 2 KB files: every ~15 lines of 150 bytes each process rolls on its own.
            repeat(20) {
                la.info("A-$it " + "x".repeat(80))
                lb.info("B-$it " + "y".repeat(80))
            }
            a.stop()
            b.stop()

            val all = linesOf(dir).joinToString("\n")
            val missing = (0 until 20).flatMap { listOf("A-$it ", "B-$it ") }.filter { "$it" !in all }
            assertEquals(emptyList(), missing, "every line of both processes is in some file under $dir")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
