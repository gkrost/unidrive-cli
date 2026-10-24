package org.krost.unidrive.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.ViewKind
import org.krost.unidrive.ViewRef
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * A view reference to a folder is a read-only alias of the real folder: a path below the entry is the real folder's
 * content under the alias prefix, found by the entry's name and followed by id. The kind plays no part (the fake provider
 * serves FAVORITES here, the code is the same for every kind), and the real tree is never written.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewAliasTest {
    private lateinit var tmp: Path
    private lateinit var db: StateDatabase
    private val provider = FakeViewProvider()
    private val kind = ViewKind.FAVORITES

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("unidrive-view-alias")
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
        isHydrated = false,
        lastSynced = Instant.ofEpochMilli(2_000),
    )

    private fun folder(id: String) = ViewRef(id, true)

    private fun file(id: String) = ViewRef(id, false)

    private fun TestScope.directory(
        scope: List<String> = emptyList(),
        ignoreCase: Boolean = false,
    ): ViewDirectory {
        val views =
            ViewNamespaces(
                specs = listOf(ViewSpec(kind, limit = 10)),
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
                trash = TrashNamespace(ignoreCase = false),
                views = views,
            )
        val cache = ViewCache(backgroundScope, provider::listView, { 10 }, { currentTime })
        return ViewDirectory(guard, db, cache, foldCase = ignoreCase)
    }

    // /Docs (folder ref) with a tree three levels deep, /Docs2 (not a ref), and a favorited file.
    private fun seedTree() {
        db.upsertEntry(row("/Docs", "id-docs", isFolder = true))
        db.upsertEntry(row("/Docs/sub", "id-sub", isFolder = true))
        db.upsertEntry(row("/Docs/sub/deep", "id-deep", isFolder = true))
        db.upsertEntry(row("/Docs/sub/deep/c.txt", "id-c"))
        db.upsertEntry(row("/Docs/sub/b.txt", "id-b"))
        db.upsertEntry(row("/Docs/a.txt", "id-a"))
        db.upsertEntry(row("/Docs2", "id-docs2", isFolder = true))
        db.upsertEntry(row("/Docs2/x.txt", "id-x"))
        db.upsertEntry(row("/loose.txt", "id-loose"))
        provider.serve(kind, folder("id-docs"), file("id-loose"))
    }

    private suspend fun ViewDirectory.children(path: String) = childrenBelow(resolve(path))

    @Test
    fun `a folder reference is an entry and the real content is listed below it to depth three with the alias prefix`() =
        runTest {
            seedTree()
            val d = directory()

            val entry = assertIs<ViewResolution.Entry>(d.resolve("/Favorites/Docs")).entry
            assertEquals("/Docs", entry.realPath)
            assertEquals(true, entry.row.isFolder)
            assertEquals(
                listOf("/Favorites/Docs/a.txt" to "id-a", "/Favorites/Docs/sub" to "id-sub"),
                d.children("/Favorites/Docs")!!.map { it.first to it.second.remoteId }.sortedBy { it.first },
            )
            assertEquals(
                listOf("/Favorites/Docs/sub/b.txt", "/Favorites/Docs/sub/deep"),
                d.children("/Favorites/Docs/sub")!!.map { it.first }.sorted(),
            )
            val deep = assertIs<ViewResolution.Below>(d.resolve("/Favorites/Docs/sub/deep"))
            assertEquals("/Docs/sub/deep", deep.realPath)
            assertEquals("/Favorites/Docs/sub/deep", deep.aliasPath)
            assertEquals(listOf("/Favorites/Docs/sub/deep/c.txt"), d.children("/Favorites/Docs/sub/deep")!!.map { it.first })
            val leaf = assertIs<ViewResolution.Below>(d.resolve("/Favorites/Docs/sub/deep/c.txt"))
            assertEquals("/Docs/sub/deep/c.txt", leaf.realPath)
        }

    @Test
    fun `the prefix matches whole names only`() =
        runTest {
            seedTree()
            val d = directory()

            assertEquals(ViewResolution.NotFound, d.resolve("/Favorites/Doc"))
            assertEquals(ViewResolution.NotFound, d.resolve("/Favorites/Doc/sub"))
            assertEquals(ViewResolution.NotFound, d.resolve("/Favorites/Docs2/x.txt"), "a real sibling that is no reference")
            assertNull(d.children("/Favorites/Doc"))
        }

    @Test
    fun `a name matches with the case and NFC key of the platform`() =
        runTest {
            val nfd = Normalizer.normalize("Café", Normalizer.Form.NFD)
            db.upsertEntry(row("/Café", "id-cafe", isFolder = true))
            db.upsertEntry(row("/Café/m.txt", "id-m"))
            provider.serve(kind, folder("id-cafe"))

            val folded = directory(ignoreCase = true)

            assertIs<ViewResolution.Below>(folded.resolve("/FAVORITES/$nfd/m.txt"))
            assertEquals("/Café/m.txt", (folded.resolve("/favorites/CAFÉ/m.txt") as ViewResolution.Below).realPath)
        }

    @Test
    fun `case is kept apart where the platform keeps it apart`() =
        runTest {
            seedTree()
            assertEquals(ViewResolution.NotFound, directory(ignoreCase = false).resolve("/Favorites/docs/a.txt"))
        }

    @Test
    fun `a path below a file entry is nothing, and a file entry and the root have no alias children`() =
        runTest {
            seedTree()
            val d = directory()

            assertEquals(ViewResolution.NotFound, d.resolve("/Favorites/loose.txt/deeper"))
            assertNull(d.children("/Favorites/loose.txt"))
            assertNull(d.children("/Favorites"), "the root lists entries, not alias children")
        }

    @Test
    fun `the alias follows a rename of the real folder because the join is by id`() =
        runTest {
            seedTree()
            val d = directory()
            assertEquals("/Docs/a.txt", (d.resolve("/Favorites/Docs/a.txt") as ViewResolution.Below).realPath)

            db.upsertEntry(row("/Archive/Papers", "id-docs", isFolder = true))

            assertEquals(ViewResolution.NotFound, d.resolve("/Favorites/Docs/a.txt"), "the entry carries the new name")
            val renamed = assertIs<ViewResolution.Below>(d.resolve("/Favorites/Papers/a.txt"))
            assertEquals("/Archive/Papers/a.txt", renamed.realPath)
            assertEquals(1, provider.viewCalls.size, "one provider call: the rename is state.db's")
        }

    @Test
    fun `a folder reference without a row, outside the scope or of the wrong kind is skipped and counted`() =
        runTest {
            db.upsertEntry(row("/Work", "id-work", isFolder = true))
            db.upsertEntry(row("/Work/w.txt", "id-w"))
            db.upsertEntry(row("/Out", "id-out", isFolder = true))
            db.upsertEntry(row("/Work/plain.txt", "id-plain"))
            provider.serve(kind, folder("id-work"), folder("id-missing"), folder("id-out"), folder("id-plain"))

            val d = directory(scope = listOf("/Work"))
            val listing = d.list(kind)

            assertEquals(listOf("Work"), listing.entries.map { it.name })
            assertEquals(3, listing.skipped)
            assertEquals(ViewResolution.NotFound, d.resolve("/Favorites/Out/x"))
        }

    @Test
    fun `a folder wins a plain name over a file and the namesake is decorated`() =
        runTest {
            db.upsertEntry(row("/A/Same", "11111111-file"))
            db.upsertEntry(row("/B/Same", "22222222-dir", isFolder = true))
            db.upsertEntry(row("/B/Same/in.txt", "id-in"))
            provider.serve(kind, file("11111111-file"), folder("22222222-dir"))

            val d = directory()

            assertEquals("/B/Same", (d.resolve("/Favorites/Same") as ViewResolution.Entry).entry.realPath)
            assertEquals("/B/Same/in.txt", (d.resolve("/Favorites/Same/in.txt") as ViewResolution.Below).realPath)
            assertEquals("/A/Same", (d.resolve("/Favorites/Same (dup-11111111)") as ViewResolution.Entry).entry.realPath)
        }
}
