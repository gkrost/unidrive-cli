package org.krost.unidrive.hydration

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.ViewKind
import org.krost.unidrive.ViewRef
import org.krost.unidrive.engine.ViewSpec
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A folder in a read-only view is an alias of the real folder, as the mount serves it: the listing below it is the real
 * content under the alias prefix, a read is a read of the real item (one cache copy, one download), and every verb that
 * would write answers `read_only` on the alias root and on everything below it, before anything is touched. The fake
 * provider serves the FAVORITES kind; the code is the same for every kind.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HydrationViewAliasTest {
    private val kind = ViewKind.FAVORITES

    // /Docs is the favorited folder with a tree three levels deep; /Docs2 is a real sibling; /loose.txt a favorited file.
    private fun TestScope.envWithAlias(): HydrationTestEnv {
        val env = HydrationTestEnv(recoveryUploadScope = this, viewSpecs = listOf(ViewSpec(kind, limit = 10)))
        env.stateDb.insertFolderEntry("/Docs", remoteId = "id-docs")
        env.stateDb.insertFolderEntry("/Docs/sub", remoteId = "id-sub")
        env.stateDb.insertFolderEntry("/Docs/sub/deep", remoteId = "id-deep")
        env.stateDb.insertUnhydratedEntry("/Docs/sub/deep/c.txt", 5, remoteId = "id-c")
        env.stateDb.insertUnhydratedEntry("/Docs/sub/b.txt", 5, remoteId = "id-b")
        env.stateDb.insertUnhydratedEntry("/Docs/a.txt", 5, remoteId = "id-a")
        env.stateDb.insertFolderEntry("/Docs2", remoteId = "id-docs2")
        env.stateDb.insertUnhydratedEntry("/Docs2/x.txt", 5, remoteId = "id-x")
        env.stateDb.insertUnhydratedEntry("/loose.txt", 5, remoteId = "id-loose")
        env.providerForTest.viewAnswers[kind] =
            CapabilityResult.Success(listOf(ViewRef("id-docs", true), ViewRef("id-loose", false)))
        return env
    }

    private fun tokenOf(result: Any?): String =
        when (result) {
            is OpenResult.Failed -> result.error.message
            is HydrateResult.Failed -> result.error.message
            is DehydrateResult.Failed -> result.error.message
            is MkdirResult.Failed -> result.error.message
            is CreateResult.Failed -> result.error.message
            is RenameResult.Failed -> result.error.message
            is UnlinkResult.Failed -> result.error.message
            is RmdirResult.Failed -> result.error.message
            else -> error("did not fail: $result")
        }

    private suspend fun HydrationTestEnv.entries(prefix: String) = assertIs<ListResult.Ok>(hydration.list(prefix)).entries

    @Test
    fun `the root lists the alias as a folder entry and the file entry, flagged and pointing at the real items`() =
        runTest {
            val env = envWithAlias()

            val listed = env.entries("/Favorites").associateBy { it.path }

            val alias = listed.getValue("/Favorites/Docs")
            assertTrue(alias.isFolder && alias.readOnly)
            assertEquals("favorites", alias.view)
            assertEquals("/Docs", alias.viewTarget)
            assertEquals("unidrive:view:favorites:id-docs", alias.remoteId)
            assertEquals("/loose.txt", listed.getValue("/Favorites/loose.txt").viewTarget)
        }

    @Test
    fun `the content below an alias is the real content with rewritten paths to depth three`() =
        runTest {
            val env = envWithAlias()

            val level1 = env.entries("/Favorites/Docs").associateBy { it.path }
            val level2 = env.entries("/Favorites/Docs/sub").associateBy { it.path }
            val level3 = env.entries("/Favorites/Docs/sub/deep")

            assertEquals(setOf("/Favorites/Docs/a.txt", "/Favorites/Docs/sub"), level1.keys)
            assertEquals(setOf("/Favorites/Docs/sub/b.txt", "/Favorites/Docs/sub/deep"), level2.keys)
            assertEquals(listOf("/Favorites/Docs/sub/deep/c.txt"), level3.map { it.path })
            val c = level3.single()
            assertTrue(c.readOnly && !c.isFolder && c.size == 5L)
            assertEquals("favorites", c.view)
            assertEquals("/Docs/sub/deep/c.txt", c.viewTarget)
            assertEquals("unidrive:view:favorites:id-c", c.remoteId, "never the bare id of the real item")
            assertTrue(level1.getValue("/Favorites/Docs/sub").let { it.isFolder && it.readOnly && it.viewTarget == "/Docs/sub" })
            assertEquals(emptyList(), env.entries("/Favorites/Docs/nothing"), "a name the real folder lacks lists nothing")
        }

    @Test
    fun `a prefix that is only the start of a name matches nothing`() =
        runTest {
            val env = envWithAlias()

            assertEquals(emptyList(), env.entries("/Favorites/Doc"))
            assertEquals(emptyList(), env.entries("/Favorites/Doc/sub"))
            assertEquals("unknown_path", tokenOf(env.hydration.openForRead("c", "h", "/Favorites/Doc/a.txt")))
            assertEquals(emptyList(), env.entries("/Favorites/Docs2"), "a real folder next to the alias is no reference")
        }

    @Test
    fun `open_read, hydrate, dehydrate and last_synced below an alias act on the real item with one download`() =
        runTest {
            val env = envWithAlias()
            env.syncEngine.seedRemoteContent("/Docs/sub/deep/c.txt", "hello")

            val viaAlias = assertIs<OpenResult.Ok>(env.hydration.openForRead("c1", "h1", "/Favorites/Docs/sub/deep/c.txt"))
            val viaReal = assertIs<OpenResult.Ok>(env.hydration.openForRead("c1", "h2", "/Docs/sub/deep/c.txt"))

            val real = env.syncEngine.resolveCachePath("/Docs/sub/deep/c.txt")
            assertEquals(real, viaAlias.cachePath)
            assertEquals(real, viaReal.cachePath)
            assertEquals("hello", Files.readString(real))
            assertEquals(1, env.syncEngine.downloadCount())
            assertTrue(Files.notExists(env.syncEngine.resolveCachePath("/Favorites/Docs/sub/deep/c.txt")), "no copy at the alias path")
            assertEquals(
                env.hydration.lastSynced("/Docs/sub/deep/c.txt"),
                env.hydration.lastSynced("/Favorites/Docs/sub/deep/c.txt"),
            )
            env.syncEngine.seedRemoteContent("/Docs/sub/b.txt", "beeee")
            assertEquals(HydrateResult.Ok, env.hydration.hydrate("/Favorites/Docs/sub/b.txt"))
            assertTrue(env.stateDb.isHydrated("/Docs/sub/b.txt"))
            assertEquals(DehydrateResult.Ok, env.hydration.dehydrate("/Favorites/Docs/sub/b.txt"))
            assertFalse(env.stateDb.isHydrated("/Docs/sub/b.txt"))
        }

    @Test
    fun `the alias follows a rename of the real folder`() =
        runTest {
            val env = envWithAlias()
            env.entries("/Favorites")

            env.stateDb.insertFolderEntry("/Archive")
            env.stateDb.insertFolderEntry("/Archive/Papers", remoteId = "id-docs")
            env.stateDb.insertUnhydratedEntry("/Archive/Papers/n.txt", 5, remoteId = "id-n")

            assertEquals(listOf("/Favorites/Papers/n.txt"), env.entries("/Favorites/Papers").map { it.path })
            assertEquals("/Archive/Papers", env.entries("/Favorites").single { it.isFolder }.viewTarget)
            assertEquals(1, env.providerForTest.viewRequests.get(), "the provider was asked once")
        }

    @Test
    fun `every write verb on a file entry, an alias root and a path three levels below it answers read_only and changes nothing`() =
        runTest {
            val env = envWithAlias()
            env.entries("/Favorites")
            env.syncEngine.seedCacheContent("/Docs/sub/deep/c.txt", "edited by the user")
            val cache = env.syncEngine.resolveCachePath("/Docs/sub/deep/c.txt")
            val before = env.entries("/Docs") + env.entries("/Docs/sub") + env.entries("/Docs/sub/deep") + env.entries("/")

            val targets = listOf("/Favorites/loose.txt", "/Favorites/Docs", "/Favorites/Docs/sub/deep/c.txt", "/Favorites/Docs/sub/deep/new.txt")
            for (path in targets) {
                assertEquals("read_only", tokenOf(env.hydration.mkdir("$path/sub")), "mkdir below $path")
                assertEquals("read_only", tokenOf(env.hydration.create("c1", "h1", "$path/new.txt")), "create below $path")
                assertEquals("read_only", tokenOf(env.hydration.unlink(path)), "unlink $path")
                assertEquals("read_only", tokenOf(env.hydration.rmdir(path)), "rmdir $path")
                assertEquals("read_only", tokenOf(env.hydration.rename(path, "/Elsewhere")), "rename out of $path")
                assertEquals("read_only", tokenOf(env.hydration.rename("/Docs/a.txt", path)), "rename into $path")
                assertEquals("read_only", tokenOf(env.hydration.rename(path, "$path-moved")), "rename $path within the view")
                assertEquals("read_only", tokenOf(env.hydration.openWriteBegin("c1", path, "h2")), "open_write_begin $path")
                assertEquals("read_only", tokenOf(env.hydration.openForWrite("c1", "h3", path, cache)), "open_write $path")
            }

            assertEquals(before, env.entries("/Docs") + env.entries("/Docs/sub") + env.entries("/Docs/sub/deep") + env.entries("/"), "no row changed")
            assertEquals(5L, env.stateDb.remoteSizeOf("/Docs/sub/deep/c.txt"), "the real file is still there")
            assertEquals("edited by the user", Files.readString(cache), "a refusal is not a delete")
            assertEquals(emptyList(), env.syncEngine.deletedPaths())
            assertEquals(emptyList(), env.syncEngine.movedPairs())
            assertEquals(emptyList(), env.syncEngine.createdFolders())
            assertEquals(0, env.syncEngine.uploadAttempts())
            assertNull(env.stateDb.remoteSizeOf("/Favorites/Docs/sub/deep/new.txt"))
            assertFalse(Files.exists(env.syncEngine.resolveCachePath("/Favorites/Docs/sub/deep/new.txt")))
        }
}
