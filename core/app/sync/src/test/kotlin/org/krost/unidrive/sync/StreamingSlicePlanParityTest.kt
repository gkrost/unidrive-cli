package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ChangeState
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncAction
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A streamed gather plans page by page, and a path that no page carries must not be decided from one page's view of
 * the remote. The plan a streamed gather ends with has to be the plan one buffered reconcile makes over the same
 * remote items and the same local changes: a remote edit that arrives on a later page cannot be deleted by a verdict
 * an earlier page issued for a local deletion.
 *
 * The pipeline below is the one the engine runs: resolveSlice per page, StreamingReconcileBuffer.classify, then
 * drainDeferred and finalizeStreaming at scan-end.
 */
class StreamingSlicePlanParityTest {
    private lateinit var db: StateDatabase
    private lateinit var syncRoot: Path

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-slice-parity")
        db = StateDatabase(Files.createTempDirectory("unidrive-slice-parity-db").resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private val t0 = Instant.parse("2026-03-28T12:00:00Z")

    private fun row(path: String) =
        SyncEntry(
            path = path,
            remoteId = "id-$path",
            remoteHash = "h",
            remoteSize = 100,
            remoteModified = t0,
            localMtime = 1711627200000,
            localSize = 100,
            isFolder = false,
            isPinned = false,
            isHydrated = true,
            lastSynced = t0,
        )

    private fun item(
        path: String,
        hash: String? = "h",
        deleted: Boolean = false,
    ) = CloudItem(
        id = "id-$path",
        name = path.substringAfterLast("/"),
        path = path,
        size = 100,
        isFolder = false,
        modified = t0,
        created = null,
        hash = hash,
        mimeType = null,
        deleted = deleted,
    )

    private fun reconciler() = Reconciler(db, syncRoot, ConflictPolicy.KEEP_BOTH)

    /** What the engine does with the delta pages of one streamed gather, ending in the plan Pass 1 and Pass 2 receive. */
    private fun streamedPlan(
        pages: List<Map<String, CloudItem>>,
        local: Map<String, ChangeState>,
    ): List<SyncAction> {
        val reconciler = reconciler()
        val buffer = StreamingReconcileBuffer()
        val safe = mutableListOf<SyncAction>()
        val seen = HashSet<SyncAction>()
        for (page in pages) {
            val classified = buffer.classify(reconciler.resolveSlice(page, local, landedRemoteId = buffer::landedIdAt))
            classified.filterTo(safe) { seen.add(it) }
        }
        val fullRemote = pages.fold(emptyMap<String, CloudItem>()) { acc, page -> acc + page }
        return reconciler.finalizeStreaming(safe + buffer.drainDeferred(), fullRemote, local)
    }

    private fun bufferedPlan(
        pages: List<Map<String, CloudItem>>,
        local: Map<String, ChangeState>,
    ): List<SyncAction> {
        val fullRemote = pages.fold(emptyMap<String, CloudItem>()) { acc, page -> acc + page }
        return reconciler().reconcile(fullRemote, local)
    }

    private fun List<SyncAction>.at(path: String) = filter { it.path == path }.map { it::class.simpleName.orEmpty() }.sorted()

    // 743 finding 4 / 742 finding 4: P is deleted locally and edited remotely, and the edit is on a later page than the
    // first page that is resolved. Page 1 does not mention P, so it is read as "remote unchanged" and plans DeleteRemote;
    // page 2 plans the Conflict. Both reach the apply, and the delete (priority 1) runs before the conflict (priority 3).
    @Test
    fun `a remote edit on a later page is not deleted by the local deletion planned on an earlier page`() {
        db.upsertEntry(row("/p.txt"))
        db.upsertEntry(row("/other1.txt"))
        db.upsertEntry(row("/other2.txt"))
        val local = mapOf("/p.txt" to ChangeState.DELETED)
        val pages =
            listOf(
                mapOf("/other1.txt" to item("/other1.txt", hash = "changed")),
                mapOf("/other2.txt" to item("/other2.txt", hash = "changed")),
                mapOf("/p.txt" to item("/p.txt", hash = "edited-remotely")),
            )

        val streamed = streamedPlan(pages, local)

        assertTrue(
            streamed.none { it is SyncAction.DeleteRemote && it.path == "/p.txt" },
            "the remote edit of /p.txt must not be deleted remotely; plan for /p.txt: ${streamed.at("/p.txt")}",
        )
        assertEquals(bufferedPlan(pages, local).at("/p.txt"), streamed.at("/p.txt"), "the streamed plan equals the buffered plan")
    }

    // Same invariant with the edit on the page right after the first one, and a second local-deleted path that no page
    // touches (it must still be deleted remotely, exactly once).
    @Test
    fun `an untouched locally deleted file is still deleted remotely once while an edited one is kept`() {
        db.upsertEntry(row("/edited.txt"))
        db.upsertEntry(row("/untouched.txt"))
        db.upsertEntry(row("/other.txt"))
        val local = mapOf("/edited.txt" to ChangeState.DELETED, "/untouched.txt" to ChangeState.DELETED)
        val pages =
            listOf(
                mapOf("/other.txt" to item("/other.txt", hash = "changed")),
                mapOf("/edited.txt" to item("/edited.txt", hash = "edited-remotely")),
            )

        val streamed = streamedPlan(pages, local)

        assertEquals(listOf("DeleteRemote"), streamed.at("/untouched.txt"), "nothing changed remotely: the local deletion propagates once")
        assertTrue(streamed.none { it is SyncAction.DeleteRemote && it.path == "/edited.txt" }, "plan: ${streamed.at("/edited.txt")}")
    }

    // The same defect from the other side: the edit is on the first page, but every later page that does not mention the
    // path plans the local deletion again, so the Conflict from page 1 is joined by a DeleteRemote from page 2.
    @Test
    fun `a remote edit on the first page is not deleted by a later page that does not mention the path`() {
        db.upsertEntry(row("/p.txt"))
        db.upsertEntry(row("/other.txt"))
        val local = mapOf("/p.txt" to ChangeState.DELETED)
        val pages =
            listOf(
                mapOf("/p.txt" to item("/p.txt", hash = "edited-remotely")),
                mapOf("/other.txt" to item("/other.txt", hash = "changed")),
            )

        val streamed = streamedPlan(pages, local)

        assertTrue(streamed.none { it is SyncAction.DeleteRemote && it.path == "/p.txt" }, "plan: ${streamed.at("/p.txt")}")
        assertEquals(bufferedPlan(pages, local).at("/p.txt"), streamed.at("/p.txt"))
    }

    // Guard for the fix: local-only changes that no page mentions are planned exactly once and as the buffered plan.
    @Test
    fun `local-only changes no page mentions are planned once and equal the buffered plan`() {
        db.upsertEntry(row("/modified.txt"))
        db.upsertEntry(row("/deleted.txt"))
        db.upsertEntry(row("/other.txt"))
        Files.write(syncRoot.resolve("new.txt"), ByteArray(7))
        Files.write(syncRoot.resolve("modified.txt"), ByteArray(20))
        val local =
            mapOf(
                "/new.txt" to ChangeState.NEW,
                "/modified.txt" to ChangeState.MODIFIED,
                "/deleted.txt" to ChangeState.DELETED,
            )
        val pages =
            (1..3).map { n ->
                mapOf("/other.txt" to item("/other.txt", hash = "changed-$n"))
            }

        val streamed = streamedPlan(pages, local)
        val buffered = bufferedPlan(pages, local)

        assertEquals(listOf("Upload"), streamed.at("/new.txt"))
        assertEquals(listOf("Upload"), streamed.at("/modified.txt"))
        assertEquals(listOf("DeleteRemote"), streamed.at("/deleted.txt"))
        for (path in local.keys) assertEquals(buffered.at(path), streamed.at(path), "plan for $path")
    }
}
