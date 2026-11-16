package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.SyncAction
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The streaming buffer compares the id of a buffered delete with the id of the item landed at its path. A delete with an
 * empty id (Dropbox's, gkrost/unidrive-cli#743) names no item, as one with none: it is no "other id". So after the live
 * item landed it stays a delete (the last delivery was the delete), and before it, it is older than the live item and
 * goes (delete, then create: the live item wins).
 */
class StreamingBufferEmptyIdTest {
    private val path = "/a.txt"

    private fun download(id: String) = SyncAction.DownloadContent(path, CloudItem(id, "a.txt", path, 9, false, Instant.parse("2026-01-02T00:00:00Z"), null, "h", null))

    @Test
    fun `a delete with an empty id after the landing stays`() {
        val buffer = StreamingReconcileBuffer()
        buffer.classify(listOf(download("id-N")))
        buffer.classify(listOf(SyncAction.DeleteLocal(path, remoteId = "")))
        assertEquals(listOf<SyncAction>(SyncAction.DeleteLocal(path, remoteId = "")), buffer.drainDeferred())
    }

    @Test
    fun `a delete with an empty id before the landing goes`() {
        val buffer = StreamingReconcileBuffer()
        buffer.classify(listOf(SyncAction.DeleteLocal(path, remoteId = "")))
        buffer.classify(listOf(download("id-N")))
        assertEquals(emptyList<SyncAction>(), buffer.drainDeferred())
    }

    @Test
    fun `a delete of another id after the landing is still dropped as a recreate`() {
        val buffer = StreamingReconcileBuffer()
        buffer.classify(listOf(download("id-N")))
        buffer.classify(listOf(SyncAction.DeleteLocal(path, remoteId = "id-old")))
        assertEquals(emptyList<SyncAction>(), buffer.drainDeferred())
    }
}
