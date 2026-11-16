package org.krost.unidrive.sync

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Part of #742 (finding 7), complementing [FailureLogWriteErrorTest]: the journal helper itself (one WARN per path,
 * resume once writable) and the failure sites the repro does not drive (folder-create ladder, skipped-ops journal).
 */
class FailureJournalTest {
    private lateinit var syncRoot: Path
    private lateinit var stateDir: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider
    private var failedReported: Int? = null

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-journal-root")
        stateDir = Files.createTempDirectory("ud-journal-state")
        db = StateDatabase(stateDir.resolve("state.db")).also { it.initialize() }
        provider = FakeCloudProvider()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        syncRoot.toFile().deleteRecursively()
        stateDir.toFile().deleteRecursively()
    }

    private fun engine(
        failureLog: Path? = null,
        skippedOps: Path? = null,
    ) = SyncEngine(
        provider = provider,
        db = db,
        syncRoot = syncRoot,
        conflictPolicy = ConflictPolicy.KEEP_BOTH,
        reporter =
            object : ProgressReporter by ProgressReporter.Silent {
                override fun onSyncComplete(
                    downloaded: Int,
                    uploaded: Int,
                    conflicts: Int,
                    durationMs: Long,
                    actionCounts: Map<String, Int>,
                    failed: Int,
                ) {
                    failedReported = failed
                }
            },
        failureLogPath = failureLog,
        skippedOpsLogPath = skippedOps,
    )

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
    fun `a write error is logged once per journal path, not once per line`() {
        val journal = stateDir.resolve("once").resolve("failures.jsonl")
        Files.createDirectories(journal) // a directory where the file belongs
        val (_, warns) =
            warnsDuring {
                repeat(5) { appendJournalLine(journal, "{}", "failure journal") }
            }
        val mine = warns.filter { "failure journal" in it && journal.toString() in it }
        assertEquals(1, mine.size, "$warns")
    }

    @Test
    fun `a journal that becomes writable again resumes writing`() {
        val journal = stateDir.resolve("resume").resolve("failures.jsonl")
        Files.createDirectories(journal)
        appendJournalLine(journal, "lost", "failure journal")
        Files.delete(journal)
        appendJournalLine(journal, "first", "failure journal")
        appendJournalLine(journal, "second", "failure journal")
        assertEquals(listOf("first", "second"), Files.readAllLines(journal))
    }

    @Test
    fun `a missing parent directory is created`() {
        val journal = stateDir.resolve("a").resolve("b").resolve("failures.jsonl")
        appendJournalLine(journal, "x", "failure journal")
        assertEquals(listOf("x"), Files.readAllLines(journal))
    }

    @Test
    fun `an unwritable journal does not abort the pass when a folder create fails`() =
        runTest {
            val journal = stateDir.resolve("failures.jsonl")
            Files.createDirectories(journal)
            Files.createDirectories(syncRoot.resolve("proj/a"))
            Files.createDirectories(syncRoot.resolve("proj/b"))
            Files.writeString(syncRoot.resolve("f.txt"), "x")
            provider.deltaItems = emptyList()
            provider.createFolderFailPaths.add("/proj/a")

            engine(failureLog = journal).syncOnce()

            assertTrue(provider.createdFolders.contains("/proj/b"), "the sibling folder is still created")
            assertEquals(listOf("/f.txt"), provider.uploadedPaths, "the later upload still goes up")
            assertTrue((failedReported ?: 0) >= 1, "the failed folder is still counted: $failedReported")
        }

    @Test
    fun `an unwritable skipped-ops journal does not abort the pass`() =
        runTest {
            val journal = stateDir.resolve("skipped-ops.jsonl")
            Files.createDirectories(journal)
            val now = Instant.parse("2026-01-01T00:00:00Z")
            for (p in listOf("/Docs", "/Docs/sub")) {
                db.upsertEntry(
                    SyncEntry(
                        path = p,
                        remoteId = "id$p",
                        remoteHash = null,
                        remoteSize = 0,
                        remoteModified = now,
                        localMtime = null,
                        localSize = null,
                        isFolder = true,
                        isPinned = false,
                        isHydrated = false,
                        lastSynced = now,
                    ),
                )
            }
            db.setSyncState("delta_cursor", "seeded-cursor")
            Files.writeString(syncRoot.resolve("f.txt"), "x")
            provider.deltaItems = emptyList()

            engine(skippedOps = journal).syncOnce()

            assertEquals(listOf("/f.txt"), provider.uploadedPaths, "the pass completes past the unwritable skip journal")
            assertTrue(Files.isDirectory(journal))
        }
}
