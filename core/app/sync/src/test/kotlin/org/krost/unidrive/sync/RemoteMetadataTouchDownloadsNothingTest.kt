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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A remote mtime-only change on a file whose bytes the row already records downloads nothing
 * (#264 item 3): the pass records the new remote metadata on the row and restores the local
 * mtime, and no download call reaches the provider.
 */
class RemoteMetadataTouchDownloadsNothingTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider
    private lateinit var engine: SyncEngine

    private val syncedModified = Instant.parse("2026-03-28T12:00:00Z")
    private val touchedModified = Instant.parse("2026-03-28T12:05:00Z")
    private val bytes = "the synced bytes".toByteArray()

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-touch-root")
        db = StateDatabase(Files.createTempDirectory("unidrive-touch-db").resolve("state.db"))
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

    private fun touchedItem() =
        CloudItem(
            id = "id-/notes.md",
            name = "notes.md",
            path = "/notes.md",
            size = bytes.size.toLong(),
            isFolder = false,
            modified = touchedModified,
            created = Instant.parse("2026-03-28T10:00:00Z"),
            hash = "hash-/notes.md",
            mimeType = "application/octet-stream",
        )

    @Test
    fun `a remote mtime-only change records the metadata and downloads nothing`() =
        runTest {
            val local = syncRoot.resolve("notes.md")
            Files.write(local, bytes)
            Files.setLastModifiedTime(local, FileTime.from(syncedModified))
            db.upsertEntry(
                SyncEntry(
                    path = "/notes.md",
                    remoteId = "id-/notes.md",
                    remoteHash = "hash-/notes.md",
                    remoteSize = bytes.size.toLong(),
                    remoteModified = syncedModified,
                    localMtime = syncedModified.toEpochMilli(),
                    localSize = bytes.size.toLong(),
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = Instant.now(),
                ),
            )
            db.setSyncState("delta_cursor", "cursor-1")
            provider.files["/notes.md"] = "other bytes the cloud may hold".toByteArray()
            provider.deltaItems = listOf(touchedItem())
            provider.deltaCursor = "cursor-2"

            engine.syncOnce()

            assertTrue(
                provider.downloadByPathCalls.none { it == "/notes.md" } &&
                    provider.downloadByIdCalls.none { it.second == "/notes.md" },
                "unchanged bytes must not download, by-path=${provider.downloadByPathCalls} by-id=${provider.downloadByIdCalls}",
            )
            assertContentEquals(bytes, Files.readAllBytes(local), "the local bytes stay")
            assertEquals(
                touchedModified.toEpochMilli(),
                Files.getLastModifiedTime(local).toMillis(),
                "the local mtime follows the remote's",
            )
            val row = db.getEntry("/notes.md")
            assertNotNull(row, "the row stays")
            assertEquals(touchedModified, row.remoteModified, "the row records the delivered metadata")
            assertEquals(touchedModified.toEpochMilli(), row.localMtime, "the row's baseline is the restored mtime")
            assertEquals("hash-/notes.md", row.remoteHash)
        }
}
