package org.krost.unidrive.engine

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.EntryStatus
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A provider whose listing lags a trash (Internxt's `/files` shows a trashed file for an hour or more) must not
 * bring back a file the mount has just deleted: the mount's enumeration skips an item whose cloud id belongs to a
 * row the mount tombstoned a moment ago. A different id at the same path (a new upload) and an old tombstone are
 * news, and the mirror's merge is unchanged.
 */
class RemoteMergeLaggingDeleteTest {
    private lateinit var tmp: Path
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("unidrive-remote-merge-lag")
        db = StateDatabase(tmp.resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tmp.toFile().deleteRecursively()
    }

    private fun gather() =
        RemoteGather(
            provider = FakeViewProvider(),
            db = db,
            guard =
                RemoteOperationGuard(
                    standingScope = emptyList(),
                    syncPaths = emptyList(),
                    excludePatterns = emptyList(),
                    maxConcurrentTransfers = 2,
                    matchesGlob = { _, _ -> false },
                ),
            options = RemoteGather.Options(providerId = "test"),
            listener = object : RemoteGather.Listener {},
            log = LoggerFactory.getLogger(RemoteMergeLaggingDeleteTest::class.java),
        )

    private fun item(
        id: String,
        path: String,
    ) = CloudItem(
        id = id,
        name = path.substringAfterLast('/'),
        path = path,
        size = 5,
        isFolder = false,
        modified = null,
        created = null,
        hash = "h1",
        mimeType = null,
    )

    private fun seedRow(
        path: String,
        id: String,
    ) = db.upsertEntry(
        SyncEntry(
            path = path,
            remoteId = id,
            remoteHash = "h1",
            remoteSize = 5,
            remoteModified = null,
            localMtime = null,
            localSize = null,
            isFolder = false,
            isPinned = false,
            isHydrated = false,
            lastSynced = Instant.parse("2026-03-01T00:00:00Z"),
        ),
    )

    private fun mountMerge(vararg items: CloudItem) {
        val map = items.associateBy { it.path }
        gather().updateRemoteEntries(map, emptyMap(), map, keepPendingUploads = true)
    }

    @Test
    fun `a listing that still holds a file the mount just deleted does not bring it back`() {
        seedRow("/a.txt", "id-a")
        db.markDeleted("/a.txt", mountDelete = true)

        mountMerge(item("id-a", "/a.txt"))

        assertNull(db.getEntry("/a.txt"), "the lagging listing must not revive the row")
        assertEquals(EntryStatus.DELETED, db.statusOf("/a.txt"))
    }

    @Test
    fun `a new file under the same name has another id and is news`() {
        seedRow("/a.txt", "id-a")
        db.markDeleted("/a.txt", mountDelete = true)

        mountMerge(item("id-new", "/a.txt"))

        assertEquals("id-new", assertNotNull(db.getEntry("/a.txt")).remoteId)
    }

    @Test
    fun `a tombstone older than the lag window no longer holds a listed item back`() {
        db.upsertEntry(
            SyncEntry(
                path = "/old.txt", remoteId = "id-old", remoteHash = "h1", remoteSize = 5, remoteModified = null,
                localMtime = null, localSize = null, isFolder = false, isPinned = false, isHydrated = false,
                lastSynced = Instant.now().minusSeconds(24 * 3600), status = EntryStatus.DELETED,
            ),
        )

        mountMerge(item("id-old", "/old.txt"))

        assertNotNull(db.getEntry("/old.txt"), "a restore long after the delete shows again")
    }

    @Test
    fun `the mirror's merge is unchanged`() {
        seedRow("/a.txt", "id-a")
        db.markDeleted("/a.txt", mountDelete = true)

        val map = mapOf("/a.txt" to item("id-a", "/a.txt"))
        gather().updateRemoteEntries(map, emptyMap(), map)

        assertNotNull(db.getEntry("/a.txt"))
    }

    @Test
    fun `a remote delete followed by a restore with the same id is news`() {
        seedRow("/a.txt", "id-a")
        db.markDeleted("/a.txt") // enumeration reaped it; no mount delete took place

        mountMerge(item("id-a", "/a.txt"))

        assertNotNull(db.getEntry("/a.txt"), "a remote restore must not be held back as the mount's own delete")
    }

    @Test
    fun `a folder delete also holds back its descendants in a lagging listing`() {
        seedRow("/dir/a.txt", "id-a")
        db.markDescendantsDeleted("/dir", mountDelete = true)

        mountMerge(item("id-a", "/dir/a.txt"))

        assertNull(db.getEntry("/dir/a.txt"))
    }
}
