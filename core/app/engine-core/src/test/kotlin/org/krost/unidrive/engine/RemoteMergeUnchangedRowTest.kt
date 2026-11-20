package org.krost.unidrive.engine

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.testing.TestScratch
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * gkrost/unidrive-cli#745 finding 7: the merge wrote every delivered row on every pass, even a re-delivery that changed
 * nothing. A full-listing provider (localfs, S3) re-delivers its whole tree each poll, so each poll was a write
 * transaction per item. A row the delivery leaves as it was is not written; what is written is unchanged. The cost is
 * pinned in runs of the row write (INSERT OR REPLACE INTO sync_entries).
 */
class RemoteMergeUnchangedRowTest {
    private lateinit var tmp: Path
    private val spy = StatementCountingConnector()
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        tmp = TestScratch.directory("unidrive-merge-unchanged")
        db = StateDatabase(tmp.resolve("state.db"), inMemory = true, connector = spy)
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tmp.toFile().deleteRecursively()
    }

    private val gather by lazy {
        RemoteGather(
            provider = FakeViewProvider(kinds = emptySet()),
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
            log = LoggerFactory.getLogger(RemoteMergeUnchangedRowTest::class.java),
        )
    }

    private fun item(
        id: String,
        path: String,
        hash: String = "h-$id",
        folder: Boolean = false,
    ) = CloudItem(
        id = id,
        name = path.substringAfterLast('/'),
        path = path,
        size = if (folder) 0 else 5,
        isFolder = folder,
        modified = Instant.parse("2026-03-01T10:00:00.123456789Z"),
        created = null,
        hash = if (folder) null else hash,
        mimeType = null,
        version = "v-$hash",
    )

    private fun tree(n: Int): Map<String, CloudItem> {
        val out = linkedMapOf("/d" to item("folder-d", "/d", folder = true))
        for (i in 0 until n) out["/d/f$i.txt"] = item("file-$i", "/d/f$i.txt")
        return out
    }

    private fun rowWrites(): Int = spy.runsOf("INSERT OR REPLACE INTO sync_entries")

    @Test
    fun `a re-delivery that changes nothing writes no row`() {
        val delivery = tree(20)
        gather.updateRemoteEntries(delivery)
        val before = delivery.keys.associateWith { db.getEntry(it) }
        spy.reset()

        val merge = gather.updateRemoteEntries(delivery)

        assertEquals(0, rowWrites(), "an unchanged re-delivery of ${delivery.size} items must not write their rows")
        assertEquals(emptySet(), merge.changedPaths, "and is no news for the view")
        assertEquals(before, delivery.keys.associateWith { db.getEntry(it) }, "the rows stay as they were")
    }

    @Test
    fun `a re-delivery writes exactly the rows it changes, as before`() {
        val delivery = tree(20)
        gather.updateRemoteEntries(delivery)
        spy.reset()

        val edited = LinkedHashMap(delivery)
        edited["/d/f3.txt"] = item("file-3", "/d/f3.txt", hash = "h-new")
        gather.updateRemoteEntries(edited)

        assertEquals(1, rowWrites(), "only the edited item's row is written")
        val row = assertNotNull(db.getEntry("/d/f3.txt"))
        assertEquals("h-new", row.remoteHash)
        assertEquals("v-h-new", row.remoteVersion)
    }

    @Test
    fun `a renamed item is still written at its new path`() {
        val delivery = tree(3)
        gather.updateRemoteEntries(delivery)
        spy.reset()

        val renamed = LinkedHashMap(delivery)
        renamed.remove("/d/f1.txt")
        renamed["/d/g1.txt"] = item("file-1", "/d/g1.txt")
        gather.updateRemoteEntries(renamed)

        assertEquals(1, rowWrites(), "the rename moves its row; the unchanged rows are not written")
        assertNull(db.getEntry("/d/f1.txt"))
        assertEquals("file-1", assertNotNull(db.getEntry("/d/g1.txt")).remoteId)
    }

    @Test
    fun `a re-delivery ends a download quarantine by writing the row`() {
        val delivery = tree(2)
        gather.updateRemoteEntries(delivery)
        db.setDownloadQuarantine("file-0", Instant.parse("2026-03-02T00:00:00Z"))
        spy.reset()

        val edited = LinkedHashMap(delivery)
        edited["/d/f0.txt"] = item("file-0", "/d/f0.txt", hash = "h-fresh")
        gather.updateRemoteEntries(edited)

        assertEquals(1, rowWrites())
        val row = assertNotNull(db.getEntry("/d/f0.txt"))
        assertEquals(false, row.downloadQuarantined, "new content ends the quarantine")
    }
}
