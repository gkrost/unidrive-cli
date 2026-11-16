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
 * The plan a streamed gather ends with (resolveSlice per page, classify, drainDeferred, finalizeStreaming) equals the plan
 * one buffered reconcile makes over the same remote items and local changes, whichever page carries a path and whatever
 * the local and remote state of it is. Local changes no page carries are planned once, at scan-end.
 */
class StreamingLocalOnlyPlanTest {
    private lateinit var db: StateDatabase
    private lateinit var syncRoot: Path

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-local-only")
        db = StateDatabase(Files.createTempDirectory("unidrive-local-only-db").resolve("state.db"))
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

    private fun reconciler(ignore: (String, Boolean) -> Boolean = { _, _ -> false }) =
        Reconciler(db, syncRoot, ConflictPolicy.KEEP_BOTH).also { it.isIgnored = ignore }

    private fun streamedPlan(
        pages: List<Map<String, CloudItem>>,
        local: Map<String, ChangeState>,
        ignore: (String, Boolean) -> Boolean = { _, _ -> false },
        enumerationComplete: Boolean = true,
    ): List<SyncAction> {
        val reconciler = reconciler(ignore)
        val buffer = StreamingReconcileBuffer()
        val safe = mutableListOf<SyncAction>()
        val seen = HashSet<SyncAction>()
        for (page in pages) {
            buffer.classify(reconciler.resolveSlice(page, local, landedRemoteId = buffer::landedIdAt)).filterTo(safe) { seen.add(it) }
        }
        val fullRemote = pages.fold(emptyMap<String, CloudItem>()) { acc, page -> acc + page }
        return reconciler.finalizeStreaming(safe + buffer.drainDeferred(), fullRemote, local, enumerationComplete = enumerationComplete)
    }

    private fun bufferedPlan(
        pages: List<Map<String, CloudItem>>,
        local: Map<String, ChangeState>,
        ignore: (String, Boolean) -> Boolean = { _, _ -> false },
        enumerationComplete: Boolean = true,
    ): List<SyncAction> {
        val fullRemote = pages.fold(emptyMap<String, CloudItem>()) { acc, page -> acc + page }
        return reconciler(ignore).reconcile(fullRemote, local, enumerationComplete = enumerationComplete)
    }

    private fun List<SyncAction>.at(path: String) = filter { it.path == path }.map { it::class.simpleName.orEmpty() }.sorted()

    private fun assertParity(
        pages: List<Map<String, CloudItem>>,
        local: Map<String, ChangeState>,
        ignore: (String, Boolean) -> Boolean = { _, _ -> false },
        enumerationComplete: Boolean = true,
        label: String = "",
    ) {
        val streamed = streamedPlan(pages, local, ignore, enumerationComplete)
        val buffered = bufferedPlan(pages, local, ignore, enumerationComplete)
        val paths = (local.keys + pages.flatMap { it.keys }).toSortedSet()
        for (path in paths) assertEquals(buffered.at(path), streamed.at(path), "$label plan for $path")
    }

    @Test
    fun `a path first mentioned on the fifth page is reconciled with its remote item, not planned as local-only`() {
        db.upsertEntry(row("/late.txt"))
        for (n in 1..4) db.upsertEntry(row("/o$n.txt"))
        val local = mapOf("/late.txt" to ChangeState.DELETED)
        val pages =
            (1..4).map { n -> mapOf("/o$n.txt" to item("/o$n.txt", hash = "c$n")) } +
                listOf(mapOf("/late.txt" to item("/late.txt", hash = "edited")))

        val streamed = streamedPlan(pages, local)

        assertTrue(streamed.none { it is SyncAction.DeleteRemote && it.path == "/late.txt" }, "plan: ${streamed.at("/late.txt")}")
        assertParity(pages, local)
    }

    @Test
    fun `a local deletion and a remote deletion of the same file is not deleted twice`() {
        db.upsertEntry(row("/gone.txt"))
        db.upsertEntry(row("/o.txt"))
        val local = mapOf("/gone.txt" to ChangeState.DELETED)
        val pages =
            listOf(
                mapOf("/o.txt" to item("/o.txt", hash = "c")),
                mapOf("/gone.txt" to item("/gone.txt", deleted = true)),
            )

        assertParity(pages, local)
        assertTrue(streamedPlan(pages, local).none { it is SyncAction.DeleteRemote && it.path == "/gone.txt" })
    }

