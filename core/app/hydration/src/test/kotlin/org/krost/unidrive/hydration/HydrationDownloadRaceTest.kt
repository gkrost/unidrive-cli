package org.krost.unidrive.hydration

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * M2 of #1040: a cold read holds the path's hydrate lock for its whole download, but `unlink` and `rename` do not
 * take it. The read then wrote its row back from the snapshot it started with (an upsert keyed by remote id), which
 * brought a deleted file back and put a renamed one back under its old path.
 */
class HydrationDownloadRaceTest {
    @Test
    fun `a file deleted while it is being read stays deleted`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.stateDb.insertUnhydratedEntry("/q/f.txt", remoteSize = 5)
        env.syncEngine.seedRemoteContent("/q/f.txt", "hello")
        val stall = MinimalFakeProvider.DownloadStall(partialBytes = 2)
        env.providerForTest.downloadStall = stall
        val read = async { env.hydration.openForRead("conn1", "h1", "/q/f.txt") }
        stall.reached.await() // the download is under way, holding the path's hydrate lock

        assertEquals(UnlinkResult.Ok, env.hydration.unlink("/q/f.txt"))
        stall.gate.complete(Unit)
        val result = read.await()

        assertIs<OpenResult.Failed>(result, "the file is gone: the read does not succeed")
        assertEquals("not_found", result.error.message)
        assertNull(env.stateDb.remoteSizeOf("/q/f.txt"), "the finished download did not bring the deleted file back")
        assertFalse(Files.exists(env.syncEngine.resolveCachePath("/q/f.txt")), "and left no bytes of it")
    }

    @Test
    fun `a file renamed while it is being read stays under its new name`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.stateDb.insertFolderEntry("/q")
        env.stateDb.insertUnhydratedEntry("/q/f.txt", remoteSize = 5)
        env.syncEngine.seedRemoteContent("/q/f.txt", "hello")
        val stall = MinimalFakeProvider.DownloadStall(partialBytes = 2)
        env.providerForTest.downloadStall = stall
        val read = async { env.hydration.openForRead("conn1", "h1", "/q/f.txt") }
        stall.reached.await()

        assertEquals(RenameResult.Ok, env.hydration.rename("/q/f.txt", "/q/g.txt"))
        stall.gate.complete(Unit)
        read.await()

        assertNull(env.stateDb.remoteSizeOf("/q/f.txt"), "the row was not put back under the old path")
        assertEquals(5L, env.stateDb.remoteSizeOf("/q/g.txt"), "it stays under the new one")
        assertFalse(Files.exists(env.syncEngine.resolveCachePath("/q/f.txt")), "no orphan cache copy under the old path")
    }
}
