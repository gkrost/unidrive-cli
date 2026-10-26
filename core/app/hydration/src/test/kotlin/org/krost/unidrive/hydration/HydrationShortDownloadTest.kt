package org.krost.unidrive.hydration

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#80, A: a download that ends with fewer bytes than the item has, without throwing (a chunked response
 * that closes early), used to be accepted: the short count became the row's `remote_size`, which switched off the size
 * check of the read, and the truncated file was served as complete. B: a read cancelled mid-download used to come back
 * as a failed result (EIO) instead of as a cancellation.
 */
class HydrationShortDownloadTest {
    private val path = "/q/f.bin"
    private val content = "0123456789"

    private fun HydrationTestEnv.seedTenByteFile(rowSize: Long = 10) {
        stateDb.insertUnhydratedEntry(path, remoteSize = rowSize)
        syncEngine.seedRemoteContent(path, content)
        providerForTest.metadataSizes[path] = content.length.toLong()
    }

    @Test
    fun `a short download is not accepted as the new remote size`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.seedTenByteFile()
        env.providerForTest.shortDownloadBytes = 4

        val result = env.hydration.openForRead("conn1", "h1", path)

        assertIs<OpenResult.Failed>(result, "4 of 10 bytes are not the file: $result")
        assertEquals(10L, env.stateDb.remoteSizeOf(path), "the row keeps the size the drive reported")
        assertFalse(env.stateDb.isHydrated(path), "and is not marked hydrated")
        assertFalse(Files.exists(env.syncEngine.resolveCachePath(path)), "no truncated cache copy is left to be served")
    }

    @Test
    fun `a short download that is retried completely is served`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.seedTenByteFile()
        env.providerForTest.shortDownloadBytes = 4
        assertIs<OpenResult.Failed>(env.hydration.openForRead("conn1", "h1", path))

        env.providerForTest.shortDownloadBytes = null
        val result = env.hydration.openForRead("conn1", "h2", path)

        assertIs<OpenResult.Ok>(result, "$result")
        assertEquals(content, Files.readString(result.cachePath))
        assertEquals(10L, env.stateDb.remoteSizeOf(path))
    }

    @Test
    fun `the hydrate verb refuses a short download too`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.seedTenByteFile()
        env.providerForTest.shortDownloadBytes = 4

        val result = env.hydration.hydrate(path)

        assertIs<HydrateResult.Failed>(result, "$result")
        assertEquals(10L, env.stateDb.remoteSizeOf(path))
        assertFalse(env.stateDb.isHydrated(path))
    }

    @Test
    fun `a file that really changed size since the enumeration is taken at its new size`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.seedTenByteFile(rowSize = 5) // the row is older than the drive's file

        val result = env.hydration.openForRead("conn1", "h1", path)

        assertIs<OpenResult.Ok>(result, "$result")
        assertEquals(content, Files.readString(result.cachePath))
        assertEquals(10L, env.stateDb.remoteSizeOf(path), "the size the drive confirms replaces the old one")
    }

    @Test
    fun `a file that really shrank is taken at its new size`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.seedTenByteFile()
        env.providerForTest.shortDownloadBytes = 4
        env.providerForTest.metadataSizes[path] = 4 // the drive itself now says 4

        val result = env.hydration.openForRead("conn1", "h1", path)

        assertIs<OpenResult.Ok>(result, "$result")
        assertEquals(4L, env.stateDb.remoteSizeOf(path))
    }

    @Test
    fun `a read cancelled while it downloads is a cancellation, not a failed result`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.seedTenByteFile()
        val stall = MinimalFakeProvider.DownloadStall(partialBytes = 2)
        env.providerForTest.downloadStall = stall
        var returned: OpenResult? = null
        val read = launch { returned = env.hydration.openForRead("conn1", "h1", path) }
        stall.reached.await()

        read.cancel()
        advanceUntilIdle()

        assertTrue(read.isCancelled)
        assertNull(returned, "a cancelled request has no answer: $returned")
    }

    @Test
    fun `a hydrate cancelled while it downloads is a cancellation, not a failed result`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.seedTenByteFile()
        val stall = MinimalFakeProvider.DownloadStall(partialBytes = 2)
        env.providerForTest.downloadStall = stall
        var returned: HydrateResult? = null
        val hydrate = launch { returned = env.hydration.hydrate(path) }
        stall.reached.await()

        hydrate.cancel()
        advanceUntilIdle()

        assertTrue(hydrate.isCancelled)
        assertNull(returned, "a cancelled request has no answer: $returned")
    }
}
