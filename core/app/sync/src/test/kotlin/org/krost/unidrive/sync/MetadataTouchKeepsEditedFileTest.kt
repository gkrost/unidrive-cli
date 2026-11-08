package org.krost.unidrive.sync

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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A metadata-only remote change must not touch over a local edit that landed since the scan: a
 * same-size rewrite whose mtime the touch would reset to the cloud's, recorded as the baseline,
 * reads as synced at the next scan and a later cloud change downloads over it
 * (gkrost/unidrive-cli#451). A file that moved away has nothing to touch either.
 */
class MetadataTouchKeepsEditedFileTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider
    private lateinit var engine: SyncEngine

    private val syncedModified = Instant.parse("2026-03-28T12:00:00Z")
    private val touchedModified = Instant.parse("2026-03-28T12:05:00Z")
    private val editedMtime = Instant.parse("2026-03-28T12:02:00Z")
    private val syncedBytes = "the synced bytes".toByteArray()
    private val editedBytes = "the edited bytes".toByteArray()

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
            size = syncedBytes.size.toLong(),
            isFolder = false,
            modified = touchedModified,
            created = Instant.parse("2026-03-28T10:00:00Z"),
            hash = "hash-/notes.md",
            mimeType = "application/octet-stream",
        )

    private fun seedRow() {
        db.upsertEntry(
            SyncEntry(
                path = "/notes.md",
                remoteId = "id-/notes.md",
                remoteHash = "hash-/notes.md",
                remoteSize = syncedBytes.size.toLong(),
                remoteModified = syncedModified,
                localMtime = syncedModified.toEpochMilli(),
                localSize = syncedBytes.size.toLong(),
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.now(),
            ),
        )
    }

    private fun row() = assertNotNull(db.getEntry("/notes.md"))

    @Test
    fun `a same-size edit since the scan is not touched over - the row keeps its baseline`() {
        seedRow()
        val local = syncRoot.resolve("notes.md")
        Files.write(local, editedBytes)
        Files.setLastModifiedTime(local, FileTime.from(editedMtime))

        engine.applyTouchMetadata(SyncAction.TouchMetadata("/notes.md", touchedItem()))

        assertContentEquals(editedBytes, Files.readAllBytes(local), "the edited bytes stay in place")
        assertEquals(
            editedMtime.toEpochMilli(),
            Files.getLastModifiedTime(local).toMillis(),
            "the local mtime is not reset to the cloud's",
        )
        assertEquals(syncedModified, row().remoteModified, "the row keeps the undelivered remote metadata")
        assertEquals(syncedModified.toEpochMilli(), row().localMtime, "the row keeps the scan baseline")
    }

    @Test
    fun `a file that moved away since the scan is not touched`() {
        seedRow()
        val local = syncRoot.resolve("notes.md")
        Files.write(local, syncedBytes)
        Files.setLastModifiedTime(local, FileTime.from(syncedModified))
        Files.move(local, syncRoot.resolve("renamed.md"))

        engine.applyTouchMetadata(SyncAction.TouchMetadata("/notes.md", touchedItem()))

        assertTrue(Files.notExists(local), "nothing was recreated at the row's path")
        assertEquals(syncedModified, row().remoteModified, "the row keeps the undelivered remote metadata")
        assertEquals(syncedModified.toEpochMilli(), row().localMtime, "the row keeps the scan baseline")
    }
}
