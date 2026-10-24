package org.krost.unidrive.engine

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.StateDatabase
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
import kotlin.test.assertTrue

/**
 * Renames that reach the merge together (a delta that carries several of them, or a rename and a create): the row of an
 * item is found by its remote id, so each item keeps the baseline of its own row whatever the order of the paths.
 * The old merge looked at the path first and merged an item into the row that happened to sit there.
 */
class RemoteMergeRenamesTest {
    private lateinit var tmp: Path
    private lateinit var db: StateDatabase
    private val provider = ListingTree()
    private val moves = mutableListOf<List<RemoteGather.RemoteMerge.Move>>()

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("unidrive-merge-renames")
        db = StateDatabase(tmp.resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tmp.toFile().deleteRecursively()
    }

    private fun gather(provider: org.krost.unidrive.CloudProvider = FakeViewProvider(kinds = emptySet())) =
        RemoteGather(
            provider = provider,
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
            listener =
                object : RemoteGather.Listener {
                    override fun onViewInvalidated(
                        changedPaths: Set<String>,
                        full: Boolean,
                        moved: List<RemoteGather.RemoteMerge.Move>,
                    ) {
                        moves += moved
                    }
                },
            log = LoggerFactory.getLogger(RemoteMergeRenamesTest::class.java),
        )

