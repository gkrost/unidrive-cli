package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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

    // ── #404: reclaim on the last close ────────────────────────────────────────────────────────────────

    @Test
    fun `the last read handle closing drops a clean unpinned copy at once`() =
        runTest {
            val env = freshEnv()
            env.seedAndSync("a.bin" to 1000)
            val hydration = env.hydration(this, budget = 1_000_000)
            hydration.requestCacheMeasurement()
            hydration.evictCache()

            val opened = assertIs<OpenResult.Ok>(hydration.openForRead("conn", "h1", "/a.bin"))
            assertContentEquals(bytesOf(1, 1000), Files.readAllBytes(opened.cachePath))
            assertEquals(1000L, hydration.cacheHealth().bytes, "precondition: the read cached the copy")

            hydration.closeHandle("conn", "h1")

            assertEquals(0L, hydration.cacheHealth().bytes, "the close reclaimed the copy without waiting for a pass")
            assertEquals(0L, env.onDisk())
            assertIs<OpenResult.Ok>(hydration.openForRead("conn", "h2", "/a.bin"))
            assertEquals(listOf("/a.bin", "/a.bin"), env.provider.downloads, "the next read fetches from the provider again")
        }

    @Test
    fun `a pinned copy is never dropped on close`() =
        runTest {
            val env = freshEnv()
            env.seedAndSync("a.bin" to 1000)
            val hydration = env.hydration(this, budget = 1_000_000)
            hydration.requestCacheMeasurement()
            hydration.evictCache()
            env.db.upsertEntry(assertNotNull(env.db.getEntry("/a.bin")).copy(isPinned = true))

            assertIs<OpenResult.Ok>(hydration.openForRead("conn", "h1", "/a.bin"))
            hydration.closeHandle("conn", "h1")

            assertEquals(1000L, hydration.cacheHealth().bytes, "a pinned row's copy stays")
            assertEquals(1000L, env.onDisk())
        }

    @Test
    fun `a dirty copy is never dropped on close`() =
        runTest {
            val env = freshEnv()
            env.seedAndSync("a.bin" to 1000)
            val hydration = env.hydration(this, budget = 1_000_000)
            hydration.requestCacheMeasurement()
            hydration.evictCache()
            val cachePath = env.engine.resolveCachePath("/a.bin")

            assertIs<OpenResult.Ok>(hydration.openForRead("conn", "h1", "/a.bin"))
            // An edit while the handle is open: these bytes exist nowhere else until they are uploaded.
            Files.writeString(cachePath, "an unsynced edit")
            Files.setLastModifiedTime(cachePath, FileTime.from(Instant.now().plusSeconds(60)))
            hydration.closeHandle("conn", "h1")

            assertTrue(Files.exists(cachePath), "the dirty copy is the only copy of the edit: it stays")
        }

    @Test
    fun `a copy under a queued upload is never dropped on close`() =
        runTest {
            val env = freshEnv()
            env.seedAndSync("a.bin" to 1000)
            val hydration = env.hydration(this, budget = 1_000_000)
            hydration.requestCacheMeasurement()
            hydration.evictCache()
            assertIs<HydrateResult.Ok>(hydration.hydrate("/a.bin"))
            val cachePath = env.engine.resolveCachePath("/a.bin")
            val gate = CompletableDeferred<Unit>().also { env.provider.uploadGate = it }

            assertIs<OpenResult.Ok>(hydration.openForWrite("conn", "h-w", "/a.bin", cachePath, baseEtag = null))
            hydration.closeHandle("conn", "h-w")
            assertTrue(hydration.hasUploadSlot("/a.bin"), "precondition: the upload is queued behind the gate")
            assertEquals(1000L, hydration.cacheHealth().bytes, "the upload pins the copy through the close")

            gate.complete(Unit)
            advanceUntilIdle()
            assertFalse(hydration.hasUploadSlot("/a.bin"), "precondition: the upload landed")

            // Once the upload landed, nothing protects the copy any more: the next last close reclaims it.
            assertIs<OpenResult.Ok>(hydration.openForRead("conn", "h2", "/a.bin"))
            hydration.closeHandle("conn", "h2")
            assertFalse(Files.exists(cachePath), "once the upload landed, the clean copy is reclaimable again")
            assertEquals(0L, env.onDisk())
        }
}
