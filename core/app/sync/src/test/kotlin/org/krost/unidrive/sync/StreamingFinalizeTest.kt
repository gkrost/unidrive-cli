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
 * Scan-end planning of the local changes no delta page carries (finalizeStreaming): it must agree with the pages on
 * what a path is (alias names), honour the sync scope and the download-only direction, and give the plan one buffered
 * reconcile makes, compared action by action.
 */
class StreamingFinalizeTest {
    private lateinit var db: StateDatabase
    private lateinit var syncRoot: Path

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-finalize")
        db = StateDatabase(Files.createTempDirectory("unidrive-finalize-db").resolve("state.db"))
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
        size: Long = 100,
        isFolder: Boolean = false,
    ) = CloudItem(
        id = "id-$path",
        name = path.substringAfterLast("/"),
        path = path,
        size = size,
        isFolder = isFolder,
        modified = t0,
        created = null,
        hash = hash,
        mimeType = null,
        deleted = deleted,
    )

    private fun reconciler(xdg: Map<String, String> = emptyMap()) =
        Reconciler(db, syncRoot, ConflictPolicy.KEEP_BOTH, xdgUserDirsOverrides = xdg)

    private fun file(
        relative: String,
        size: Int,
    ) {
        Files.createDirectories(syncRoot.resolve(relative).parent)
        Files.write(syncRoot.resolve(relative), ByteArray(size))
    }

    private fun merged(pages: List<Map<String, CloudItem>>) = pages.fold(emptyMap<String, CloudItem>()) { acc, page -> acc + page }

    /** The pipeline the engine runs per gather: resolveSlice per page, classify, drain, finalizeStreaming. */
    private fun streamedPlan(
        reconciler: Reconciler,
        pages: List<Map<String, CloudItem>>,
        local: Map<String, ChangeState>,
        syncPaths: List<String> = emptyList(),
        downloadOnly: Boolean = false,
        stable: Set<String>? = null,
    ): List<SyncAction> {
        val buffer = StreamingReconcileBuffer()
        val safe = mutableListOf<SyncAction>()
        val seen = HashSet<SyncAction>()
        for (page in pages) {
            val actions =
                reconciler.resolveSlice(page, local, syncPaths, stable, downloadOnly, buffer::landedIdAt)
            buffer.classify(actions).filterTo(safe) { seen.add(it) }
        }
        return reconciler.finalizeStreaming(
            safe + buffer.drainDeferred(),
            merged(pages),
            local,
            syncPaths,
            downloadOnly,
            stableRemoteTopLevelNames = stable,
        )
    }

    private fun List<SyncAction>.at(path: String) = filter { it.path == path }

    private val xdg = mapOf("XDG_PICTURES_DIR" to "Bilder")

    // The delta carries /Pictures/photo.jpg but not the /Pictures folder item: only the pre-fetched top-level names know
    // that /Bilder is /Pictures. The page maps its key to /Bilder/photo.jpg and plans it; scan-end must read the key as
    // carried too, or it plans a second action for the same file (an upload without the canonical target, a /Bilder tree).
    @Test
    fun `scan-end reads an aliased path a page carried as carried, with the same stable names`() {
        Files.createDirectories(syncRoot.resolve("Bilder"))
        file("Bilder/photo.jpg", 77)
        val local = mapOf("/Bilder/photo.jpg" to ChangeState.NEW)
        val pages =
            listOf(
                mapOf("/Pictures/photo.jpg" to item("/Pictures/photo.jpg", size = 77)),
                mapOf("/Documents/f.txt" to item("/Documents/f.txt", size = 10)),
            )
        val stable = setOf("Pictures", "Documents")

        val pagesOnly = pages.flatMap { reconciler(xdg).resolveSlice(it, local, stableRemoteTopLevelNames = stable) }
        val streamed = streamedPlan(reconciler(xdg), pages, local, stable = stable)

        assertEquals(pagesOnly.at("/Bilder/photo.jpg"), streamed.at("/Bilder/photo.jpg"), "scan-end adds nothing for a carried path")
        assertTrue(streamed.none { it is SyncAction.CreateRemoteFolder && it.path.startsWith("/Bilder") }, "no /Bilder tree: $streamed")
        assertTrue(
            streamed.filterIsInstance<SyncAction.Upload>().none { it.remoteTarget == null && it.path.startsWith("/Bilder") },
            "no upload without the canonical target: $streamed",
        )
    }

    @Test
    fun `scan-end plans a local-only aliased file with the canonical remote target`() {
        Files.createDirectories(syncRoot.resolve("Bilder"))
        file("Bilder/other.jpg", 5)
        val local = mapOf("/Bilder/other.jpg" to ChangeState.NEW)
        val pages =
            listOf(
                mapOf("/Pictures" to item("/Pictures", isFolder = true)),
                mapOf("/Documents/f.txt" to item("/Documents/f.txt", size = 10)),
            )

        val streamed = streamedPlan(reconciler(xdg), pages, local, stable = setOf("Pictures", "Documents"))

        assertEquals(
            listOf<SyncAction>(SyncAction.Upload("/Bilder/other.jpg", remoteTarget = "/Pictures/other.jpg")),
            streamed.at("/Bilder/other.jpg"),
        )
        assertTrue(streamed.none { it is SyncAction.CreateRemoteFolder && it.path.startsWith("/Bilder") }, "no /Bilder tree: $streamed")
    }

    // The same local-only file with an incremental delta that carries no /Pictures item: only the stable names alias it.
    @Test
    fun `scan-end aliases a local-only file with the stable names when the delta carries no folder item`() {
        Files.createDirectories(syncRoot.resolve("Bilder"))
        file("Bilder/other.jpg", 5)
        val local = mapOf("/Bilder/other.jpg" to ChangeState.NEW)
        val pages = listOf(mapOf("/Documents/f.txt" to item("/Documents/f.txt", size = 10)))

        val streamed = streamedPlan(reconciler(xdg), pages, local, stable = setOf("Pictures", "Documents"))

        val uploads = streamed.filterIsInstance<SyncAction.Upload>().filter { it.path == "/Bilder/other.jpg" }
        assertEquals(listOf("/Pictures/other.jpg"), uploads.map { it.remoteTarget }, "plan: $streamed")
    }

    @Test
    fun `scan-end plans only the local-only paths inside the sync scope`() {
        file("in/a.txt", 3)
        file("out/b.txt", 3)
        db.upsertEntry(row("/out/gone.txt"))
        db.upsertEntry(row("/in/gone.txt"))
        val local =
            mapOf(
                "/in/a.txt" to ChangeState.NEW,
                "/out/b.txt" to ChangeState.NEW,
                "/out/gone.txt" to ChangeState.DELETED,
                "/in/gone.txt" to ChangeState.DELETED,
            )
        val pages = listOf(mapOf("/in/other.txt" to item("/in/other.txt")))

        val streamed = streamedPlan(reconciler(), pages, local, syncPaths = listOf("/in"))

        assertEquals(listOf("/in/a.txt"), streamed.filterIsInstance<SyncAction.Upload>().map { it.path })
        assertEquals(listOf("/in/gone.txt"), streamed.filterIsInstance<SyncAction.DeleteRemote>().map { it.path })
        assertTrue(streamed.none { it.path.startsWith("/out") }, "nothing outside the scope: $streamed")
    }

    @Test
    fun `scan-end of a download-only sync re-downloads a locally deleted file instead of deleting it remotely`() {
        file("new.txt", 3)
        db.upsertEntry(row("/gone.txt"))
        val local = mapOf("/new.txt" to ChangeState.NEW, "/gone.txt" to ChangeState.DELETED)
        val pages = listOf(mapOf("/other.txt" to item("/other.txt")))

        val downloadOnly = streamedPlan(reconciler(), pages, local, downloadOnly = true)
        val both = streamedPlan(reconciler(), pages, local, downloadOnly = false)

        assertTrue(downloadOnly.none { it is SyncAction.DeleteRemote }, "plan: $downloadOnly")
        assertTrue(downloadOnly.any { it is SyncAction.DownloadContent && it.path == "/gone.txt" }, "plan: $downloadOnly")
        assertTrue(both.any { it is SyncAction.DeleteRemote && it.path == "/gone.txt" }, "the same local deletion propagates otherwise: $both")
        val oracle = reconciler().reconcile(merged(pages), local, downloadOnly = true)
        assertEquals(oracle.at("/gone.txt"), downloadOnly.at("/gone.txt"))
        assertEquals(oracle.at("/new.txt"), downloadOnly.at("/new.txt"))
    }

    // A twin the gather re-keys to a decorated name is not carried by any page at that key, and scan-end counts the key as
    // carried: the engine resolves re-keyed items (tombstones too) with resolveSlice, so the local change meets the item.
    @Test
    fun `a tombstone at a re-keyed path is planned by resolveSlice and scan-end adds nothing for it`() {
        val key = "/f (id-2).txt"
        db.upsertEntry(row(key))
        file("f (id-2).txt", 40)
        val local = mapOf(key to ChangeState.MODIFIED)
        val tombstone = item(key, deleted = true)

        val slice = reconciler().resolveSlice(mapOf(key to tombstone), local)
        val buffered = reconciler().reconcile(mapOf(key to tombstone), local)
        val finalized = reconciler().finalizeStreaming(slice, mapOf(key to tombstone), local)

        assertTrue(slice.isNotEmpty(), "the slice plans the local change against the tombstone")
        assertEquals(buffered.at(key), finalized.at(key))
    }

    // Action-by-action equality with the buffered reconcile on a mixed fixture: local NEW (also in a folder), several
    // local-only paths, a path carried by two pages, a remote edit on a late page, a remote tombstone.
    @Test
    fun `the streamed plan equals the buffered plan action by action on a mixed fixture`() {
        for (path in listOf("/edited.txt", "/gone.txt", "/mod.txt", "/del1.txt", "/del2.txt", "/tomb.txt", "/o1.txt", "/o2.txt")) {
            db.upsertEntry(row(path))
        }
        file("new1.txt", 7)
        file("dir/new2.txt", 9)
        file("mod.txt", 33)
        file("tomb.txt", 21)
        val local =
            mapOf(
                "/new1.txt" to ChangeState.NEW,
                "/dir/new2.txt" to ChangeState.NEW,
                "/mod.txt" to ChangeState.MODIFIED,
                "/del1.txt" to ChangeState.DELETED,
                "/del2.txt" to ChangeState.DELETED,
                "/edited.txt" to ChangeState.DELETED,
                "/gone.txt" to ChangeState.DELETED,
                "/tomb.txt" to ChangeState.MODIFIED,
            )
        val o1 = item("/o1.txt", hash = "c1")
        val pages =
            listOf(
                mapOf("/o1.txt" to o1),
                mapOf("/o1.txt" to o1, "/o2.txt" to item("/o2.txt", hash = "c2")),
                mapOf("/gone.txt" to item("/gone.txt", deleted = true), "/tomb.txt" to item("/tomb.txt", deleted = true)),
                mapOf("/edited.txt" to item("/edited.txt", hash = "edited-remotely")),
            )

        val order = compareBy<SyncAction>({ it.path }, { it::class.simpleName })
        val streamed = streamedPlan(reconciler(), pages, local).sortedWith(order)
        val buffered = reconciler().reconcile(merged(pages), local).sortedWith(order)

        assertEquals(buffered, streamed)
        assertTrue(streamed.none { it is SyncAction.DeleteRemote && it.path == "/edited.txt" })
    }
}
