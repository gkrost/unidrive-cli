package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * gkrost/unidrive-cli#845: a tracked item the cloud moved is a local move in the streaming gather too, as in the buffered
 * one. Planned as a download at the new path, the old path's file kept no row and the next pass uploaded it as a new file.
 */
class StreamingRemoteMoveTest {
    private data class Outcome(val rows: List<String>, val files: List<String>, val uploads: List<String>)

    private suspend fun go(streaming: Boolean): Outcome {
        val syncRoot = TestScratch.directory("ud-probe-root")
        val db = StateDatabase(TestScratch.directory("ud-probe-db").resolve("state.db"))
        db.initialize()
        val provider = FakeCloudProvider()
        provider.files["/p"] = ByteArray(3)
        provider.deltaPages = listOf(listOf(item("X", "/p")) to "c1")

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
        provider.files.remove("/p")
        provider.files["/q"] = ByteArray(3)
        provider.deltaPages = listOf(listOf(item("X", "/q")) to "c2")
        engine().syncOnce()
        engine().syncOnce()
        val files = Files.walk(syncRoot).use { w -> w.filter { Files.isRegularFile(it) }.map { it.toString().removePrefix(syncRoot.toString()).replace('\\', '/') }.toList() }
        return Outcome(db.getAllEntries().map { it.path }.sorted(), files.sorted(), provider.uploadedPaths.toList())
    }

    @Test
    fun `a tracked item the cloud moved is moved locally, not downloaded again and its old file uploaded (streaming matches buffered)`() =
        runTest {
            val buffered = go(streaming = false)
            val streamed = go(streaming = true)
            assertEquals(buffered, streamed)
            assertEquals(listOf("/q"), streamed.files)
            assertEquals(emptyList(), streamed.uploads)
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
