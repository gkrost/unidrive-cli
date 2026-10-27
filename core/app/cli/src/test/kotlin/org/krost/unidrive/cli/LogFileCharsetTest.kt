package org.krost.unidrive.cli

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.util.LogbackMDCAdapter
import ch.qos.logback.core.OutputStreamAppender
import ch.qos.logback.core.encoder.LayoutWrappingEncoder
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * #147: a log line naming an Arabic, Cyrillic, CJK or emoji path must reach the file as those characters, not as `?`.
 * An encoder without a charset encodes with the JVM's default charset, which is the ANSI code page on Windows whenever
 * `file.encoding` says so (`COMPAT`, or a runtime older than JEP 400), and there every character outside it becomes `?`.
 * The JVM default cannot be switched inside a running test, so each encoder is checked for an explicit UTF-8, and the
 * bytes on disk are checked to be the UTF-8 of the message.
 */
class LogFileCharsetTest {
    private val unicode = "Привет 猫 🐈 مرحبا"

    @Test
    fun `the shipped logback config writes unidrive log in UTF-8 on every appender`() {
        val dir = Files.createTempDirectory("unidrive-logcharset")
        try {
            val shipped =
                javaClass.classLoader
                    .getResourceAsStream("logback.xml")!!
                    .use { String(it.readBytes(), Charsets.UTF_8) }
            val logDir = dir.toString().replace('\\', '/')
            // Never the operator's live log: only the LOG_DIR property is replaced, the rest is the shipped file.
            val config = shipped.replace(Regex("""<property name="LOG_DIR"[^>]*/>"""), """<property name="LOG_DIR" value="$logDir"/>""")
            assertNotEquals(shipped, config, "precondition: the LOG_DIR property was found")

            val context = LoggerContext().also { it.setMDCAdapter(LogbackMDCAdapter()) }
            JoranConfigurator().also { it.context = context }.doConfigure(ByteArrayInputStream(config.toByteArray(Charsets.UTF_8)))
            val appenders = context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME).iteratorForAppenders().asSequence().toList()
            assertTrue(appenders.isNotEmpty(), "precondition: the root logger has appenders")
            for (appender in appenders) {
                val encoder = (appender as OutputStreamAppender<ILoggingEvent>).encoder as LayoutWrappingEncoder<ILoggingEvent>
                assertEquals(Charsets.UTF_8, encoder.charset, "the encoder of appender ${appender.name} names UTF-8, not the JVM default")
            }

            context.getLogger("org.krost.unidrive.test").warn("upload failed for /_INBOX/{}.txt", unicode)
            context.stop()

            assertUtf8Line(dir.resolve("unidrive.log"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the log_file redirect writes UTF-8 too`() {
        val dir = Files.createTempDirectory("unidrive-logcharset")
        try {
            val context = LoggerContext().also { it.setMDCAdapter(LogbackMDCAdapter()) }
            val logFile = dir.resolve("redirect.log")
            val appender = redirectFileAppender(context, logFile)
            val encoder = appender.encoder as LayoutWrappingEncoder<ILoggingEvent>
            assertEquals(Charsets.UTF_8, encoder.charset, "the redirect's encoder names UTF-8, not the JVM default")

            context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME).addAppender(appender)
            context.getLogger("org.krost.unidrive.test").warn("upload failed for /_INBOX/{}.txt", unicode)
            context.stop()

            assertUtf8Line(logFile)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun assertUtf8Line(file: Path) {
        val bytes = Files.readAllBytes(file)
        val expected = "upload failed for /_INBOX/$unicode.txt".toByteArray(Charsets.UTF_8)
        val text = String(bytes, Charsets.UTF_8)
        assertTrue(indexOf(bytes, expected) >= 0, "the file holds the UTF-8 bytes of the message: $text")
    }

    private fun indexOf(
        haystack: ByteArray,
        needle: ByteArray,
    ): Int =
        (0..haystack.size - needle.size).firstOrNull { start ->
            needle.indices.all { haystack[start + it] == needle[it] }
        } ?: -1
}
