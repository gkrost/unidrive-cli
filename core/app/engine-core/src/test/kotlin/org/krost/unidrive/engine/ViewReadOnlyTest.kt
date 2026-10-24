package org.krost.unidrive.engine

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.CloudItem
import org.krost.unidrive.ViewKind
import org.krost.unidrive.ViewRef
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The read-only rule reaches the views through the same two places as the trash namespace: the guard that every mount
 * verb asks, and the provider decorator that is the last line. A path in a view never reaches the provider; reads do.
 */
class ViewReadOnlyTest {
    private val views =
        ViewNamespaces(
            specs = listOf(ViewSpec(ViewKind.RECENTS, limit = 10), ViewSpec(ViewKind.FAVORITES, "Starred", limit = 10)),
            ignoreCase = false,
        )
    private val trash = TrashNamespace(ignoreCase = false)
    private val inner = FakeViewProvider()
    private val provider = ReadOnlyTrashProvider(inner, trash, views)
    private val local: Path = Files.createTempFile("view-readonly", ".bin")

    private fun guard(withViews: ViewNamespaces = views) =
        RemoteOperationGuard(
            standingScope = emptyList(),
            syncPaths = emptyList(),
            excludePatterns = emptyList(),
            maxConcurrentTransfers = 2,
            matchesGlob = { _, _ -> false },
            trash = trash,
            views = withViews,
        )

    @Test
    fun `every path in a view is read-only for the guard, the root included`() {
        val g = guard()

        assertTrue(g.isReadOnlyPath("/Recents"))
        assertTrue(g.isReadOnlyPath("/Recents/a.txt"))
        assertTrue(g.isReadOnlyPath("/Starred/sub/a.txt"))
        assertFalse(g.isReadOnlyPath("/Favorites/a.txt"), "the folder is called what the profile says")
        assertFalse(g.isReadOnlyPath("/Docs/a.txt"))
        assertFalse(g.isReadOnlyPath("/RecentsOf/a.txt"))
    }

    @Test
    fun `the trash is read-only as before, and a guard without views asks nothing of them`() {
        trash.place(listOf(CloudItem("t1", "a.txt", "/Old/a.txt", 1, false, null, null, null, null, trashed = true))) { emptyList() }

        assertTrue(guard().isReadOnlyPath("/Papierkorb/Old/a.txt"))
        assertTrue(guard(ViewNamespaces.NONE).isReadOnlyPath("/Papierkorb/Old/a.txt"))
        assertFalse(guard(ViewNamespaces.NONE).isReadOnlyPath("/Recents/a.txt"))
        assertTrue(RemoteOperationGuard(emptyList(), emptyList(), emptyList(), 1, { _, _ -> false }).views.isEmpty())
    }

    @Test
    fun `every write to a path in a view is refused before it reaches the provider`() =
        runTest {
            assertFailsWith<ViewReadOnlyException> { provider.upload(local, "/Recents/a.txt") }
            assertFailsWith<ViewReadOnlyException> { provider.upload(local, "/Recents/new.txt", existingRemoteId = null) }
            assertFailsWith<ViewReadOnlyException> { provider.delete("/Recents/a.txt") }
            assertFailsWith<ViewReadOnlyException> { provider.delete("/Recents") }
            assertFailsWith<ViewReadOnlyException> { provider.deleteById("real-id", "/Recents/a.txt") }
            assertFailsWith<ViewReadOnlyException> { provider.createFolder("/Recents/sub") }
            assertFailsWith<ViewReadOnlyException> { provider.createFolders(listOf("/Fine", "/Starred/x")) }
            assertFailsWith<ViewReadOnlyException> { provider.move("/Recents/a.txt", "/Elsewhere/a.txt") }
            assertFailsWith<ViewReadOnlyException> { provider.move("/Docs/a.txt", "/Recents/a.txt") }
            assertFailsWith<ViewReadOnlyException> { provider.moveById("real-id", "/Docs/a.txt", "/Recents/a.txt") }

            assertEquals(emptyList(), inner.calls.toList(), "nothing reached the provider")
        }

    @Test
    fun `a key of a view is refused by id whatever path the call carries`() =
        runTest {
            val entryKey = ViewNamespaces.entryKey(ViewKind.RECENTS, "real-id")

            assertFailsWith<ViewReadOnlyException> { provider.upload(local, "/Docs/a.txt", existingRemoteId = entryKey) }
            assertFailsWith<ViewReadOnlyException> { provider.deleteById(entryKey, "/Docs/a.txt") }
            assertFailsWith<ViewReadOnlyException> { provider.deleteById(ViewNamespaces.rootKey(ViewKind.RECENTS), "/anything") }
            assertFailsWith<ViewReadOnlyException> { provider.moveById(entryKey, "/Docs/a.txt", "/Docs/b.txt") }

            assertEquals(emptyList(), inner.calls.toList())
        }

    @Test
    fun `the real item behind an entry is as writable as ever, by its own path and its own id`() =
        runTest {
            provider.upload(local, "/Docs/a.txt", existingRemoteId = "real-id")
            provider.deleteById("real-id", "/Docs/a.txt")
            provider.moveById("real-id", "/Docs/a.txt", "/Docs/b.txt")
            provider.createFolder("/Docs/sub")

            assertEquals(
                listOf("upload /Docs/a.txt real-id", "deleteById real-id /Docs/a.txt", "moveById real-id /Docs/a.txt /Docs/b.txt", "createFolder /Docs/sub"),
                inner.calls.toList(),
            )
        }

    @Test
    fun `reading from a view path is not a write`() =
        runTest {
            val target = Files.createTempFile("view-readonly-read", ".bin")

            provider.listChildren("/Recents")
            provider.getMetadata("/Recents/a.txt")
            provider.download("/Recents/a.txt", target)

            assertEquals(listOf("listChildren /Recents", "getMetadata /Recents/a.txt", "download /Recents/a.txt"), inner.calls.toList())
        }

    @Test
    fun `listing a view is a read and reaches the provider through the decorator`() =
        runTest {
            inner.serve(ViewKind.RECENTS, ViewRef("a", false))

            val listed = provider.listView(ViewKind.RECENTS, 7)

            assertEquals(CapabilityResult.Success(listOf(ViewRef("a", false))), listed)
            assertEquals(listOf(ViewKind.RECENTS to 7), inner.viewCalls.toList())
        }

    @Test
    fun `a decorator built the old way, with the trash only, refuses what it always refused and nothing else`() =
        runTest {
            val old = ReadOnlyTrashProvider(inner, trash)
            trash.place(listOf(CloudItem("t1", "a.txt", "/Old/a.txt", 1, false, null, null, null, null, trashed = true))) { emptyList() }

            assertFailsWith<TrashReadOnlyException> { old.delete("/Papierkorb/Old/a.txt") }
            old.createFolder("/Recents/x")

            assertEquals(listOf("createFolder /Recents/x"), inner.calls.toList())
        }
}
