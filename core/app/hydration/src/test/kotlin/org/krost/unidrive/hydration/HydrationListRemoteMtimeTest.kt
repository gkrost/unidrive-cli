package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.testing.TestScratch
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Live drive (low): after a (re-)download, `hydration.list` reported the file's mtime as the time of the download, the cache
 * copy's own stamp, instead of the file's modification time in the cloud. A clean cloud-backed file lists its remote mtime;
 * an edit or a never-uploaded file keeps its local one.
 */
class HydrationListRemoteMtimeTest {
    @Test
    fun `a downloaded file lists its remote mtime, not the time of the download`() = runTest {
        val modified = Instant.parse("2026-03-28T12:00:00Z")
        val provider = MemProvider()
        provider.seed("/f.txt", "cloud bytes".toByteArray(), modified)
        val db = StateDatabase(TestScratch.directory("ud-mtime-db").resolve("state.db"), inMemory = true).also { it.initialize() }
        val engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = TestScratch.directory("ud-mtime-root"),
                cacheRoot = TestScratch.directory("ud-mtime-cache"),
            )
        MountEngine.over(engine).enumerateRemoteIntoState(reset = false)
        val hydration = HydrationImpl(engine, db, recoveryUploadScope = this)
        assertIs<OpenResult.Ok>(hydration.openForRead("conn", "r1", "/f.txt"))
        hydration.closeHandle("conn", "r1")

        val listed = assertIs<ListResult.Ok>(hydration.list("/"))
        assertEquals(modified.toEpochMilli(), listed.entries.single { it.path == "/f.txt" }.mtimeEpochMillis)
    }
}
