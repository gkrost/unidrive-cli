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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Keep-both on "remote deleted, local modified" must upload the kept file to the row's
 * canonical remote path (#264 item 1). The row of an XDG-aliased file is keyed at the
 * real-local path (/Bilder/x.jpg) and carries the canonical (/Pictures/x.jpg) in
 * remotePath; an upload to the real-local path would build a /Bilder tree in the cloud
 * and the row would lose its alias. A non-aliased row keeps uploading in place.
 */
class KeepBothRemoteDeletedCanonicalUploadTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider
    private lateinit var engine: SyncEngine

    private val remoteModified = Instant.parse("2026-03-28T12:00:00Z")
    private val baselineBytes = "the synced bytes".toByteArray()
    private val editBytes = "my own edit".toByteArray()
    private val baselineMtime = FileTime.from(Instant.parse("2026-03-28T12:00:00Z"))
    private val editMtime = FileTime.from(Instant.parse("2026-04-01T08:00:00Z"))

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-keepboth-canonical-root")
        db = StateDatabase(Files.createTempDirectory("unidrive-keepboth-canonical-db").resolve("state.db"))
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

    private fun cloudItem(
        path: String,
        size: Long = 100,
        isFolder: Boolean = false,
        deleted: Boolean = false,
    ) = CloudItem(
        id = "id-$path",
        name = path.substringAfterLast("/"),
        path = path,
        size = size,
        isFolder = isFolder,
        modified = remoteModified,
        created = Instant.parse("2026-03-28T10:00:00Z"),
        hash = if (isFolder) null else "hash-$path",
        mimeType = "application/octet-stream",
        deleted = deleted,
    )

    /** The aliased row: keyed at the real-local path, canonical path in remotePath. */
    private fun seedAliasedRow() {
        val local = syncRoot.resolve("Bilder").resolve("x.jpg")
        Files.createDirectories(local.parent)
        Files.write(local, editBytes)
        Files.setLastModifiedTime(local, editMtime)
        db.upsertEntry(
            SyncEntry(
                path = "/Bilder/x.jpg",
                remotePath = "/Pictures/x.jpg",
                remoteId = "id-/Pictures/x.jpg",
                remoteHash = "hash-/Pictures/x.jpg",
                remoteSize = baselineBytes.size.toLong(),
                remoteModified = remoteModified,
                localMtime = baselineMtime.toMillis(),
                localSize = baselineBytes.size.toLong(),
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.now(),
            ),
        )
        db.setSyncState("delta_cursor", "cursor-1")
    }

    @Test
    fun `keep-both uploads a remote-deleted aliased row to its canonical remote path`() =
        runTest {
            seedAliasedRow()
            provider.files["/Pictures/x.jpg"] = baselineBytes
            // The alive canonical top-level folder in the page is what fires the alias; the
            // tombstone at the canonical pairs with the aliased row by effective remote path.
            provider.deltaItems =
                listOf(
                    cloudItem("/Pictures", isFolder = true),
                    cloudItem("/Pictures/x.jpg", size = baselineBytes.size.toLong(), deleted = true),
                )
            provider.deltaCursor = "cursor-2"

            engine.syncOnce()

            assertTrue(
                provider.uploadAttempts.contains("/Pictures/x.jpg"),
                "the kept edit uploads to the canonical remote path, found ${provider.uploadAttempts}",
            )
            assertFalse(
                provider.uploadAttempts.contains("/Bilder/x.jpg"),
                "no upload to the real-local alias path, found ${provider.uploadAttempts}",
            )
            assertTrue(Files.exists(syncRoot.resolve("Bilder/x.jpg")), "the local edit stays at its path")

            val row = db.getEntry("/Bilder/x.jpg")
            assertNotNull(row, "the row stays keyed at the real-local path")
            assertEquals("/Pictures/x.jpg", row.remotePath, "the row keeps its alias")
            assertNull(db.getEntry("/Pictures/x.jpg"), "no phantom canonical-keyed row")
        }

    @Test
    fun `keep-both uploads a remote-deleted non-aliased row in place`() =
        runTest {
            val local = syncRoot.resolve("notes.md")
            Files.write(local, editBytes)
            Files.setLastModifiedTime(local, editMtime)
            db.upsertEntry(
                SyncEntry(
                    path = "/notes.md",
                    remoteId = "id-/notes.md",
                    remoteHash = "hash-/notes.md",
                    remoteSize = baselineBytes.size.toLong(),
                    remoteModified = remoteModified,
                    localMtime = baselineMtime.toMillis(),
                    localSize = baselineBytes.size.toLong(),
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = Instant.now(),
                ),
            )
            db.setSyncState("delta_cursor", "cursor-1")
            provider.files["/notes.md"] = baselineBytes
            provider.deltaItems = listOf(cloudItem("/notes.md", size = baselineBytes.size.toLong(), deleted = true))
            provider.deltaCursor = "cursor-2"

            engine.syncOnce()

            assertTrue(
                provider.uploadAttempts.contains("/notes.md"),
                "the kept edit uploads in place, found ${provider.uploadAttempts}",
            )
            val row = db.getEntry("/notes.md")
            assertNotNull(row)
            assertNull(row.remotePath, "a non-aliased row keeps remotePath null")
        }
}
