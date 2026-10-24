package org.krost.unidrive.engine

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.EnumerationTracker
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An item that replaces another of the other kind at the same path (a file deleted and a folder created under its name,
 * or the reverse) is a new row, not the old row with new cloud fields: the old row's kind, hydration, local baseline and
 * pin describe an item that is gone (archive#1030).
 */
class RemoteMergeKindTest {
    private lateinit var tmp: Path
    private lateinit var db: StateDatabase
    private val provider = Tree()

    private val synced = Instant.parse("2026-03-01T00:00:00Z")

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("unidrive-merge-kind")
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
            listener = object : RemoteGather.Listener {},
            log = LoggerFactory.getLogger(RemoteMergeKindTest::class.java),
        )

    private fun cachePath(path: String): Path = tmp.resolve("cache").resolve(path.removePrefix("/"))

    private fun enumeration() =
        RemoteEnumeration(
            gather = gather(),
            db = db,
            tracker = EnumerationTracker(),
            reapGuards = RemoteEnumeration.ReapGuards(cachePathOf = ::cachePath),
            log = LoggerFactory.getLogger(RemoteMergeKindTest::class.java),
        )

    private fun item(
        id: String,
        path: String,
        folder: Boolean,
    ) = CloudItem(
        id = id,
        name = path.substringAfterLast('/'),
        path = path,
        size = if (folder) 0 else 7,
        isFolder = folder,
        modified = null,
        created = null,
        hash = if (folder) null else "h-$id",
        mimeType = null,
    )

    private fun seed(
        path: String,
        id: String,
        folder: Boolean,
    ) = db.upsertEntry(
        SyncEntry(
            path = path,
            remoteId = id,
            remoteHash = if (folder) null else "h-$id",
            remoteSize = if (folder) 0 else 5,
            remoteModified = null,
            localMtime = if (folder) null else 222,
            localSize = if (folder) null else 222,
            isFolder = folder,
            isPinned = !folder,
            isHydrated = !folder,
            lastSynced = synced,
            cacheBacked = if (folder) null else true,
        ),
    )

    @Test
    fun `a folder delivered where a file sat is a folder row without the file's baseline`() {
        seed("/x", "file1", folder = false)

        val merge = gather().updateRemoteEntries(mapOf("/x" to item("folder2", "/x", folder = true)))

        val row = assertNotNull(db.getEntry("/x"))
        assertEquals("folder2", row.remoteId)
        assertTrue(row.isFolder, "the kind comes from the delivered item")
        assertFalse(row.isHydrated)
        assertFalse(row.isPinned)
        assertNull(row.localSize)
        assertNull(row.remoteHash)
        assertEquals(listOf("file1"), merge.replaced.map { it.remoteId }, "the old row is reported for its cache copy")
        assertTrue("/x" in merge.changedPaths)
    }

    @Test
    fun `a file delivered where a folder sat is a file row`() {
        seed("/x", "folder1", folder = true)

        val merge = gather().updateRemoteEntries(mapOf("/x" to item("file2", "/x", folder = false)))

        val row = assertNotNull(db.getEntry("/x"))
        assertEquals("file2", row.remoteId)
        assertFalse(row.isFolder)
        assertEquals("h-file2", row.remoteHash)
        assertEquals(7, row.remoteSize)
        assertEquals(listOf("folder1"), merge.replaced.map { it.remoteId })
    }

    @Test
    fun `an item of the same kind at the path still merges into the row there`() {
        seed("/x", "file1", folder = false)

        val merge = gather().updateRemoteEntries(mapOf("/x" to item("file1", "/x", folder = false).copy(size = 9, hash = "h-new")))

        val row = assertNotNull(db.getEntry("/x"))
        assertEquals(222, row.localSize, "the baseline of the same item stays")
        assertEquals(9, row.remoteSize)
        assertTrue(merge.replaced.isEmpty())
    }

    @Test
    fun `the cache copy of a file replaced by a folder is removed, unless it is an unsent edit`() =
        runTest {
            seed("/x", "file1", folder = false)
            seed("/y", "file3", folder = false)
            for ((path, mtime) in listOf("/x" to synced.minusSeconds(60), "/y" to synced.plusSeconds(60))) {
                val file = cachePath(path)
                Files.createDirectories(file.parent)
                Files.writeString(file, "bytes")
                Files.setLastModifiedTime(file, FileTime.from(mtime))
            }
            provider.items = listOf(item("folder2", "/x", folder = true), item("folder4", "/y", folder = true))

            val result = enumeration().enumerate(reset = false)

            assertTrue(result.ok)
            assertTrue(assertNotNull(db.getEntry("/x")).isFolder)
            assertFalse(Files.exists(cachePath("/x")), "a folder's children cannot be cached below a file")
            assertTrue(Files.exists(cachePath("/y")), "newer than the row's watermark: the only copy of an edit")
        }

    private class Tree : org.krost.unidrive.CloudProvider by FakeViewProvider(kinds = emptySet()) {
        var items: List<CloudItem> = emptyList()

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((itemsSoFar: Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ) = org.krost.unidrive.DeltaPage(items = items, cursor = "c", hasMore = false, complete = true)
    }
}
