package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ChangeState
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncAction
import org.krost.unidrive.sync.model.SyncEntry
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
import kotlin.test.assertTrue

/**
 * #768: the download paths beside applyDownload (a CreatePlaceholder that downloads or truncates, conflict resolution)
 * never place content over a local file that differs from the state the row last synced without keeping it aside first;
 * a last-writer-wins loser is kept even when the engine has no conflict log.
 */
class PlaceholderOverwriteGuardTest {
    private lateinit var syncRoot: Path
    private lateinit var meta: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider
    private val t0 = Instant.parse("2026-01-01T00:00:00Z")

    @BeforeTest
    fun setUp() {
        syncRoot = TestScratch.directory("ud-768-root")
        meta = TestScratch.directory("ud-768-meta")
        db = StateDatabase(meta.resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun engine(
        policy: ConflictPolicy = ConflictPolicy.KEEP_BOTH,
        log: ConflictLog? = null,
    ) = SyncEngine(
        provider = provider,
        db = db,
        syncRoot = syncRoot,
        conflictPolicy = policy,
        reporter = ProgressReporter.Silent,
        conflictLog = log,
        syncDirection = SyncDirection.DOWNLOAD,
    )

    private fun remote(
        bytes: ByteArray,
        modified: Instant = t0,
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

    private val local: Path get() = syncRoot.resolve("doc.txt")

    // A synced row whose baseline is the local file as it is now.
    private fun baselineRow() {
        db.upsertEntry(
            SyncEntry(
                path = "/doc.txt",
                remoteId = "id-doc",
                remoteHash = null,
                remoteSize = Files.size(local),
                remoteModified = t0,
                localMtime = Files.getLastModifiedTime(local).toMillis(),
                localSize = Files.size(local),
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = t0,
            ),
        )
    }

    private fun copies(): List<Path> = syncRoot.listDirectoryEntries().filter { it.fileName.toString() != "doc.txt" }

    private fun write(text: String, at: Instant) {
        Files.writeString(local, text)
        Files.setLastModifiedTime(local, FileTime.from(at))
    }

    @Test
    fun `a zero-byte remote placed over a local file edited since its sync keeps the edit aside`() =
        runTest {
            write("synced", t0)
            baselineRow()
            write("edited after the sync", t0.plusSeconds(10))

            engine().applyCreatePlaceholder(SyncAction.CreatePlaceholder("/doc.txt", remote(ByteArray(0)), shouldHydrate = true))

            assertEquals(0L, Files.size(local), "the cloud's empty file took the path")
            assertEquals(listOf("edited after the sync"), copies().map { Files.readString(it) })
        }

    @Test
    fun `a both-new adoption whose local file changed before the apply keeps it aside instead of downloading over it`() =
        runTest {
            // No row: the local file is new. The plan adopted it on a size match; then it was edited to another size.
            write("a longer local edit made after the plan", t0)

            engine().applyCreatePlaceholder(SyncAction.CreatePlaceholder("/doc.txt", remote("cloud".toByteArray()), shouldHydrate = true))

            assertEquals("cloud", Files.readString(local))
            assertEquals(listOf("a longer local edit made after the plan"), copies().map { Files.readString(it) })
        }

    @Test
    fun `a placeholder over a local file still at its synced state is replaced without a conflict copy`() =
        runTest {
            write("synced", t0)
            baselineRow()

            engine().applyCreatePlaceholder(SyncAction.CreatePlaceholder("/doc.txt", remote("new cloud bytes".toByteArray()), shouldHydrate = true))

            assertEquals("new cloud bytes", Files.readString(local))
            assertEquals(emptyList(), copies())
        }

    private fun lwwConflict(item: CloudItem) =
        SyncAction.Conflict(
            path = "/doc.txt",
            localState = ChangeState.MODIFIED,
            remoteState = ChangeState.MODIFIED,
            remoteItem = item,
            policy = ConflictPolicy.LAST_WRITER_WINS,
        )

    @Test
    fun `last-writer-wins without a conflict log keeps the losing local file aside`() =
        runTest {
            write("synced", t0)
            baselineRow()
            write("older local edit", t0.plusSeconds(10))

            engine(ConflictPolicy.LAST_WRITER_WINS).applyConflict(lwwConflict(remote("newer cloud".toByteArray(), t0.plusSeconds(3600))))

            assertEquals("newer cloud", Files.readString(local))
            assertEquals(listOf("older local edit"), copies().map { Files.readString(it) })
        }

    @Test
    fun `last-writer-wins with a conflict log backs the loser up once and leaves no extra copy`() =
        runTest {
            write("synced", t0)
            baselineRow()
            write("older local edit", t0.plusSeconds(10))
            val backups = meta.resolve("backups")
            val log = ConflictLog(meta.resolve("conflicts.jsonl"), backups)

            engine(ConflictPolicy.LAST_WRITER_WINS, log).applyConflict(lwwConflict(remote("newer cloud".toByteArray(), t0.plusSeconds(3600))))

            assertEquals("newer cloud", Files.readString(local))
            assertEquals(emptyList(), copies(), "the backup holds the loser; the sync root gets no copy")
            assertTrue(backups.listDirectoryEntries().map { Files.readString(it) } == listOf("older local edit"))
        }
}
