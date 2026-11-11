package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.ItemRemoteState
import org.krost.unidrive.sync.model.SyncEntry
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * `hydration.locate` (gkrost/unidrive-cli#632): where the items with the given remote ids are now. The state comes
 * from the provider's by-id answer; the rows only name the path, and only decide when the provider cannot tell.
 */
class HydrationLocateTest {
    private fun row(
        path: String,
        remoteId: String,
        isFolder: Boolean = false,
    ) = SyncEntry(
        path = path,
        remoteId = remoteId,
        remoteHash = "h-$path",
        remoteSize = 1L,
        remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
        localMtime = null,
        localSize = null,
        isFolder = isFolder,
        isPinned = false,
        isHydrated = false,
        lastSynced = Instant.now(),
    )

    @Test
    fun `an id the provider lists answers listed, with the path the rows hold`() = runTest {
        val env = HydrationTestEnv()
        // Another client moved the item: the provider still serves the id, the row still names the old path.
        env.providerForTest.itemStatesById = mapOf("rid-moved" to ItemRemoteState.Live(isFolder = false))
        env.stateDb.upsert(row("/old/spot.txt", "rid-moved"))

        val r = env.hydration.locate(listOf("rid-moved"))
        val item = assertIs<LocateResult.Ok>(r).items.single()
        assertEquals("rid-moved", item.remoteId)
        val listed = assertIs<LocatedItem.Listed>(item.found)
        assertEquals("/old/spot.txt", listed.path)
        assertEquals(false, listed.isFolder)
    }

    @Test
    fun `a folder id carries the provider's folder word`() = runTest {
        val env = HydrationTestEnv()
        env.providerForTest.itemStatesById = mapOf("rid-folder" to ItemRemoteState.Live(isFolder = true))
        val item = assertIs<LocateResult.Ok>(env.hydration.locate(listOf("rid-folder"))).items.single()
        val listed = assertIs<LocatedItem.Listed>(item.found)
        assertEquals(true, listed.isFolder)
        assertNull(listed.path, "no row names the id: the path is null, not a guess")
    }

    @Test
    fun `a trashed id answers trashed`() = runTest {
        val env = HydrationTestEnv()
        env.providerForTest.itemStatesById = mapOf("rid-trash" to ItemRemoteState.Trashed(isFolder = false))
        env.stateDb.upsert(row("/gone.txt", "rid-trash"))
        val item = assertIs<LocateResult.Ok>(env.hydration.locate(listOf("rid-trash"))).items.single()
        assertIs<LocatedItem.Trashed>(item.found)
    }

    @Test
    fun `an id nobody can name answers unknown, and a provider that cannot tell falls back to the rows`() = runTest {
        val env = HydrationTestEnv()
        // No by-id answers at all (the default): a live row is listed, nothing is unknown.
        env.stateDb.upsert(row("/still/here.txt", "rid-row"))
        val items = assertIs<LocateResult.Ok>(env.hydration.locate(listOf("rid-row", "rid-never"))).items
        assertIs<LocatedItem.Listed>(items[0].found)
        assertIs<LocatedItem.Unknown>(items[1].found)
    }

    @Test
    fun `the answers keep the request order over a mixed batch`() = runTest {
        val env = HydrationTestEnv()
        env.providerForTest.itemStatesById =
            mapOf(
                "rid-b" to ItemRemoteState.Trashed(true),
                "rid-c" to ItemRemoteState.Live(isFolder = null),
            )
        env.stateDb.upsert(row("/kept.txt", "rid-a"))
        env.stateDb.upsert(row("/c-folder", "rid-c", isFolder = true))
        val items = assertIs<LocateResult.Ok>(env.hydration.locate(listOf("rid-a", "rid-b", "rid-c"))).items
        assertEquals(listOf("rid-a", "rid-b", "rid-c"), items.map { it.remoteId })
        assertIs<LocatedItem.Listed>(items[0].found)
        assertIs<LocatedItem.Trashed>(items[1].found)
        val listed = assertIs<LocatedItem.Listed>(items[2].found)
        assertEquals(true, listed.isFolder, "a null folder word falls back to the row's")
    }

    @Test
    fun `a provider that cannot be asked fails the whole request`() = runTest {
        val env = HydrationTestEnv()
        env.providerForTest.itemStateFailure = java.io.IOException("provider unreachable")
        assertIs<LocateResult.Failed>(env.hydration.locate(listOf("rid-a", "rid-b")))
    }
}
