package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncAction
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.krost.unidrive.testing.TestScratch

/**
 * gkrost/unidrive-cli#742 finding 3: user data created locally must never be silently overwritten.
 * A file that exists at a download's destination when the download starts, created after the scan
 * planned a first-time download (so the row has no local baseline), has to survive as a conflict-local
 * copy, the way an edit against a recorded baseline does.
 */
class DownloadOverFileCreatedAfterScanTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider
    private lateinit var engine: SyncEngine

    private val syncedModified = Instant.parse("2026-03-28T12:00:00Z")
    private val remoteModified = Instant.parse("2026-03-28T13:00:00Z")
    private val baselineBytes = "the synced bytes..".toByteArray()
    private val localBytes = "typed by the user after the scan".toByteArray()
    private val remoteBytes = "the remote bytes!".toByteArray()

    @BeforeTest
    fun setUp() {
        syncRoot = TestScratch.directory("unidrive-dl-late-root")
        db = StateDatabase(TestScratch.directory("unidrive-dl-late-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
        engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = syncRoot,
                conflictPolicy = ConflictPolicy.KEEP_BOTH,
                useTrash = false,
            )
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun remoteItem() =
        CloudItem(
            id = "id-/notes.md",
            name = "notes.md",
            path = "/notes.md",
            size = remoteBytes.size.toLong(),
            isFolder = false,
            modified = remoteModified,
            created = Instant.parse("2026-03-28T10:00:00Z"),
            hash = "remote-hash-/notes.md",
            mimeType = "application/octet-stream",
        )

    private fun row(
        hydrated: Boolean,
        localMtime: Long?,
        localSize: Long?,
    ) = SyncEntry(
        path = "/notes.md",
        remoteId = "id-/notes.md",
        remoteHash = "baseline-hash-/notes.md",
        remoteSize = baselineBytes.size.toLong(),
        remoteModified = syncedModified,
        localMtime = localMtime,
        localSize = localSize,
        isFolder = false,
        isPinned = false,
        isHydrated = hydrated,
        lastSynced = Instant.now(),
    )

    private fun otherFiles(local: Path): List<Pair<String, ByteArray>> =
        Files.walk(syncRoot).use { s ->
            s.filter { Files.isRegularFile(it) && it != local }.map { it.fileName.toString() to Files.readAllBytes(it) }.toList()
        }

    @Test
    fun `a file created after the scan at the path of a baseline-less download is kept as a conflict copy`() =
        runTest {
            // Unhydrated row: no local mtime or size, so there is no baseline to judge an edit against.
            db.upsertEntry(row(hydrated = false, localMtime = null, localSize = null))
            provider.files["/notes.md"] = remoteBytes
            // The user creates the file after the scan planned the download, before the download starts.
            val local = syncRoot.resolve("notes.md")
            Files.write(local, localBytes)

            engine.applyDownload(SyncAction.DownloadContent("/notes.md", remoteItem()))

            val copies = otherFiles(local)
            val survivors = listOf(Files.readAllBytes(local)) + copies.map { it.second }
            assertTrue(
                survivors.any { it.contentEquals(localBytes) },
                "the user's bytes must survive somewhere; found ${survivors.map { String(it) }}",
            )
            assertEquals(1, copies.size, "the created file is kept as one conflict copy: ${copies.map { it.first }}")
            assertTrue(copies[0].first.contains(".conflict-local-"), "the copy is a conflict-local copy: ${copies[0].first}")
        }

    @Test
    fun `a file edited after the scan against a recorded baseline is kept as a conflict copy`() =
        runTest {
            val local = syncRoot.resolve("notes.md")
            Files.write(local, baselineBytes)
            Files.setLastModifiedTime(local, FileTime.from(syncedModified))
            db.upsertEntry(
                row(hydrated = true, localMtime = syncedModified.toEpochMilli(), localSize = baselineBytes.size.toLong()),
            )
            provider.files["/notes.md"] = remoteBytes
            Files.write(local, localBytes)
            Files.setLastModifiedTime(local, FileTime.from(Instant.parse("2026-03-28T12:30:00Z")))

            engine.applyDownload(SyncAction.DownloadContent("/notes.md", remoteItem()))

            assertContentEquals(remoteBytes, Files.readAllBytes(local), "the download installs the remote bytes")
            val copies = otherFiles(local)
            assertEquals(1, copies.size, "the edit is kept as one conflict copy: ${copies.map { it.first }}")
            assertContentEquals(localBytes, copies[0].second, "the copy holds the edited bytes")
            assertTrue(copies[0].first.contains(".conflict-local-"), "the copy is a conflict-local copy: ${copies[0].first}")
        }
}
