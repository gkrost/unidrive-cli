package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ChangeState
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncAction
import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The plan a streamed gather makes for a tracked item the cloud renamed (page slices, then scan-end) is the plan the
 * buffered gather makes for the same input: one move, one download of the newest version, and no delete at the old name.
 * A later step of the apply can hide an extra action; the plan must not depend on it.
 */
class StreamingMovePlanParityTest {
    private lateinit var db: StateDatabase
    private lateinit var reconciler: Reconciler
    private val t0 = Instant.parse("2026-03-28T12:00:00Z")

    @BeforeTest
    fun setUp() {
        val root = TestScratch.directory("ud-move-plan-root")
        db = StateDatabase(TestScratch.directory("ud-move-plan-db").resolve("state.db"))
        db.initialize()
        db.upsertEntry(
            SyncEntry(
                path = "/p",
                remoteId = "X",
                remoteHash = "hA",
                remoteSize = 3,
                remoteModified = t0,
                localMtime = 1711627200000,
                localSize = 3,
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = t0,
            ),
        )
        reconciler = Reconciler(db, root, ConflictPolicy.KEEP_BOTH)
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun at(
        hash: String,
        size: Long,
        seconds: Long,
    ) = CloudItem(id = "X", name = "q", path = "/q", size = size, isFolder = false, modified = t0.plusSeconds(seconds), created = null, hash = hash, mimeType = null)

    // What each action does, in a form two plans can be compared by: the order within one apply class is not the contract.
    private fun shape(actions: List<SyncAction>): List<String> =
        actions
            .map {
                when (it) {
                    is SyncAction.MoveLocal -> "move ${it.fromPath} -> ${it.path}"
                    is SyncAction.DownloadContent -> "download ${it.path} ${it.remoteItem.hash}"
                    else -> "${it.javaClass.simpleName} ${it.path}"
                }
            }.sorted()

    private fun streamed(
        pages: List<CloudItem>,
        local: Map<String, ChangeState>,
    ): List<String> {
        val slices = pages.flatMap { reconciler.resolveSlice(mapOf(it.path to it), local) }
        return shape(reconciler.finalizeStreaming(slices, mapOf(pages.last().path to pages.last()), local))
    }

    private fun buffered(
        last: CloudItem,
        local: Map<String, ChangeState>,
    ): List<String> = shape(reconciler.reconcile(mapOf(last.path to last), local))

    @Test
    fun `two edits of a renamed item on two pages plan one move and one download of the newer version`() {
        val first = at("hB", 4, 9)
        val newer = at("hC", 5, 20)
        val buffered = buffered(newer, emptyMap())
        assertEquals(listOf("download /q hC", "move /p -> /q"), buffered)
        assertEquals(buffered, streamed(listOf(first, newer), emptyMap()))
    }

    @Test
    fun `a local delete of the old name plans no delete beside the rename and edit`() {
        val renamed = at("hB", 4, 9)
        val local = mapOf("/p" to ChangeState.DELETED)
        val buffered = buffered(renamed, local)
        assertEquals(listOf("download /q hB", "move /p -> /q"), buffered)
        assertEquals(buffered, streamed(listOf(renamed), local))
    }

    @Test
    fun `a local delete of the old name plans no delete beside a plain rename`() {
        val renamed = at("hA", 3, 0)
        val local = mapOf("/p" to ChangeState.DELETED)
        val buffered = buffered(renamed, local)
        assertEquals(listOf("move /p -> /q"), buffered)
        assertEquals(buffered, streamed(listOf(renamed), local))
    }
}
