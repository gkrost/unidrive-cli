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
    fun `a journal that recovers and fails again is reported again, once per episode`() {
        val journal = stateDir.resolve("episodes").resolve("failures.jsonl")
        val (_, warns) =
            warnsDuring {
                Files.createDirectories(journal)
                repeat(3) { appendJournalLine(journal, "a", "failure journal") } // episode 1
                Files.delete(journal)
                appendJournalLine(journal, "ok", "failure journal") // recovered
                Files.delete(journal)
                Files.createDirectories(journal)
                repeat(3) { appendJournalLine(journal, "b", "failure journal") } // episode 2
            }
        assertEquals(2, warns.count { journal.toString() in it }, "$warns")
    }

    @Test
    fun `a cancellation from the write is rethrown, not swallowed and not reported`() {
        val journal = stateDir.resolve("cancel").resolve("failures.jsonl")
        val (thrown, warns) =
            warnsDuring {
                runCatching {
                    appendJournalLine(journal, "x", "failure journal") { _, _ -> throw java.util.concurrent.CancellationException("cancelled") }
                }.exceptionOrNull()
            }
        assertTrue(thrown is java.util.concurrent.CancellationException, "$thrown")
        assertTrue(warns.none { journal.toString() in it }, "$warns")
    }

    @Test
    fun `a runtime exception from the write is swallowed with one warning`() {
        val journal = stateDir.resolve("runtime").resolve("failures.jsonl")
        val (_, warns) =
            warnsDuring {
                repeat(2) {
                    appendJournalLine(journal, "x", "failure journal") { _, _ -> throw IllegalArgumentException("bad path") }
                }
            }
        assertEquals(1, warns.count { journal.toString() in it }, "$warns")
    }

    @Test
    fun `an io exception from the write is swallowed`() {
        val journal = stateDir.resolve("io").resolve("failures.jsonl")
        appendJournalLine(journal, "x", "failure journal") { _, _ -> throw java.io.IOException("disk full") }
    }

    /** What a failed download leaves behind: the failed count, the row, the local stub, the plan's other uploads. */
    private data class DownloadFailureOutcome(
        val failed: Int?,
        val rowPresent: Boolean,
        val rowHydrated: Boolean?,
        val rowRemoteId: String?,
        val localExists: Boolean,
        val localSize: Long?,
        val uploaded: List<String>,
    )

    private fun downloadFailureRun(blockJournal: Boolean): DownloadFailureOutcome {
        val root = Files.createTempDirectory("ud-journal-dl-root")
        val state = Files.createTempDirectory("ud-journal-dl-state")
        val journal = state.resolve("failures.jsonl")
        if (blockJournal) Files.createDirectories(journal)
        val runDb = StateDatabase(state.resolve("state.db")).also { it.initialize() }
        val fake = FakeCloudProvider()
        failedHolder = null
        try {
            fake.files["/d.txt"] = "bytes".toByteArray()
            fake.deltaItems +=
                org.krost.unidrive.CloudItem(
                    id = "id-d",
                    name = "d.txt",
                    path = "/d.txt",
                    size = 5,
                    isFolder = false,
                    modified = Instant.parse("2026-03-28T12:00:00Z"),
                    created = Instant.parse("2026-03-28T12:00:00Z"),
                    hash = null,
                    mimeType = "application/octet-stream",
                )
            fake.beforeDownload = { path -> if (path == "/d.txt") throw org.krost.unidrive.ProviderException("refused") }
            Files.writeString(root.resolve("a.txt"), "x")
            val e =
                SyncEngine(
                    provider = fake,
                    db = runDb,
                    syncRoot = root,
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
                                failedHolder = failed
                            }
                        },
                    failureLogPath = journal,
                )
            kotlinx.coroutines.runBlocking { e.syncOnce() }
            val row = runDb.getEntry("/d.txt")
            val local = root.resolve("d.txt")
            return DownloadFailureOutcome(
                failed = failedHolder,
                rowPresent = row != null,
                rowHydrated = row?.isHydrated,
                rowRemoteId = row?.remoteId,
                localExists = Files.exists(local),
                localSize = if (Files.exists(local)) Files.size(local) else null,
                uploaded = fake.uploadedPaths.toList(),
            )
        } finally {
            runDb.close()
            root.toFile().deleteRecursively()
            state.toFile().deleteRecursively()
        }
    }

    private var failedHolder: Int? = null

    @Test
    fun `a failed download leaves the same row and stub and counts whether or not the journal is writable`() {
        val healthy = downloadFailureRun(blockJournal = false)
        val blocked = downloadFailureRun(blockJournal = true)

        assertEquals(1, healthy.failed, "the healthy run counts the failed download: $healthy")
        assertTrue(healthy.rowPresent && healthy.rowHydrated == false, "restoreToPlaceholder reset the row: $healthy")
        assertEquals(healthy, blocked, "an unwritable journal must change nothing about the failed download")
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
