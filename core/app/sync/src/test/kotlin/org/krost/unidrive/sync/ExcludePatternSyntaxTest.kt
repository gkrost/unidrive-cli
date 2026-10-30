package org.krost.unidrive.sync

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.model.ConflictPolicy
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.PatternSyntaxException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #218: `exclude_patterns` are checked by the matcher's own compile step, not by the JDK's glob. The JDK let
 * `*.{tmp,bak}` through, which the matcher takes literally, without a word, and refused `My [Draft*`, which the
 * matcher builds fine, so sync dropped a pattern the commands still honoured.
 */
class ExcludePatternSyntaxTest {
    private lateinit var syncRoot: Path
    private lateinit var stateDir: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-exclude-syntax-root")
        stateDir = Files.createTempDirectory("ud-exclude-syntax-state")
        db = StateDatabase(stateDir.resolve("state.db")).also { it.initialize() }
        provider = FakeCloudProvider()
        ExcludePatterns.forgetWarnings()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        syncRoot.toFile().deleteRecursively()
        stateDir.toFile().deleteRecursively()
    }

    private fun engine(vararg excludes: String) =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = ProgressReporter.Silent,
            excludePatterns = excludes.toList(),
        )

    private fun localFile(name: String) {
        Files.writeString(syncRoot.resolve(name), name)
    }

    /** The WARN lines every logger of the engine's packages wrote while [block] ran. */
    private inline fun <T> warnsDuring(block: () -> T): Pair<T, List<String>> {
        val logger = LoggerFactory.getLogger("org.krost.unidrive") as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        val result =
            try {
                block()
            } finally {
                logger.detachAppender(appender)
                appender.stop()
            }
        return result to appender.list.filter { it.level.levelStr == "WARN" }.map { it.formattedMessage }
    }

    @Test
    fun `a brace pattern warns once and matches only the literal name`() =
        runTest {
            localFile("a.bak")
            localFile("b.{tmp,bak}")

            val (engine, warns) = warnsDuring { engine("*.{tmp,bak}") }
            engine.syncOnce()

            assertEquals(
                listOf(
                    "Exclude pattern '*.{tmp,bak}' is kept, but its [ ] { } match themselves: " +
                        "exclude_patterns have no [...] classes and no {a,b} alternation",
                ),
                warns.filter { "*.{tmp,bak}" in it },
                "all warnings: $warns",
            )
            assertTrue("/a.bak" in provider.uploadedPaths, "no alternation: a.bak is no match, got ${provider.uploadedPaths}")
            assertFalse("/b.{tmp,bak}" in provider.uploadedPaths, "the literal name is excluded, got ${provider.uploadedPaths}")
            assertFalse(Reconciler.matchesGlob("/a.bak", "*.{tmp,bak}"))
            assertTrue(Reconciler.matchesGlob("/b.{tmp,bak}", "*.{tmp,bak}"))

            val (_, again) = warnsDuring { engine("*.{tmp,bak}") }
            assertTrue(again.none { "*.{tmp,bak}" in it }, "one warning per pattern and process, got: $again")
        }

    @Test
    fun `an unclosed bracket pattern is accepted by sync, as the commands already took it`() =
        runTest {
            localFile("My [Draft 1.txt")
            localFile("other.txt")

            engine("My [Draft*").syncOnce()

            assertTrue("/other.txt" in provider.uploadedPaths, "got ${provider.uploadedPaths}")
            assertFalse(
                "/My [Draft 1.txt" in provider.uploadedPaths,
                "sync must hold back what status, verify and doctor treat as excluded, got ${provider.uploadedPaths}",
            )
            assertEquals(listOf("My [Draft*"), ExcludePatterns.accepted(listOf("My [Draft*")))
        }

    // Every character, alone and next to the wildcards, compiles in the matcher: there is no pattern it cannot build,
    // so the drop below is reached only through a compile step that throws.
    @Test
    fun `the matcher compiles every character in every position`() {
        val shapes = listOf("%s", "*%s?", "**/%s/**", "a/**/%s", "%s**")
        for (c in 0..0xFFFF) {
            val ch = c.toChar().toString()
            for (shape in shapes) Reconciler.matchesGlobRegex("x", shape.format(ch))
        }
        for (tricky in listOf("", "/", "//", "\\", "\\E", "\\Q", "a\\Eb", "***", "**/**/**", "/**/", "😀*")) {
            Reconciler.matchesGlobRegex("x", tricky)
        }
    }

    @Test
    fun `a pattern the compile step refuses is dropped with a warning`() {
        val (accepted, warns) =
            warnsDuring {
                ExcludePatterns.accepted(listOf("good", "boom", "*.log")) { pattern ->
                    if (pattern == "boom") throw PatternSyntaxException("refused", pattern, 0)
                }
            }

        assertEquals(listOf("good", "*.log"), accepted)
        assertEquals(1, warns.count { it.startsWith("Invalid exclude pattern 'boom', skipping: refused") }, "got: $warns")
    }
}
