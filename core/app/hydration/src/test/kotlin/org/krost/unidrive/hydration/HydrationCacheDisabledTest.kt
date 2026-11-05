package org.krost.unidrive.hydration

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #404: a profile without `hydration_cache_max_bytes` (the config's disabled sentinel) keeps no content cache:
 * every read fetches from the provider, `cache.bytes` returns to 0 as soon as nothing is open, a clean copy
 * leaves with its last read handle, and a pin's copy does not survive the pass. A pinned row's copy is never
 * dropped, and a copy whose bytes exist nowhere else stays, exactly as the budgeted reclaim rules say.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HydrationCacheDisabledTest {
    private class Env(
        val provider: MemProvider,
        val db: StateDatabase,
        val engine: SyncEngine,
    )

    private fun freshEnv(): Env {
        val provider = MemProvider()
        val db = StateDatabase(Files.createTempDirectory("ud-404-db").resolve("state.db"))
        db.initialize()
        val engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = Files.createTempDirectory("ud-404-root"),
                cacheRoot = Files.createTempDirectory("ud-404-cache"),
                cacheKey = "ud-404-isolated-profile",
            )
        return Env(provider, db, engine)
    }

    private fun bytesOf(
        seed: Int,
        size: Int,
    ) = ByteArray(size) { (seed + it).toByte() }

    private fun Env.hydration(scope: TestScope) =
        HydrationImpl(
            engine,
            db,
            recoveryUploadScope = scope,
            cacheMaxBytes = HydrationImpl.CACHE_DISABLED_BYTES,
            cacheAccessGraceMs = 0,
            evictionDelayMs = 0,
            evictionMinIntervalMs = 0,
        )

    private fun Env.seedAndSync(vararg files: Pair<String, Int>) {
        files.forEachIndexed { i, (name, size) -> provider.seed("/$name", bytesOf(i * 7 + 1, size)) }
        runBlocking { MountEngine.over(engine).enumerateRemoteIntoState(true) }
    }

    private fun Env.onDisk(): Long =
        Files.walk(engine.resolveCachePath("/")).use { s -> s.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum() }

    @Test
    fun `a read fetches from the provider and keeps no bytes once its handle closes`() =
        runTest {
            val env = freshEnv()
            env.seedAndSync("a.bin" to 1000)
            val hydration = env.hydration(this)
            hydration.requestCacheMeasurement()
            hydration.evictCache()

            val opened = assertIs<OpenResult.Ok>(hydration.openForRead("conn", "h1", "/a.bin"))
            assertContentEquals(bytesOf(1, 1000), Files.readAllBytes(opened.cachePath), "the read serves the right bytes")
            assertEquals(listOf("/a.bin"), env.provider.downloads, "the read fetched from the provider")
            assertEquals(1000L, hydration.cacheHealth().bytes, "the copy is cached while the handle is open")

            hydration.closeHandle("conn", "h1")

            assertEquals(0L, hydration.cacheHealth().bytes, "the copy is gone with the last handle")
            assertEquals(0L, env.onDisk())

            assertIs<OpenResult.Ok>(hydration.openForRead("conn", "h2", "/a.bin"))
            assertEquals(listOf("/a.bin", "/a.bin"), env.provider.downloads, "the next read fetches from the provider again")
            hydration.closeHandle("conn", "h2")
            assertEquals(0L, env.onDisk())
        }

    @Test
    fun `a pin's copy does not survive the pass, a pinned row's copy does`() =
        runTest {
            val env = freshEnv()
            env.seedAndSync("a.bin" to 1000, "b.bin" to 2000)
            val hydration = env.hydration(this)
            hydration.requestCacheMeasurement()
            hydration.evictCache()
            env.db.upsertEntry(assertNotNull(env.db.getEntry("/b.bin")).copy(isPinned = true))

            assertIs<HydrateResult.Ok>(hydration.hydrate("/a.bin"))
            assertIs<HydrateResult.Ok>(hydration.hydrate("/b.bin"))
            advanceUntilIdle()

            assertEquals(2000L, hydration.cacheHealth().bytes, "only the pinned row's copy stays")
            assertFalse(Files.exists(env.engine.resolveCachePath("/a.bin")), "the unpinned pin copy is reclaimed")
            assertTrue(Files.exists(env.engine.resolveCachePath("/b.bin")), "a pinned row's copy is never dropped")
        }

    @Test
    fun `the start sweep of a disabled cache clears what an earlier run left`() =
        runTest {
            val env = freshEnv()
            env.seedAndSync("a.bin" to 1000)
            runBlocking { env.engine.ensureHydrated("/a.bin") }
            assertTrue(env.onDisk() > 0, "precondition: the earlier run left a copy")

            env.hydration(this).sweepCache()

            assertEquals(0L, env.onDisk(), "the disabled cache reclaims at start")
        }
}
