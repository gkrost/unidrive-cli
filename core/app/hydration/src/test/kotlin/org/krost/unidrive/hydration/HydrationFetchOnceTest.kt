package org.krost.unidrive.hydration

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * gkrost/unidrive-cli#392: each file's content is fetched from the provider once per hydration. The provider here counts every
 * download call by path ([MemProvider.downloads]); the pin (`hydration.hydrate`), the reads that follow it, a refresh
 * (enumeration) in between and a second pin must not ask for the same bytes again.
 */
class HydrationFetchOnceTest {
    private class Env(
        val provider: MemProvider,
        val db: StateDatabase,
        val engine: SyncEngine,
    )

    private fun freshEnv(vararg files: Pair<String, Int>): Env {
        val provider = MemProvider()
        val db = StateDatabase(Files.createTempDirectory("ud-392-db").resolve("state.db"))
        db.initialize()
        val engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = Files.createTempDirectory("ud-392-root"),
                cacheRoot = Files.createTempDirectory("ud-392-cache"),
                cacheKey = "ud-392-isolated-profile",
            )
        files.forEachIndexed { i, (name, size) -> provider.seed("/$name", ByteArray(size) { (i + it).toByte() }) }
        kotlinx.coroutines.runBlocking { MountEngine.over(engine).enumerateRemoteIntoState(true) }
        return Env(provider, db, engine)
    }

    private fun Env.hydration(scope: kotlinx.coroutines.test.TestScope) = HydrationImpl(engine, db, recoveryUploadScope = scope)

    private fun Env.fetches(name: String) = provider.downloads.count { it == "/$name" }

    @Test
    fun `a pin, the reads that follow and a refresh in between fetch each file once`() =
        runTest {
            val env = freshEnv("a.bin" to 4000, "b.bin" to 5000)
            val hydration = env.hydration(this)

            for (n in listOf("a.bin", "b.bin")) assertIs<HydrateResult.Ok>(hydration.hydrate("/$n"))
            repeat(2) { MountEngine.over(env.engine).enumerateRemoteIntoState(false) }
            for ((i, n) in listOf("a.bin", "b.bin").withIndex()) {
                assertIs<OpenResult.Ok>(hydration.openForRead("conn", "h$i", "/$n"))
                hydration.closeHandle("conn", "h$i")
                assertIs<HydrateResult.Ok>(hydration.hydrate("/$n"))
            }

            assertEquals(1, env.fetches("a.bin"), "a.bin was asked for once: ${env.provider.downloads}")
            assertEquals(1, env.fetches("b.bin"), "b.bin was asked for once: ${env.provider.downloads}")
        }

    @Test
    fun `a pin and a read racing for the same file share one fetch`() =
        runTest {
            val env = freshEnv("a.bin" to 4000)
            val hydration = env.hydration(this)

            val results =
                listOf(
                    async { hydration.hydrate("/a.bin", "conn1") },
                    async { hydration.openForRead("conn2", "h1", "/a.bin") },
                    async { hydration.openForRead("conn3", "h2", "/a.bin") },
                    async { hydration.hydrate("/a.bin", "conn4") },
                ).awaitAll()

            assertEquals(4, results.size)
            assertEquals(1, env.fetches("a.bin"), "one fetch for four callers: ${env.provider.downloads}")
        }
}
