package org.krost.unidrive.hydration

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#391: `daemon.status.cache.bytes` is the engine's running total of the hydration cache. It must follow
 * every way a cache copy leaves the disk (unpin/dehydrate, a delete through the mount, a delete seen in the cloud) and a pin
 * must be held to the budget like a read is, so that "free up space" reclaims in the number the status reports and the
 * bound is enforced without waiting for an unrelated close.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HydrationCacheReclaimTest {
    private class Env(
        val provider: MemProvider,
        val db: StateDatabase,
        val engine: SyncEngine,
    )

    private fun freshEnv(): Env {
        val provider = MemProvider()
        val db = StateDatabase(Files.createTempDirectory("ud-391-db").resolve("state.db"))
        db.initialize()
        val engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = Files.createTempDirectory("ud-391-root"),
                cacheRoot = Files.createTempDirectory("ud-391-cache"),
                cacheKey = "ud-391-isolated-profile",
            )
        return Env(provider, db, engine)
    }

    private fun bytesOf(
        seed: Int,
        size: Int,
    ) = ByteArray(size) { (seed + it).toByte() }

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

    private fun Env.seedAndSync(vararg files: Pair<String, Int>) {
        files.forEachIndexed { i, (name, size) -> provider.seed("/$name", bytesOf(i * 7 + 1, size)) }
        kotlinx.coroutines.runBlocking { MountEngine.over(engine).enumerateRemoteIntoState(true) }
    }

    private fun Env.onDisk(): Long =
        Files.walk(engine.resolveCachePath("/")).use { s -> s.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum() }

    @Test
    fun `unpin reclaims the pinned bytes in the number the status reports`() =
        runTest {
            val env = freshEnv()
            env.seedAndSync("a.bin" to 1000, "b.bin" to 2000, "c.bin" to 3000)
            val hydration = env.hydration(this, budget = 1_000_000)
            hydration.requestCacheMeasurement()
            hydration.evictCache()

            for (n in listOf("a.bin", "b.bin", "c.bin")) assertIs<HydrateResult.Ok>(hydration.hydrate("/$n"))
            assertEquals(6000L, hydration.cacheHealth().bytes, "the pin put the files into the cache and the total")
            assertEquals(6000L, env.onDisk())

            for (n in listOf("a.bin", "b.bin")) assertIs<DehydrateResult.Ok>(hydration.dehydrate("/$n"))

            assertEquals(3000L, hydration.cacheHealth().bytes, "unpin of two files reclaims their size at once")
            assertEquals(3000L, env.onDisk())
        }

    @Test
    fun `a delete through the mount takes the copy out of the total`() =
        runTest {
            val env = freshEnv()
            env.seedAndSync("a.bin" to 1000, "b.bin" to 2000)
            val hydration = env.hydration(this, budget = 1_000_000)
            hydration.evictCache()
            for (n in listOf("a.bin", "b.bin")) assertIs<HydrateResult.Ok>(hydration.hydrate("/$n"))

            assertIs<UnlinkResult.Ok>(hydration.unlink("/a.bin"))

            assertEquals(2000L, hydration.cacheHealth().bytes)
            assertEquals(2000L, env.onDisk())
        }

    @Test
    fun `a file deleted in the cloud leaves the total when the enumeration reaps its copy`() =
        runTest {
            val env = freshEnv()
            env.seedAndSync("a.bin" to 1000, "b.bin" to 2000)
            val hydration = env.hydration(this, budget = 1_000_000)
            hydration.evictCache()
            for (n in listOf("a.bin", "b.bin")) assertIs<HydrateResult.Ok>(hydration.hydrate("/$n"))
            assertEquals(3000L, hydration.cacheHealth().bytes)

            env.provider.delete("/a.bin", null)
            val mount = MountEngine.over(env.engine)
            mount.enumerateRemoteIntoState(true)
            repeat(2) { mount.enumerateRemoteIntoState(false) }

            assertFalse(Files.exists(env.engine.resolveCachePath("/a.bin")), "precondition: the reap removed the copy from the disk")
            assertEquals(2000L, env.onDisk())
            assertEquals(2000L, hydration.cacheHealth().bytes, "and from the total, without waiting for a walk")
        }

    @Test
    fun `a pin that takes the cache over its budget triggers the eviction pass`() =
        runTest {
            val env = freshEnv()
            env.seedAndSync("a.bin" to 1000, "b.bin" to 1000, "c.bin" to 1000)
            val hydration = env.hydration(this, budget = 1500)
            hydration.evictCache()

            for (n in listOf("a.bin", "b.bin", "c.bin")) assertIs<HydrateResult.Ok>(hydration.hydrate("/$n"))
            advanceUntilIdle()

            assertTrue(env.onDisk() <= 1500L, "the budget holds after a pin, not only after a close (on disk: ${env.onDisk()})")
            assertEquals(env.onDisk(), hydration.cacheHealth().bytes)
        }
}
