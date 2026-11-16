package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ChangeState
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncAction
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a buffered reconcile plans around a tombstone of another item than the row it meets (gkrost/unidrive-cli#743):
 * the follow-up plan for the replacement, the folder case, and the tombstones the engine synthesizes from a row (a
 * full-listing sweep, a folder expansion), which carry the row's own id and so keep pairing with it.
 */
class StaleTombstoneIdCoverageTest {
    private lateinit var db: StateDatabase
    private lateinit var syncRoot: Path

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-stale-tomb-cov")
        db = StateDatabase(Files.createTempDirectory("unidrive-stale-tomb-cov-db").resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private val t0 = Instant.parse("2026-03-28T12:00:00Z")

    private fun row(
        path: String,
        remoteId: String,
        isFolder: Boolean = false,
    ) = SyncEntry(
        path = path,
        remoteId = remoteId,
        remoteHash = if (isFolder) null else "h",
        remoteSize = 100,
        remoteModified = t0,
        localMtime = 1711627200000,
        localSize = 100,
        isFolder = isFolder,
        isPinned = false,
        isHydrated = true,
        lastSynced = t0,
    )

    private fun tombstone(
        path: String,
        id: String,
        isFolder: Boolean = false,
    ) = CloudItem(id, path.substringAfterLast('/'), path, 0, isFolder, null, null, null, null, deleted = true)

    private fun plan(
        item: CloudItem,
        local: ChangeState,
    ): List<SyncAction> = Reconciler(db, syncRoot, ConflictPolicy.KEEP_BOTH).reconcile(mapOf(item.path to item), mapOf(item.path to local))

    @Test
    fun `an edited file under a stale tombstone is uploaded onto the replacement's id`() {
        db.upsertEntry(row("/a.txt", "id-new"))
        Files.write(syncRoot.resolve("a.txt"), ByteArray(33))
        val uploads = plan(tombstone("/a.txt", "id-old"), ChangeState.MODIFIED)
        assertEquals(listOf("id-new"), uploads.filterIsInstance<SyncAction.Upload>().map { it.remoteId }, "plan: $uploads")
        assertTrue(uploads.none { it is SyncAction.Conflict || it is SyncAction.DeleteLocal }, "plan: $uploads")
    }

    @Test
    fun `a file deleted locally under a stale tombstone is deleted in the cloud`() {
        db.upsertEntry(row("/a.txt", "id-new"))
        val actions = plan(tombstone("/a.txt", "id-old"), ChangeState.DELETED)
        assertEquals(listOf(SyncAction.DeleteRemote("/a.txt")), actions)
    }

    @Test
    fun `a folder tombstone of another id leaves the folder row and its directory alone`() {
        db.upsertEntry(row("/d", "dir-new", isFolder = true))
        Files.createDirectories(syncRoot.resolve("d"))
        assertEquals(emptyList(), plan(tombstone("/d", "dir-old", isFolder = true), ChangeState.UNCHANGED))
    }

    @Test
    fun `a tombstone synthesized from the row carries its id and still deletes the unchanged file`() {
        // the full-listing sweep and the folder expansion build their tombstones from the row
        val tracked = row("/a.txt", "id-1")
        db.upsertEntry(tracked)
        Files.write(syncRoot.resolve("a.txt"), ByteArray(33))
        assertEquals(listOf(SyncAction.DeleteLocal("/a.txt", remoteId = "id-1")), plan(tombstone("/a.txt", tracked.remoteId!!), ChangeState.UNCHANGED))
    }
}
