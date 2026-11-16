package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.testfixtures.EngineOpts
import org.krost.unidrive.sync.testfixtures.TwinProvider
import org.krost.unidrive.sync.testfixtures.World
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * finalizeStreaming alias-maps with the top-level names the pages of THIS gather used. The engine is long-lived: a pass
 * that returns before the names are fetched (fast bootstrap) must not hand finalizeStreaming the previous pass's set.
 */
class StreamedTopLevelNamesTest {
    @Test
    fun `a fast-bootstrap pass does not keep the top-level names of the previous pass`() =
        runTest {
            val world = World(Files.createTempDirectory("ud-topnames"))
            try {
                val engine = world.engine(EngineOpts(streaming = true, fastBootstrap = true))
                world.provider.deltaItems = listOf(TwinProvider.item("/Pictures", folder = true), TwinProvider.item("/a.txt"))

                // Pass 1 enumerates (the provider has no fast bootstrap): the pages use the pre-fetched names.
                engine.syncOnce()
                assertEquals(setOf("Pictures"), engine.streamedTopLevelNames)
                assertEquals(1, world.provider.calls.count { it.startsWith("delta cursor=") })

                // Pass 2 starts without a cursor on a provider that now bootstraps: it returns before fetching names.
                world.provider.supportsFastBootstrap = true
                world.db.setSyncState("delta_cursor", "")
                engine.syncOnce()

                assertEquals(1, world.provider.calls.count { it.startsWith("delta cursor=") }, "pass 2 took the fast-bootstrap path")
                assertNull(engine.streamedTopLevelNames, "no pages ran: no names for finalizeStreaming")
            } finally {
                world.close()
            }
        }
}
