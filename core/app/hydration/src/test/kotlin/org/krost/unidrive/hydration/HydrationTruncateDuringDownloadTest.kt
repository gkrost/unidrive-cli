package org.krost.unidrive.hydration

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
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
    fun `a truncate that lands while a finished download takes the cache copy's place stays noted as an edit`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.stateDb.insertUnhydratedEntry("/q/f.txt", remoteSize = 5, remoteHash = "v1")
        env.syncEngine.seedRemoteContent("/q/f.txt", "cloud")
        val cachePath = env.syncEngine.resolveCachePath("/q/f.txt")
        val fired = AtomicBoolean(false)
        val begin = AtomicReference<OpenResult>()
        val edit = AtomicReference<Thread>()
        // The download asks the provider for its hash algorithm while it writes its row back, after its bytes took the
        // cache copy's place and after its last look at the row. The truncate arrives then, from another thread, as a
        // client's O_TRUNC open does; the download goes on once the truncate returned or waits for the download.
        env.providerForTest.onHashAlgorithm = {
            if (Files.exists(cachePath) && fired.compareAndSet(false, true)) {
                val writer =
                    thread {
                        val result = runBlocking { env.hydration.openWriteBegin("conn2", "/q/f.txt", handleId = "h2") }
                        begin.set(result)
                        if (result is OpenResult.Ok) Files.writeString(result.cachePath, "my edit")
                    }
                edit.set(writer)
                awaitReturnedOrWaiting(writer)
            }
        }

        val read = env.hydration.openForRead("conn1", "h1", "/q/f.txt")
        assertNotNull(edit.get(), "the truncate was sent while the download wrote its row back").join(10_000)

        assertIs<OpenResult.Ok>(begin.get())
        assertNotNull(env.stateDb.editBaseOf("/q/f.txt"), "the edit is still noted as unsynced")
        assertEquals("my edit", Files.readString(cachePath), "the cache copy holds the edit")
        // The row the download wrote back claimed the edited copy as a complete cloud copy, which the read then refused
        // as an incomplete hydration.
        assertIs<OpenResult.Ok>(read)
    }

    // Until [writer] has returned, or its runBlocking has parked: its coroutine waits for something the download holds.
    private fun awaitReturnedOrWaiting(writer: Thread) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (writer.isAlive && System.nanoTime() < deadline) {
            val parked = writer.state == Thread.State.WAITING || writer.state == Thread.State.TIMED_WAITING
            if (parked && writer.stackTrace.any { it.methodName == "joinBlocking" }) return
            Thread.sleep(5)
        }
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
