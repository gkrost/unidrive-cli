package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ChangeState
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncAction
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** gkrost/unidrive-cli#845 (#971 review): one named test per invariant of [StreamingWithdrawals]. */
class StreamingWithdrawalsReviewTest {
    private fun apply(
        withdrawals: StreamingWithdrawals,
        buffer: StreamingReconcileBuffer,
        withdrawn: List<CloudItem> = emptyList(),
        live: Map<String, CloudItem> = emptyMap(),
    ) = withdrawals.apply(withdrawn, live, mutableListOf(), mutableSetOf(), mutableSetOf(), buffer)

    @Test
    fun `a tombstone in a page's live slice does not clear the withdrawal it deletes`() =
        runTest {
            val withdrawals = StreamingWithdrawals()
            val buffer = StreamingReconcileBuffer()
            val x = item("X", "/p")
            withdrawals.run(SyncAction.DownloadContent("/p", x)) {}
            apply(withdrawals, buffer, withdrawn = listOf(x), live = mapOf("/p" to x.copy(deleted = true)))
            assertEquals(listOf("/p" to "X"), withdrawals.completedAndWithdrawn(), "the tombstone left the withdrawal standing")
        }

    @Test
    fun `a live occurrence of the withdrawn item clears its withdrawal`() =
        runTest {
            val withdrawals = StreamingWithdrawals()
            val buffer = StreamingReconcileBuffer()
            val x = item("X", "/p")
            withdrawals.run(SyncAction.DownloadContent("/p", x)) {}
            apply(withdrawals, buffer, withdrawn = listOf(x))
            apply(withdrawals, buffer, live = mapOf("/p" to x))
            assertEquals(emptyList(), withdrawals.completedAndWithdrawn(), "moved away and back: nothing to clean up")
        }

    @Test
    fun `a finished streamed download of a superseded item is reported for cleanup, a cancelled one is not`() =
        runTest {
            val withdrawals = StreamingWithdrawals()
            val buffer = StreamingReconcileBuffer()
            val x = item("X", "/p")
            val y = item("Y", "/y")
            withdrawals.run(SyncAction.DownloadContent("/p", x)) {}
            apply(withdrawals, buffer, withdrawn = listOf(x, y))
            withdrawals.run(SyncAction.DownloadContent("/y", y)) { error("a withdrawn transfer does not start") }
            assertEquals(listOf("/p" to "X"), withdrawals.completedAndWithdrawn())
        }

    @Test
    fun `a conflict deferred for a superseded item is dropped`() {
        val withdrawals = StreamingWithdrawals()
        val buffer = StreamingReconcileBuffer()
        val x = item("X", "/p")
        buffer.classify(listOf(SyncAction.Conflict("/p", ChangeState.MODIFIED, ChangeState.DELETED, x, ConflictPolicy.KEEP_BOTH)))
        apply(withdrawals, buffer, withdrawn = listOf(x))
        assertEquals(emptyList(), buffer.drainDeferred(), "no conflict copy for an item no longer at the path")
    }

    @Test
    fun `a conflict deferred for another id at the path stays`() {
        val withdrawals = StreamingWithdrawals()
        val buffer = StreamingReconcileBuffer()
        val y = item("Y", "/p")
        buffer.classify(listOf(SyncAction.Conflict("/p", ChangeState.MODIFIED, ChangeState.DELETED, y, ConflictPolicy.KEEP_BOTH)))
        apply(withdrawals, buffer, withdrawn = listOf(item("X", "/p")))
        assertEquals(1, buffer.drainDeferred().size)
    }

    @Test
    fun `the remote path of a withdrawn item translates to the aliased local path`() {
        val syncRoot = TestScratch.directory("ud-withdraw-alias-root")
        Files.createDirectories(syncRoot.resolve("Bilder"))
        val db = StateDatabase(TestScratch.directory("ud-withdraw-alias-db").resolve("state.db"))
        db.initialize()
        try {
            val reconciler = Reconciler(db, syncRoot, ConflictPolicy.KEEP_BOTH, xdgUserDirsOverrides = mapOf("XDG_PICTURES_DIR" to "Bilder"))
            assertEquals("/Bilder/a.jpg", reconciler.remoteToLocal("/Pictures/a.jpg", setOf("Pictures")))
            assertEquals("/Docs/a.jpg", reconciler.remoteToLocal("/Docs/a.jpg", setOf("Pictures", "Docs")))
        } finally {
            db.close()
        }
    }

    private fun item(
        id: String,
        path: String,
    ) = CloudItem(
        id = id,
        name = path.substringAfterLast("/"),
        path = path,
        size = 3,
        isFolder = false,
        modified = Instant.parse("2026-03-28T12:00:00Z"),
        created = Instant.parse("2026-03-28T10:00:00Z"),
        hash = "hash-$id",
        mimeType = null,
    )
}
