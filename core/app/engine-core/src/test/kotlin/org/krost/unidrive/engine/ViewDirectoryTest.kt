package org.krost.unidrive.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.ViewKind
import org.krost.unidrive.ViewRef
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A view is a join of the provider's references with the rows state.db holds: what has no row to show is skipped and
 * counted, names inside the view are told apart deterministically, and a path in a view resolves to the real row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewDirectoryTest {
    private lateinit var tmp: Path
    private lateinit var db: StateDatabase
    private val provider = FakeViewProvider()
    private val recents = ViewKind.RECENTS

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("unidrive-view-directory")
        db = StateDatabase(tmp.resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tmp.toFile().deleteRecursively()
    }

    private fun row(
        path: String,
        id: String,
        isFolder: Boolean = false,
        hydrated: Boolean = false,
    ) = SyncEntry(
        path = path,
        remoteId = id,
        remoteHash = "h-$id",
        remoteSize = 10,
        remoteModified = Instant.ofEpochMilli(1_000),
        localMtime = null,
        localSize = null,
        isFolder = isFolder,
        isPinned = false,
        isHydrated = hydrated,
        lastSynced = Instant.ofEpochMilli(2_000),
    )

    private fun file(id: String) = ViewRef(id, false)

    private class Setup(
        val directory: ViewDirectory,
        val guard: RemoteOperationGuard,
        val views: ViewNamespaces,
    )

    private fun TestScope.setup(
        scope: List<String> = emptyList(),
        trash: TrashNamespace = TrashNamespace(ignoreCase = false),
        ignoreCase: Boolean = false,
    ): Setup {
        val views =
            ViewNamespaces(
                specs = listOf(ViewSpec(recents, limit = 10)),
                topLevelNames = { db.listDirectChildren("").map { it.path.removePrefix("/") } },
                ignoreCase = ignoreCase,
            )
        val guard =
            RemoteOperationGuard(
                standingScope = scope,
                syncPaths = emptyList(),
                excludePatterns = emptyList(),
                maxConcurrentTransfers = 2,
                matchesGlob = { _, _ -> false },
                trash = trash,
                views = views,
            )
        val cache = ViewCache(backgroundScope, provider::listView, { 10 }, { currentTime })
        return Setup(ViewDirectory(guard, db, cache, foldCase = ignoreCase), guard, views)
    }

    private fun names(listing: ViewListing) = listing.entries.map { it.name }

    @Test
    fun `the entries are the references that have a row, in the provider's order, named by the row's last segment`() =
        runTest {
            db.upsertEntry(row("/Work/Reports/q3.pdf", "id-q3"))
            db.upsertEntry(row("/Notes/todo.txt", "id-todo"))
            db.upsertEntry(row("/Photos/cat.jpg", "id-cat"))
            provider.serve(recents, file("id-todo"), file("id-cat"), file("id-q3"))

            val listing = setup().directory.list(recents)

            assertEquals(listOf("todo.txt", "cat.jpg", "q3.pdf"), names(listing))
            assertEquals(listOf("/Notes/todo.txt", "/Photos/cat.jpg", "/Work/Reports/q3.pdf"), listing.entries.map { it.realPath })
            assertEquals(0, listing.skipped)
        }

    @Test
    fun `a reference without a live row, below the trash, outside the scope or of the wrong kind is skipped and counted`() =
        runTest {
            val trash = TrashNamespace(ignoreCase = false)
            trash.place(listOf(org.krost.unidrive.CloudItem("t1", "gone.txt", "/Old/gone.txt", 1, false, null, null, null, null, trashed = true))) { emptyList() }
            db.upsertEntry(row("/In/a.txt", "id-a"))
            db.upsertEntry(row("/Papierkorb/Old/gone.txt", "t1"))
            db.upsertEntry(row("/Elsewhere/b.txt", "id-b"))
            db.upsertEntry(row("/In/Folder", "id-folder", isFolder = true))
            provider.serve(recents, file("id-a"), file("no-row"), file("t1"), file("id-b"), file("id-folder"))

            val listing = setup(scope = listOf("/In"), trash = trash).directory.list(recents)

            assertEquals(listOf("a.txt"), names(listing))
            assertEquals(4, listing.skipped, "no row, in the trash, out of scope, a folder where a file was said")
        }

    @Test
    fun `the same reference twice is listed once and counted once`() =
        runTest {
            db.upsertEntry(row("/a.txt", "id-a"))
            provider.serve(recents, file("id-a"), file("id-a"))

            val listing = setup().directory.list(recents)

            assertEquals(listOf("a.txt"), names(listing))
            assertEquals(0, listing.skipped)
        }

    @Test
    fun `two items with one name in different folders are told apart, the same way after any reordering`() =
        runTest {
            db.upsertEntry(row("/One/a.txt", "11111111-aaaa"))
            db.upsertEntry(row("/Two/a.txt", "22222222-bbbb"))
            db.upsertEntry(row("/Three/a.txt", "33333333-cccc"))
            val ids = listOf("11111111-aaaa", "22222222-bbbb", "33333333-cccc")
            val seen = mutableSetOf<Map<String, String>>()

            for (order in listOf(ids, ids.reversed(), listOf(ids[1], ids[2], ids[0]))) {
                provider.serve(recents, *order.map(::file).toTypedArray())
                val s = setup()
                s.directory.list(recents).also { listing ->
                    seen += listing.entries.associate { it.ref.id to it.name }
                    assertEquals(order, listing.entries.map { it.ref.id }, "entries stay in the provider's order")
                }
            }

            assertEquals(
                setOf(mapOf("11111111-aaaa" to "a.txt", "22222222-bbbb" to "a (dup-22222222).txt", "33333333-cccc" to "a (dup-33333333).txt")),
                seen,
            )
        }

    @Test
    fun `the row is read when the view is listed, so it follows a rename or a download`() =
        runTest {
            db.upsertEntry(row("/Docs/draft.txt", "id-1"))
            provider.serve(recents, file("id-1"))
            val s = setup()
            assertEquals(listOf("draft.txt"), names(s.directory.list(recents)))

            db.upsertEntry(row("/Docs/final.txt", "id-1", hydrated = true))

            val listing = s.directory.list(recents)
            assertEquals(listOf("final.txt"), names(listing))
            assertTrue(listing.entries.single().row.isHydrated)
            assertEquals(1, provider.viewCalls.size, "and it did not ask the provider again")
        }

    @Test
    fun `a path in a view resolves to the root, to the real row of an entry, or to nothing`() =
        runTest {
            db.upsertEntry(row("/Docs/a.txt", "id-a"))
            provider.serve(recents, file("id-a"))
            val s = setup()

            assertEquals(ViewResolution.Root(recents), s.directory.resolve("/Recents"))
            val entry = assertIs<ViewResolution.Entry>(s.directory.resolve("/Recents/a.txt")).entry
            assertEquals("/Docs/a.txt", entry.realPath)
            assertEquals("id-a", entry.row.remoteId)
            assertEquals(ViewResolution.NotFound, s.directory.resolve("/Recents/missing.txt"))
            assertEquals(ViewResolution.NotFound, s.directory.resolve("/Recents/a.txt/deeper"), "a view is flat")
            assertEquals(ViewResolution.NotFound, s.directory.resolve("/Docs/a.txt"), "not a path in a view")
            assertEquals(ViewResolution.NotFound, s.directory.resolve("/"))
        }

    @Test
    fun `a decorated entry resolves too, and a name matches the way the file system compares names`() =
        runTest {
            db.upsertEntry(row("/One/Photo.JPG", "11111111-a"))
            db.upsertEntry(row("/Two/photo.jpg", "22222222-b"))
            provider.serve(recents, file("11111111-a"), file("22222222-b"))
            val s = setup(ignoreCase = true)

            val plain = assertIs<ViewResolution.Entry>(s.directory.resolve("/RECENTS/photo.jpg")).entry
            val decorated = assertIs<ViewResolution.Entry>(s.directory.resolve("/Recents/photo (dup-22222222).jpg")).entry

            assertEquals("/One/Photo.JPG", plain.realPath)
            assertEquals("/Two/photo.jpg", decorated.realPath)
        }

    @Test
    fun `the status counts as of the last listing and asks nothing of the provider`() =
        runTest {
            db.upsertEntry(row("/a.txt", "id-a"))
            provider.serve(recents, file("id-a"), file("no-row"))
            val s = setup()

            val before = s.directory.status().single()
            assertEquals(ViewState.UNAVAILABLE, before.state, "nothing was listed yet")
            assertEquals(0, provider.viewCalls.size)

            s.directory.list(recents)
            advanceTimeBy(1_000)

            val status = s.directory.status().single()
            assertEquals(recents, status.kind)
            assertEquals("/Recents", status.rootPath)
            assertEquals("Recents", status.folderName)
            assertEquals("Recents", status.configuredName)
            assertEquals(1, status.entries)
            assertEquals(1, status.skipped)
            assertEquals(ViewState.OK, status.state)
            assertEquals(0L, status.asOfMs)
            assertEquals(1, provider.viewCalls.size)
        }

    @Test
    fun `the status names the root as last worked out and does not read state db for it`() =
        runTest {
            val s = setup()
            assertEquals("/Recents", s.views.rootPath(recents))
            db.upsertEntry(row("/Recents", "id-real-folder", isFolder = true))
            s.views.invalidate()

            assertEquals("/Recents", s.directory.status().single().rootPath, "a status reply does not wait for the top level")
            assertEquals("/Recents (2)", s.views.rootPath(recents), "a question that needs it does")
            assertEquals("/Recents (2)", s.directory.status().single().rootPath)
            assertEquals("Recents", s.directory.status().single().configuredName)
        }

    @Test
    fun `a view whose provider has none lists empty and says unavailable`() =
        runTest {
            val s = setup()

            val listing = s.directory.list(recents)
            runCurrent()

            assertTrue(listing.entries.isEmpty())
            assertEquals(ViewState.UNAVAILABLE, s.directory.status().single().state)
        }

    @Test
    fun `a root folder named like a live top-level item moves aside and the entries stay`() =
        runTest {
            db.upsertEntry(row("/Recents", "id-real-folder", isFolder = true))
            db.upsertEntry(row("/Recents/mine.txt", "id-mine"))
            provider.answers[recents] = CapabilityResult.Success(listOf(file("id-mine")))
            val s = setup()

            assertEquals("/Recents (2)", s.views.rootPath(recents))
            val entry = assertIs<ViewResolution.Entry>(s.directory.resolve("/Recents (2)/mine.txt")).entry
            assertEquals("/Recents/mine.txt", entry.realPath, "the real item lives in the real folder")
            assertTrue(!s.guard.isReadOnlyPath("/Recents/mine.txt"), "and is as writable as ever")
            assertTrue(s.guard.isReadOnlyPath("/Recents (2)/mine.txt"))
        }
}
