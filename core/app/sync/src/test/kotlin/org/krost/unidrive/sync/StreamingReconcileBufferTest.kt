package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.ChangeState
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamingReconcileBufferTest {
    private fun conflict(path: String) =
        SyncAction.Conflict(path, ChangeState.DELETED, ChangeState.MODIFIED, remoteItem = null, policy = ConflictPolicy.KEEP_BOTH)

    // The page that carries a remote edit plans the Conflict; an earlier page's DeleteRemote for the path must not
    // outlive it, whatever class it is (the old key was path + class, so both survived).
    @Test
    fun `a later verdict for a path replaces the earlier deferred action of another class`() {
        val buffer = StreamingReconcileBuffer()
        buffer.classify(listOf(SyncAction.DeleteRemote("/p.txt")))
        buffer.classify(listOf(conflict("/p.txt")))

        assertEquals(listOf<SyncAction>(conflict("/p.txt")), buffer.drainDeferred())
    }

    @Test
    fun `the later verdict wins in either direction`() {
        val buffer = StreamingReconcileBuffer()
        buffer.classify(listOf(conflict("/p.txt")))
        buffer.classify(listOf(SyncAction.DeleteRemote("/p.txt")))

        assertEquals(listOf<SyncAction>(SyncAction.DeleteRemote("/p.txt")), buffer.drainDeferred())
    }

    @Test
    fun `the deferred actions one call emits for a path stay together`() {
        val buffer = StreamingReconcileBuffer()
        buffer.classify(listOf(SyncAction.DeleteRemote("/p.txt"), SyncAction.DeleteLocal("/p.txt", remoteId = "id")))

        val drained = buffer.drainDeferred()
        assertEquals(2, drained.size, "both actions of one verdict are kept: $drained")
        assertTrue(drained.any { it is SyncAction.DeleteRemote } && drained.any { it is SyncAction.DeleteLocal })
    }

    @Test
    fun `a verdict for one path leaves the deferred actions of other paths alone`() {
        val buffer = StreamingReconcileBuffer()
        buffer.classify(listOf(SyncAction.DeleteRemote("/a.txt"), SyncAction.DeleteRemote("/b.txt")))
        buffer.classify(listOf(conflict("/a.txt")))

        val drained = buffer.drainDeferred()
        assertEquals(setOf<SyncAction>(conflict("/a.txt"), SyncAction.DeleteRemote("/b.txt")), drained.toSet())
        assertEquals(2, drained.size)
    }
}
