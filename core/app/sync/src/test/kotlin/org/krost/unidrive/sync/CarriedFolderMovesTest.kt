package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.SyncAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#743 finding 7, the tail: [dropMovesCarriedByFolderMoves] compared every MoveLocal with every folder
 * move (three times), O(moves × folder moves). A renamed tree whose provider lists every descendant folder makes both
 * the size of the tree: about 10⁸ comparisons for 10k subfolders. The cost is pinned in folder moves examined.
 */
class CarriedFolderMovesTest {
    private fun item(
        path: String,
        folder: Boolean,
    ) = CloudItem(
        id = "id-$path",
        name = path.substringAfterLast('/'),
        path = path,
        size = if (folder) 0 else 5,
        isFolder = folder,
        modified = null,
        created = null,
        hash = null,
        mimeType = null,
    )

    private fun move(
        from: String,
        to: String,
        folder: Boolean,
    ) = SyncAction.MoveLocal(path = to, fromPath = from, remoteItem = item(to, folder))

    @Test
    fun `a renamed tree is settled in folder moves examined linear in its moves`() {
        val k = 300
        val plan = ArrayList<SyncAction>()
        plan += move("/old", "/new", folder = true)
        // Every subfolder got a new name too, so each keeps a move of its own; its file moves along with it.
        for (i in 0 until k) {
            plan += move("/old/s$i", "/new/t$i", folder = true)
            plan += move("/old/s$i/f.txt", "/new/t$i/f.txt", folder = false)
        }
        val moves = plan.size
        var probes = 0L

        dropMovesCarriedByFolderMoves(plan) { probes++ }

        val expected =
            listOf<SyncAction>(move("/old", "/new", folder = true)) +
                (0 until k).map { move("/old/s$it", "/new/t$it", folder = true).copy(fromPath = "/new/s$it") }
        assertEquals(expected, plan, "the files go with their folders, which start where the top move puts them")
        // Each move walks its own ancestors (depth 3 here) in each of the two passes.
        assertTrue(probes <= 8L * moves, "examined $probes folder moves for $moves moves")
    }

    @Test
    fun `guard - carried moves go, a rename inside the tree is re-rooted, one below a nested folder move is not`() {
        val plan =
            mutableListOf<SyncAction>(
                move("/a", "/b", folder = true),
                // carried along: its destination is its old path under /b
                move("/a/x.txt", "/b/x.txt", folder = false),
                // renamed inside the moved folder: re-rooted to start where the folder move put it
                move("/a/y.txt", "/b/z.txt", folder = false),
                // a nested folder move that is itself renamed, and a file below it with a new name
                move("/a/n", "/b/m", folder = true),
                move("/a/n/q.txt", "/b/m/r.txt", folder = false),
                // out of the nested folder up into /b: /a carries it, but /a/n lies between, so it is left as planned
                move("/a/n/w.txt", "/b/w.txt", folder = false),
                // unrelated
                move("/c/u.txt", "/d/u.txt", folder = false),
            )

        dropMovesCarriedByFolderMoves(plan)

        assertEquals(
            listOf<SyncAction>(
                move("/a", "/b", folder = true),
                move("/a/y.txt", "/b/z.txt", folder = false).copy(fromPath = "/b/y.txt"),
                move("/a/n", "/b/m", folder = true).copy(fromPath = "/b/n"),
                // the deepest carrier is /a/n: re-rooted under /b/m
                move("/a/n/q.txt", "/b/m/r.txt", folder = false).copy(fromPath = "/b/m/q.txt"),
                move("/a/n/w.txt", "/b/w.txt", folder = false),
                move("/c/u.txt", "/d/u.txt", folder = false),
            ),
            plan,
        )
    }
}
