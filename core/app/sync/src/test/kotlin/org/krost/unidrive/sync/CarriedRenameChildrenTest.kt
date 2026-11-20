package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.SyncAction
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * gkrost/unidrive-cli#743 finding 7: a remote rename of a tree with k folders dropped the placeholders and downloads
 * below each old folder path with a scan of the whole plan per folder, O(k × plan). The verdict stays the per-folder
 * scan's; the cost is pinned in plan elements visited.
 */
class CarriedRenameChildrenTest {
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

    private fun item(path: String) =
        CloudItem(id = "id-$path", name = path.substringAfterLast('/'), path = path, size = 1, isFolder = false, modified = null, created = null, hash = null, mimeType = null)

    private fun download(path: String) = SyncAction.DownloadContent(path, item(path))

    @Test
    fun `the children of k renamed folders are dropped in one pass over the plan`() {
        val k = 500
        val plan = CountingPlan(ArrayList())
        val carried = CarriedRenameChildren()
        for (i in 0 until k) {
            plan += download("/old/F$i/f.txt")
            plan += download("/new/F$i/f.txt")
            carried.folderLeft("/old/F$i")
        }
        carried.dropFrom(plan)
        assertEquals((0 until k).map { "/new/F$it/f.txt" }, plan.map { it.path })
        assertEquals(2L * k + k, plan.visits, "one pass over the plan, then the read-back above")
    }

    @Test
    fun `the verdict is the per-folder scan's`() {
        val carried = CarriedRenameChildren()
        val keepUpload = SyncAction.Upload("/old/A/u.txt")
        val plan =
            mutableListOf<SyncAction>(
                download("/old/A/x.txt"),
                SyncAction.CreatePlaceholder("/old/A/sub/y.txt", item("/old/A/sub/y.txt"), shouldHydrate = false),
                download("/old/AB/z.txt"), // /old/A does not cover /old/AB
                download("/old/A"), // the folder's own path is not below it
                keepUpload, // nor is any other kind of action
            )
        carried.folderLeft("/old/A")
        // Appended after /old/A left: the per-folder scan had already run, so it stays; /old/B leaves after it.
        val lateUnderA = download("/old/A/late.txt")
        val lateUnderB = download("/old/B/late.txt")
        plan += lateUnderA
        carried.appended(lateUnderA)
        plan += lateUnderB
        carried.appended(lateUnderB)
        carried.folderLeft("/old/B")
        carried.dropFrom(plan)
        assertEquals(listOf("/old/AB/z.txt", "/old/A", "/old/A/u.txt", "/old/A/late.txt"), plan.map { it.path })
    }
}
