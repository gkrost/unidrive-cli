package org.krost.unidrive.hydration

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.krost.unidrive.FolderState
import org.krost.unidrive.ProviderException
import org.krost.unidrive.sync.model.EntryStatus
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#134: an upload refused with `parent_gone` for a file whose folder exists locally gets one look at the
 * folder before the refusal stands. The folder the provider's walk missed is asked for by its id: trashed or not found, it
 * is created again (and the folders below it that state.db tracks) and the bytes go once more; live, it is looked up by its
 * path once more. Only when that does not bring the folder back is the refusal recorded, as before.
 */
class HydrationParentGoneRecoveryTest {
    private fun HydrationTestEnv.seedNewFileIn(
        folders: List<String>,
        file: String,
    ): Path {
        for (folder in folders) stateDb.insertFolderEntry(folder)
        stateDb.insertCreatedRow(file)
        val cache = syncEngine.resolveCachePath(file)
        Files.createDirectories(cache.parent)
        Files.writeString(cache, "the user's bytes")
        return cache
    }

    // The folder the walk missed is trashed in the cloud and exists here: it is created again, the row takes the new id, the
    // bytes go once more and land. Nothing is refused, nothing remembered, and the row is not tombstoned.
    @Test
    fun `a folder the id lookup finds trashed is created again and the upload is tried once more`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, maxUploadAttempts = 3, uploadRetryDelaysMs = listOf(1L, 1L))
        val cache = env.seedNewFileIn(listOf("/docs"), "/docs/new.txt")
        env.syncEngine.refuseUploadsParentGone(1, folder = "/docs")
        env.syncEngine.answerFolderState("id-/docs", FolderState.TRASHED)
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { events.add(it) } }
        yield()

        env.hydration.openForWrite("conn1", "h1", "/docs/new.txt", cache)
        advanceUntilIdle()

        assertEquals(listOf("/docs"), env.syncEngine.createdFolders(), "the folder is created again")
        assertEquals(2, env.syncEngine.uploadAttempts(), "the refused attempt and the one after the folder is back")
        assertEquals("folder-/docs", env.stateDb.remoteIdOf("/docs"), "the folder's row takes the id of the new folder")
        assertEquals("the user's bytes", env.syncEngine.remoteContentSeen("/docs/new.txt"))
        val completed = events.filterIsInstance<HydrationEvent.Completed>().single { it.handleId == "h1" }
        assertTrue(completed.ok, "the upload completed: ${completed.error}")
        assertTrue(events.filterIsInstance<HydrationEvent.Failed>().isEmpty(), "no failure was announced: $events")
        assertNull(env.stateDb.uploadRefusal("/docs/new.txt"), "nothing is remembered as refused")
        assertEquals(EntryStatus.EXISTS, env.stateDb.statusOf("/docs"))
        collector.cancel()
    }

    // The walk missed a folder above the file's own: it and the tracked folder below it are created, top first.
    @Test
    fun `a folder chain is created again from the folder the walk missed down to the file's folder`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        val cache = env.seedNewFileIn(listOf("/a", "/a/b"), "/a/b/new.txt")
        env.syncEngine.refuseUploadsParentGone(1, folder = "/a")
        env.syncEngine.answerFolderState("id-/a", FolderState.NOT_FOUND)

        env.hydration.openForWrite("conn1", "h1", "/a/b/new.txt", cache)
        advanceUntilIdle()

        assertEquals(listOf("/a", "/a/b"), env.syncEngine.createdFolders())
        assertEquals("folder-/a/b", env.stateDb.remoteIdOf("/a/b"))
        assertEquals("the user's bytes", env.syncEngine.remoteContentSeen("/a/b/new.txt"))
    }

    // The folder is only looked at once per submission: a refusal that comes again after the folder was created stands.
    @Test
    fun `a refusal that comes again after the folder was created again stands`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, maxUploadAttempts = 3, uploadRetryDelaysMs = listOf(1L, 1L))
        val cache = env.seedNewFileIn(listOf("/docs"), "/docs/new.txt")
        env.syncEngine.refuseUploadsParentGone(999, folder = "/docs")
        env.syncEngine.answerFolderState("id-/docs", FolderState.TRASHED)
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { events.add(it) } }
        yield()

        env.hydration.openForWrite("conn1", "h1", "/docs/new.txt", cache)
        advanceUntilIdle()

        assertEquals(2, env.syncEngine.uploadAttempts(), "one attempt, one more after the folder, then the refusal")
        assertEquals(listOf("/docs"), env.syncEngine.createdFolders(), "the folder is created once, not at every refusal")
        assertEquals("parent_gone: /docs", events.filterIsInstance<HydrationEvent.Failed>().single().error.message)
        assertTrue(env.stateDb.uploadRefusal("/docs/new.txt")?.endsWith("|parent_gone: /docs") == true)
        assertEquals("the user's bytes", Files.readString(cache))
        collector.cancel()
    }

    // The provider cannot create the folder again: the refusal is recorded as before, and the lookup by id still decides the
    // tombstoning (archive#580 part 2).
    @Test
    fun `a folder that cannot be created again leaves the refusal as it was`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        val cache = env.seedNewFileIn(listOf("/docs"), "/docs/new.txt")
        env.syncEngine.refuseUploadsParentGone(1, folder = "/docs")
        env.syncEngine.answerFolderState("id-/docs", FolderState.TRASHED)
        env.syncEngine.failCreateFolder(ProviderException("HTTP 503 Service Unavailable"))
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { events.add(it) } }
        yield()

        env.hydration.openForWrite("conn1", "h1", "/docs/new.txt", cache)
        advanceUntilIdle()

        assertEquals(1, env.syncEngine.uploadAttempts(), "the bytes were not sent again")
        assertEquals("parent_gone: /docs", events.filterIsInstance<HydrationEvent.Failed>().single().error.message)
        assertTrue(env.stateDb.uploadRefusal("/docs/new.txt")?.endsWith("|parent_gone: /docs") == true)
        assertEquals(EntryStatus.DELETED, env.stateDb.statusOf("/docs"), "the tombstone of the confirmed trashed folder goes on as before")
        assertEquals(EntryStatus.EXISTS, env.stateDb.statusOf("/docs/new.txt"), "the refused file's row stays")
        collector.cancel()
    }

    // A folder the id lookup finds live although the walk missed it (a listing that lags behind): it is looked up by its path
    // again, and when that finds the same folder the bytes go once more, without a folder being created.
    @Test
    fun `a folder the id lookup finds live and the path lookup finds is tried again without creating it`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        val cache = env.seedNewFileIn(listOf("/docs"), "/docs/new.txt")
        env.syncEngine.refuseUploadsParentGone(1, folder = "/docs")
        env.syncEngine.answerFolderState("id-/docs", FolderState.LIVE)
        env.syncEngine.liveFolder("/docs", "id-/docs")

        env.hydration.openForWrite("conn1", "h1", "/docs/new.txt", cache)
        advanceUntilIdle()

        assertTrue(env.syncEngine.createdFolders().isEmpty(), "a live folder is not created")
        assertEquals(2, env.syncEngine.uploadAttempts())
        assertEquals("the user's bytes", env.syncEngine.remoteContentSeen("/docs/new.txt"))
        assertEquals("id-/docs", env.stateDb.remoteIdOf("/docs"), "the path lookup found the tracked folder")
        assertNull(env.stateDb.uploadRefusal("/docs/new.txt"))
    }

    // Live by its id and still not found by its path, or the provider cannot tell, or the lookup fails: nothing is created and
    // the refusal stands.
    @Test
    fun `a folder that is live by its id but not found by its path, unknown, or whose lookup fails is not created`() = runTest {
        val cases: List<Pair<String, (HydrationTestEnv) -> Unit>> =
            listOf(
                "live, no path" to { env -> env.syncEngine.answerFolderState("id-/docs", FolderState.LIVE) },
                "unknown" to { env -> env.syncEngine.answerFolderState("id-/docs", FolderState.UNKNOWN) },
                "failing" to { env -> env.syncEngine.failFolderStateLookups(java.io.IOException("connection reset")) },
            )
        for ((name, arrange) in cases) {
            val env = HydrationTestEnv(recoveryUploadScope = this)
            val cache = env.seedNewFileIn(listOf("/docs"), "/docs/new.txt")
            env.syncEngine.refuseUploadsParentGone(1, folder = "/docs")
            arrange(env)

            env.hydration.openForWrite("conn1", "h1", "/docs/new.txt", cache)
            advanceUntilIdle()

            assertTrue(env.syncEngine.createdFolders().isEmpty(), "$name: no folder is created")
            assertEquals(1, env.syncEngine.uploadAttempts(), "$name: the bytes are not sent again")
            assertNotNull(env.stateDb.uploadRefusal("/docs/new.txt"), "$name: the refusal is recorded")
        }
    }

    // No folder row for the folder (it does not exist here): nothing local asks for it, and the refusal is as before.
    @Test
    fun `a folder with no row in state db is not created again`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        val cache = env.seedNewFileIn(emptyList(), "/docs/new.txt")
        env.syncEngine.refuseUploadsParentGone(1, folder = "/docs")
        env.syncEngine.answerFolderState("id-/docs", FolderState.TRASHED)

        env.hydration.openForWrite("conn1", "h1", "/docs/new.txt", cache)
        advanceUntilIdle()

        assertTrue(env.syncEngine.createdFolders().isEmpty())
        assertEquals(1, env.syncEngine.uploadAttempts())
        assertNotNull(env.stateDb.uploadRefusal("/docs/new.txt"))
        assertFalse(env.syncEngine.folderStateLookups() > 1, "at most the tombstone's own lookup")
    }
    @Test
    fun `a replacement during folder creation is not evicted or followed`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        val cache = env.seedNewFileIn(listOf("/a", "/a/b"), "/a/b/new.txt")
        env.syncEngine.refuseUploadsParentGone(1, folder = "/a")
        env.syncEngine.answerFolderState("id-/a", FolderState.NOT_FOUND)
        env.syncEngine.onCreateFolder { if (it == "/a") env.stateDb.insertFolderEntry("/a", remoteId = "replacement") }

        env.hydration.openForWrite("conn1", "h1", "/a/b/new.txt", cache)
        advanceUntilIdle()

        assertEquals("replacement", env.stateDb.remoteIdOf("/a"))
        assertEquals(listOf("/a"), env.syncEngine.createdFolders())
        assertEquals(1, env.syncEngine.uploadAttempts())
    }

    @Test
    fun `a folder deleted while its live path is looked up is not restored`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        val cache = env.seedNewFileIn(listOf("/docs"), "/docs/new.txt")
        env.syncEngine.refuseUploadsParentGone(1, folder = "/docs")
        env.syncEngine.answerFolderState("id-/docs", FolderState.LIVE)
        env.syncEngine.liveFolder("/docs", "id-/docs")
        env.syncEngine.onFolderMetadata { env.stateDb.deleteRow(it) }

        env.hydration.openForWrite("conn1", "h1", "/docs/new.txt", cache)
        advanceUntilIdle()

        assertNull(env.stateDb.remoteIdOf("/docs"))
        assertEquals(1, env.syncEngine.uploadAttempts())
    }

    @Test
    fun `a live path lookup cannot steal an id tracked at another path`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        val cache = env.seedNewFileIn(listOf("/docs", "/elsewhere"), "/docs/new.txt")
        env.stateDb.insertFolderEntry("/elsewhere", remoteId = "live-/docs")
        env.syncEngine.refuseUploadsParentGone(1, folder = "/docs")
        env.syncEngine.answerFolderState("id-/docs", FolderState.LIVE)
        env.syncEngine.liveFolder("/docs")

        env.hydration.openForWrite("conn1", "h1", "/docs/new.txt", cache)
        advanceUntilIdle()

        assertEquals("live-/docs", env.stateDb.remoteIdOf("/elsewhere"))
        assertEquals("id-/docs", env.stateDb.remoteIdOf("/docs"))
        assertEquals(1, env.syncEngine.uploadAttempts())
    }

    @Test
    fun `a live folder at the same path with a different id is not adopted`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        val cache = env.seedNewFileIn(listOf("/docs"), "/docs/new.txt")
        env.syncEngine.refuseUploadsParentGone(1, folder = "/docs")
        env.syncEngine.answerFolderState("id-/docs", FolderState.LIVE)
        env.syncEngine.liveFolder("/docs")

        env.hydration.openForWrite("conn1", "h1", "/docs/new.txt", cache)
        advanceUntilIdle()

        assertEquals("id-/docs", env.stateDb.remoteIdOf("/docs"))
        assertEquals(1, env.syncEngine.uploadAttempts())
        assertNotNull(env.stateDb.uploadRefusal("/docs/new.txt"))
    }

    @Test
    fun `a created folder id already tracked elsewhere is not stolen`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        val cache = env.seedNewFileIn(listOf("/docs", "/elsewhere"), "/docs/new.txt")
        env.stateDb.insertFolderEntry("/elsewhere", remoteId = "folder-/docs")
        env.syncEngine.refuseUploadsParentGone(1, folder = "/docs")
        env.syncEngine.answerFolderState("id-/docs", FolderState.NOT_FOUND)

        env.hydration.openForWrite("conn1", "h1", "/docs/new.txt", cache)
        advanceUntilIdle()

        assertEquals("folder-/docs", env.stateDb.remoteIdOf("/elsewhere"))
        assertEquals(1, env.syncEngine.uploadAttempts())
    }

}
