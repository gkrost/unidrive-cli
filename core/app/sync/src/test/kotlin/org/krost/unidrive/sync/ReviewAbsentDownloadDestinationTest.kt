package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncAction
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #513 review: a download whose destination was PROVEN empty when cleared (an unhydrated row, no local
 * baseline at all) must not replace a file that appears during the transfer. The mid-transfer re-check
 * used to fall back to the row for null baselines, read the new file as unedited, and replace it with
 * nothing kept; the placement now keeps an appeared file aside as a conflict-local copy, without a
 * baseline, the way an edited file is kept when the destination existed.
 */
class ReviewAbsentDownloadDestinationTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider
    private lateinit var engine: SyncEngine

    private val syncedModified = Instant.parse("2026-03-28T12:00:00Z")
    private val remoteModified = Instant.parse("2026-03-28T13:00:00Z")
    private val createdBytes = "created during the download".toByteArray()
    private val remoteBytes = "the remote bytes!".toByteArray()

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-dl-absent-root")
        db = StateDatabase(Files.createTempDirectory("unidrive-dl-absent-db").resolve("state.db"))
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

    @Test
    fun `a file created during a download to an absent unhydrated path is preserved`() =
        runTest {
        // The row is unhydrated: no local file, no local mtime or size — the destination is empty and
        // the row offers no baseline either. The pre-transfer guard proves the absence; the file the
        // hook creates during the transfer has nothing to be judged against and must be kept aside
        // before the placement replaces it.
        db.upsertEntry(
            SyncEntry(
                path = "/notes.md",
                remoteId = "id-/notes.md",
                remoteHash = "baseline-hash-/notes.md",
                remoteSize = remoteBytes.size.toLong(),
                remoteModified = syncedModified,
                localMtime = null,
                localSize = null,
                isFolder = false,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )
        provider.files["/notes.md"] = remoteBytes
        provider.beforeDownload = {
            Files.write(syncRoot.resolve("notes.md"), createdBytes)
        }

        engine.applyDownload(SyncAction.DownloadContent("/notes.md", remoteItem()))

        val local = syncRoot.resolve("notes.md")
        assertContentEquals(remoteBytes, Files.readAllBytes(local), "the download installs the remote bytes")
        val kept =
            Files.walk(syncRoot).use { s ->
                s.filter { Files.isRegularFile(it) && it != local }.map { it.fileName.toString() to Files.readAllBytes(it) }.toList()
            }
        assertEquals(1, kept.size, "the file that appeared mid-transfer is kept as one conflict copy: $kept")
        assertContentEquals(createdBytes, kept[0].second, "the copy holds the created bytes, not the remote's")
        assertTrue(kept[0].first.contains(".conflict-local-"), "the copy is a conflict-local copy: ${kept[0].first}")
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
}
