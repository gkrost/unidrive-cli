package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#845 / #971: a transfer that FINISHED before the page that supersedes its item arrived is not
 * undone by the withdrawal. The invariant is that the next pass converges to what the buffered gather ends at, and
 * that the stale local copy is never uploaded (a resurrected deleted file).
 */
class StreamingFinishedTransferTest {
    private data class Outcome(
        val rows: List<Pair<String, String>>,
        val files: Set<String>,
        val remote: Set<String>,
        val downloaded: Boolean,
        val uploads: List<String>,
    )

    private suspend fun twoPasses(
        streaming: Boolean,
        lastPage: CloudItem,
        remoteAfter: Set<String>,
    ): Outcome {
        val syncRoot = TestScratch.directory("ud-finished-root")
        val db = StateDatabase(TestScratch.directory("ud-finished-db").resolve("state.db"))
        db.initialize()
        try {
            val provider = FakeCloudProvider()
            provider.files["/p"] = ByteArray(3)
            provider.files["/y"] = ByteArray(3)
            provider.files["/z"] = ByteArray(3)
            if (!lastPage.deleted) provider.files[lastPage.path] = ByteArray(3)
            provider.deltaPages =
                listOf(
                    listOf(item("X", "/p")) to "c1",
                    listOf(item("Y", "/y")) to "c2",
                    listOf(item("Z", "/z")) to "c3",
                    listOf(lastPage) to "c4",
                )
            if (streaming) {
                // The fourth page is requested only once /p is on disk and its row is written: the transfer has finished when it arrives.
                provider.suspendDuringDelta = {
                    if (provider.deltaCalls == 4) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            repeat(300) { if (db.getEntry("/p")?.isHydrated != true) Thread.sleep(10) }
                        }
                    }
                }
            }
            fun engine() =
                SyncEngine(
                    provider = provider,
                    db = db,
                    syncRoot = syncRoot,
                    conflictPolicy = ConflictPolicy.KEEP_BOTH,
                    reporter = ProgressReporter.Silent,
                    streamingReconciliation = streaming,
                )
            engine().syncOnce()
            val downloaded = "/p" in provider.downloadByIdCalls.map { it.second } + provider.downloadByPathCalls
            // The cloud now holds what the pages describe; the second pass gets no news.
            provider.files.keys.retainAll(remoteAfter)
            provider.suspendDuringDelta = null
            provider.deltaPages = listOf(emptyList<CloudItem>() to "c5")
            engine().syncOnce()
            val files = Files.walk(syncRoot).use { s -> s.filter { Files.isRegularFile(it) }.map { "/" + syncRoot.relativize(it) }.toList() }
            return Outcome(
                rows = db.getAllEntries().map { it.path to it.remoteId.orEmpty() }.sortedBy { it.first },
                files = files.toSet(),
                remote = provider.files.keys.toSet(),
                downloaded = downloaded,
                uploads = provider.uploadedPaths.toList(),
            )
        } finally {
            db.close()
        }
    }

    private suspend fun converges(
        lastPage: CloudItem,
        remoteAfter: Set<String>,
    ): Outcome {
        val buffered = twoPasses(streaming = false, lastPage = lastPage, remoteAfter = remoteAfter)
        val streamed = twoPasses(streaming = true, lastPage = lastPage, remoteAfter = remoteAfter)
        assertTrue(streamed.downloaded, "the scenario: /p was downloaded before the superseding page")
        assertEquals(buffered.rows, streamed.rows, "state rows after the next pass: streaming vs buffered")
        assertEquals(buffered.files, streamed.files, "local files after the next pass: streaming vs buffered")
        assertEquals(buffered.remote, streamed.remote, "the cloud after the next pass: streaming vs buffered")
        assertFalse("/p" in streamed.uploads, "the stale local copy of /p is not uploaded: ${streamed.uploads}")
        return streamed
    }

    @Test
    fun `a transfer finished before a tombstone page converges on the next pass without uploading the stale copy`() =
        runTest {
            val out = converges(lastPage = item("X", "/p").copy(deleted = true), remoteAfter = setOf("/y", "/z"))
            assertFalse("/p" in out.files, "the deleted item is gone locally")
        }

    @Test
    fun `a transfer finished before a pathless tombstone page converges on the next pass without uploading the stale copy`() =
        runTest {
            val out = converges(lastPage = item("X", "").copy(deleted = true), remoteAfter = setOf("/y", "/z"))
            assertFalse("/p" in out.files, "the deleted item is gone locally")
        }

    @Test
    fun `a transfer finished before the item moved converges on the next pass without uploading the stale copy`() =
        runTest {
            val out = converges(lastPage = item("X", "/q"), remoteAfter = setOf("/q", "/y", "/z"))
            assertFalse("/p" in out.files, "the old path is gone locally")
            assertEquals(listOf("/q"), out.rows.filter { it.second == "X" }.map { it.first })
        }

    private fun item(
        id: String,
        path: String,
    ) = CloudItem(
        id = id,
        name = path.substringAfterLast("/"),
        path = path,
        size = 3,
        isFolder = false,
        modified = Instant.parse("2026-03-28T12:00:00Z"),
        created = Instant.parse("2026-03-28T10:00:00Z"),
        hash = "hash-$id",
        mimeType = null,
    )
}
