package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * gkrost/unidrive-cli#845 (#745 finding 6, streaming path): one gather can deliver the same remote id at more than one
 * path (an item moved mid-listing). The buffered gather keeps only the last occurrence (GatherIdIndex); the streaming
 * gather admitted every occurrence, so the item stood at its stale path and its new one, and the merge (path order)
 * could leave the row at the stale one. The pages are two apart, past the one-page rename lookahead.
 */
class StreamingRepeatedIdTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider

    @BeforeTest
    fun setUp() {
        syncRoot = TestScratch.directory("ud-stream-repeat-root")
        db = StateDatabase(TestScratch.directory("ud-stream-repeat-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun `an id delivered at two paths pages apart keeps its row at the last path only`() =
        runTest {
            provider.files["/a/x"] = ByteArray(3)
            provider.files["/b/x"] = ByteArray(3)
            // "/b/x" sorts after "/a/x": the path-ordered merge used to apply the stale occurrence last.
            provider.deltaPages =
                listOf(
                    listOf(item("X", "/b/x")) to "c1",
                    listOf(item("Y", "/y")) to "c2",
                    listOf(item("X", "/a/x")) to "c3",
                )
            provider.files["/y"] = ByteArray(3)
            streamedPass()

            val rows = db.getAllEntries().filter { it.remoteId == "X" }.map { it.path }
            assertEquals(listOf("/a/x"), rows, "the item moved mid-gather: its earlier path is stale")
        }

    private suspend fun streamedPass() =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = ProgressReporter.Silent,
            streamingReconciliation = true,
        ).syncOnce()

    // PR #917 review: a pathless tombstone for an id with no row resolves to no path. It still ends the live occurrence
    // the lookahead holds from the page before, which must not be reconciled or downloaded.
    @Test
    fun `a pathless tombstone on the next page ends the held live occurrence of an untracked id`() =
        runTest {
            provider.files["/n"] = ByteArray(3)
            provider.deltaPages =
                listOf(
                    listOf(item("X", "/n")) to "c1",
                    listOf(item("X", "").copy(deleted = true)) to "c2",
                )
            streamedPass()

            assertEquals(emptyList(), db.getAllEntries().filter { it.remoteId == "X" }.map { it.path }, "no ghost row for X")
            assertFalse(Files.exists(syncRoot.resolve("n")), "the deleted item is not downloaded")
        }

    @Test
    fun `guard - a live occurrence after a pathless tombstone of the same id is kept`() =
        runTest {
            provider.files["/n"] = ByteArray(3)
            provider.deltaPages =
                listOf(
                    listOf(item("X", "").copy(deleted = true)) to "c1",
                    listOf(item("X", "/n")) to "c2",
                )
            streamedPass()

            assertEquals(listOf("/n"), db.getAllEntries().filter { it.remoteId == "X" }.map { it.path }, "the last occurrence is live")
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
