package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.krost.unidrive.testing.TestScratch

/**
 * #517 review: a dehydrate whose locked cache delete is REFUSED (the copy is not the one the caller vetted)
 * leaves the copy in place — and it must leave it ACCOUNTED for, too. The forget ran before the refusal check,
 * so the retained copy vanished from cacheHealth.bytes: daemon.status underreported cache use and the budget
 * trigger could skip reclaim until the periodic full reconciliation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewRefusedDehydrateAccountingTest {
    private class Env(val provider: MemProvider, val db: StateDatabase, val engine: SyncEngine)

    private fun freshEnv(): Env {
        val provider = MemProvider()
        val db = StateDatabase(TestScratch.directory("ud-517-db").resolve("state.db"))
        db.initialize()
        val engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = TestScratch.directory("ud-517-root"),
                cacheRoot = TestScratch.directory("ud-517-cache"),
                cacheKey = "ud-517-isolated-profile",
            )
        return Env(provider, db, engine)
    }

    private fun Env.hydration(scope: TestScope) =
        HydrationImpl(
            engine,
            db,
            recoveryUploadScope = scope,
            cacheMaxBytes = 1_000_000,
            cacheAccessGraceMs = 0,
            evictionDelayMs = 0,
            evictionMinIntervalMs = 0,
        )

    @Test
    fun `a refused dehydrate keeps the retained copy accounted`() =
        runTest {
            
            val env = freshEnv()
            env.provider.seed("/a.bin", ByteArray(3000) { (it * 3 + 1).toByte() })
            kotlinx.coroutines.runBlocking { MountEngine.over(env.engine).enumerateRemoteIntoState(true) }
            val hydration = env.hydration(this)
            hydration.requestCacheMeasurement()
            hydration.evictCache()
            assertIs<HydrateResult.Ok>(hydration.hydrate("/a.bin"))
            assertEquals(3000L, hydration.cacheHealth().bytes, "precondition: the hydrated copy is accounted")

            // The reviewer's scenario: a hydrate of the same path holds the hydration lock while a write
            // lands on the copy, and the dehydrate waits for that lock. When it finally gets in, the copy
            // on disk is not the one its stat vetted — the locked delete refuses, the copy stays, and the
            // accounting must stay with it.
            env.db.markUnhydrated("/a.bin") // the copy stays on disk and accounted; the row says placeholder
            env.provider.downloadReached = CompletableDeferred()
            env.provider.downloadRelease = CompletableDeferred()
            // The download is held while virtual time runs on (advanceUntilIdle): not a stall (gkrost/unidrive-cli#508).
            MountEngine.over(env.engine).downloadStall.timeoutMs = 0
            val hydrate = async { MountEngine.over(env.engine).ensureHydratedReporting("/a.bin") }
            env.provider.downloadReached?.await() // the hydrate holds the path's lock, mid-download

            val dehydrate = async { hydration.dehydrate("/a.bin") }
            // Dehydrate has stat-vetted the copy by now (that happens before the locked delete it waits for).
            advanceUntilIdle()
            env.provider.downloadRelease?.complete(Unit)
            hydrate.await() // the download swaps a fresh copy in, then frees the lock

            assertIs<DehydrateResult.Failed>(dehydrate.await(), "the locked delete refuses: the copy is not the one the stat vetted")
            assertTrue(Files.exists(env.engine.resolveCachePath("/a.bin")), "the refused dehydrate leaves the copy in place")
            assertEquals(
                3000L,
                hydration.cacheHealth().bytes,
                "the retained copy stays accounted for — forgetting it here reported a cache that was not the truth",
            )
        }

    // The same refusal when the fresh copy happens to carry the vetted copy's size AND modification time (a download that
    // ends in the millisecond of the earlier one, as a fast runner does): the swap put another file there, and the locked
    // delete must see that, not only a (mtime, size) pair that a different file can share.
    @Test
    fun `a refused dehydrate sees a swapped-in copy with the vetted size and mtime`() =
        runTest {
            val env = freshEnv()
            env.provider.seed("/a.bin", ByteArray(3000) { (it * 3 + 1).toByte() })
            kotlinx.coroutines.runBlocking { MountEngine.over(env.engine).enumerateRemoteIntoState(true) }
            val hydration = env.hydration(this)
            assertIs<HydrateResult.Ok>(hydration.hydrate("/a.bin"))
            val cache = env.engine.resolveCachePath("/a.bin")
            val vettedMtime = Files.getLastModifiedTime(cache)

            env.db.markUnhydrated("/a.bin")
            env.provider.downloadWritten = { Files.setLastModifiedTime(it, vettedMtime) }
            env.provider.downloadReached = CompletableDeferred()
            env.provider.downloadRelease = CompletableDeferred()
            // The download is held while virtual time runs on (advanceUntilIdle): not a stall (gkrost/unidrive-cli#508).
            MountEngine.over(env.engine).downloadStall.timeoutMs = 0
            val hydrate = async { MountEngine.over(env.engine).ensureHydratedReporting("/a.bin") }
            env.provider.downloadReached?.await()

            val dehydrate = async { hydration.dehydrate("/a.bin") }
            advanceUntilIdle()
            env.provider.downloadRelease?.complete(Unit)
            hydrate.await()

            assertIs<DehydrateResult.Failed>(dehydrate.await(), "the copy on disk is not the file the stat vetted")
            assertTrue(Files.exists(cache), "the swapped-in copy stays")
        }
}
// trace run
