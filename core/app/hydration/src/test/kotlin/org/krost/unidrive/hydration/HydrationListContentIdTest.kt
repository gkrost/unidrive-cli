package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * gkrost/unidrive-cli#171: `hydration.list` names the stored object a row's bytes are (`content_id`; Internxt `<fileId>|<size>`). A rename keeps
 * it while it stamps a new modified time and a new write token, so a client that keeps a local copy tells a rename from new bytes by it.
 */
class HydrationListContentIdTest {
    @Test
    fun `list hands out the row's content identity, and none for a row without one`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertHydratedEntry("/named.txt", localSize = 5, remoteContentId = "obj-1|5")
        env.stateDb.insertHydratedEntry("/plain.txt", localSize = 5)

        val r = env.hydration.list("")

        assertIs<ListResult.Ok>(r)
        val ids = r.entries.associate { it.path to it.contentId }
        assertEquals("obj-1|5", ids["/named.txt"])
        assertNull(ids["/plain.txt"], "a provider that names no stored object lists none")
    }
}
