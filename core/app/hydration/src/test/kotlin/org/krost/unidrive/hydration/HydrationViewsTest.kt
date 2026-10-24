package org.krost.unidrive.hydration

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.ViewKind
import org.krost.unidrive.ViewRef
import org.krost.unidrive.engine.ViewSpec
import org.krost.unidrive.engine.ViewState
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The read-only views as the mount serves them (the framework of the Recents folder): the top level lists a root, the
 * root lists the provider's references joined to rows state.db holds, a read through an entry is a read of the real item
 * (one cache copy, one download), and every verb that would write answers `read_only` and changes nothing. A fake
 * provider scripts the references; no view source of a real provider is involved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HydrationViewsTest {
    private val recents = ViewKind.RECENTS

    private fun ref(id: String) = ViewRef(id, false)

    // Three files in two folders, two of them called todo.txt, and one reference without a row.
    private fun TestScope.envWithRecents(
        syncPaths: List<String> = emptyList(),
        specs: List<ViewSpec> = listOf(ViewSpec(recents, limit = 10)),
    ): HydrationTestEnv {
        val env = HydrationTestEnv(recoveryUploadScope = this, syncPaths = syncPaths, viewSpecs = specs)
        env.stateDb.insertFolderEntry("/Notes")
        env.stateDb.insertFolderEntry("/Work")
        env.stateDb.insertFolderEntry("/Work/Reports")
        env.stateDb.insertUnhydratedEntry("/Notes/todo.txt", 5, remoteId = "22222222-todo")
        env.stateDb.insertUnhydratedEntry("/Work/todo.txt", 5, remoteId = "33333333-todo")
        env.stateDb.insertUnhydratedEntry("/Work/Reports/q3.pdf", 5, remoteId = "11111111-q3")
        env.providerForTest.viewAnswers[recents] =
            CapabilityResult.Success(listOf(ref("22222222-todo"), ref("33333333-todo"), ref("11111111-q3"), ref("44444444-gone")))
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

    // ---- the listing ---------------------------------------------------------------------------------------------

    @Test
    fun `the top level lists the root of the view next to the real rows, and only the root is a view`() =
        runTest {
            val env = envWithRecents()

            val top = env.entries("/").associateBy { it.path }

            val root = top.getValue("/Recents")
            assertTrue(root.isFolder && root.readOnly && !root.trashRoot)
            assertEquals("recents", root.view)
            assertEquals("unidrive:view:recents", root.remoteId)
            assertNull(root.viewTarget)
            assertEquals(0L, root.size)
            assertEquals(setOf("/Notes", "/Work", "/Recents"), top.keys)
            assertTrue(top.getValue("/Work").let { !it.readOnly && it.view == null && it.viewTarget == null }, "a real folder is not a view")
            assertEquals(0, env.providerForTest.viewRequests.get(), "listing the top level asks the provider for nothing")
        }

    @Test
    fun `without a view the listing is exactly the rows`() =
        runTest {
            val env = HydrationTestEnv(recoveryUploadScope = this)
            env.stateDb.insertFolderEntry("/Recents")

            val top = env.entries("/")

            assertEquals(listOf("/Recents"), top.map { it.path })
            assertFalse(top.single().readOnly)
            assertNull(top.single().view)
            assertEquals(emptyList(), env.hydration.viewStatuses())
        }

    @Test
    fun `the view lists the references that have a row, in the provider's order, flagged and pointing at the real item`() =
        runTest {
            val env = envWithRecents()

            val listed = env.entries("/Recents")

            assertEquals(
                listOf("/Recents/todo.txt", "/Recents/todo (dup-33333333).txt", "/Recents/q3.pdf"),
                listed.map { it.path },
                "the reference without a row is not listed; the namesakes are told apart",
            )
            assertEquals(listOf("/Notes/todo.txt", "/Work/todo.txt", "/Work/Reports/q3.pdf"), listed.map { it.viewTarget })
            assertEquals(
                listOf("unidrive:view:recents:22222222-todo", "unidrive:view:recents:33333333-todo", "unidrive:view:recents:11111111-q3"),
                listed.map { it.remoteId },
                "never the bare id of the real item",
            )
            assertTrue(listed.all { it.readOnly && it.view == "recents" && !it.isFolder && it.size == 5L })
            assertEquals(listOf("hash-/Notes/todo.txt", "hash-/Work/todo.txt", "hash-/Work/Reports/q3.pdf"), listed.map { it.etag }, "from the real row")
            val status = env.hydration.viewStatuses().single()
            assertEquals(3, status.entries)
            assertEquals(1, status.skipped)
            assertEquals(ViewState.OK, status.state)
        }

    @Test
    fun `an entry follows the real row when it is renamed or downloaded, without asking the provider again`() =
        runTest {
            val env = envWithRecents()
            env.entries("/Recents")

            env.stateDb.deleteRow("/Work/Reports/q3.pdf")
            env.stateDb.insertUnhydratedEntry("/Work/Reports/q3-final.pdf", 5, remoteId = "11111111-q3")
            val listed = env.entries("/Recents")

            assertEquals("/Recents/q3-final.pdf", listed.last().path)
            assertEquals("/Work/Reports/q3-final.pdf", listed.last().viewTarget)
            assertEquals(1, env.providerForTest.viewRequests.get())
        }

    @Test
    fun `a path below an entry and a name the view does not list have no children`() =
        runTest {
            val env = envWithRecents()

            assertEquals(emptyList(), env.entries("/Recents/q3.pdf"))
            assertEquals(emptyList(), env.entries("/Recents/nothing/deeper"))
        }

    @Test
    fun `a provider without the view gives an empty root and an unavailable state`() =
        runTest {
            val env = envWithRecents()
            env.providerForTest.viewAnswers.clear()

            assertTrue(env.entries("/").any { it.path == "/Recents" && it.view == "recents" }, "the root is there whatever is in it")
            assertEquals(emptyList(), env.entries("/Recents"))

            assertEquals(ViewState.UNAVAILABLE, env.hydration.viewStatuses().single().state)
        }

    @Test
    fun `a failing refresh keeps serving the list before it and says stale`() =
        runTest {
            val env = envWithRecents()
            assertEquals(3, env.entries("/Recents").size)
            env.viewClockMs.set(61_000)
            env.providerForTest.viewFailure = IOException("connection reset")

            val during = env.entries("/Recents")
            runCurrent()

            assertEquals(3, during.size, "answered at once from the list before")
            assertEquals(3, env.entries("/Recents").size)
            val status = env.hydration.viewStatuses().single()
            assertEquals(ViewState.STALE, status.state)
            assertEquals(3, status.entries)
        }

    @Test
    fun `a refresh that changes the list is reported once, one that changes nothing is not`() =
        runTest {
            val env = envWithRecents()
            env.entries("/Recents")
            assertEquals(listOf(recents), env.viewChanges.toList(), "the first list")

            env.viewClockMs.set(61_000)
            env.entries("/Recents")
            runCurrent()
            assertEquals(1, env.viewChanges.size, "the same list again is no change")

            env.viewClockMs.set(130_000)
            env.providerForTest.viewAnswers[recents] = CapabilityResult.Success(listOf(ref("11111111-q3")))
            env.entries("/Recents")
            runCurrent()
            assertEquals(listOf(recents, recents), env.viewChanges.toList())
            assertEquals(listOf("/Recents/q3.pdf"), env.entries("/Recents").map { it.path })
        }

    @Test
    fun `a real top-level folder with the root's name keeps it and the view moves aside`() =
        runTest {
            val env = envWithRecents()
            env.stateDb.insertFolderEntry("/Recents")

            val top = env.entries("/").associateBy { it.path }

            assertFalse(top.getValue("/Recents").readOnly, "the live folder is not a view")
            assertNull(top.getValue("/Recents").view)
            assertEquals("recents", top.getValue("/Recents (2)").view)
            assertEquals(3, env.entries("/Recents (2)").size)
            assertIs<MkdirResult.Ok>(env.hydration.mkdir("/Recents/sub"), "and the real folder takes writes as ever")
            assertEquals("read_only", tokenOf(env.hydration.mkdir("/Recents (2)/sub")))
        }

    // ---- reads resolve to the real item --------------------------------------------------------------------------

    @Test
    fun `open_read through an entry gives the real item's cache file with one download, and every later open is a hit`() =
        runTest {
            val env = envWithRecents()
            env.syncEngine.seedRemoteContent("/Work/Reports/q3.pdf", "hello")

            val viaView = assertIs<OpenResult.Ok>(env.hydration.openForRead("c1", "h1", "/Recents/q3.pdf"))
            val viaRealPath = assertIs<OpenResult.Ok>(env.hydration.openForRead("c1", "h2", "/Work/Reports/q3.pdf"))
            val viaViewAgain = assertIs<OpenResult.Ok>(env.hydration.openForRead("c2", "h3", "/Recents/q3.pdf"))

            val real = env.syncEngine.resolveCachePath("/Work/Reports/q3.pdf")
            assertEquals(real, viaView.cachePath)
            assertEquals(real, viaRealPath.cachePath)
            assertEquals(real, viaViewAgain.cachePath)
            assertEquals("hello", Files.readString(real))
            assertEquals(1, env.syncEngine.downloadCount(), "exactly one provider download")
            assertTrue(Files.notExists(env.syncEngine.resolveCachePath("/Recents/q3.pdf")), "no copy at the view's path")
            assertEquals(emptyList(), env.syncEngine.createdFolders(), "and nothing was created in the cloud")
        }

    @Test
    fun `the events of a read through the view name the real path`() =
        runTest {
            val env = envWithRecents()
            env.syncEngine.seedRemoteContent("/Work/Reports/q3.pdf", "hello")
            val events = mutableListOf<HydrationEvent>()
            val collector = launch { env.hydration.events.collect { events.add(it) } }
            yield()

            env.hydration.openForRead("c1", "h1", "/Recents/q3.pdf")
            advanceUntilIdle()

            assertEquals(
                listOf(
                    HydrationEvent.Hydrating("/Work/Reports/q3.pdf"),
                    HydrationEvent.Hydrated("/Work/Reports/q3.pdf", 5, fetched = true), // the first read fetches from the provider
                ),
                events.filter { it is HydrationEvent.Hydrating || it is HydrationEvent.Hydrated },
            )
            assertEquals("/Work/Reports/q3.pdf", events.filterIsInstance<HydrationEvent.Completed>().single().path)
            assertEquals("h1", events.filterIsInstance<HydrationEvent.Completed>().single().handleId)
            collector.cancel()
        }

    @Test
    fun `hydrate, dehydrate and last_synced through an entry act on the real item`() =
        runTest {
            val env = envWithRecents()
            env.syncEngine.seedRemoteContent("/Notes/todo.txt", "hello")

            assertEquals(HydrateResult.Ok, env.hydration.hydrate("/Recents/todo.txt"))
            assertTrue(env.stateDb.isHydrated("/Notes/todo.txt"))
            assertEquals(1, env.syncEngine.downloadCount())
            assertEquals(env.hydration.lastSynced("/Notes/todo.txt"), env.hydration.lastSynced("/Recents/todo.txt"))
            assertEquals(DehydrateResult.Ok, env.hydration.dehydrate("/Recents/todo.txt"))
            assertFalse(env.stateDb.isHydrated("/Notes/todo.txt"))
            assertFalse(Files.exists(env.syncEngine.resolveCachePath("/Notes/todo.txt")))
        }

    @Test
    fun `reads of the root, of a name the view does not list and of a path below an entry answer unknown_path`() =
        runTest {
            val env = envWithRecents()

            for (path in listOf("/Recents", "/Recents/missing.txt", "/Recents/q3.pdf/deeper")) {
                assertEquals("unknown_path", tokenOf(env.hydration.openForRead("c", "h", path)), path)
                assertEquals("unknown_path", tokenOf(env.hydration.hydrate(path)), path)
                assertEquals("unknown_path", tokenOf(env.hydration.dehydrate(path)), path)
                assertEquals(LastSyncedResult.Unknown("unknown_path"), env.hydration.lastSynced(path), path)
            }
            assertEquals(0, env.syncEngine.downloadCount())
        }

    @Test
    fun `a path that is not in a view is read as it always was`() =
        runTest {
            val env = envWithRecents()
            env.syncEngine.seedRemoteContent("/Notes/todo.txt", "hello")

            val opened = assertIs<OpenResult.Ok>(env.hydration.openForRead("c", "h", "/Notes/todo.txt"))

            assertEquals("hello", Files.readString(opened.cachePath))
            assertEquals("unknown_path", tokenOf(env.hydration.openForRead("c", "h2", "/Nowhere/x.txt")))
        }

    // ---- the verbs that write ------------------------------------------------------------------------------------

    @Test
    fun `every write verb on a view path answers read_only and changes nothing`() =
        runTest {
            val env = envWithRecents()
            env.entries("/Recents")
            env.syncEngine.seedCacheContent("/Notes/todo.txt", "edited by the user")
            val cache = env.syncEngine.resolveCachePath("/Notes/todo.txt")
            val before = env.entries("/Notes") + env.entries("/Work") + env.entries("/Work/Reports")

            assertEquals("read_only", tokenOf(env.hydration.mkdir("/Recents/sub")))
            assertEquals("read_only", tokenOf(env.hydration.mkdir("/Recents")))
            assertEquals("read_only", tokenOf(env.hydration.create("c1", "h1", "/Recents/new.txt")))
            assertEquals("read_only", tokenOf(env.hydration.unlink("/Recents/todo.txt")))
            assertEquals("read_only", tokenOf(env.hydration.unlink("/Recents/missing.txt")))
            assertEquals("read_only", tokenOf(env.hydration.rmdir("/Recents")))
            assertEquals("read_only", tokenOf(env.hydration.rename("/Recents/todo.txt", "/Elsewhere.txt")), "out of the view")
            assertEquals("read_only", tokenOf(env.hydration.rename("/Notes/todo.txt", "/Recents/todo.txt")), "into the view")
            assertEquals("read_only", tokenOf(env.hydration.rename("/Recents/todo.txt", "/Recents/other.txt")), "within the view")
            assertEquals("read_only", tokenOf(env.hydration.rename("/Recents/todo.txt", "/Work/todo.txt", replace = true)))
            assertEquals("read_only", tokenOf(env.hydration.openWriteBegin("c1", "/Recents/todo.txt", "h2")))
            assertEquals("read_only", tokenOf(env.hydration.openForWrite("c1", "h3", "/Recents/todo.txt", cache)))

            assertEquals(before, env.entries("/Notes") + env.entries("/Work") + env.entries("/Work/Reports"), "no row changed")
            assertEquals(5L, env.stateDb.remoteSizeOf("/Notes/todo.txt"), "deleting an entry did not delete the real file")
            assertEquals("edited by the user", Files.readString(cache), "a refusal is not a delete: the bytes stay where they are")
            assertEquals(emptyList(), env.syncEngine.deletedPaths(), "nothing was deleted in the cloud")
            assertEquals(emptyList(), env.syncEngine.movedPairs(), "nothing was moved in the cloud")
            assertEquals(emptyList(), env.syncEngine.createdFolders(), "nothing was created in the cloud")
            assertEquals(0, env.syncEngine.uploadAttempts(), "no upload was queued")
            assertNull(env.stateDb.remoteSizeOf("/Recents/new.txt"))
            assertFalse(Files.exists(env.syncEngine.resolveCachePath("/Recents/new.txt")))
        }

    @Test
    fun `in a profile with a sync scope a write to a view is read_only, not outside_scope, and out-of-scope rows are not listed`() =
        runTest {
            val env = envWithRecents(syncPaths = listOf("/Work"))

            val listed = env.entries("/Recents")

            assertEquals(listOf("/Recents/todo.txt", "/Recents/q3.pdf"), listed.map { it.path }, "the file in /Notes is outside the scope")
            assertEquals(listOf("/Work/todo.txt", "/Work/Reports/q3.pdf"), listed.map { it.viewTarget })
            assertEquals(2, env.hydration.viewStatuses().single().skipped)
            assertEquals("read_only", tokenOf(env.hydration.mkdir("/Recents/sub")))
            assertEquals("read_only", tokenOf(env.hydration.create("c1", "h1", "/Recents/new.txt")))
            assertEquals("read_only", tokenOf(env.hydration.rename("/Work/todo.txt", "/Recents/todo.txt")))
            assertEquals("read_only", tokenOf(env.hydration.openWriteBegin("c1", "/Recents/todo.txt", "h2")))
            assertEquals("outside_scope", tokenOf(env.hydration.mkdir("/Elsewhere/sub")), "and a real out-of-scope path still is")
        }

    @Test
    fun `a change in a cache copy at a view path is never uploaded`() =
        runTest {
            val env = envWithRecents()
            env.entries("/Recents")
            val mount = MountEngine.over(env.engine)
            val stray = env.syncEngine.resolveCachePath("/Recents/todo.txt")
            Files.createDirectories(stray.parent)
            Files.writeString(stray, "written behind the engine's back")

            val outcome = mount.uploadFromCache("/Recents/todo.txt", stray)

            assertFalse(outcome.sent, "nothing is sent")
            assertEquals(0, env.syncEngine.uploadAttempts())
            assertNull(env.stateDb.remoteSizeOf("/Recents/todo.txt"), "and no row is made for it: a view holds none")
        }
}
