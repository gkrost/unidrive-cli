package org.krost.unidrive.hydration

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.ViewKind
import org.krost.unidrive.ViewRef
import org.krost.unidrive.engine.ViewSpec
import org.krost.unidrive.engine.ViewState
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Shared view (the items the account shares) as the mount serves it: the same framework as every view, over a real
 * state.db and a fake provider that scripts the references. The view is told apart by its words (`shared`, the root
 * `/Shared`, the keys `unidrive:view:shared[:<id>]`) and by nothing else: it lists the files that have a row, a read through
 * an entry is a read of the real item with one download, and every verb that would write answers `read_only` and changes
 * nothing. Sharing is nowhere written: nothing here creates, changes or removes a share.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HydrationSharedViewTest {
    private val sharedBy = ViewKind.SHARED_BY_ME

    private fun file(id: String) = ViewRef(id, false)

    // Two files in two folders, one reference without a row. The provider says the account shares all three.
    private fun TestScope.envWithShared(
        syncPaths: List<String> = emptyList(),
        specs: List<ViewSpec> = listOf(ViewSpec(sharedBy, limit = 5_000)),
    ): HydrationTestEnv {
        val env = HydrationTestEnv(recoveryUploadScope = this, syncPaths = syncPaths, viewSpecs = specs)
        env.stateDb.insertFolderEntry("/Work")
        env.stateDb.insertFolderEntry("/Work/Reports")
        env.stateDb.insertFolderEntry("/Photos")
        env.stateDb.insertUnhydratedEntry("/Work/Reports/q3.pdf", 5, remoteId = "11111111-q3")
        env.stateDb.insertUnhydratedEntry("/Photos/holiday.jpg", 5, remoteId = "22222222-holiday")
        env.providerForTest.viewAnswers[sharedBy] =
            CapabilityResult.Success(listOf(file("11111111-q3"), file("33333333-gone"), file("22222222-holiday")))
        return env
    }

    private fun tokenOf(result: Any?): String =
        when (result) {
            is OpenResult.Failed -> result.error.message
            is HydrateResult.Failed -> result.error.message
            is MkdirResult.Failed -> result.error.message
            is CreateResult.Failed -> result.error.message
            is RenameResult.Failed -> result.error.message
            is UnlinkResult.Failed -> result.error.message
            is RmdirResult.Failed -> result.error.message
            else -> error("did not fail: $result")
        }

    private suspend fun HydrationTestEnv.entries(prefix: String) = assertIs<ListResult.Ok>(hydration.list(prefix)).entries

    @Test
    fun `the top level lists the root of the Shared view, flagged, and asks the provider for nothing`() =
        runTest {
            val env = envWithShared()

            val top = env.entries("/").associateBy { it.path }

            val root = top.getValue("/Shared")
            assertTrue(root.isFolder && root.readOnly && !root.trashRoot)
            assertEquals("shared", root.view)
            assertEquals("unidrive:view:shared", root.remoteId)
            assertNull(root.viewTarget)
            assertEquals(setOf("/Work", "/Photos", "/Shared"), top.keys)
            assertEquals(0, env.providerForTest.viewRequests.get())
        }

    @Test
    fun `the view lists the shared files that have a row, each pointing at the real file, and counts the one that has none`() =
        runTest {
            val env = envWithShared()

            val listed = env.entries("/Shared")

            assertEquals(listOf("/Shared/q3.pdf", "/Shared/holiday.jpg"), listed.map { it.path })
            assertEquals(listOf("/Work/Reports/q3.pdf", "/Photos/holiday.jpg"), listed.map { it.viewTarget })
            assertEquals(
                listOf("unidrive:view:shared:11111111-q3", "unidrive:view:shared:22222222-holiday"),
                listed.map { it.remoteId },
                "never the bare id of the real item",
            )
            assertTrue(listed.all { it.readOnly && it.view == "shared" && !it.isFolder && it.size == 5L })
            val status = env.hydration.viewStatuses().single()
            assertEquals(sharedBy, status.kind)
            assertEquals("/Shared", status.rootPath)
            assertEquals(2, status.entries)
            assertEquals(1, status.skipped)
            assertEquals(ViewState.OK, status.state)
        }

    @Test
    fun `two shared files with one name are told apart, the same way whatever the order of the list`() =
        runTest {
            val env = envWithShared()
            env.stateDb.insertUnhydratedEntry("/Photos/q3.pdf", 5, remoteId = "44444444-q3")
            env.providerForTest.viewAnswers[sharedBy] = CapabilityResult.Success(listOf(file("44444444-q3"), file("11111111-q3")))

            val listed = env.entries("/Shared")

            assertEquals(listOf("/Shared/q3 (dup-44444444).pdf", "/Shared/q3.pdf"), listed.map { it.path }, "the smaller id keeps the plain name")
        }

    @Test
    fun `a file outside the sync scope is not listed and is counted`() =
        runTest {
            val env = envWithShared(syncPaths = listOf("/Work"))

            val listed = env.entries("/Shared")

            assertEquals(listOf("/Shared/q3.pdf"), listed.map { it.path })
            assertEquals(2, env.hydration.viewStatuses().single().skipped)
        }

    @Test
    fun `a provider without the view gives an empty root and an unavailable state`() =
        runTest {
            val env = envWithShared()
            env.providerForTest.viewAnswers.clear()

            assertTrue(env.entries("/").any { it.path == "/Shared" && it.view == "shared" })
            assertEquals(emptyList(), env.entries("/Shared"))
            assertEquals(ViewState.UNAVAILABLE, env.hydration.viewStatuses().single().state)
        }

    // ---- the name of the root --------------------------------------------------------------------------------------

    @Test
    fun `the root takes the name the profile asks for`() =
        runTest {
            val env = envWithShared(specs = listOf(ViewSpec(sharedBy, "Geteilt", 5_000)))

            val top = env.entries("/").associateBy { it.path }

            assertEquals("shared", top.getValue("/Geteilt").view)
            assertFalse("/Shared" in top)
            assertEquals(2, env.entries("/Geteilt").size)
            val status = env.hydration.viewStatuses().single()
            assertEquals("Geteilt", status.folderName)
            assertEquals("Geteilt", status.configuredName)
        }

    @Test
    fun `a real top-level folder named Shared keeps its name and the view becomes Shared (2)`() =
        runTest {
            val env = envWithShared()
            env.stateDb.insertFolderEntry("/Shared")
            env.stateDb.insertUnhydratedEntry("/Shared/mine.txt", 5, remoteId = "55555555-mine")

            val top = env.entries("/").associateBy { it.path }

            assertFalse(top.getValue("/Shared").readOnly, "the live folder is not a view")
            assertNull(top.getValue("/Shared").view)
            assertEquals("shared", top.getValue("/Shared (2)").view)
            assertEquals(listOf("/Shared (2)/q3.pdf", "/Shared (2)/holiday.jpg"), env.entries("/Shared (2)").map { it.path })
            assertEquals(listOf("/Shared/mine.txt"), env.entries("/Shared").map { it.path }, "and the real folder lists its own content")
            assertIs<MkdirResult.Ok>(env.hydration.mkdir("/Shared/sub"), "and takes writes as ever")
            assertEquals("read_only", tokenOf(env.hydration.mkdir("/Shared (2)/sub")))
            val status = env.hydration.viewStatuses().single()
            assertEquals("Shared (2)", status.folderName)
            assertEquals("Shared", status.configuredName)
        }

    // ---- no view, no request ---------------------------------------------------------------------------------------

    @Test
    fun `a mount that does not show the view lists only its rows and makes no request`() =
        runTest {
            val env = envWithShared(specs = emptyList())

            val top = env.entries("/")
            assertEquals(setOf("/Work", "/Photos"), top.map { it.path }.toSet())
            assertTrue(top.none { it.view != null })
            assertEquals(emptyList(), env.entries("/Shared"))
            assertEquals("unknown_path", tokenOf(env.hydration.openForRead("c", "h", "/Shared/q3.pdf")))
            assertEquals(emptyList(), env.hydration.viewStatuses())
            assertEquals(0, env.providerForTest.viewRequests.get())
        }

    @Test
    fun `the Shared view and another view are two views, each asked for once`() =
        runTest {
            val env =
                envWithShared(specs = listOf(ViewSpec(ViewKind.RECENTS, limit = 10), ViewSpec(sharedBy, limit = 5_000)))
            env.providerForTest.viewAnswers[ViewKind.RECENTS] = CapabilityResult.Success(listOf(file("22222222-holiday")))

            val top = env.entries("/").associateBy { it.path }

            assertEquals("recents", top.getValue("/Recents").view)
            assertEquals("shared", top.getValue("/Shared").view)
            assertEquals(listOf("/Recents/holiday.jpg"), env.entries("/Recents").map { it.path })
            assertEquals(listOf("/Shared/q3.pdf", "/Shared/holiday.jpg"), env.entries("/Shared").map { it.path })
            env.entries("/Shared")
            env.entries("/Recents")
            assertEquals(2, env.providerForTest.viewRequests.get(), "one request for each view")
            assertEquals(listOf(ViewKind.RECENTS, sharedBy), env.hydration.viewStatuses().map { it.kind })
        }

    // ---- reads resolve to the real file ----------------------------------------------------------------------------

    @Test
    fun `open_read through an entry gives the real file's cache path with one download, and every later open is a hit`() =
        runTest {
            val env = envWithShared()
            env.syncEngine.seedRemoteContent("/Work/Reports/q3.pdf", "hello")

            val viaView = assertIs<OpenResult.Ok>(env.hydration.openForRead("c1", "h1", "/Shared/q3.pdf"))
            val viaRealPath = assertIs<OpenResult.Ok>(env.hydration.openForRead("c1", "h2", "/Work/Reports/q3.pdf"))
            val viaViewAgain = assertIs<OpenResult.Ok>(env.hydration.openForRead("c2", "h3", "/Shared/q3.pdf"))

            val real = env.syncEngine.resolveCachePath("/Work/Reports/q3.pdf")
            assertEquals(real, viaView.cachePath)
            assertEquals(real, viaRealPath.cachePath)
            assertEquals(real, viaViewAgain.cachePath)
            assertEquals("hello", Files.readString(real))
            assertEquals(1, env.syncEngine.downloadCount(), "exactly one provider download")
            assertTrue(Files.notExists(env.syncEngine.resolveCachePath("/Shared/q3.pdf")), "no copy at the view's path")
        }

    @Test
    fun `the root, a name the view does not list and a reference without a row are not readable`() =
        runTest {
            val env = envWithShared()

            for (path in listOf("/Shared", "/Shared/missing.txt", "/Shared/gone.txt", "/Shared/q3.pdf/deeper")) {
                assertEquals("unknown_path", tokenOf(env.hydration.openForRead("c", "h", path)), path)
            }
            assertEquals(0, env.syncEngine.downloadCount())
        }

    // ---- the verbs that write --------------------------------------------------------------------------------------

    @Test
    fun `every write verb in or on the Shared view answers read_only, and no share, row, byte or cloud object changes`() =
        runTest {
            val env = envWithShared()
            env.entries("/Shared")
            env.syncEngine.seedCacheContent("/Work/Reports/q3.pdf", "edited by the user")
            val cache = env.syncEngine.resolveCachePath("/Work/Reports/q3.pdf")
            val before = env.entries("/Work") + env.entries("/Work/Reports") + env.entries("/Photos")

            assertEquals("read_only", tokenOf(env.hydration.mkdir("/Shared/sub")))
            assertEquals("read_only", tokenOf(env.hydration.mkdir("/Shared")))
            assertEquals("read_only", tokenOf(env.hydration.create("c1", "h1", "/Shared/new.txt")))
            assertEquals("read_only", tokenOf(env.hydration.unlink("/Shared/q3.pdf")), "removing an entry does not unshare or delete")
            assertEquals("read_only", tokenOf(env.hydration.unlink("/Shared/missing.txt")))
            assertEquals("read_only", tokenOf(env.hydration.rmdir("/Shared")))
            assertEquals("read_only", tokenOf(env.hydration.rename("/Shared/q3.pdf", "/Elsewhere.pdf")), "out of the view")
            assertEquals("read_only", tokenOf(env.hydration.rename("/Photos/holiday.jpg", "/Shared/holiday.jpg")), "into the view")
            assertEquals("read_only", tokenOf(env.hydration.rename("/Shared/q3.pdf", "/Shared/other.pdf")), "within the view")
            assertEquals("read_only", tokenOf(env.hydration.openWriteBegin("c1", "/Shared/q3.pdf", "h2")))
            assertEquals("read_only", tokenOf(env.hydration.openForWrite("c1", "h3", "/Shared/q3.pdf", cache)))

            assertEquals(before, env.entries("/Work") + env.entries("/Work/Reports") + env.entries("/Photos"), "no row changed")
            assertEquals(5L, env.stateDb.remoteSizeOf("/Work/Reports/q3.pdf"), "the real file is still there")
            assertEquals("edited by the user", Files.readString(cache), "a refusal is not a delete: the bytes stay where they are")
            assertEquals(emptyList(), env.syncEngine.deletedPaths(), "nothing was deleted in the cloud")
            assertEquals(emptyList(), env.syncEngine.movedPairs(), "nothing was moved in the cloud")
            assertEquals(emptyList(), env.syncEngine.createdFolders(), "nothing was created in the cloud")
            assertEquals(0, env.syncEngine.uploadAttempts(), "no upload was queued")
            assertNull(env.stateDb.remoteSizeOf("/Shared/new.txt"))
        }

    @Test
    fun `in a profile with a sync scope a write to the Shared view is read_only, not outside_scope`() =
        runTest {
            val env = envWithShared(syncPaths = listOf("/Work"))

            assertEquals("read_only", tokenOf(env.hydration.mkdir("/Shared/sub")))
            assertEquals("read_only", tokenOf(env.hydration.create("c1", "h1", "/Shared/new.txt")))
            assertEquals("read_only", tokenOf(env.hydration.rename("/Work/Reports/q3.pdf", "/Shared/q3.pdf")))
            assertEquals("outside_scope", tokenOf(env.hydration.mkdir("/Elsewhere/sub")), "and a real out-of-scope path still is")
        }
}
