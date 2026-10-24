package org.krost.unidrive.cli

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.util.LogbackMDCAdapter
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * archive#1060: a provider exception can quote a pre-authenticated URL (Ktor's timeout exceptions print the whole
 * request URL with its query), and a log call anywhere may print its message or its stack trace. This runs the
 * shipped `logback.xml` itself, with the log directory moved into a temp directory, and reads what reaches the file.
 */
class RedactingLogConfigTest {
    private val timeout = "Request timeout has expired [url=https://x/y?tempauth=SECRET, request_timeout=600000 ms]"

    @Test
    fun `the bundled logback config writes no URL query, neither in a message nor in a stack trace`() {
        val dir = Files.createTempDirectory("unidrive-logcfg")
        try {
            val shipped =
                javaClass.classLoader
                    .getResourceAsStream("logback.xml")!!
                    .use { String(it.readBytes(), Charsets.UTF_8) }
            val logDir = dir.toString().replace('\\', '/')
            // Never the operator's live log: only the LOG_DIR property is replaced, the rest is the shipped file.
            val config = shipped.replace(Regex("""<property name="LOG_DIR"[^>]*/>"""), """<property name="LOG_DIR" value="$logDir"/>""")
            assertNotEquals(shipped, config, "precondition: the LOG_DIR property was found")

            // A context of its own, not the JVM's: it needs the MDC adapter slf4j would have bound to the global one.
            val context = LoggerContext().also { it.setMDCAdapter(LogbackMDCAdapter()) }
            JoranConfigurator().also { it.context = context }.doConfigure(ByteArrayInputStream(config.toByteArray(Charsets.UTF_8)))
            val logger = context.getLogger("org.krost.unidrive.test")
            logger.warn("download failed: {}", timeout)
            logger.warn("upload failed", RuntimeException("wrapped", IOException(timeout)))
            context.stop()

            val status = context.statusManager.copyOfStatusList.joinToString(System.lineSeparator()) { it.toString() }
            val text = Files.readString(dir.resolve("unidrive.log"))
            val shown = "log:${System.lineSeparator()}$text${System.lineSeparator()}status:${System.lineSeparator()}$status"
            assertFalse("SECRET" in text, shown)
            assertTrue("download failed: Request timeout has expired [url=https://x/y?<redacted>, request_timeout=600000 ms]" in text, shown)
            assertTrue("Caused by: java.io.IOException: Request timeout has expired [url=https://x/y?<redacted>," in text, shown)
            assertTrue("\tat org.krost.unidrive.cli.RedactingLogConfigTest" in text, "the stack trace is still printed: $shown")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
