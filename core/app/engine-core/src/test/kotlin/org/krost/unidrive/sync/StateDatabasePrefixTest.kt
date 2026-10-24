package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Prefix and child queries of [StateDatabase] match paths exactly, by code points: #489 (UTF-16 length against
 * SQLite's character-counting substr let grandchildren through below names outside the BMP) and #490 (SQLite's
 * LIKE folds ASCII case, so a case-only folder rename deleted the folder's whole subtree). Non-BMP characters are
 * built from code points, so the source stays ASCII.
 */
class StateDatabasePrefixTest {
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        db = StateDatabase(Files.createTempDirectory("unidrive-prefix").resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun entry(
        path: String,
        folder: Boolean = false,
    ) = SyncEntry(
        path = path,
        remoteId = "id-$path",
        remoteHash = "h",
        remoteSize = 1,
        remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
        localMtime = 1,
        localSize = 1,
        isFolder = folder,
        isPinned = false,
        isHydrated = false,
        lastSynced = Instant.now(),
    )

    private fun cps(vararg codePoints: Int) = String(codePoints, 0, codePoints.size)

    private val cat = cps(0x1F638) // one emoji: two UTF-16 units, one SQLite character
    private val cats = cat + cat + cat

    private fun paths(entries: List<SyncEntry>) = entries.map { it.path }.sorted()

    @Test
    fun `listDirectChildren below a non-BMP folder name returns only direct children`() {
        db.upsertEntry(entry("/a/$cats", folder = true))
        db.upsertEntry(entry("/a/$cats/x", folder = true))
        db.upsertEntry(entry("/a/$cats/x/deep.txt"))
        db.upsertEntry(entry("/a/$cats/y.txt"))
        assertEquals(listOf("/a/$cats/x", "/a/$cats/y.txt"), paths(db.listDirectChildren("/a/$cats")))
    }

    @Test
    fun `hasEntriesBelow sees a row at any depth, also below a missing intermediate folder row`() {
        db.upsertEntry(entry("/dir", folder = true))
        assertEquals(false, db.hasEntriesBelow("/dir/"), "a folder with nothing below it")
        db.upsertEntry(entry("/dir/sub/deep.txt")) // no row for /dir/sub
        assertEquals(emptyList(), db.listDirectChildren("/dir"), "the one-level listing does not see it")
        assertEquals(true, db.hasEntriesBelow("/dir/"))
        assertEquals(false, db.hasEntriesBelow("/di/"), "a shorter name is not a prefix of the folder")
        assertEquals(false, db.hasEntriesBelow("/dir/sub/deep.txt/"), "a file has nothing below it")
    }

    @Test
    fun `hasEntriesBelow does not mix folders whose names differ only in case`() {
        db.upsertEntry(entry("/Docs", folder = true))
        db.upsertEntry(entry("/docs", folder = true))
        db.upsertEntry(entry("/docs/b.txt"))
        assertEquals(false, db.hasEntriesBelow("/Docs/"))
        assertEquals(true, db.hasEntriesBelow("/docs/"))
    }

    @Test
    fun `listDirectChildren of the root and of an ASCII folder are unchanged`() {
        db.upsertEntry(entry("/top", folder = true))
        db.upsertEntry(entry("/top/a.txt"))
        db.upsertEntry(entry("/top/sub/b.txt"))
        db.upsertEntry(entry("/root.txt"))
        assertEquals(listOf("/root.txt", "/top"), paths(db.listDirectChildren("")))
        assertEquals(listOf("/top/a.txt"), paths(db.listDirectChildren("/top")))
    }

    @Test
    fun `listDirectChildren does not mix folders whose names differ only in case`() {
        db.upsertEntry(entry("/Docs", folder = true))
        db.upsertEntry(entry("/Docs/a.txt"))
        db.upsertEntry(entry("/docs", folder = true))
        db.upsertEntry(entry("/docs/b.txt"))
        assertEquals(listOf("/Docs/a.txt"), paths(db.listDirectChildren("/Docs")))
        assertEquals(listOf("/docs/b.txt"), paths(db.listDirectChildren("/docs")))
    }

    @Test
    fun `listDirectChildren does not leak siblings that share the parent's path prefix`() {
        // The path range that makes the listing an index seek (#727) must stay exactly "starts with the
        // parent path plus a slash": '/a.txt' and '/a0' share the '/a' characters but are siblings of
        // '/a', and '/a0/deep.txt' and '/ab/other.txt' are other subtrees. Only one-level rows under
        // '/a/' belong to the listing.
        db.upsertEntry(entry("/a", folder = true))
        db.upsertEntry(entry("/a/child.txt"))
        db.upsertEntry(entry("/a.txt"))
        db.upsertEntry(entry("/a0", folder = true))
        db.upsertEntry(entry("/a0/deep.txt"))
        db.upsertEntry(entry("/ab/other.txt"))
        assertEquals(listOf("/a/child.txt"), paths(db.listDirectChildren("/a")))
    }

    @Test
    fun `listDirectChildren of every folder of a mixed tree is exactly its one-level rows`() {
        // The listing reads the folder index (the path up to its last '/'): over a tree with names outside the BMP,
        // decomposed names, case-only twins, a name made of the folder's own letters and dots, every folder lists
        // what a walk of the rows by their parent says, no more and no less.
        val nfd = "e" + cps(0x0301) // e + combining acute: stored NFC, asked in either form
        val tree =
            listOf(
                "/root.txt", "/top", "/top/a.txt", "/top/top", "/top/top/top.txt", "/top/pot.otp",
                "/$cats", "/$cats/$cat", "/$cats/$cat/x$cat.txt", "/$cats/y",
                "/Docs", "/Docs/a.txt", "/docs", "/docs/b.txt", "/docs/b.txt.d", "/docs/b.txt.d/c",
                "/caf$nfd", "/caf$nfd/menu.txt", "/a.b", "/a.b/.hidden", "/a.b/..", "/sp ace", "/sp ace/x y.txt",
            )
        for (p in tree) db.upsertEntry(entry(p, folder = !p.substringAfterLast('/').contains('.')))
        val stored = db.getAllEntries().map { it.path }
        fun parentOf(p: String) = p.substring(0, p.lastIndexOf('/') + 1)
        for (folder in listOf("") + stored) {
            val expected = stored.filter { parentOf(it) == "$folder/" || (folder.isEmpty() && parentOf(it) == "/") }.sorted()
            assertEquals(expected, paths(db.listDirectChildren(folder)), "children of '$folder'")
        }
        assertEquals(listOf("/caf${cps(0xE9)}/menu.txt"), paths(db.listDirectChildren("/caf$nfd")), "an NFD folder name is matched NFC")
    }

    @Test
    fun `a case-only folder rename keeps the whole subtree`() {
        db.upsertEntry(entry("/Docs", folder = true))
        db.upsertEntry(entry("/Docs/a.txt"))
        db.upsertEntry(entry("/Docs/sub/b.txt"))
        db.renamePrefix("/Docs", "/docs")
        assertEquals(listOf("/docs", "/docs/a.txt", "/docs/sub/b.txt"), paths(db.getAllEntries()))
    }

    @Test
    fun `a rename leaves a skipped tree in the source and one in the destination, slash-bounded`() {
        // /old/keep stays put and /new/stay is not deleted; /old/keeper is a different tree and does move.
        db.upsertEntry(entry("/old", folder = true))
        db.upsertEntry(entry("/old/keep", folder = true))
        db.upsertEntry(entry("/old/keep/a.txt"))
        db.upsertEntry(entry("/old/keeper.txt"))
        db.upsertEntry(entry("/old/move.txt"))
        db.upsertEntry(entry("/new/stay", folder = true))
        db.upsertEntry(entry("/new/stay/b.txt"))
        db.upsertEntry(entry("/new/staying.txt"))

        db.renamePrefix("/old", "/new", skipPrefixes = listOf("/old/keep", "/new/stay"))

        assertEquals(
            listOf(
                "/new",
                "/new/keeper.txt",
                "/new/move.txt",
                "/new/stay",
                "/new/stay/b.txt",
                "/old/keep",
                "/old/keep/a.txt",
            ),
            paths(db.getAllEntries()),
        )
        assertEquals("id-/old/keep/a.txt", db.getEntry("/old/keep/a.txt")?.remoteId)
        assertEquals("id-/old/keep", db.getEntry("/old/keep")?.remoteId)
        assertEquals("id-/new/stay/b.txt", db.getEntry("/new/stay/b.txt")?.remoteId)
        assertEquals("id-/new/stay", db.getEntry("/new/stay")?.remoteId)
        assertEquals("id-/old/keeper.txt", db.getEntry("/new/keeper.txt")?.remoteId)
        assertEquals("id-/old/move.txt", db.getEntry("/new/move.txt")?.remoteId)
        assertEquals("id-/old", db.getEntry("/new")?.remoteId)
        assertNull(db.getEntry("/old/keeper.txt"))
        assertNull(db.getEntry("/old/move.txt"))
        assertNull(db.getEntry("/old"))
        assertNull(db.getEntry("/new/keep/a.txt"))
        assertNull(db.getEntry("/new/staying.txt"), "/new/stay does not cover /new/staying.txt")
    }

    @Test
    fun `a rename onto itself changes nothing`() {
        db.upsertEntry(entry("/same", folder = true))
        db.upsertEntry(entry("/same/a.txt"))
        db.renamePrefix("/same", "/same")
        assertEquals(listOf("/same", "/same/a.txt"), paths(db.getAllEntries()))
    }

    @Test
    fun `renaming a non-BMP folder rewrites the descendants' paths exactly`() {
        db.upsertEntry(entry("/$cats", folder = true))
        db.upsertEntry(entry("/$cats/inner/file.txt"))
        db.renamePrefix("/$cats", "/plain")
        assertEquals(listOf("/plain", "/plain/inner/file.txt"), paths(db.getAllEntries()))
    }

    @Test
    fun `prefix queries are exact about case and about LIKE wildcards`() {
        db.upsertEntry(entry("/Docs/a.txt"))
        db.upsertEntry(entry("/docs/b.txt"))
        db.upsertEntry(entry("/a_b/c.txt"))
        db.upsertEntry(entry("/axb/d.txt"))
        assertEquals(listOf("/Docs/a.txt"), paths(db.getEntriesByPrefix("/Docs/")))
        assertEquals(listOf("/a_b/c.txt"), paths(db.getEntriesByPrefix("/a_b/")))
        assertEquals(1, db.countEntriesUnderTopLevel("/Docs"))
    }
}
