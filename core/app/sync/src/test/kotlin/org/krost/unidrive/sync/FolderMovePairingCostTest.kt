package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncAction
import org.krost.unidrive.sync.model.SyncEntry
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#743 finding 7: a local folder move of a tree with k subfolders plans a delete and a create per
 * subfolder. The top folder's move settles the subfolders' creates (they move along), but the pairing loop still
 * looked for a source of each settled create, scanning the whole plan per subfolder: O(k × plan). The cost is pinned
 * in plan elements visited.
 */
class FolderMovePairingCostTest {
    // Counts every element the pairing reads from the plan (iteration, including the iterator-based removals).
    private class CountingPlan(
        private val inner: MutableList<SyncAction>,
    ) : MutableList<SyncAction> by inner {
        var visits = 0L

        override fun iterator(): MutableIterator<SyncAction> = listIterator()

        override fun listIterator(): MutableListIterator<SyncAction> = listIterator(0)

        override fun listIterator(index: Int): MutableListIterator<SyncAction> {
            val it = inner.listIterator(index)
            return object : MutableListIterator<SyncAction> by it {
                override fun next(): SyncAction = it.next().also { visits++ }
            }
        }

        override fun get(index: Int): SyncAction = inner[index].also { visits++ }
    }

    private fun row(
        path: String,
        folder: Boolean,
    ) = SyncEntry(
        path = path,
        remoteId = "id-$path",
        remoteHash = null,
        remoteSize = if (folder) 0 else 10,
        remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
        localMtime = 1L,
        localSize = if (folder) 0 else 10,
        isFolder = folder,
        isPinned = false,
        isHydrated = true,
        lastSynced = Instant.now(),
    )

    private fun movedTree(k: Int): Pair<CountingPlan, Map<String, SyncEntry>> {
        val entries = HashMap<String, SyncEntry>()
        val plan = ArrayList<SyncAction>()
        entries["/old/A"] = row("/old/A", folder = true)
        plan += SyncAction.DeleteRemote("/old/A")
        plan += SyncAction.CreateRemoteFolder("/new/A")
        for (i in 0 until k) {
            entries["/old/A/S$i"] = row("/old/A/S$i", folder = true)
            entries["/old/A/S$i/f.txt"] = row("/old/A/S$i/f.txt", folder = false)
            plan += SyncAction.DeleteRemote("/old/A/S$i")
            plan += SyncAction.DeleteRemote("/old/A/S$i/f.txt")
            plan += SyncAction.CreateRemoteFolder("/new/A/S$i")
            plan += SyncAction.Upload("/new/A/S$i/f.txt")
        }
        // The rest of the pass: new files elsewhere, which no move touches.
        for (i in 0 until 4 * k) plan += SyncAction.Upload("/other/n$i.txt")
        return CountingPlan(plan) to entries
    }

    private fun pair(
        plan: CountingPlan,
        entries: Map<String, SyncEntry>,
    ): Set<String> =
        pairFolderMoves(
            plan,
            plan.filterIsInstance<SyncAction.DeleteRemote>(),
            entries,
            isAbsentLocally = { it.startsWith("/old/") }, // the tree is on disk at its new place only
        ) { null }

    @Test
    fun `a moved tree is paired in time linear in the plan, not per subfolder`() {
        val k = 400
        val (plan, entries) = movedTree(k)
        val size = plan.size.toLong()
        plan.visits = 0
        pair(plan, entries)
        // The creates are listed once, the top move scans the plan for its candidates and settles it once, and the
        // two linear removals of the pair: a handful of passes. Per settled subfolder it was one more scan.
        assertTrue(plan.visits <= 8 * size, "visited ${plan.visits} plan elements for a plan of $size")
    }

    @Test
    fun `guard - the moved tree becomes one move and nothing of the subtree stays planned`() {
        val (plan, entries) = movedTree(3)
        val paired = pair(plan, entries)
        assertEquals(setOf("/old/A"), paired)
        assertEquals(
            listOf(SyncAction.MoveRemote(path = "/new/A", fromPath = "/old/A", remoteId = "id-/old/A")),
            plan.filterNot { it.path.startsWith("/other/") },
        )
        assertEquals(12, plan.count { it.path.startsWith("/other/") }, "the unrelated uploads stay planned")
    }
}
