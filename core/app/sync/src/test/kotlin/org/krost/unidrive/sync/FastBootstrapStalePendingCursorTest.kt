package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.testfixtures.EngineOpts
import org.krost.unidrive.sync.testfixtures.TwinProvider
import org.krost.unidrive.sync.testfixtures.World
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import org.krost.unidrive.testing.TestScratch

/**
 * The delta cursor only moves forward, to the cursor of the pass whose plan applied (audit 745 finding 5).
 *
 * A fast-bootstrap pass sets `delta_cursor` straight to the remote's latest cursor and never writes `pending_cursor`.
 * The caller then promotes `pending_cursor`, so a `pending_cursor` left behind by an EARLIER pass that never promoted
 * (its gather died on a page, or `refresh` skipped the transfers) overwrites the cursor the bootstrap just adopted.
 * The cursor goes backwards, to a token of an abandoned enumeration, which is the opposite of what the user asked for.
 */
class FastBootstrapStalePendingCursorTest {
    private fun withWorld(block: suspend (World) -> Unit) =
        runTest {
            val world = World(TestScratch.directory("ud-stale-pending"))
            try {
                block(world)
            } finally {
                world.close()
            }
        }

    /** What a gather that died on page 3 of a first listing leaves behind: a mid-listing token, no delta cursor. */
    private fun leaveStalePending(world: World) {
        world.db.setSyncState("pending_cursor", "stale-page-3")
        world.db.setSyncState("pending_cursor_complete", "false")
        assertEquals(null, world.db.getSyncState("delta_cursor"), "pass 1 never promoted")
    }

    private fun bootstrapTo(world: World) {
        world.provider.supportsFastBootstrap = true
        world.provider.deltaCursor = "C2"
    }

    @Test
    fun `a fast bootstrap keeps the cursor it adopted over a stale pending cursor`() =
        withWorld { world ->
            leaveStalePending(world)
            bootstrapTo(world)

            world.engine(EngineOpts(fastBootstrap = true)).syncOnce()

            assertEquals("C2", world.db.getSyncState("delta_cursor"), "the bootstrap's cursor must survive the promote")
        }

    @Test
    fun `a streaming fast bootstrap keeps the cursor it adopted over a stale pending cursor`() =
        withWorld { world ->
            leaveStalePending(world)
            bootstrapTo(world)

            world.engine(EngineOpts(streaming = true, fastBootstrap = true)).syncOnce()

            assertEquals("C2", world.db.getSyncState("delta_cursor"), "the bootstrap's cursor must survive the promote")
        }

    @Test
    fun `a fast bootstrap with no stale pending cursor adopts the remote cursor`() =
        withWorld { world ->
            bootstrapTo(world)

            world.engine(EngineOpts(fastBootstrap = true)).syncOnce()

            assertEquals("C2", world.db.getSyncState("delta_cursor"))
        }

    @Test
    fun `a normal pass writes the pending cursor and promotes it`() =
        withWorld { world ->
            world.provider.deltaItems = listOf(TwinProvider.item("/a.txt"))
            world.provider.files["/a.txt"] = ByteArray(10)
            world.provider.deltaCursor = "C1"

            world.engine().syncOnce()

            assertEquals("C1", world.db.getSyncState("pending_cursor"))
            assertEquals("C1", world.db.getSyncState("delta_cursor"))
        }

    @Test
    fun `a normal pass moves a delta cursor forward over a stale pending one`() =
        withWorld { world ->
            world.db.setSyncState("delta_cursor", "C1")
            world.db.setSyncState("pending_cursor", "stale-older")
            world.provider.incrementalItems = emptyList()
            world.provider.deltaCursor = "C2"

            world.engine().syncOnce()

            assertEquals("C2", world.db.getSyncState("delta_cursor"))
        }
}
