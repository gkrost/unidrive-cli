package org.krost.unidrive.telemetry

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#225: the history's writer thread and the request metrics' writer thread are threads of their own, so the
 * MDC of the code that opened them (a daemon: its profile and build) is carried onto them; their lines printed `[*]` in the
 * log two daemons share. The MDC is read while each line is logged, on the thread that logs it.
 */
class TelemetryWritersLogContextTest {
    private lateinit var dir: Path
    private val loggers =
        listOf(HistoryWriter::class.java, RequestMetricsRecorder::class.java).map { LoggerFactory.getLogger(it) as Logger }
    private val lines = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
    private val appender =
        object : AppenderBase<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                lines += event.formattedMessage to HashMap(event.mdcPropertyMap)
            }
        }

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("telemetry-log-context-test")
        appender.start()
        loggers.forEach {
            it.level = Level.DEBUG
            it.addAppender(appender)
        }
    }

    @AfterTest
    fun tearDown() {
        loggers.forEach {
            it.detachAppender(appender)
            it.level = null
        }
        MDC.clear()
        runCatching { dir.toFile().deleteRecursively() }
    }

    @Test
    fun `the writer threads log with the MDC of the code that opened them`() {
        MDC.put("profile", "tw-profile")
        MDC.put("build", "tw-build")
        val history = TransferHistory.open("tw-profile", dir.resolve("telemetry.db"))
        history.requestMetrics()
        MDC.clear()

        // The process dies: each writer is interrupted and says so on its own thread.
        history.abandon()

        val interrupted = lines.filter { (message, _) -> message.contains("interrupted") }
        assertTrue(interrupted.any { it.first.startsWith("History writer") }, "the history writer's line: $lines")
        assertTrue(interrupted.any { it.first.startsWith("Request metrics writer") }, "the metrics writer's line: $lines")
        for ((message, mdc) in interrupted) {
            assertEquals("tw-profile", mdc["profile"], "the profile of '$message'")
            assertEquals("tw-build", mdc["build"], "the build of '$message'")
        }
    }
}
