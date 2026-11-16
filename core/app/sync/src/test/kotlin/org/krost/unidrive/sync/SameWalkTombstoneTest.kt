package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A Dropbox walk that creates an item and deletes it again reports the item and then a tombstone carrying the item's id
 * (the provider pairs them within the walk); a delete that comes before the create carries no id. The engine must read
 * the first as one item that came and went and the second as a live replacement, in both modes, whether the entries
 * share a page or not (gkrost/unidrive-cli#743). A tombstone without an id is a delete of the path in every other case.
 */
class SameWalkTombstoneTest {
    private val path = "/a.txt"

    private fun item(
        id: String,
        size: Long,
        deleted: Boolean = false,
    ) = CloudItem(id, "a.txt", path, size, false, Instant.parse("2026-01-02T00:00:00Z").plusSeconds(size), Instant.parse("2026-01-01T00:00:00Z"), "h$size", null, deleted = deleted)

    private class Outcome(
        val fileSize: Long?,
        val rowId: String?,
        val deletedInCloud: List<String>,
    ) {
        override fun toString() = "file=$fileSize row=$rowId deletedInCloud=$deletedInCloud"
    }

    /** [before] is tracked and on disk (null: nothing at the path); then the incremental feed [pages] is walked. */
    private fun walk(
        streaming: Boolean,
        before: CloudItem?,
        pages: List<List<CloudItem>>,
        liveAfter: List<CloudItem>,
    ): Outcome {
        val root: Path = Files.createTempDirectory("ud-walk-root")
        val db = StateDatabase(Files.createTempDirectory("ud-walk-db").resolve("state.db")).also { it.initialize() }
        try {
            val provider = ListingFeedProvider()
            val engine =
                SyncEngine(
                    provider = provider,
                    db = db,
                    syncRoot = root,
                    reporter = DuplicateTwinNamesTest.CountingReporter(),
                    cacheRoot = Files.createTempDirectory("ud-walk-cache"),
                    cacheKey = "same-walk-tombstone-test",
                    streamingReconciliation = streaming,
                )
            provider.live = listOfNotNull(before)
            runTest { engine.syncOnce() }
            provider.live = liveAfter
            provider.pages = pages
            runTest { engine.syncOnce() }
            return Outcome(root.resolve("a.txt").takeIf { Files.exists(it) }?.let { Files.size(it) }, db.getEntry(path)?.remoteId, provider.deleted.toList())
        } finally {
            db.close()
        }
    }

    private fun createThenDeleteLeavesNothing(
        streaming: Boolean,
        pages: List<List<CloudItem>>,
    ) {
        val outcome = walk(streaming, before = null, pages = pages, liveAfter = emptyList())
        assertEquals(null, outcome.fileSize, "an item created and deleted in one walk leaves no file (streaming=$streaming): $outcome")
        assertEquals(null, outcome.rowId, "and no row (streaming=$streaming): $outcome")
        assertTrue(outcome.deletedInCloud.isEmpty(), "the cloud is not touched (streaming=$streaming): $outcome")
    }

    @Test
    fun `buffered - an item created and deleted in one page leaves nothing`() = createThenDeleteLeavesNothing(false, listOf(listOf(item("id-N", 9), item("id-N", 9, deleted = true))))

    @Test
    fun `streaming - an item created and deleted in one page leaves nothing`() = createThenDeleteLeavesNothing(true, listOf(listOf(item("id-N", 9), item("id-N", 9, deleted = true))))

    @Test
    fun `buffered - an item created and deleted on two pages leaves nothing`() = createThenDeleteLeavesNothing(false, listOf(listOf(item("id-N", 9)), listOf(item("id-N", 9, deleted = true))))

    @Test
    fun `streaming - an item created and deleted on two pages leaves nothing`() = createThenDeleteLeavesNothing(true, listOf(listOf(item("id-N", 9)), listOf(item("id-N", 9, deleted = true))))

    private fun deleteThenCreateKeepsTheLiveItem(
        streaming: Boolean,
        pages: List<List<CloudItem>>,
    ) {
        val outcome = walk(streaming, before = item("id-O", 7), pages = pages, liveAfter = listOf(item("id-N", 9)))
        assertEquals(9L, outcome.fileSize, "the live item is at the path, never deleted (streaming=$streaming): $outcome")
        assertEquals("id-N", outcome.rowId, "the path tracks the live item (streaming=$streaming): $outcome")
        assertTrue(outcome.deletedInCloud.isEmpty(), "the cloud is not touched (streaming=$streaming): $outcome")
    }

    @Test
    fun `buffered - a delete before the create in one page leaves the live item`() = deleteThenCreateKeepsTheLiveItem(false, listOf(listOf(item("", 0, deleted = true), item("id-N", 9))))

    @Test
    fun `streaming - a delete before the create in one page leaves the live item`() = deleteThenCreateKeepsTheLiveItem(true, listOf(listOf(item("", 0, deleted = true), item("id-N", 9))))

    @Test
    fun `buffered - a delete before the create on two pages leaves the live item`() = deleteThenCreateKeepsTheLiveItem(false, listOf(listOf(item("", 0, deleted = true)), listOf(item("id-N", 9))))

    @Test
    fun `streaming - a delete before the create on two pages leaves the live item`() = deleteThenCreateKeepsTheLiveItem(true, listOf(listOf(item("", 0, deleted = true)), listOf(item("id-N", 9))))

    private fun idlessDeleteStillDeletesTheRow(streaming: Boolean) {
        val outcome = walk(streaming, before = item("id-O", 7), pages = listOf(listOf(item("", 0, deleted = true))), liveAfter = emptyList())
        assertFalse(outcome.fileSize != null, "a delete of the path removes the tracked file (streaming=$streaming): $outcome")
        assertEquals(null, outcome.rowId, "and its row (streaming=$streaming): $outcome")
    }

    @Test
    fun `guard buffered - a tombstone without an id deletes the row at its path`() = idlessDeleteStillDeletesTheRow(false)

    @Test
    fun `guard streaming - a tombstone without an id deletes the row at its path`() = idlessDeleteStillDeletesTheRow(true)
}