    private fun item(
        id: String,
        path: String,
        size: Long = 5,
        hash: String = "h-$id",
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

    // A row as the mount leaves it: [marker] is its baseline (a size nothing else has), so a row can be told by it.
    private fun seed(
        path: String,
        id: String,
        hydrated: Boolean,
        marker: Long,
    ) = db.upsertEntry(
        SyncEntry(
            path = path,
            remoteId = id,
            remoteHash = "h-$id",
            remoteSize = 5,
            remoteModified = null,
            localMtime = if (hydrated) marker else null,
            localSize = if (hydrated) marker else null,
            isFolder = false,
            isPinned = hydrated,
            isHydrated = hydrated,
            lastSynced = Instant.parse("2026-03-01T00:00:00Z"),
            cacheBacked = if (hydrated) true else null,
        ),
    )

    private fun assertRow(
        path: String,
        id: String,
        hydrated: Boolean,
        marker: Long,
    ) {
        val row = assertNotNull(db.getEntry(path), "a row at $path")
        assertEquals(id, row.remoteId, "$path belongs to $id")
        assertEquals(hydrated, row.isHydrated, "$path keeps the hydration state of $id's own row")
        assertEquals(if (hydrated) marker else null, row.localSize, "$path keeps the baseline of $id's own row")
        assertEquals(hydrated, row.isPinned)
    }

    @Test
    fun `a chain of renames in one delivery moves each row with its own baseline, whatever the order of the paths`() {
        for (order in listOf(listOf("/b", "/c"), listOf("/c", "/b"))) {
            db.resetAll()
            seed("/a", "id1", hydrated = false, marker = 0)
            seed("/b", "id2", hydrated = true, marker = 222)
            val changes = linkedMapOf<String, CloudItem>()
            for (path in order) changes[path] = if (path == "/b") item("id1", "/b") else item("id2", "/c")

            val merge = gather().updateRemoteEntries(changes)

            assertRow("/b", "id1", hydrated = false, marker = 0)
            assertRow("/c", "id2", hydrated = true, marker = 222)
            assertNull(db.getEntry("/a"))
            assertEquals(
                listOf(RemoteGather.RemoteMerge.Move("/b", "/c"), RemoteGather.RemoteMerge.Move("/a", "/b")),
                merge.moved.sortedBy { if (it.to == "/c") 0 else 1 },
                "both renames are reported, the occupant's first ($order)",
            )
            assertTrue(merge.changedPaths.containsAll(listOf("/a", "/b", "/c")))
        }
    }

    @Test
    fun `two items that swap names in one delivery swap their rows, whatever the order of the paths`() {
        for (order in listOf(listOf("/a", "/b"), listOf("/b", "/a"))) {
            db.resetAll()
            seed("/a", "id1", hydrated = false, marker = 0)
            seed("/b", "id2", hydrated = true, marker = 222)
            val changes = linkedMapOf<String, CloudItem>()
            for (path in order) changes[path] = if (path == "/a") item("id2", "/a") else item("id1", "/b")

            val merge = gather().updateRemoteEntries(changes)

            assertRow("/a", "id2", hydrated = true, marker = 222)
            assertRow("/b", "id1", hydrated = false, marker = 0)
            assertEquals(2, db.getEntryCount(), "no row is left behind under a parking name ($order)")
            // Replayed in order, the reported moves take each cache copy to its row's new path.
            val cache = mutableMapOf("/a" to "one", "/b" to "two")
            for (move in merge.moved) cache[move.to] = checkNotNull(cache.remove(move.from)) { "nothing at ${move.from}" }
            assertEquals(mapOf("/a" to "two", "/b" to "one"), cache.toMap(), "the copies follow their rows ($order)")
        }
    }

    @Test
    fun `an item created where a renamed item left does not take the renamed item's row`() {
        seed("/a", "id1", hydrated = true, marker = 111)

        val merge = gather().updateRemoteEntries(linkedMapOf("/a" to item("id3", "/a"), "/b" to item("id1", "/b")))

        assertRow("/b", "id1", hydrated = true, marker = 111)
        val created = assertNotNull(db.getEntry("/a"))
        assertEquals("id3", created.remoteId)
        assertEquals(false, created.isHydrated, "the new item has no baseline of its own yet")
        assertNull(created.localSize)
        assertEquals(listOf(RemoteGather.RemoteMerge.Move("/a", "/b")), merge.moved)
    }

    @Test
    fun `an item delivered where another item still sits and which the delivery does not move keeps the old replacement rule`() {
        seed("/a", "id1", hydrated = true, marker = 111)

        gather().updateRemoteEntries(mapOf("/a" to item("id9", "/a")))

        assertEquals("id9", db.getEntry("/a")?.remoteId, "the occupant is replaced, as before")
        assertEquals(1, db.getEntryCount())
    }

    @Test
    fun `renames that land in different merge chunks still pair each row with its item`() =
        runTest {
            seed("/a", "id1", hydrated = true, marker = 111)
            seed("/b", "id2", hydrated = true, marker = 222)
            fun cache(path: String, text: String) {
                val file = tmp.resolve("cache").resolve(path.removePrefix("/"))
                Files.createDirectories(file.parent)
                Files.writeString(file, text)
            }
            cache("/a", "one")
            cache("/b", "two")
            // id1 /a -> /b and id2 /b -> /a: the swap, one row per merge transaction.
            provider.items = listOf(item("id1", "/b"), item("id2", "/a"))
            val enumeration =
                RemoteEnumeration(
                    gather = gather(provider),
                    db = db,
                    tracker = org.krost.unidrive.sync.EnumerationTracker(),
                    reapGuards = RemoteEnumeration.ReapGuards(cachePathOf = { tmp.resolve("cache").resolve(it.removePrefix("/")) }),
                    log = LoggerFactory.getLogger(RemoteMergeRenamesTest::class.java),
                    mergeChunkRows = 1,
                )

            val result = enumeration.enumerate(reset = false)

            assertTrue(result.ok)
            assertRow("/a", "id2", hydrated = true, marker = 222)
            assertRow("/b", "id1", hydrated = true, marker = 111)
            assertEquals("two", Files.readString(tmp.resolve("cache/a")), "the cache copy of id2 followed its row")
            assertEquals("one", Files.readString(tmp.resolve("cache/b")), "the cache copy of id1 followed its row")
        }

    private fun folderItem(
        id: String,
        path: String,
        parentId: String?,
    ) = CloudItem(
        id = id,
        name = path.substringAfterLast('/'),
        path = path,
        size = 0,
        isFolder = true,
        modified = null,
        created = null,
        hash = null,
        mimeType = null,
        parentId = parentId,
    )

    private fun seedFolder(
        path: String,
        id: String,
        remotePath: String? = null,
    ) = db.upsertEntry(
        SyncEntry(
            path = path,
            remotePath = remotePath,
            remoteId = id,
            remoteHash = null,
            remoteSize = 0,
            remoteModified = null,
            localMtime = null,
            localSize = null,
            isFolder = true,
            isPinned = false,
            isHydrated = false,
            lastSynced = Instant.parse("2026-03-01T00:00:00Z"),
        ),
    )

    private fun enumeration() =
        RemoteEnumeration(
            gather = gather(provider),
            db = db,
            tracker = org.krost.unidrive.sync.EnumerationTracker(),
            reapGuards = RemoteEnumeration.ReapGuards(cachePathOf = { tmp.resolve("cache").resolve(it.removePrefix("/")) }),
            log = LoggerFactory.getLogger(RemoteMergeRenamesTest::class.java),
        )

    // A drive that keeps two folders of one name in one parent (Internxt): the kept folder /top holds wb, a twin of
    // /top is kept locally under its decorated name. wb is renamed in the cloud: its rows move inside the kept
    // folder, where their parent is, never below the twin that shares the kept folder's canonical path.
    @Test
    fun `a folder renamed inside the kept folder takes its rows along inside it, not below the decorated twin`() {
        for (twinDelivered in listOf(false, true)) {
            db.resetAll()
            seedFolder("/top", "aaaaaaaa-0001")
            seedFolder("/top (dup-bbbbbbbb)", "bbbbbbbb-0002", remotePath = "/top")
            seedFolder("/top/wb", "wwwwwwww-0003")
            seed("/top/wb/CASE.txt", "cccccccc-0004", hydrated = true, marker = 111)
            seedFolder("/top/wb/sub", "ssssssss-0005")
            val gather = gather()
            gather.loadTwins()
            val changes = linkedMapOf<String, CloudItem>()
            if (twinDelivered) changes["/top (dup-bbbbbbbb)"] = folderItem("bbbbbbbb-0002", "/top", parentId = null)
            changes["/top/wb2"] = folderItem("wwwwwwww-0003", "/top/wb2", parentId = "aaaaaaaa-0001")

            val moved = gather.moveRenamedFolders(changes, emptyMap())
            gather.updateRemoteEntries(changes)

            val rows = db.getAllEntries().associate { it.path to it.remoteId }
            assertEquals(listOf(RemoteGather.RemoteMerge.Move("/top/wb", "/top/wb2")), moved, "twin delivered: $twinDelivered")
            assertEquals(
                mapOf(
                    "/top" to "aaaaaaaa-0001",
                    "/top (dup-bbbbbbbb)" to "bbbbbbbb-0002",
                    "/top/wb2" to "wwwwwwww-0003",
                    "/top/wb2/CASE.txt" to "cccccccc-0004",
                    "/top/wb2/sub" to "ssssssss-0005",
                ),
                rows,
                "twin delivered: $twinDelivered",
            )
            assertRow("/top/wb2/CASE.txt", "cccccccc-0004", hydrated = true, marker = 111)
            assertNull(db.getEntry("/top/wb2/CASE.txt")?.remotePath, "the moved row is at its cloud path, no remote_path of a twin")
        }
    }

    // A folder renamed inside the twin stays inside it, and its rows keep the canonical cloud path in remote_path: that
    // is what makes them the twin's rows (DuplicateNames.keyOf), and what an upload or delete below them is sent to.
    @Test
    fun `a folder renamed inside the decorated twin keeps the canonical cloud path of its rows`() {
        seedFolder("/top", "aaaaaaaa-0001")
        seedFolder("/top (dup-bbbbbbbb)", "bbbbbbbb-0002", remotePath = "/top")
        seedFolder("/top (dup-bbbbbbbb)/x", "xxxxxxxx-0009", remotePath = "/top/x")
        db.upsertEntry(
            SyncEntry(
                path = "/top (dup-bbbbbbbb)/x/f.txt",
                remotePath = "/top/x/f.txt",
                remoteId = "ffffffff-0010",
                remoteHash = "h",
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
        val gather = gather()
        gather.loadTwins()
        val changes = linkedMapOf("/top (dup-bbbbbbbb)/y" to folderItem("xxxxxxxx-0009", "/top/y", parentId = "bbbbbbbb-0002"))

        val moved = gather.moveRenamedFolders(changes, emptyMap())
        gather.updateRemoteEntries(changes)

        assertEquals(listOf(RemoteGather.RemoteMerge.Move("/top (dup-bbbbbbbb)/x", "/top (dup-bbbbbbbb)/y")), moved)
        assertEquals(
            mapOf(
                "/top" to null,
                "/top (dup-bbbbbbbb)" to "/top",
                "/top (dup-bbbbbbbb)/y" to "/top/y",
                "/top (dup-bbbbbbbb)/y/f.txt" to "/top/y/f.txt",
            ),
            db.getAllEntries().associate { it.path to it.remotePath },
        )
    }

    // The issue's sequence through the mount's enumeration: a same-named twin of a root folder arrives, and a later
    // delta (Internxt re-delivers the twin from its rewound cursor) renames a folder inside the kept one.
    @Test
    fun `the mount's enumeration keeps a renamed folder's rows in the kept folder when a twin of that folder appeared`() =
        runTest {
            provider.duplicates = true
            val top = folderItem("aaaaaaaa-0001", "/top", parentId = null)
            val twin = folderItem("bbbbbbbb-0002", "/top", parentId = null)
            val case = item("cccccccc-0004", "/top/wb/CASE.txt").copy(parentId = "wwwwwwww-0003")
            val sub = folderItem("ssssssss-0005", "/top/wb/sub", parentId = "wwwwwwww-0003")
            provider.items = listOf(top, folderItem("wwwwwwww-0003", "/top/wb", parentId = "aaaaaaaa-0001"), case, sub)
            assertTrue(enumeration().enumerate(reset = false).ok)
            provider.items = listOf(twin)
            assertTrue(enumeration().enumerate(reset = false).ok)
            assertEquals("bbbbbbbb-0002", db.getEntry("/top (dup-bbbbbbbb)")?.remoteId, "the twin is kept under its decorated name")

            provider.items = listOf(twin, folderItem("wwwwwwww-0003", "/top/wb2", parentId = "aaaaaaaa-0001"))
            assertTrue(enumeration().enumerate(reset = false).ok)

            val rows = db.getAllEntries().associate { it.path to it.remoteId }
            assertEquals(
                mapOf(
                    "/top" to "aaaaaaaa-0001",
                    "/top (dup-bbbbbbbb)" to "bbbbbbbb-0002",
                    "/top/wb2" to "wwwwwwww-0003",
                    "/top/wb2/CASE.txt" to "cccccccc-0004",
                    "/top/wb2/sub" to "ssssssss-0005",
                ),
                rows,
            )
        }

    // A rename onto the path of a pending upload (the mount's enumeration): the pending row moves aside under a
    // conflict name and its cache copy goes with it, before the renamed item's row takes the path. The upsert used to
    // evict the pending row, leaving its cache copy, the only copy of the unsent bytes, without a row.
    @Test
    fun `a rename onto a pending upload takes the pending row and its cache copy aside`() =
        runTest {
            seed("/x.txt", "id1", hydrated = false, marker = 0)
            db.upsertEntry(
                SyncEntry(
                    path = "/n.txt",
                    remoteId = null,
                    remoteHash = null,
                    remoteSize = 0,
                    remoteModified = null,
                    localMtime = 1_000,
                    localSize = 4,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = Instant.parse("2026-03-01T00:00:00Z"),
                    cacheBacked = true,
                ),
            )
            val cache = tmp.resolve("cache")
            Files.createDirectories(cache)
            Files.writeString(cache.resolve("n.txt"), "mine")
            provider.items = listOf(item("id1", "/n.txt"))

            assertTrue(enumeration().enumerate(reset = false).ok)

            val aside = db.pendingUploadPaths().singleOrNull()
            assertNotNull(aside, "the unsent bytes are still owed to the cloud: ${db.getAllEntries().map { it.path to it.remoteId }}")
            assertEquals("mine", Files.readString(cache.resolve(aside.removePrefix("/"))), "the cache copy followed the pending row")
            assertEquals("id1", db.getEntry("/n.txt")?.remoteId)
            assertTrue(!Files.exists(cache.resolve("n.txt")), "the renamed item's row does not inherit the unsent bytes")
        }

    private class ListingTree : org.krost.unidrive.CloudProvider by FakeViewProvider(kinds = emptySet()) {
        var items: List<CloudItem> = emptyList()
        var duplicates = false

        override val allowsDuplicateNames: Boolean get() = duplicates

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((itemsSoFar: Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ) = org.krost.unidrive.DeltaPage(items = items, cursor = "c", hasMore = false, complete = true)
    }
}
