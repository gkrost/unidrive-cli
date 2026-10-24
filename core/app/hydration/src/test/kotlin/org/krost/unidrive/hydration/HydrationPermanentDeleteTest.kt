package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** #1178: `rm --permanent` through the hydration verbs. */
class HydrationPermanentDeleteTest {
    @Test
    fun `#1178 unlink permanent deletes for good through the provider, the default never does`() = runTest {
        val env = HydrationTestEnv()
        env.providerForTest.supportsPermanent = true
        env.stateDb.insertUnhydratedEntry("/gone.txt", remoteSize = 5)
        env.stateDb.insertUnhydratedEntry("/kept.txt", remoteSize = 5)

        assertTrue(env.hydration.unlink("/gone.txt", permanent = true) is UnlinkResult.Ok)
        assertEquals(listOf("/gone.txt"), env.providerForTest.permanentlyDeleted)
        assertEquals(emptyList(), env.providerForTest.deletedPaths, "the trash delete did not run")

        assertTrue(env.hydration.unlink("/kept.txt") is UnlinkResult.Ok)
        assertEquals(listOf("/gone.txt"), env.providerForTest.permanentlyDeleted, "the default path never deletes for good")
        assertEquals(listOf("/kept.txt"), env.providerForTest.deletedPaths)
    }

    @Test
    fun `#1178 unlink permanent on a provider without the capability is a typed refusal and the row stays`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/stay.txt", remoteSize = 5)

        val result = env.hydration.unlink("/stay.txt", permanent = true)

        assertTrue(result is UnlinkResult.Failed && result.error == HydrationError.PermanentUnsupported, "typed refusal: $result")
        assertNotNull(env.stateDb.statusOf("/stay.txt"), "nothing was deleted")
        assertEquals(emptyList(), env.providerForTest.deletedPaths, "no silent fall back to the trash delete")
    }

    @Test
    fun `#1178 rmdir permanent without the capability is a typed refusal`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/dir", remoteSize = 0, isFolder = true)

        val result = env.hydration.rmdir("/dir", recursive = true, permanent = true)

        assertTrue(result is RmdirResult.Failed && result.error == HydrationError.PermanentUnsupported, "typed refusal: $result")
        assertNotNull(env.stateDb.statusOf("/dir"))
        assertEquals(emptyList(), env.providerForTest.deletedPaths)
    }

}
