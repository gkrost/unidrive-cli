package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.engine.CursorState
import org.krost.unidrive.sync.testfixtures.EngineOpts
import org.krost.unidrive.sync.testfixtures.World
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** A fast bootstrap leaves the pending state agreeing with the cursor it adopted, so `doctor` and `status` read it as complete (#745). */
class FastBootstrapPendingStateTest {
    private fun withWorld(block: suspend (World) -> Unit) =
        runTest {
            val world = World(Files.createTempDirectory("ud-bootstrap-pending"))
            try {
                block(world)
            } finally {
                world.close()
            }
        }

    private fun leaveStaleState(world: World) {
        world.db.setSyncState("pending_cursor", "stale-page-3")
        world.db.setSyncState("pending_cursor_complete", "false")
        world.db.setSyncState("pending_cursor_skipped_paths", "/a\n/b")
        world.provider.supportsFastBootstrap = true
        world.provider.deltaCursor = "C2"
    }

    private fun assertAgrees(world: World) {
        assertEquals("C2", world.db.getSyncState("delta_cursor"))
        assertEquals("C2", world.db.getSyncState("pending_cursor"))
        assertEquals("true", world.db.getSyncState("pending_cursor_complete"))
        assertEquals("", world.db.getSyncState("pending_cursor_skipped_paths"))
    }

    @Test
    fun `a fast bootstrap leaves pending equal to the adopted cursor and complete`() =
        withWorld { world ->
            leaveStaleState(world)
            world.engine(EngineOpts(fastBootstrap = true)).syncOnce()
            assertAgrees(world)
        }

    @Test
    fun `a streaming fast bootstrap leaves pending equal to the adopted cursor and complete`() =
        withWorld { world ->
            leaveStaleState(world)
            world.engine(EngineOpts(streaming = true, fastBootstrap = true)).syncOnce()
            assertAgrees(world)
        }

    @Test
    fun `adopting a bootstrap cursor survives a promote and stamps the full scan`() =
        withWorld { world ->
            val cursors = CursorState(world.db)
            world.db.setSyncState("pending_cursor", "stale")
            cursors.adoptBootstrapCursor("C2")
            cursors.promote()
            assertEquals("C2", cursors.deltaCursor)
            assertEquals(true, cursors.lastFullScan != null)
        }
}
