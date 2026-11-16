package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ConflictPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Part of #742 (finding 7): failures.jsonl is a diary of what failed, not part of the sync. A write error on that
 * diary (a directory at its path, a read-only folder, a full disk, a lock) must be logged and ignored. It used to
 * escape the catch ladder of the failing transfer and abort the whole pass, so one file's failure became every
 * file's failure.
 */
class FailureLogWriteErrorTest {
    private lateinit var syncRoot: Path
    private lateinit var stateDir: Path
    private lateinit var failureLog: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider

    private val warnings = mutableListOf<String>()
    private var failedReported: Int? = null

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-failure-write-root")
        stateDir = Files.createTempDirectory("ud-failure-write-state")
        failureLog = stateDir.resolve("failures.jsonl")
        db = StateDatabase(stateDir.resolve("state.db")).also { it.initialize() }
        provider = FakeCloudProvider()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        syncRoot.toFile().deleteRecursively()
        stateDir.toFile().deleteRecursively()
    }

    private fun engine() =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter =
                object : ProgressReporter by ProgressReporter.Silent {
                    override fun onWarning(message: String) {
                        warnings.add(message)
                    }

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
        )

    private fun local(
        name: String,
        text: String = "x",
    ) = Files.writeString(syncRoot.resolve(name), text)

    // A directory where the file belongs: createDirectories passes, writeString throws, on Windows and on Linux.
    private fun blockJournal() {
        Files.createDirectories(failureLog)
    }

    @Test
    fun `an unwritable failure journal does not abort the pass, the other files transfer`() =
        runTest {
            blockJournal()
            for (n in listOf("a.txt", "b.txt", "c.txt")) local(n)
            provider.uploadThrowByPath["/b.txt"] = java.io.IOException("boom")

            engine().syncOnce()

            assertEquals(setOf("/a.txt", "/c.txt"), provider.uploadedPaths.toSet(), "the healthy files still go up")
            assertTrue(warnings.any { "/b.txt" in it }, "the failing file is still reported as failed: $warnings")
            assertEquals(1, failedReported, "the pass reports its one failed file")
            assertTrue(Files.isDirectory(failureLog), "the journal path is untouched")
        }

    @Test
    fun `an unwritable failure journal does not abort the pass when a download fails`() =
        runTest {
            blockJournal()
            provider.files["/d.txt"] = "bytes".toByteArray()
            provider.deltaItems +=
                CloudItem(
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
            provider.beforeDownload = { path -> if (path == "/d.txt") throw org.krost.unidrive.ProviderException("refused") }
            local("a.txt")

            engine().syncOnce()

            assertEquals(listOf("/a.txt"), provider.uploadedPaths, "the unrelated upload still goes up")
            assertEquals(1, failedReported)
        }

    @Test
    fun `a writable failure journal still records the failure`() =
        runTest {
            local("a.txt")
            provider.uploadThrowByPath["/a.txt"] = java.io.IOException("boom")

            engine().syncOnce()

            val lines = Files.readAllLines(failureLog).filter { it.isNotEmpty() }
            assertEquals(1, lines.size, "$lines")
            assertTrue("/a.txt" in lines.single())
        }

    @Test
    fun `two failures append two lines`() =
        runTest {
            for (n in listOf("a.txt", "b.txt")) {
                local(n)
                provider.uploadThrowByPath["/$n"] = java.io.IOException("boom")
            }

            engine().syncOnce()

            val lines = Files.readAllLines(failureLog).filter { it.isNotEmpty() }
            assertEquals(2, lines.size, "$lines")
        }
}
