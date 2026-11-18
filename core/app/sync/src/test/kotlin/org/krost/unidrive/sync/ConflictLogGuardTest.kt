package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ChangeState
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncAction
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.io.path.listDirectoryEntries
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * #779: the conflict journal is a diary, the loser backup is data. A journal that cannot be written never fails an
 * action whose keep-aside already moved the file (the retry would make a second conflict copy); a loser backup that
 * cannot be made fails the action before the local file is replaced.
 */
class ConflictLogGuardTest {
    private lateinit var syncRoot: Path
    private lateinit var meta: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider
    private val t0 = Instant.parse("2026-01-01T00:00:00Z")

    @BeforeTest
    fun setUp() {
        syncRoot = TestScratch.directory("ud-779-root")
        meta = TestScratch.directory("ud-779-meta")
        db = StateDatabase(meta.resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun engine(
        policy: ConflictPolicy,
        log: ConflictLog,
    ) = SyncEngine(
        provider = provider,
        db = db,
        syncRoot = syncRoot,
        conflictPolicy = policy,
        reporter = ProgressReporter.Silent,
        conflictLog = log,
    )

    private fun remote(
        bytes: ByteArray,
        modified: Instant,
    ): CloudItem {
        provider.files["/doc.txt"] = bytes
        return CloudItem(
            id = "id-doc",
            name = "doc.txt",
            path = "/doc.txt",
            size = bytes.size.toLong(),
            isFolder = false,
            modified = modified,
            created = t0,
            hash = null,
            mimeType = "text/plain",
        )
    }

    private fun conflict(
        item: CloudItem,
        policy: ConflictPolicy,
    ) = SyncAction.Conflict(
        path = "/doc.txt",
        localState = ChangeState.MODIFIED,
        remoteState = ChangeState.MODIFIED,
        remoteItem = item,
        policy = policy,
    )

    @Test
    fun `an unwritable conflict journal does not fail a keep-both whose local edit was already moved aside`() =
        runTest {
            // A directory where the journal file should be: every append fails.
            val journal = Files.createDirectories(meta.resolve("conflicts.jsonl"))
            val log = ConflictLog(journal, meta.resolve("backups"))
            Files.writeString(syncRoot.resolve("doc.txt"), "local edit")
            val item = remote("cloud bytes".toByteArray(), t0.plusSeconds(60))

            engine(ConflictPolicy.KEEP_BOTH, log).applyConflict(conflict(item, ConflictPolicy.KEEP_BOTH))

            assertEquals("cloud bytes", Files.readString(syncRoot.resolve("doc.txt")), "the cloud's version took the path")
            val copies = syncRoot.listDirectoryEntries().filter { it.fileName.toString() != "doc.txt" }
            assertEquals(1, copies.size, "exactly one conflict copy: $copies")
            assertEquals("local edit", Files.readString(copies.single()))
        }

    @Test
    fun `a loser backup that cannot be made fails last-writer-wins before the local file is replaced`() =
        runTest {
            // A regular file where the backup folder should be: the backup copy cannot be made.
            val backups = meta.resolve("backups")
            Files.writeString(backups, "not a folder")
            val log = ConflictLog(meta.resolve("conflicts.jsonl"), backups)
            val local = syncRoot.resolve("doc.txt")
            Files.writeString(local, "local edit")
            Files.setLastModifiedTime(local, FileTime.from(t0))
            val item = remote("cloud bytes".toByteArray(), t0.plusSeconds(3600))

            assertFails { engine(ConflictPolicy.LAST_WRITER_WINS, log).applyConflict(conflict(item, ConflictPolicy.LAST_WRITER_WINS)) }

            assertEquals("local edit", Files.readString(local), "the loser stays in place when it could not be backed up")
            assertTrue(provider.downloadByIdCalls.isEmpty(), "nothing was downloaded over it")
        }
}
