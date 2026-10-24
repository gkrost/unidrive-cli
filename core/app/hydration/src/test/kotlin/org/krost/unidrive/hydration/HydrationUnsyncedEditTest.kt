package org.krost.unidrive.hydration

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An edit in the cache copy of a cloud-backed file that the cloud has not seen yet (#1070, #1069, and the
 * candidate M1 of #1040): the mount remembers it, with the cloud version it started from, until an upload
 * lands, so that no replay or read loses it and no replay overwrites a newer cloud version.
 */
class HydrationUnsyncedEditTest {
    private fun writeCache(env: HydrationTestEnv, path: String, content: String) {
        val cache = env.syncEngine.resolveCachePath(path)
        Files.createDirectories(cache.parent)
        Files.writeString(cache, content)
    }

    // The client's O_TRUNC open of [path] and the bytes it writes through the handle; the handle is closed, but the
    // dirty-close open_write (which would queue the upload) never arrives: the daemon stopped, or the client died.
    private suspend fun beginAndWrite(env: HydrationTestEnv, path: String, content: String) {
        val begin = env.hydration.openWriteBegin("conn1", path, handleId = "h1")
        assertIs<OpenResult.Ok>(begin)
        Files.writeString(begin.cachePath, content)
        env.hydration.closeHandle("conn1", "h1")
    }

    // #1070: openWriteBegin leaves the row of a cloud-only file unhydrated, so neither replay loop saw the bytes.
    @Test
    fun `an edit begun on a never-hydrated cloud row is replayed after a restart`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.stateDb.insertUnhydratedEntry("/q/f.txt", remoteSize = 5)
        env.syncEngine.seedRemoteContent("/q/f.txt", "cloud")
        beginAndWrite(env, "/q/f.txt", "my edit")

        assertEquals(1, env.hydration.replayPendingUploads(), "the written bytes are owed to the cloud")
        advanceUntilIdle()

        assertEquals("my edit", env.syncEngine.remoteContentSeen("/q/f.txt"))
    }

    // #1070: after a failed ladder the cache copy is the only copy of the edit; a read must not download over it.
    @Test
    fun `a failed upload of an edit begun on a never-hydrated row leaves the bytes to read, not to download over`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, maxUploadAttempts = 1)
        env.stateDb.insertUnhydratedEntry("/q/f.txt", remoteSize = 5)
        env.syncEngine.seedRemoteContent("/q/f.txt", "cloud")
        env.syncEngine.failUploads(999)
        val begin = env.hydration.openWriteBegin("conn1", "/q/f.txt", handleId = "h1")
        assertIs<OpenResult.Ok>(begin)
        Files.writeString(begin.cachePath, "my edit")
        env.hydration.openForWrite("conn1", "h1", "/q/f.txt", begin.cachePath)
        advanceUntilIdle()
        env.hydration.closeHandle("conn1", "h1")
        assertNotNull(env.stateDb.lastErrorAt("/q/f.txt"), "the ladder failed")

        val read = env.hydration.openForRead("conn1", "h2", "/q/f.txt")

        assertIs<OpenResult.Ok>(read)
        assertEquals("my edit", Files.readString(read.cachePath), "the edit is read back from the cache")
        assertEquals(0, env.syncEngine.downloadAttempts(), "nothing is downloaded over the only copy of the edit")
    }

    @Test
    fun `dehydrate refuses the only copy of a failed cloud-backed edit`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, maxUploadAttempts = 1)
        env.stateDb.insertUnhydratedEntry("/q/f.txt", remoteSize = 5)
        env.syncEngine.seedRemoteContent("/q/f.txt", "cloud")
        env.syncEngine.failUploads(999)
        val begin = env.hydration.openWriteBegin("conn1", "/q/f.txt", handleId = "h1")
        assertIs<OpenResult.Ok>(begin)
        Files.writeString(begin.cachePath, "my edit")
        env.hydration.openForWrite("conn1", "h1", "/q/f.txt", begin.cachePath)
        advanceUntilIdle()
        env.hydration.closeHandle("conn1", "h1")
        assertNotNull(env.stateDb.lastErrorAt("/q/f.txt"))
        assertNotNull(env.stateDb.editBaseOf("/q/f.txt"))

        assertEquals(DehydrateResult.Busy, env.hydration.dehydrate("/q/f.txt"))
        assertEquals("my edit", Files.readString(begin.cachePath), "dehydrate must not delete the only copy")
        assertNotNull(env.stateDb.editBaseOf("/q/f.txt"), "the edit remains marked for replay")
    }

    // #1069: the replay used the row's CURRENT remote hash as the write token. A poll that delivered another
    // device's version after the edit began moves it, and the replay then overwrote that version.
    @Test
    fun `the replay does not send an edit over a cloud version that arrived after the edit began`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        writeCache(env, "/q/f.txt", "v1 bytes")
        val cache = env.syncEngine.resolveCachePath("/q/f.txt")
        env.stateDb.insertUploadedRow(
            "/q/f.txt",
            mtime = Files.getLastModifiedTime(cache).toMillis(),
            size = Files.size(cache),
            remoteHash = "v1",
        )
        val events = mutableListOf<HydrationEvent>()
        val collector = launch { env.hydration.events.collect { events.add(it) } }
        yield()
        beginAndWrite(env, "/q/f.txt", "my edit")
        env.stateDb.mergeRemoteVersion("/q/f.txt", "v2") // the poll: another device wrote v2

        assertEquals(0, env.hydration.replayPendingUploads(), "the edit started from v1, the cloud holds v2")
        advanceUntilIdle()

        assertNull(env.syncEngine.remoteContentSeen("/q/f.txt"), "the newer cloud version is not overwritten")
        assertEquals("my edit", Files.readString(cache), "the edit survives in the cache")
        assertNotNull(env.stateDb.lastErrorAt("/q/f.txt"), "the row shows the edit as not uploaded")
        assertTrue(
            events.filterIsInstance<HydrationEvent.Failed>().any { it.error is HydrationError.Conflict },
            "the refusal is reported as a conflict",
        )
        collector.cancel()
    }

    @Test
    fun `the replay sends an edit with the version it started from when the cloud has not moved`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        writeCache(env, "/q/f.txt", "v1 bytes")
        val cache = env.syncEngine.resolveCachePath("/q/f.txt")
        env.stateDb.insertUploadedRow(
            "/q/f.txt",
            mtime = Files.getLastModifiedTime(cache).toMillis(),
            size = Files.size(cache),
            remoteHash = "v1",
        )
        beginAndWrite(env, "/q/f.txt", "my edit")

        assertEquals(1, env.hydration.replayPendingUploads())
        advanceUntilIdle()

        assertEquals("my edit", env.syncEngine.remoteContentSeen("/q/f.txt"))
        assertEquals("v1", env.syncEngine.lastUploadIfMatch(), "the write token is the version the edit started from")
    }

    // #511: for a provider with write tokens of its own (OneDrive's eTag) the edit is noted with the row's version, and
    // the replay hands exactly that version to the provider as If-Match.
    @Test
    fun `the replay sends an edit with the provider version it started from`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, providerId = "onedrive", reportsItemVersions = true)
        writeCache(env, "/q/f.txt", "v1 bytes")
        val cache = env.syncEngine.resolveCachePath("/q/f.txt")
        env.stateDb.insertUploadedRow(
            "/q/f.txt",
            mtime = Files.getLastModifiedTime(cache).toMillis(),
            size = Files.size(cache),
            remoteHash = "v1",
            remoteVersion = "\"{F},1\"",
        )
        beginAndWrite(env, "/q/f.txt", "my edit")
        assertEquals("\"{F},1\"", env.stateDb.editBaseOf("/q/f.txt"), "the edit starts from the row's write token")

        assertEquals(1, env.hydration.replayPendingUploads())
        advanceUntilIdle()

        assertEquals("my edit", env.syncEngine.remoteContentSeen("/q/f.txt"))
        assertEquals("\"{F},1\"", env.syncEngine.lastUploadIfMatch(), "the write is conditional on the version the edit started from")
    }

    @Test
    fun `the replay does not send an edit over a provider version that arrived after the edit began`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, providerId = "onedrive", reportsItemVersions = true)
        writeCache(env, "/q/f.txt", "v1 bytes")
        val cache = env.syncEngine.resolveCachePath("/q/f.txt")
        env.stateDb.insertUploadedRow(
            "/q/f.txt",
            mtime = Files.getLastModifiedTime(cache).toMillis(),
            size = Files.size(cache),
            remoteHash = "v1",
            remoteVersion = "\"{F},1\"",
        )
        beginAndWrite(env, "/q/f.txt", "my edit")
        // The poll: the item has a new version; its content hash happens to read the same.
        env.stateDb.mergeRemoteVersion("/q/f.txt", "v1", "\"{F},2\"")

        assertEquals(0, env.hydration.replayPendingUploads(), "the edit started from version 1, the cloud holds version 2")
        advanceUntilIdle()

        assertNull(env.syncEngine.remoteContentSeen("/q/f.txt"), "the newer cloud version is not overwritten")
        assertEquals("my edit", Files.readString(cache), "the edit survives in the cache")
    }

    @Test
    fun `an edit noted with the content hash before the row had a version is still replayed, unconditionally`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, providerId = "onedrive", reportsItemVersions = true)
        writeCache(env, "/q/f.txt", "v1 bytes")
        val cache = env.syncEngine.resolveCachePath("/q/f.txt")
        env.stateDb.insertUploadedRow(
            "/q/f.txt",
            mtime = Files.getLastModifiedTime(cache).toMillis(),
            size = Files.size(cache),
            remoteHash = "v1",
        )
        beginAndWrite(env, "/q/f.txt", "my edit") // noted as "v1", the hash: the row has no version yet
        env.stateDb.mergeRemoteVersion("/q/f.txt", "v1", "\"{F},1\"") // a delivery brings the version, same content

        assertEquals(1, env.hydration.replayPendingUploads(), "the cloud still holds the content the edit started from")
        advanceUntilIdle()

        assertEquals("my edit", env.syncEngine.remoteContentSeen("/q/f.txt"))
        assertNull(env.syncEngine.lastUploadIfMatch(), "a content hash is never sent as the provider's If-Match")
    }

    // M1 of #1040: a warm copy was one whose size equals the row's remote size; an edit of another size was
    // downloaded over by the next read.
    @Test
    fun `a read does not download over an unsynced edit of another size`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        writeCache(env, "/q/f.txt", "12345")
        val cache = env.syncEngine.resolveCachePath("/q/f.txt")
        env.stateDb.insertUploadedRow(
            "/q/f.txt",
            mtime = Files.getLastModifiedTime(cache).toMillis(),
            size = 5L,
            remoteHash = "v1",
        )
        env.syncEngine.seedRemoteContent("/q/f.txt", "12345")
        beginAndWrite(env, "/q/f.txt", "8 bytes!")

        val read = env.hydration.openForRead("conn1", "h2", "/q/f.txt")

        assertIs<OpenResult.Ok>(read)
        assertEquals("8 bytes!", Files.readString(read.cachePath))
        assertEquals(0, env.syncEngine.downloadAttempts(), "the edit is the copy of record")
    }

    @Test
    fun `an edit is noted with the version it started from, whatever a poll delivers after`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.stateDb.insertUnhydratedEntry("/q/f.txt", remoteSize = 5, remoteHash = "v1")
        val begin = env.hydration.openWriteBegin("conn1", "/q/f.txt", handleId = "h1")
        assertIs<OpenResult.Ok>(begin)
        assertEquals("v1", env.stateDb.editBaseOf("/q/f.txt"), "noted at the truncation")
        env.stateDb.mergeRemoteVersion("/q/f.txt", "v2")
        Files.writeString(begin.cachePath, "my edit")
        assertEquals("v1", env.stateDb.editBaseOf("/q/f.txt"), "a poll does not move the version the edit started from")

        // The client's dirty close, with the version it saw (v1): the cloud has v2 now.
        val close = env.hydration.openForWrite("conn1", "h1", "/q/f.txt", begin.cachePath, baseEtag = "v1")

        assertEquals(OpenResult.Failed(HydrationError.Conflict), close)
        assertEquals("v1", env.stateDb.editBaseOf("/q/f.txt"), "the edit is still owed, from v1")
        assertNull(env.syncEngine.remoteContentSeen("/q/f.txt"))
    }

    @Test
    fun `an edit ends when its upload lands`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.stateDb.insertUnhydratedEntry("/q/f.txt", remoteSize = 5, remoteHash = "v1")
        val begin = env.hydration.openWriteBegin("conn1", "/q/f.txt", handleId = "h1")
        assertIs<OpenResult.Ok>(begin)
        Files.writeString(begin.cachePath, "my edit")
        assertIs<OpenResult.Ok>(env.hydration.openForWrite("conn1", "h1", "/q/f.txt", begin.cachePath, baseEtag = "v1"))
        advanceUntilIdle()
        env.hydration.closeHandle("conn1", "h1")

        assertEquals("my edit", env.syncEngine.remoteContentSeen("/q/f.txt"))
        assertEquals("v1", env.syncEngine.lastUploadIfMatch())
        assertNull(env.stateDb.editBaseOf("/q/f.txt"), "the upload landed: the cache copy is the cloud's")
    }

    // An upload that lands while the client writes again: what was sent is the cloud's, the new bytes are a new edit.
    @Test
    fun `a write that lands during an upload stays noted, from the version the upload stored`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        env.syncEngine.setUploadGate(gate)
        env.stateDb.insertUnhydratedEntry("/q/f.txt", remoteSize = 5, remoteHash = "v1")
        val begin = env.hydration.openWriteBegin("conn1", "/q/f.txt", handleId = "h1")
        assertIs<OpenResult.Ok>(begin)
        Files.writeString(begin.cachePath, "first")
        env.hydration.openForWrite("conn1", "h1", "/q/f.txt", begin.cachePath)
        advanceUntilIdle() // parked inside the provider, the stats of "first" taken
        Files.writeString(begin.cachePath, "first, and more")
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("15", env.stateDb.editBaseOf("/q/f.txt"), "noted from the version just stored (the fake's hash is the size)")
    }
}
