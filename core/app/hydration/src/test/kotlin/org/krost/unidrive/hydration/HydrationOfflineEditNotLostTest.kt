package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * An edit made while the daemon was stopped leaves no `edit_base` (only a running daemon writes it): the cache copy differs
 * from the row's baseline, nothing more. The startup replay finds it by that difference. A read that comes first used to
 * download over it, because the size of the edit differs from the row's remote size (candidate M1 of archive#1040).
 */
class HydrationOfflineEditNotLostTest {
    @Test
    fun `a read does not download over a cache copy edited while the daemon was stopped`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        val cache = env.syncEngine.resolveCachePath("/q/f.txt")
        Files.createDirectories(cache.parent)
        Files.writeString(cache, "12345")
        val baseline = Files.getLastModifiedTime(cache).toMillis()
        env.stateDb.insertUploadedRow("/q/f.txt", mtime = baseline, size = 5L, remoteHash = "v1")
        env.syncEngine.seedRemoteContent("/q/f.txt", "12345")
        Files.writeString(cache, "an edit of another size")
        Files.setLastModifiedTime(cache, FileTime.fromMillis(baseline + 60_000))

        val read = env.hydration.openForRead("conn1", "h1", "/q/f.txt")

        assertIs<OpenResult.Ok>(read)
        assertEquals("an edit of another size", Files.readString(read.cachePath), "the edit is read back, not replaced")
        assertEquals(0, env.syncEngine.downloadAttempts(), "nothing is downloaded over the only copy of the edit")
    }

    @Test
    fun `a clean cache copy of another size than the row is still downloaded again`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        val cache = env.syncEngine.resolveCachePath("/q/f.txt")
        Files.createDirectories(cache.parent)
        Files.writeString(cache, "123")
        // The row's baseline is the cache copy as it is: a short copy nobody wrote (a crash, a cleared cache) is no edit.
        env.stateDb.upsert(
            SyncEntry(
                path = "/q/f.txt",
                remoteId = "remote-/q/f.txt",
                remoteHash = "v1",
                remoteSize = 5L,
                remoteModified = Instant.now(),
                localMtime = Files.getLastModifiedTime(cache).toMillis(),
                localSize = 3L,
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.now(),
                cacheBacked = true,
            ),
        )
        env.syncEngine.seedRemoteContent("/q/f.txt", "12345")

        val read = env.hydration.openForRead("conn1", "h1", "/q/f.txt")

        assertIs<OpenResult.Ok>(read)
        assertEquals("12345", Files.readString(read.cachePath))
        assertEquals(1, env.syncEngine.downloadAttempts())
    }
}
