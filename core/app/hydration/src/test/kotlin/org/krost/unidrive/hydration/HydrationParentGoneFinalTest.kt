package org.krost.unidrive.hydration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#951 (what is left of #134): a pending upload refused `parent_gone` stays refused. The refusal is on the
 * row (state.db), so neither a backoff round, a resume, nor a daemon start offers the same bytes to the provider again; the
 * row stays failed and visible. The ways out are the user's own: a client's retry, `history.retry`, or new content.
 * (Test drive 2, E3 had these offers go through at every start; on a folder that never came back that looped for hours.)
 */
class HydrationParentGoneFinalTest {
    private class Env(
        val provider: MemProvider,
        val dbPath: Path,
        val syncRoot: Path,
        val cacheRoot: Path,
    ) {
        lateinit var db: StateDatabase
        lateinit var engine: SyncEngine
        lateinit var mount: MountEngine

        // A (re)start: the state.db is opened from its file again and a new engine is made over it.
        suspend fun start() {
            db = StateDatabase(dbPath).also { it.initialize() }
            engine = SyncEngine(provider = provider, db = db, syncRoot = syncRoot, cacheRoot = cacheRoot)
            mount = MountEngine.over(engine)
            mount.enumerateRemoteIntoState(reset = false)
        }

        fun stop() = db.close()
    }

    private suspend fun env(): Env {
        val provider = MemProvider()
        provider.seedFolder("/dir")
        val env =
            Env(
                provider,
                TestScratch.directory("ud-951-db").resolve("state.db"),
                TestScratch.directory("ud-951-root"),
                TestScratch.directory("ud-951-cache"),
            )
        env.start()
        return env
    }

    // A file written below /dir while /dir is gone in the cloud: its upload is refused parent_gone. Afterwards the folder is back.
    private suspend fun Env.refusedParentGone(
        scope: CoroutineScope,
        retryDelays: List<Long>,
    ): HydrationImpl {
        provider.items.remove("/dir")
        provider.requireParents = true
        val hydration = HydrationImpl(engine, db, recoveryUploadScope = scope, exhaustedRetryDelaysMs = retryDelays)
        val created = assertIs<CreateResult.Ok>(hydration.create("conn", "h1", "/dir/new.txt"))
        Files.writeString(created.cachePath, "only copy")
        hydration.openForWrite("conn", "h1", "/dir/new.txt", created.cachePath)
        // runCurrent, not advanceUntilIdle: the backoff's delay must not elapse before the check.
        if (scope is TestScope) scope.runCurrent()
        assertNull(provider.remote["/dir/new.txt"], "precondition: the upload was refused")
        assertTrue(db.uploadRefusal("/dir/new.txt")?.endsWith("|parent_gone: /dir") == true, "precondition: the refusal is on the row")
        provider.seedFolder("/dir")
        return hydration
    }

    @Test
    fun `a parent_gone refusal is not offered again by a backoff round`() = runTest {
        val env = env()
        env.refusedParentGone(this, retryDelays = listOf(60_000L, 120_000L))

        advanceTimeBy(24 * 60 * 60 * 1000L)
        advanceUntilIdle()

        assertNull(env.provider.remote["/dir/new.txt"], "the refused row was not sent again")
        assertNotNull(env.db.uploadRefusal("/dir/new.txt"), "the refusal is still on the row")
        env.stop()
    }

    @Test
    fun `a parent_gone refusal is not offered again by a resume`() = runTest {
        val env = env()
        env.refusedParentGone(this, retryDelays = emptyList())

        env.mount.pause.pause("test", auto = false)
        env.mount.pause.resume()
        advanceUntilIdle()

        assertNull(env.provider.remote["/dir/new.txt"], "the refused row was not sent again")
        env.stop()
    }

    @Test
    fun `a parent_gone refusal survives a daemon restart and is not replayed`() = runTest {
        val env = env()
        env.refusedParentGone(this, retryDelays = emptyList())
        env.stop()

        env.start() // the state.db is read from its file by a new engine
        val restarted = HydrationImpl(env.engine, env.db, recoveryUploadScope = this, exhaustedRetryDelaysMs = emptyList())
        restarted.replayPendingUploads()
        advanceUntilIdle()

        assertNull(env.provider.remote["/dir/new.txt"], "the start did not offer the refused row again")
        assertTrue(env.db.uploadRefusal("/dir/new.txt")?.endsWith("|parent_gone: /dir") == true, "the refusal is persisted")
        env.stop()
    }

    @Test
    fun `a client retry still sends a parent_gone row once the parent is back`() = runTest {
        val env = env()
        val hydration = env.refusedParentGone(this, retryDelays = emptyList())

        hydration.openForWrite("conn", "h2", "/dir/new.txt", env.mount.resolveCachePath("/dir/new.txt"))
        advanceUntilIdle()

        assertEquals("only copy", env.provider.remote["/dir/new.txt"]?.let { String(it) })
        env.stop()
    }
}
