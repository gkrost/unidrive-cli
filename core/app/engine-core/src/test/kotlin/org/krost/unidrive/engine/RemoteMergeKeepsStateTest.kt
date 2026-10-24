package org.krost.unidrive.engine

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the remote merge (`RemoteGather.updateRemoteEntries`) leaves alone when the cloud delivers an item again:
 * a delta that rewinds its cursor (Internxt) re-delivers the same items at every poll, and none of it may erase the
 * marks of an edit that has not reached the cloud.
 */
class RemoteMergeKeepsStateTest {
    private lateinit var tmp: Path
    private lateinit var db: StateDatabase

    private val synced = Instant.parse("2026-03-01T00:00:00Z")

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("unidrive-remote-merge-state")
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
            log = LoggerFactory.getLogger(RemoteMergeKeepsStateTest::class.java),
        )

    private fun cachePath(path: String): Path = tmp.resolve("cache").resolve(path.removePrefix("/"))

    private fun item(
        id: String,
        path: String,
        size: Long = 5,
        hash: String? = "h1",
    ) = CloudItem(
        id = id,
        name = path.substringAfterLast('/'),
        path = path,
        size = size,
        isFolder = false,
        modified = null,
        created = null,
        hash = hash,
        mimeType = null,
    )

    // A hydrated, cloud-backed row as the mount leaves it after a download: its baseline is the cache copy.
    private fun seedRow(
        path: String,
        id: String,
    ) {
        db.upsertEntry(
            SyncEntry(
                path = path,
                remoteId = id,
                remoteHash = "h1",
                remoteSize = 5,
                remoteModified = null,
                localMtime = 1_000,
                localSize = 5,
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = synced,
                cacheBacked = true,
            ),
        )
    }

    private fun cacheCopy(
        path: String,
        modified: Instant,
    ) {
        val file = cachePath(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, "edit!")
        Files.setLastModifiedTime(file, FileTime.from(modified))
    }

    private val guards = RemoteEnumeration.ReapGuards(cachePathOf = ::cachePath)

    @Test
    fun `an item delivered again leaves lastSynced alone, so an edit newer than it stays an unsynced edit`() {
        seedRow("/a.txt", "id-a")
        cacheCopy("/a.txt", synced.plusSeconds(60))
        assertTrue(guards.holdsUnsyncedEdit("/a.txt", db.getEntry("/a.txt")), "precondition: the edit is guarded")

        gather().updateRemoteEntries(mapOf("/a.txt" to item("id-a", "/a.txt")))

        val row = assertNotNull(db.getEntry("/a.txt"))
        assertEquals(synced, row.lastSynced, "a re-delivery is not a sync of the cache")
        assertTrue(guards.holdsUnsyncedEdit("/a.txt", row), "the edit is still guarded after the item was delivered again")
    }

    @Test
    fun `a changed item also leaves lastSynced alone`() {
        seedRow("/a.txt", "id-a")
        cacheCopy("/a.txt", synced.plusSeconds(60))

        gather().updateRemoteEntries(mapOf("/a.txt" to item("id-a", "/a.txt", size = 9, hash = "h2")))

        val row = assertNotNull(db.getEntry("/a.txt"))
        assertEquals(9, row.remoteSize, "the cloud fields follow the delivery")
        assertEquals(synced, row.lastSynced)
        assertTrue(guards.holdsUnsyncedEdit("/a.txt", row))
    }

    @Test
    fun `a delivery stores the item's write token apart from its hash, and a new token is a change for the view`() {
        seedRow("/a.txt", "id-a")

        val first = gather().updateRemoteEntries(mapOf("/a.txt" to item("id-a", "/a.txt").copy(version = "\"{A},1\"")))
        assertEquals("\"{A},1\"", db.getEntry("/a.txt")?.remoteVersion)
        assertEquals("h1", db.getEntry("/a.txt")?.remoteHash, "the content hash stays the hash")
        assertTrue("/a.txt" in first.changedPaths, "the row's token moved: the client must list it again")

        val again = gather().updateRemoteEntries(mapOf("/a.txt" to item("id-a", "/a.txt").copy(version = "\"{A},1\"")))
        assertTrue("/a.txt" !in again.changedPaths, "the same version delivered again changes nothing")

        gather().updateRemoteEntries(mapOf("/a.txt" to item("id-a", "/a.txt")))
        assertNull(db.getEntry("/a.txt")?.remoteVersion, "a delivery without a token leaves none: the hash is the token")
    }

    @Test
    fun `a new item gets its lastSynced from the merge`() {
        val before = Instant.now()

        gather().updateRemoteEntries(mapOf("/new.txt" to item("id-new", "/new.txt")))

        assertTrue(!assertNotNull(db.getEntry("/new.txt")).lastSynced.isBefore(before))
    }

    @Test
    fun `the refusal stamp, the name block and the failure time survive a delivery of an unchanged item`() {
        seedRow("/a.txt", "id-a")
        seedRow("/b.txt", "id-b")
        val failedAt = Instant.parse("2026-03-02T00:00:00Z")
        assertTrue(db.markUploadFailed("/a.txt", failedAt))
        assertTrue(db.markUploadRefused("/a.txt", "1000|5|9999999999999|too large"))
        assertTrue(db.markNameBlocked("/b.txt", "similar_name", "B.txt", failedAt))

        gather().updateRemoteEntries(mapOf("/a.txt" to item("id-a", "/a.txt"), "/b.txt" to item("id-b", "/b.txt")))

        assertEquals("1000|5|9999999999999|too large", db.uploadRefusal("/a.txt"))
        assertEquals(failedAt, db.getEntry("/a.txt")?.lastErrorAt)
        assertEquals(StateDatabase.NameBlock("similar_name", "B.txt"), db.nameBlock("/b.txt"))
        assertEquals(failedAt, db.getEntry("/b.txt")?.lastErrorAt)
    }

    @Test
    fun `the marks survive a delivery of a changed item as well`() {
        seedRow("/a.txt", "id-a")
        assertTrue(db.markUploadFailed("/a.txt", synced))
        assertTrue(db.markUploadRefused("/a.txt", "1000|5|9999999999999|too large"))

        gather().updateRemoteEntries(mapOf("/a.txt" to item("id-a", "/a.txt", size = 9, hash = "h2")))

        assertEquals("1000|5|9999999999999|too large", db.uploadRefusal("/a.txt"))
        assertEquals(synced, db.getEntry("/a.txt")?.lastErrorAt)
    }

    @Test
    fun `a name block does not follow the row to a new name`() {
        seedRow("/old.txt", "id-a")
        assertTrue(db.markNameBlocked("/old.txt", "similar_name", "OLD.txt", synced))

        gather().updateRemoteEntries(mapOf("/new.txt" to item("id-a", "/new.txt")))

        assertNull(db.getEntry("/old.txt"))
        assertNull(db.nameBlock("/new.txt"), "the block was about the old name")
    }

    @Test
    fun `a delivery after a quarantine still clears the quarantine and its failure time`() {
        seedRow("/q.txt", "id-q")
        assertTrue(db.setDownloadQuarantine("id-q", synced))

        gather().updateRemoteEntries(mapOf("/q.txt" to item("id-q", "/q.txt")))

        val row = assertNotNull(db.getEntry("/q.txt"))
        assertEquals(false, row.downloadQuarantined)
        assertNull(row.lastErrorAt)
    }

    @Test
    fun `an ordinary upsert of the row still clears the refusal and the name block`() {
        seedRow("/a.txt", "id-a")
        assertTrue(db.markUploadRefused("/a.txt", "1000|5|9999999999999|too large"))
        assertTrue(db.markNameBlocked("/a.txt", "similar_name", "A.txt", synced))

        // What a successful upload does: it rewrites the row.
        db.upsertEntry(assertNotNull(db.getEntry("/a.txt")).copy(remoteHash = "h2"))

        assertNull(db.uploadRefusal("/a.txt"))
        assertNull(db.nameBlock("/a.txt"))
    }

    // #1040 S2 (the owner's decision of 2026-10-20): two devices create the same path while this one's upload is
    // still pending. The cloud item must not turn the pending row into a synced one: that dropped the unsent bytes
    // from the replay (pendingUploadPaths) and from the reap guard, with no conflict raised. The row stays pending,
    // and the upload meets the provider's create collision (keep-both) when it is sent.
    private fun seedPendingUpload() {
        db.upsertEntry(
            SyncEntry(
                path = "/n.txt",
                remoteId = null,
                remoteHash = null,
                remoteSize = 0,
                remoteModified = null,
                localMtime = 1_000,
                localSize = 7,
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = synced,
                cacheBacked = true,
            ),
        )
        assertEquals(listOf("/n.txt"), db.pendingUploadPaths(), "precondition: the row is a pending upload")
    }

    private val theirs = mapOf("/n.txt" to item(id = "other-device", path = "/n.txt", size = 11, hash = "theirs"))

    @Test
    fun `on a mount a cloud item at the path of a pending upload leaves the pending row as it is`() {
        seedPendingUpload()

        // The mount's enumeration (RemoteEnumeration) merges with keepPendingUploads.
        gather().updateRemoteEntries(theirs, emptyMap(), keepPendingUploads = true)

        val row = assertNotNull(db.getEntry("/n.txt"))
        assertNull(row.remoteId, "the pending row does not take the other device's item")
        assertEquals(null, row.remoteHash)
        assertEquals(7L, row.localSize, "the local baseline is the unsent file's")
        assertEquals(listOf("/n.txt"), db.pendingUploadPaths(), "the unsent bytes are still owed to the cloud")
        assertNull(db.getEntryByRemoteId("other-device"), "the other item is not tracked until the upload meets it")
    }

    @Test
    fun `on a mirror the item still merges into the pending row, whose reconciler keeps both from the sync root`() {
        // The mirror's file is in the sync root, not only in a cache: its reconciler needs the merged row to see the
        // file new on both sides (ConflictDownloadFailureTest pins the keep-both that follows).
        seedPendingUpload()

        gather().updateRemoteEntries(theirs)

        assertEquals("other-device", db.getEntry("/n.txt")?.remoteId)
    }

    // The S2 residual: the arriving item is a RENAME. Another device moved its file, tracked here at /x.txt, to /n.txt
    // while this mount's upload of /n.txt is still pending (the cloud name was free: the upload has not landed). The
    // tracked rename must happen (its row follows the cloud), and the upsert at /n.txt used to evict the pending row
    // silently: the unsent bytes were owed to nobody and their cache copy was an orphan.
    private fun seedTheirsAtOldPath() {
        db.upsertEntry(
            SyncEntry(
                path = "/x.txt",
                remoteId = "other-device",
                remoteHash = "h1",
                remoteSize = 5,
                remoteModified = null,
                localMtime = null,
                localSize = null,
                isFolder = false,
                isPinned = false,
                isHydrated = false,
                lastSynced = synced,
            ),
        )
    }

    @Test
    fun `on a mount a rename onto the path of a pending upload moves the pending row aside under a conflict name`() {
        seedTheirsAtOldPath()
        seedPendingUpload()

        val merge = gather().updateRemoteEntries(theirs, emptyMap(), keepPendingUploads = true)

        val pending = db.pendingUploadPaths()
        assertEquals(1, pending.size, "the unsent bytes are still owed to the cloud, got $pending")
        val aside = pending.single()
        assertTrue(aside.matches(Regex("""/n\.conflict-local-\d{8}T\d{4}\.txt""")), "a keep-both name beside the old one: $aside")
        val row = assertNotNull(db.getEntry(aside))
        assertNull(row.remoteId)
        assertEquals(7L, row.localSize, "the pending row keeps its local baseline")
        assertEquals(1_000L, row.localMtime)
        assertEquals("other-device", db.getEntry("/n.txt")?.remoteId, "the tracked rename still follows the cloud")
        assertNull(db.getEntry("/x.txt"))
        // The enumeration carries cache copies along the reported moves, in order: the pending copy leaves first.
        assertEquals(
            listOf(RemoteGather.RemoteMerge.Move("/n.txt", aside), RemoteGather.RemoteMerge.Move("/x.txt", "/n.txt")),
            merge.moved,
        )
        assertTrue(aside in merge.changedPaths && "/n.txt" in merge.changedPaths)
    }

    @Test
    fun `on a mirror a rename onto the path of a pending upload merges as before`() {
        // The mirror's bytes are a file in the sync root, which the reconciler keeps; the merge is not changed there.
        seedTheirsAtOldPath()
        seedPendingUpload()

        val merge = gather().updateRemoteEntries(theirs)

        assertEquals("other-device", db.getEntry("/n.txt")?.remoteId)
        assertEquals(listOf(RemoteGather.RemoteMerge.Move("/x.txt", "/n.txt")), merge.moved)
    }
}
