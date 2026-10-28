package org.krost.unidrive.hydration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.ignore.IgnoreSettings
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#68: `mkdir` of a folder the ignore rules exclude creates it here only; it is never created in the
 * cloud, like any ignored item (docs/dev/specs/ignore-layering-design.md, the mount). The folder is listed, marked
 * excluded, and what is written into it stays here too.
 */
class HydrationIgnoredMkdirTest {
    private fun CoroutineScope.env() = HydrationTestEnv(recoveryUploadScope = this, ignore = IgnoreSettings())

    private suspend fun HydrationTestEnv.listed(prefix: String): Map<String, ListResult.Entry> {
        val r = hydration.list(prefix)
        assertIs<ListResult.Ok>(r)
        return r.entries.associateBy { it.path }
    }

    private fun HydrationTestEnv.project() {
        stateDb.insertFolderEntry("/proj")
        syncEngine.seedCacheContent("/proj/.unidrive.ignore", "build/\n")
    }

    @Test
    fun `mkdir of an ignored folder is kept here, listed as excluded, and never created in the cloud`() =
        runTest {
            val env = env()
            env.project()

            assertEquals(MkdirResult.Ok, env.hydration.mkdir("/proj/build"))

            assertEquals(emptyList(), env.syncEngine.createdFolders(), "nothing is created in the cloud")
            val build = env.listed("/proj").getValue("/proj/build")
            assertTrue(build.excluded, "the folder is marked")
            assertNull(build.remoteId, "it has no cloud copy")
        }

    @Test
    fun `a file written into the ignored folder stays here as well`() =
        runTest {
            val env = env()
            env.project()
            env.hydration.mkdir("/proj/build")

            val created = env.hydration.create("conn1", "h1", "/proj/build/out.bin")
            assertIs<CreateResult.Ok>(created)
            Files.writeString(created.cachePath, "build output")
            val opened = env.hydration.openForWrite("conn1", "h1", "/proj/build/out.bin", created.cachePath)
            assertIs<OpenResult.Ok>(opened)
            advanceUntilIdle()

            assertTrue(opened.excluded)
            assertNull(env.syncEngine.remoteContentSeen("/proj/build/out.bin"))
            assertEquals(emptyList(), env.syncEngine.createdFolders())
        }

    @Test
    fun `rmdir of the ignored folder is local too`() =
        runTest {
            val env = env()
            env.project()
            env.hydration.mkdir("/proj/build")

            assertEquals(RmdirResult.Ok, env.hydration.rmdir("/proj/build", recursive = false, permanent = false))

            assertEquals(emptyList(), env.syncEngine.deletedPaths(), "the cloud is not asked")
            assertTrue("/proj/build" !in env.listed("/proj"))
        }

    @Test
    fun `mkdir of a folder the rules do not exclude still creates it in the cloud`() =
        runTest {
            val env = env()
            env.project()

            assertEquals(MkdirResult.Ok, env.hydration.mkdir("/proj/src"))

            assertEquals(listOf("/proj/src"), env.syncEngine.createdFolders())
        }
}
