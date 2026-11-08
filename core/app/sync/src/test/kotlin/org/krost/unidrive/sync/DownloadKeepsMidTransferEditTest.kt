package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ConflictPolicy
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

/**
 * The pre-transfer edit check covers only scan→placement-cleared. A save that lands while a long
 * download runs used to be replaced with nothing logged; the placement now re-checks the stats the
 * check cleared against and keeps the mid-transfer edit as a conflict-local copy (gkrost/unidrive-cli#451).
 */
class DownloadKeepsMidTransferEditTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider
    private lateinit var engine: SyncEngine

    private val syncedModified = Instant.parse("2026-03-28T12:00:00Z")
    private val remoteModified = Instant.parse("2026-03-28T13:00:00Z")
    private val baselineBytes = "the synced bytes..".toByteArray()
    private val editedBytes = "the edited bytes!!".toByteArray()
    private val remoteBytes = "the remote bytes!".toByteArray()

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-dl-root")
        db = StateDatabase(Files.createTempDirectory("unidrive-dl-db").resolve("state.db"))
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

    private fun seedRow() {
        val local = syncRoot.resolve("notes.md")
        Files.write(local, baselineBytes)
        Files.setLastModifiedTime(local, FileTime.from(syncedModified))
        db.upsertEntry(
            SyncEntry(
                path = "/notes.md",
                remoteId = "id-/notes.md",
                remoteHash = "baseline-hash-/notes.md",
                remoteSize = baselineBytes.size.toLong(),
                remoteModified = syncedModified,
                localMtime = syncedModified.toEpochMilli(),
                localSize = baselineBytes.size.toLong(),
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.now(),
            ),
        )
    }

    private fun conflictCopies(): List<String> =
        Files.list(syncRoot).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .map { it.fileName.toString() }
                .filter { it.contains(".conflict-local-") }
                .toList()
        }

    @Test
    fun `an edit saved while the download runs is kept as a conflict copy and the download still installs`() =
        runTest {
            seedRow()
            provider.files["/notes.md"] = remoteBytes
            provider.deltaItems = listOf(remoteItem())
            provider.beforeDownload = {
                val local = syncRoot.resolve("notes.md")
                Files.write(local, editedBytes)
                Files.setLastModifiedTime(local, FileTime.from(Instant.parse("2026-03-28T12:30:00Z")))
            }

            engine.syncOnce()

            val local = syncRoot.resolve("notes.md")
            assertContentEquals(remoteBytes, Files.readAllBytes(local), "the download installs the remote bytes")
            assertEquals(remoteModified.toEpochMilli(), Files.getLastModifiedTime(local).toMillis())
            val copies = conflictCopies()
            assertTrue(copies.isNotEmpty(), "the mid-transfer edit must be kept as a conflict-local copy")
            assertEquals(1, copies.size, "one copy, for the one edit: $copies")
            assertContentEquals(
                editedBytes,
                Files.readAllBytes(syncRoot.resolve(copies[0])),
                "the copy holds the edited bytes, not the remote's",
            )
            assertTrue(
                provider.uploadedPaths.any { it == "/${copies[0]}" },
                "the kept copy uploads in this pass, uploaded=${provider.uploadedPaths}",
            )
            val row = db.getEntry("/notes.md")
            assertTrue(row != null && row.remoteHash == "remote-hash-/notes.md", "the row records the download")
        }

    @Test
    fun `a download with no edit during the transfer keeps no copy`() =
        runTest {
            seedRow()
            provider.files["/notes.md"] = remoteBytes
            provider.deltaItems = listOf(remoteItem())

            engine.syncOnce()

            assertEquals(emptyList(), conflictCopies(), "an unedited file takes the download without a copy")
            assertContentEquals(remoteBytes, Files.readAllBytes(syncRoot.resolve("notes.md")))
            assertTrue(provider.uploadedPaths.isEmpty(), "nothing to upload, uploaded=${provider.uploadedPaths}")
        }
}
