package org.krost.unidrive.hydration

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.FolderState
import org.krost.unidrive.ProviderException
import org.krost.unidrive.sync.model.EntryStatus
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * archive#580 part 2: an upload refused with `parent_gone` (the provider's walk proved its folder gone in the cloud) makes the
 * mount ask the provider for that tracked folder by its id. Only an answer that the folder is trashed or not found
 * tombstones its subtree; the refused file's row and every row with unsent bytes stay. A lookup that fails, or an answer
 * that the folder is live or cannot be told, changes nothing.
 */
class HydrationParentGoneTombstoneTest {
    // archive#580 part 2: the tracked folder of a parent_gone refusal and what lies below it, plus a row outside it. Returns the
    // cache copy of the refused upload.
    private fun HydrationTestEnv.seedTrashedFolder(): Path {
        stateDb.insertFolderEntry("/trashed")
        stateDb.insertFolderEntry("/trashed/sub")
        stateDb.insertHydratedEntry("/trashed/clean.txt", localSize = 5)
        seedSyncedCacheContent("/trashed/clean.txt", "clean")
        stateDb.insertUnhydratedEntry("/trashed/sub/deep.txt", remoteSize = 3)
        stateDb.insertHydratedEntry("/trashed/edited.txt", localSize = 6)
        seedSyncedCacheContent("/trashed/edited.txt", "edited")
        stateDb.noteEditBase("/trashed/edited.txt", "hash-/trashed/edited.txt")
        stateDb.insertUnhydratedEntry("/other.txt", remoteSize = 1)
        stateDb.insertCreatedRow("/trashed/new.txt")
        val cache = syncEngine.resolveCachePath("/trashed/new.txt")
        Files.createDirectories(cache.parent)
        Files.writeString(cache, "the user's bytes")
        syncEngine.refuseUploadsParentGone(1, folder = "/trashed")
        return cache
    }

    // archive#580 part 2: after a parent_gone refusal the engine asks the provider for the tracked folder by its id. The answer
    // "trashed" tombstones the subtree (the folder, the folder below it, the clean rows) and the view learns the paths;
    // the refused file's row and a row with an unsent edit are kept, with their bytes, and nothing outside it changes.
    @Test
    fun `a parent_gone refusal whose folder the id lookup confirms trashed tombstones its subtree and keeps unsent rows`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        val cache = env.seedTrashedFolder()
        env.syncEngine.answerFolderState("id-/trashed", FolderState.TRASHED)

        env.hydration.openForWrite("conn1", "h1", "/trashed/new.txt", cache)
        advanceUntilIdle()

        val deleted = EntryStatus.DELETED
        val exists = EntryStatus.EXISTS
        assertEquals(1, env.syncEngine.folderStateLookups(), "the folder is asked for by its id once")
        for (path in listOf("/trashed", "/trashed/sub", "/trashed/clean.txt", "/trashed/sub/deep.txt")) {
            assertEquals(deleted, env.stateDb.statusOf(path), "$path is tombstoned")
        }
        assertEquals(exists, env.stateDb.statusOf("/trashed/new.txt"), "the refused upload's row stays")
        assertEquals(exists, env.stateDb.statusOf("/trashed/edited.txt"), "a row with an unsent edit stays")
        assertEquals(exists, env.stateDb.statusOf("/other.txt"), "a row outside the folder is untouched")
        assertEquals("the user's bytes", Files.readString(cache), "the refused bytes stay in the cache")
        assertEquals("edited", Files.readString(env.syncEngine.resolveCachePath("/trashed/edited.txt")), "the unsent edit stays")
        assertFalse(Files.exists(env.syncEngine.resolveCachePath("/trashed/clean.txt")), "a clean cache copy goes with its row")
        assertTrue(env.viewInvalidations.any { "/trashed" in it }, "the view learns the tombstoned folder: ${env.viewInvalidations}")
    }

    // archive#580 part 2: a lookup that fails (a 503-class gateway, archive#387; an I/O error) proves nothing: no row changes.
    @Test
    fun `a parent_gone refusal whose id lookup fails changes no row`() = runTest {
        for (failure in listOf(java.io.IOException("connection reset"), ProviderException("HTTP 503 Service Unavailable"))) {
            val env = HydrationTestEnv(recoveryUploadScope = this)
            val cache = env.seedTrashedFolder()
            env.syncEngine.answerFolderState("id-/trashed", FolderState.TRASHED)
            env.syncEngine.failFolderStateLookups(failure)

            env.hydration.openForWrite("conn1", "h1", "/trashed/new.txt", cache)
            advanceUntilIdle()

            assertEquals(1, env.syncEngine.folderStateLookups(), "the folder is asked for by its id ($failure)")
            for (path in listOf("/trashed", "/trashed/sub", "/trashed/clean.txt", "/trashed/sub/deep.txt", "/trashed/new.txt", "/trashed/edited.txt")) {
                assertEquals(EntryStatus.EXISTS, env.stateDb.statusOf(path), "$path stays after $failure")
            }
            assertTrue(Files.exists(env.syncEngine.resolveCachePath("/trashed/clean.txt")), "no cache copy goes ($failure)")
            assertTrue(env.viewInvalidations.isEmpty(), "the view is not invalidated ($failure)")
        }
    }

    // archive#580 part 2: the provider answers that the folder is live (a trash undone in the lag window, archive#132) or cannot tell:
    // the subtree's rows stay.
    @Test
    fun `a parent_gone refusal whose folder the id lookup finds live or cannot tell changes no row`() = runTest {
        for (state in listOf(FolderState.LIVE, FolderState.UNKNOWN)) {
            val env = HydrationTestEnv(recoveryUploadScope = this)
            val cache = env.seedTrashedFolder()
            env.syncEngine.answerFolderState("id-/trashed", state)

            env.hydration.openForWrite("conn1", "h1", "/trashed/new.txt", cache)
            advanceUntilIdle()

            assertEquals(1, env.syncEngine.folderStateLookups())
            for (path in listOf("/trashed", "/trashed/sub", "/trashed/clean.txt", "/trashed/sub/deep.txt")) {
                assertEquals(EntryStatus.EXISTS, env.stateDb.statusOf(path), "$path stays when $state")
            }
        }
    }

    // archive#580 part 2: not found from a call that succeeded is as good as trashed.
    @Test
    fun `a parent_gone refusal whose folder the id lookup does not find tombstones its subtree`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        val cache = env.seedTrashedFolder()
        env.syncEngine.answerFolderState("id-/trashed", FolderState.NOT_FOUND)

        env.hydration.openForWrite("conn1", "h1", "/trashed/new.txt", cache)
        advanceUntilIdle()

        assertEquals(EntryStatus.DELETED, env.stateDb.statusOf("/trashed"))
        assertEquals(EntryStatus.DELETED, env.stateDb.statusOf("/trashed/clean.txt"))
        assertEquals(EntryStatus.EXISTS, env.stateDb.statusOf("/trashed/new.txt"))
    }
}
