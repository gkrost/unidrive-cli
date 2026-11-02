package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ChangeState
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamingReconcileBufferTest {
    private fun item(path: String) =
        CloudItem(
            id = "id$path",
            name = path.substringAfterLast('/'),
            path = path,
            size = 1,
            isFolder = false,
            modified = null,
            created = null,
            hash = null,
            mimeType = null,
        )

    @Test
    fun `classify splits additive safe-now from deletion-bearing deferred`() {
        val buf = StreamingReconcileBuffer()
        val safe = buf.classify(listOf(SyncAction.Upload("/a"), SyncAction.DeleteLocal("/b")))
        assertEquals(listOf(SyncAction.Upload("/a")), safe, "additive action fires now")
        assertEquals(listOf(SyncAction.DeleteLocal("/b")), buf.drainDeferred(), "deletion is deferred")
    }

    @Test
    fun `Conflict is deferred only when one side is DELETED`() {
        val buf = StreamingReconcileBuffer()
        val liveConflict =
            SyncAction.Conflict("/m", ChangeState.MODIFIED, ChangeState.MODIFIED, null, ConflictPolicy.KEEP_BOTH)
        val deleteConflict =
            SyncAction.Conflict("/d", ChangeState.DELETED, ChangeState.MODIFIED, null, ConflictPolicy.KEEP_BOTH)
        val safe = buf.classify(listOf(liveConflict, deleteConflict))
        assertEquals(listOf(liveConflict), safe)
        assertEquals(listOf(deleteConflict), buf.drainDeferred())
    }

    // C6 invariant: a delete/recreate split across delta pages must keep the recreated
    // file. Page 1 tombstones id A (DeleteLocal, deferred); a later page re-creates
    // the file at the same path under id B (DownloadContent, safe-now, already written).
    // The drain must drop that stale DeleteLocal. The same id is not a recreate — see
    // the test below. If this test is removed or loosened, the streaming
    // delete-then-recreate data-loss path silently re-opens.
    @Test
    fun `deferred delete is suppressed when a different remote id lands at the same path`() {
        val buf = StreamingReconcileBuffer()
        buf.classify(listOf(SyncAction.DeleteLocal("/p", remoteId = "A"))) // earlier page: tombstone(A)
        buf.classify(listOf(SyncAction.DownloadContent("/p", item("/p").copy(id = "B")))) // later page: recreate B
        assertTrue(
            buf.drainDeferred().none { it.path == "/p" },
            "a deferred delete must be dropped when a different id recreated that path",
        )
    }

    // The same item downloaded mid-gather and tombstoned later is a real delete.
    // Path-only suppression would drop it, leave the local file, and the next edit
    // would upload it again.
    // The reverse order of the test above. Graph delivers an item more than once and
    // the last occurrence wins; a restore is another delivery of the same id. An
    // earlier tombstone must not drain and delete the content that landed after it.
    @Test
    fun `an earlier tombstone does not delete the same item delivered later`() {
        val buf = StreamingReconcileBuffer()
        buf.classify(listOf(SyncAction.DeleteLocal("/x", remoteId = "A")))
        buf.classify(listOf(SyncAction.DownloadContent("/x", item("/x").copy(id = "A"))))
        assertTrue(
            buf.drainDeferred().none { it.path == "/x" },
            "a tombstone older than the live delivery of the same id must not drain",
        )
    }

    @Test
    fun `the same order inside one page keeps the later live delivery`() {
        val buf = StreamingReconcileBuffer()
        buf.classify(
            listOf(
                SyncAction.DeleteLocal("/x", remoteId = "A"),
                SyncAction.DownloadContent("/x", item("/x").copy(id = "A")),
            ),
        )
        assertTrue(buf.drainDeferred().none { it.path == "/x" })
    }

    @Test
    fun `a tombstone after a restore of the same id is kept`() {
        val buf = StreamingReconcileBuffer()
        buf.classify(listOf(SyncAction.DeleteLocal("/x", remoteId = "A")))
        buf.classify(listOf(SyncAction.DownloadContent("/x", item("/x").copy(id = "A"))))
        val last = SyncAction.DeleteLocal("/x", remoteId = "A")
        buf.classify(listOf(last))
        assertEquals(listOf(last), buf.drainDeferred(), "the last delivery is the tombstone")
    }

    @Test
    fun `delete of the item just downloaded is kept`() {
        val buf = StreamingReconcileBuffer()
        buf.classify(listOf(SyncAction.DownloadContent("/x", item("/x").copy(id = "A"))))
        val delete = SyncAction.DeleteLocal("/x", remoteId = "A")
        buf.classify(listOf(delete))
        assertEquals(
            listOf(delete),
            buf.drainDeferred(),
            "a delete of the item just downloaded must still drain",
        )
    }

    @Test
    fun `the buffer names the id that landed at a path this gather`() {
        val buf = StreamingReconcileBuffer()
        assertEquals(null, buf.landedIdAt("/x"))
        buf.classify(listOf(SyncAction.Upload("/u")))
        assertEquals(null, buf.landedIdAt("/u"), "a local upload lands no remote content")
        buf.classify(listOf(SyncAction.DownloadContent("/x", item("/x").copy(id = "A"))))
        assertEquals("A", buf.landedIdAt("/x"))
    }

    @Test
    fun `a delete with no remote id is not treated as a different id`() {
        val buf = StreamingReconcileBuffer()
        buf.classify(listOf(SyncAction.DownloadContent("/x", item("/x").copy(id = "A"))))
        val delete = SyncAction.DeleteLocal("/x")
        buf.classify(listOf(delete))
        assertEquals(
            listOf(delete),
            buf.drainDeferred(),
            "a missing tombstone id must not drop a real delete",
        )
    }

    // Bot P1 (the complement): a LOCAL Upload must NOT suppress a deferred delete. The
    // localChanges map is replayed every page, so a MODIFIED-local path produces a
    // per-page safe-now Upload; the page carrying the remote tombstone emits a
    // DELETED-bearing Conflict. That conflict is a genuine modify-vs-remote-delete and
    // must survive the drain — a local Upload is not landed remote content, so it
    // must not supersede a delete.
    @Test
    fun `local Upload does not suppress a deferred delete-modify conflict`() {
        val buf = StreamingReconcileBuffer()
        buf.classify(listOf(SyncAction.Upload("/p"))) // no-remote page: MODIFIED-local replayed
        val conflict =
            SyncAction.Conflict("/p", ChangeState.MODIFIED, ChangeState.DELETED, null, ConflictPolicy.KEEP_BOTH)
        buf.classify(listOf(conflict)) // tombstone page: real delete-vs-modify conflict
        assertEquals(
            listOf(conflict),
            buf.drainDeferred(),
            "a local Upload must not drop a genuine remote-delete conflict",
        )
    }

    @Test
    fun `local Upload does not suppress a deferred remote delete`() {
        val buf = StreamingReconcileBuffer()
        buf.classify(listOf(SyncAction.Upload("/p")))
        val delete = SyncAction.DeleteLocal("/p", remoteId = "A")
        buf.classify(listOf(delete))
        assertEquals(
            listOf(delete),
            buf.drainDeferred(),
            "a same-path local Upload must not drop a deferred remote delete",
        )
    }

    @Test
    fun `deferred delete survives when no safe-now action touched the path`() {
        val buf = StreamingReconcileBuffer()
        buf.classify(listOf(SyncAction.DeleteLocal("/gone")))
        assertEquals(
            listOf(SyncAction.DeleteLocal("/gone")),
            buf.drainDeferred(),
            "a genuine remote deletion with no competing remote-content action must still drain",
        )
    }
}
