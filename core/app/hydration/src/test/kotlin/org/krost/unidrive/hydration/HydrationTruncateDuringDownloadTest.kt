package org.krost.unidrive.hydration

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Candidate M1 of archive#1040, the "truncate during a running download" entry point: `open_write_begin` does not take
 * the path's hydrate lock, so a cold download already under way finished by moving its bytes over the truncated copy and
 * ended the edit's mark (`edit_base`).
 */
class HydrationTruncateDuringDownloadTest {
    @Test
    fun `a truncate while a cold download runs is not overwritten by the finished download`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.stateDb.insertUnhydratedEntry("/q/f.txt", remoteSize = 5, remoteHash = "v1")
        env.syncEngine.seedRemoteContent("/q/f.txt", "cloud")
        val stall = MinimalFakeProvider.DownloadStall(partialBytes = 2)
        env.providerForTest.downloadStall = stall
        val read = async { env.hydration.openForRead("conn1", "h1", "/q/f.txt") }
        stall.reached.await() // the download is under way, holding the path's hydrate lock

        val begin = env.hydration.openWriteBegin("conn1", "/q/f.txt", handleId = "h2")
        assertIs<OpenResult.Ok>(begin)
        Files.writeString(begin.cachePath, "my edit")
        stall.gate.complete(Unit)
        read.await()

        assertNotNull(env.stateDb.editBaseOf("/q/f.txt"), "the edit is still noted as unsynced")
        assertEquals("my edit", Files.readString(begin.cachePath), "the finished download did not replace the edit")
    }

    @Test
    fun `a download that nobody interrupted still lands and ends in a clean row`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.stateDb.insertUnhydratedEntry("/q/f.txt", remoteSize = 5, remoteHash = "v1")
        env.syncEngine.seedRemoteContent("/q/f.txt", "cloud")

        val read = env.hydration.openForRead("conn1", "h1", "/q/f.txt")

        assertIs<OpenResult.Ok>(read)
        assertEquals("cloud", Files.readString(read.cachePath))
        assertEquals(null, env.stateDb.editBaseOf("/q/f.txt"))
    }
}
