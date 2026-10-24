package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.RemoteContentRefusedDownloadException
import org.krost.unidrive.UnsupportedRemoteObjectDownloadException
import org.krost.unidrive.sync.model.SyncEntry
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A cloud object whose content fails its integrity check is refused with `content_refused` after the whole ciphertext
 * was downloaded. The daemon remembers that refusal per remote id like a truncated object (HydrationRemoteIncompleteMemoryTest),
 * so a repeated read or pin is answered from memory with the same token instead of downloading the object again, until
 * the object's cloud version changes.
 */
class HydrationContentRefusedMemoryTest {
    private val token = "content_refused: RIPEMD-160(SHA-256) mismatch"

    private fun env(): HydrationTestEnv {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/refused.bin", remoteSize = 500, remoteId = "id-refused")
        env.syncEngine.refuseContent("id-refused", "RIPEMD-160(SHA-256) mismatch")
        return env
    }

    private fun tokenOf(result: OpenResult): String {
        assertTrue(result is OpenResult.Failed, "expected a refusal: $result")
        return result.error.message
    }

    @Test
    fun `a second read of a refused object is refused from memory without downloading it again`() = runTest {
        val env = env()

        val first = env.hydration.openForRead("conn1", "h1", "/refused.bin")
        val second = env.hydration.openForRead("conn1", "h2", "/refused.bin")

        assertEquals(token, tokenOf(first))
        assertEquals(token, tokenOf(second), "the repeat carries the same token with the same reason")
        assertEquals(1, env.syncEngine.downloadAttempts(), "ONE download for two reads")
    }

    @Test
    fun `a pin after a read is refused from memory too, and over IPC the reply is the same`() = runTest {
        val env = env()
        val handler = HydrationIpcHandler(env.hydration)
        val request = """{"verb":"hydration.open_read","handle_id":"h1","path":"/refused.bin"}"""

        val first = handler.handle("conn1", request)
        val pin = env.hydration.hydrate("/refused.bin")
        val again = handler.handle("conn1", request)

        assertEquals("""{"ok":false,"error":"$token"}""", first.trim())
        assertTrue(pin is HydrateResult.Failed)
        assertEquals(token, pin.error.message)
        assertEquals(first, again)
        assertEquals(1, env.syncEngine.downloadAttempts(), "read and pin share the memory")
    }

    @Test
    fun `a refused object is not counted as truncated`() = runTest {
        val env = env()

        env.hydration.openForRead("conn1", "h1", "/refused.bin")

        assertEquals(0, MountEngine.over(env.engine).remoteIncompleteCount(), "remote_incomplete counts truncated objects only")
    }

    @Test
    fun `a new cloud version is downloaded again and reads`() = runTest {
        val env = env()
        env.hydration.openForRead("conn1", "h1", "/refused.bin")

        // The enumeration reports a new version of the object (another size), whose content is fine.
        env.syncEngine.seedRemoteContent("/refused.bin", "x".repeat(700))
        env.stateDb.insertUnhydratedEntry("/refused.bin", remoteSize = 700, remoteId = "id-refused")
        env.syncEngine.restoreContent("id-refused")
        val again = env.hydration.openForRead("conn1", "h2", "/refused.bin")

        assertEquals(2, env.syncEngine.downloadAttempts(), "a changed version: the provider is asked again")
        assertTrue(again is OpenResult.Ok, "the new version reads: $again")
    }

    private fun row(
        remoteId: String?,
        size: Long = 500,
    ) = SyncEntry(
        path = "/w.bin",
        remoteId = remoteId,
        remoteHash = "h",
        remoteSize = size,
        remoteModified = null,
        localMtime = null,
        localSize = null,
        isFolder = false,
        isPinned = false,
        isHydrated = false,
        lastSynced = Instant.now(),
    )

    @Test
    fun `a remembered content refusal keeps its reason until the TTL, then the provider is asked again`() {
        var now = 1_000L
        val memory = RemoteIncompleteMemory(ttlMs = 100, clock = { now })
        val entry = row("id-c")
        memory.remember(entry, RemoteContentRefusedDownloadException("refused", reason = "SHA-256 mismatch"))

        val recalled = memory.recall(entry)
        now += 100

        assertTrue(recalled is RemoteContentRefusedDownloadException, "recalled as the same kind of refusal: $recalled")
        assertEquals("SHA-256 mismatch", recalled.reason)
        assertNull(memory.recall(entry), "older than the TTL: one real attempt revalidates it")
    }

    @Test
    fun `a content refusal is dropped by a whole download or another cloud version, and only that kind is kept`() {
        val memory = RemoteIncompleteMemory()
        val entry = row("id-c")
        val refusal = RemoteContentRefusedDownloadException("refused", reason = "SHA-256 mismatch")

        memory.remember(entry, refusal)
        memory.forget(entry)
        assertNull(memory.recall(entry), "the object downloaded whole")

        memory.remember(entry, refusal)
        assertNull(memory.recall(row("id-c", size = 700)), "the row describes another cloud version")
        assertNull(memory.recall(entry), "and the entry is gone, not merely skipped")

        assertFalse(memory.remember(entry, UnsupportedRemoteObjectDownloadException("odd", reason = "several parts")))
        assertNull(memory.recall(entry), "an unreadable object is not a refused content: nothing is kept for it")
    }
}