    @Test
    fun `a local edit and a remote deletion on a later page is not planned as a plain upload or remote delete by an earlier page`() {
        db.upsertEntry(row("/mod.txt"))
        db.upsertEntry(row("/o.txt"))
        Files.write(syncRoot.resolve("mod.txt"), ByteArray(33))
        val local = mapOf("/mod.txt" to ChangeState.MODIFIED)
        val pages =
            listOf(
                mapOf("/o.txt" to item("/o.txt", hash = "c")),
                mapOf("/mod.txt" to item("/mod.txt", deleted = true)),
            )

        assertParity(pages, local)
    }

    @Test
    fun `local changes of an ignored path are not planned at scan-end`() {
        db.upsertEntry(row("/ignored/x.txt"))
        db.upsertEntry(row("/o.txt"))
        Files.createDirectories(syncRoot.resolve("ignored"))
        Files.write(syncRoot.resolve("ignored/new.txt"), ByteArray(5))
        val local = mapOf("/ignored/x.txt" to ChangeState.DELETED, "/ignored/new.txt" to ChangeState.NEW, "/kept.txt" to ChangeState.DELETED)
        db.upsertEntry(row("/kept.txt"))
        val ignore = { path: String, _: Boolean -> path.startsWith("/ignored") }
        val pages = listOf(mapOf("/o.txt" to item("/o.txt", hash = "c")))

        val streamed = streamedPlan(pages, local, ignore)

        assertTrue(streamed.none { it.path.startsWith("/ignored") }, "plan: $streamed")
        assertEquals(listOf("DeleteRemote"), streamed.at("/kept.txt"))
        assertParity(pages, local, ignore)
    }

    @Test
    fun `an incomplete enumeration plans local creations like the buffered plan and still keeps a remote edit`() {
        db.upsertEntry(row("/p.txt"))
        db.upsertEntry(row("/gone.txt"))
        db.upsertEntry(row("/o.txt"))
        Files.write(syncRoot.resolve("new.txt"), ByteArray(4))
        val local = mapOf("/p.txt" to ChangeState.DELETED, "/gone.txt" to ChangeState.DELETED, "/new.txt" to ChangeState.NEW)
        val pages =
            listOf(
                mapOf("/o.txt" to item("/o.txt", hash = "c")),
                mapOf("/p.txt" to item("/p.txt", hash = "edited")),
            )

        assertParity(pages, local, enumerationComplete = false)
        assertTrue(streamedPlan(pages, local, enumerationComplete = false).none { it is SyncAction.DeleteRemote && it.path == "/p.txt" })
    }

    // Property-style: every combination of local state, remote state and the page the path is on gives the buffered plan.
    @Test
    fun `every combination of local state, remote state and page position equals the buffered plan`() {
        // The rows and the file are the same for every combination and the plans only read them.
        for (path in listOf("/p.txt", "/a.txt", "/b.txt", "/c.txt")) db.upsertEntry(row(path))
        Files.write(syncRoot.resolve("p.txt"), ByteArray(33))
        val combos =
            listOf("absent", "edited", "tombstone", "touch").flatMap { remote ->
                listOf(ChangeState.DELETED, ChangeState.MODIFIED, ChangeState.UNCHANGED).flatMap { local ->
                    (0..2).map { Triple(remote, local, it) }
                }
            }
        combos.forEach { (remoteState, localState, position) ->
            val pItem =
                when (remoteState) {
                    "edited" -> item("/p.txt", hash = "edited")
                    "tombstone" -> item("/p.txt", deleted = true)
                    "touch" -> item("/p.txt")
                    else -> null
                }
            val pages =
                listOf("/a.txt", "/b.txt", "/c.txt").mapIndexed { i, other ->
                    val page = linkedMapOf(other to item(other, hash = "c$i"))
                    if (pItem != null && i == position) page["/p.txt"] = pItem
                    page as Map<String, CloudItem>
                }
            assertParity(pages, mapOf("/p.txt" to localState), label = "remote=$remoteState local=$localState page=$position")
        }
    }
}
