package org.krost.unidrive.hydration

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.HashVerifier
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.krost.unidrive.testing.TestScratch

/**
 * gkrost/unidrive-cli#746 finding 8: a cache copy only its content hash can vouch for (a row whose local file is not the
 * cache copy) is read once per eviction, not twice. The close reclaim and the budget pass used to classify it before the
 * path's hydration lock and again under it, so closing a multi-GB legacy copy read it twice inline. The proof under the
 * lock is the one that counts; the look before it must still keep a copy whose bytes no version vouches for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HydrationEvictionHashesOnceTest {
    private class Env(
        val provider: MemProvider,
        val db: StateDatabase,
        val engine: SyncEngine,
    )

    private fun freshEnv(): Env {
        val provider = MemProvider()
        val db = StateDatabase(TestScratch.directory("ud-746-8-db").resolve("state.db"))
        db.initialize()
        val engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = TestScratch.directory("ud-746-8-root"),
                cacheRoot = TestScratch.directory("ud-746-8-cache"),
                cacheKey = "ud-746-8-isolated-profile",
            )
        provider.seed("/a.bin", ByteArray(1000) { it.toByte() })
        runBlocking { MountEngine.over(engine).enumerateRemoteIntoState(true) }
        return Env(provider, db, engine)
    }

    private fun Env.hydration(
        scope: TestScope,
        budget: Long,
    ) = HydrationImpl(
        engine,
        db,
        recoveryUploadScope = scope,
        cacheMaxBytes = budget,
        cacheAccessGraceMs = 0,
        evictionDelayMs = 0,
        evictionMinIntervalMs = 0,
    )

    // The row's local file is not the cache copy (a legacy row): only the recorded hash of the bytes can vouch for the copy.
    private fun Env.legacyRow(
        cache: Path,
        recordedHash: String = HashVerifier.computeSha256Hex(cache),
    ) {
        db.upsertEntry(assertNotNull(db.getEntry("/a.bin")).copy(cacheBacked = false, localHash = recordedHash))
    }

    @Test
    fun `the close reclaim reads a hash-proved copy once`() =
        runTest {
            val env = freshEnv()
            val hydration = env.hydration(this, HydrationImpl.CACHE_DISABLED_BYTES)
            val cache = assertIs<OpenResult.Ok>(hydration.openForRead("conn", "h1", "/a.bin")).cachePath
            env.legacyRow(cache)
            env.provider.hashAlgorithmAsks.set(0)

            hydration.closeHandle("conn", "h1")
            advanceUntilIdle()

            assertFalse(Files.exists(cache), "precondition: the hash vouches for the copy, so the reclaim drops it")
            assertEquals(1, env.provider.hashAlgorithmAsks.get(), "one content-hash proof of the copy, under the lock")
        }

    @Test
    fun `the budget pass reads a hash-proved copy once`() =
        runTest {
            val env = freshEnv()
            val cache = env.engine.ensureHydrated("/a.bin")
            env.legacyRow(cache)
            val hydration = env.hydration(this, budget = 1)
            env.provider.hashAlgorithmAsks.set(0)

            hydration.evictCache()

            assertFalse(Files.exists(cache), "precondition: the hash vouches for the copy, so the pass evicts it")
            assertEquals(1, env.provider.hashAlgorithmAsks.get(), "one content-hash proof of the copy, under the lock")
        }

    @Test
    fun `a copy no recorded hash vouches for stays through the close reclaim and the budget pass`() =
        runTest {
            val env = freshEnv()
            val hydration = env.hydration(this, budget = 1)
            val cache = assertIs<OpenResult.Ok>(hydration.openForRead("conn", "h1", "/a.bin")).cachePath
            env.legacyRow(cache, recordedHash = "0".repeat(64))

            hydration.closeHandle("conn", "h1")
            advanceUntilIdle()
            hydration.evictCache()

            assertTrue(Files.exists(cache), "bytes no version vouches for exist nowhere else: never evicted")
        }
}
