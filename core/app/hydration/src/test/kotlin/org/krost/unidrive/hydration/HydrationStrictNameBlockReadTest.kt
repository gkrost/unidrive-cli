package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.NameRefusedException
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.testing.TestScratch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#29 follow-up: an item the strict-name mode (Internxt's `internxt_strict_names`) blocks is not
 * materialised. open_read and hydrate refuse it with the stable `name_incompatible` token, download nothing, and leave
 * the block where it is. Before, the download's row write cleared the block (the status count dropped by one per read)
 * until the next poll marked it again.
 */
class HydrationStrictNameBlockReadTest {
    private class Env(
        val provider: MemProvider,
        val db: StateDatabase,
        val hydration: HydrationImpl,
    )

    private suspend fun blockedFile(scope: kotlinx.coroutines.CoroutineScope): Env {
        val provider = MemProvider()
        provider.seed("/odd.txt", "cloud bytes".toByteArray())
        val db = StateDatabase(TestScratch.directory("ud-29-strict-db").resolve("state.db"), inMemory = true)
        db.initialize()
        val engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = TestScratch.directory("ud-29-strict-root"),
                cacheRoot = TestScratch.directory("ud-29-strict-cache"),
            )
        MountEngine.over(engine).enumerateRemoteIntoState(reset = false)
        assertTrue(db.markNameBlocked("/odd.txt", "${NameRefusedException.NAME_INCOMPATIBLE}: trailing_dot", null), "precondition: the row is blocked")
        provider.downloads.clear()
        return Env(provider, db, HydrationImpl(engine, db, recoveryUploadScope = scope))
    }

    @Test
    fun `open_read of a strict-blocked file is refused with name_incompatible`() = runTest {
        val env = blockedFile(this)

        val result = assertIs<OpenResult.Failed>(env.hydration.openForRead("conn", "h1", "/odd.txt"))

        assertTrue(result.error.message.startsWith(NameRefusedException.NAME_INCOMPATIBLE), "got ${result.error.message}")
        assertEquals(emptyList(), env.provider.downloads, "nothing is downloaded")
    }

    @Test
    fun `open_read leaves the strict block in place`() = runTest {
        val env = blockedFile(this)

        env.hydration.openForRead("conn", "h1", "/odd.txt")

        assertEquals("${NameRefusedException.NAME_INCOMPATIBLE}: trailing_dot", env.db.nameBlock("/odd.txt")?.reason)
        assertEquals(1, env.db.countNameBlocked(NameRefusedException.NAME_INCOMPATIBLE))
    }

    @Test
    fun `hydrate of a strict-blocked file is refused with name_incompatible and leaves the block`() = runTest {
        val env = blockedFile(this)

        val result = assertIs<HydrateResult.Failed>(env.hydration.hydrate("/odd.txt"))

        assertTrue(result.error.message.startsWith(NameRefusedException.NAME_INCOMPATIBLE), "got ${result.error.message}")
        assertEquals(emptyList(), env.provider.downloads, "nothing is downloaded")
        assertEquals("${NameRefusedException.NAME_INCOMPATIBLE}: trailing_dot", env.db.nameBlock("/odd.txt")?.reason)
    }
}
