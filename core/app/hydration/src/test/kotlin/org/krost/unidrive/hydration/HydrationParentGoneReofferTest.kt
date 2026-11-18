package org.krost.unidrive.hydration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Test drive 2, E3: a pending upload refused `parent_gone` (its folder is gone in the cloud and could not be put back yet, e.g.
 * the old folder still held the name in the trash) was never offered again: not in this process, not after two daemon starts,
 * not after a pause and resume. Such a row is never stranded: the refusal is offered again after a backoff, and a start or a
 * resume offers it again too.
 */
class HydrationParentGoneReofferTest {
    private class Env(
        val provider: MemProvider,
        val db: StateDatabase,
        val engine: SyncEngine,
        val mount: MountEngine,
    )

    private suspend fun env(): Env {
        val provider = MemProvider()
        provider.seedFolder("/dir")
        val db = StateDatabase(TestScratch.directory("ud-e3-db").resolve("state.db"), inMemory = true).also { it.initialize() }
        val engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = TestScratch.directory("ud-e3-root"),
                cacheRoot = TestScratch.directory("ud-e3-cache"),
            )
        val mount = MountEngine.over(engine)
        mount.enumerateRemoteIntoState(reset = false)
        return Env(provider, db, engine, mount)
    }

    // A file written below /dir while /dir is gone in the cloud: its upload is refused parent_gone.
    private suspend fun Env.refusedParentGone(scope: CoroutineScope, retryDelays: List<Long>): HydrationImpl {
        provider.items.remove("/dir")
        provider.requireParents = true
        val hydration = HydrationImpl(engine, db, recoveryUploadScope = scope, exhaustedRetryDelaysMs = retryDelays)
        val created = assertIs<CreateResult.Ok>(hydration.create("conn", "h1", "/dir/new.txt"))
        Files.writeString(created.cachePath, "only copy")
        hydration.openForWrite("conn", "h1", "/dir/new.txt", created.cachePath)
        // runCurrent, not advanceUntilIdle: the backoff's delay must not elapse before the folder is back.
        if (scope is TestScope) scope.runCurrent()
        assertNull(provider.remote["/dir/new.txt"], "precondition: the upload was refused")
        // The folder is back (the trash released the name, or it was put back).
        provider.seedFolder("/dir")
        return hydration
    }

    @Test
    fun `a parent_gone refusal is offered again after a backoff`() = runTest {
        val env = env()
        env.refusedParentGone(this, retryDelays = listOf(60_000L))

        advanceTimeBy(60_001L)
        advanceUntilIdle()

        assertEquals("only copy", env.provider.remote["/dir/new.txt"]?.let { String(it) })
    }

    @Test
    fun `a daemon start offers a parent_gone pending upload again`() = runTest {
        val env = env()
        env.refusedParentGone(this, retryDelays = emptyList())

        val restarted = HydrationImpl(env.engine, env.db, recoveryUploadScope = this, exhaustedRetryDelaysMs = emptyList())
        restarted.replayPendingUploads()
        advanceUntilIdle()

        assertEquals("only copy", env.provider.remote["/dir/new.txt"]?.let { String(it) })
    }

    @Test
    fun `a resume offers a parent_gone pending upload again`() = runTest {
        val env = env()
        env.refusedParentGone(this, retryDelays = emptyList())

        env.mount.pause.pause("test", auto = false)
        env.mount.pause.resume()
        advanceUntilIdle()

        assertEquals("only copy", env.provider.remote["/dir/new.txt"]?.let { String(it) })
    }
}
