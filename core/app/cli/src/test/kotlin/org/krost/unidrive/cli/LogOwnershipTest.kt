package org.krost.unidrive.cli

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.krost.unidrive.testing.TestScratch

/** gkrost/unidrive-cli#897: one named test per invariant of the owned, per-profile log. */
class LogOwnershipTest {
    private fun owned(
        dir: Path,
        profile: String,
        maxFileSize: String,
    ): LoggerContext {
        val context = LoggerContext().also { it.setMDCAdapter(ch.qos.logback.classic.util.LogbackMDCAdapter()) }
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(LogOwnership.ownedAppender(context, dir, profile, maxFileSize, retention = false))
        return context
    }

    private fun allLines(dir: Path): String =
        Files.list(dir).use { s -> s.toList().filter { it.toString().endsWith(".log") }.joinToString("\n") { Files.readString(it) } }

    @Test
    fun `the owned log file name is stable per profile and cannot leave the log folder`() {
        assertEquals("unidrive-work.log", LogOwnership.ownedFileName("work"))
        assertEquals("unidrive-work.log", LogOwnership.ownedFileName("work"))
        assertEquals("unidrive-.._x_y.log", LogOwnership.ownedFileName("../x/y"))
        assertEquals("unidrive-a_b.log", LogOwnership.ownedFileName("a b"))
    }

    @Test
    fun `an owned log that rolls by size keeps every line of its process`() {
        val dir = TestScratch.directory("unidrive-owned")
        try {
            val context = owned(dir, "p", "2KB")
            val log = context.getLogger("org.krost.unidrive.test")
            repeat(30) { log.info("OWN-$it " + "z".repeat(80)) }
            context.stop()
            assertTrue(Files.exists(dir.resolve("unidrive-p.log")), "the live file carries the profile's name")
            val text = allLines(dir)
            assertEquals(emptyList(), (0 until 30).filter { "OWN-$it " !in text }, "every line is in some file")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an owned log rolling does not touch the shared log written by another process`() {
        val dir = TestScratch.directory("unidrive-owned")
        try {
            val shared = Files.createFile(dir.resolve("unidrive.2026-11-22.log"))
            val context = owned(dir, "p", "2KB")
            val log = context.getLogger("org.krost.unidrive.test")
            repeat(30) {
                log.info("OWN-$it " + "z".repeat(80))
                Files.writeString(shared, "SHARED-$it\n", java.nio.file.StandardOpenOption.APPEND)
            }
            context.stop()
            assertEquals(30, Files.readAllLines(shared).size, "the shared file kept all of its lines")
            val text = allLines(dir)
            assertEquals(emptyList(), (0 until 30).filter { "OWN-$it " !in text || "SHARED-$it\n" !in text })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `claiming the log moves the root logger from the shared file to the profile file`() {
        val dir = TestScratch.directory("unidrive-owned")
        try {
            val context = LoggerContext().also { it.setMDCAdapter(ch.qos.logback.classic.util.LogbackMDCAdapter()) }
            val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
            val shared = ch.qos.logback.core.FileAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
            shared.context = context
            shared.name = LogOwnership.SHARED_APPENDER
            shared.file = dir.resolve("unidrive.2026-11-22.log").toString()
            shared.encoder = ch.qos.logback.classic.encoder.PatternLayoutEncoder().also { it.context = context; it.pattern = "%msg%n"; it.start() }
            shared.start()
            root.addAppender(shared)
            context.getLogger("t").info("before")
            LogOwnership.claim(context, dir, "p")
            context.getLogger("t").info("after")
            context.stop()
            assertEquals(null, root.getAppender(LogOwnership.SHARED_APPENDER))
            assertEquals("before\n", Files.readString(dir.resolve("unidrive.2026-11-22.log")))
            assertTrue(Files.readString(dir.resolve("unidrive-p.log")).contains("after"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
