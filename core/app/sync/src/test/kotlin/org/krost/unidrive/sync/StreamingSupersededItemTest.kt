package org.krost.unidrive.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.time.Instant
import java.util.Timer
import kotlin.concurrent.schedule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#845 (the streaming remainder of #917): the buffered gather sees every page before it plans; the
 * streaming gather plans each page as it arrives. The invariant: the same page sequence ends in the same state through
 * either, so a later page that supersedes an item takes back what an earlier page planned for it.
 *
 * Every scenario runs through both gathers. In the streaming run the download of the superseded path waits at a gate
 * (the transfer is planned and started, not finished, when the later page arrives); a timer opens the gate late, so a
 * transfer that is not withdrawn finishes and shows in the outcome.
 */
class StreamingSupersededItemTest {
    private data class Outcome(
        val rows: List<Pair<String, String>>,
        val files: Set<String>,
        val downloads: List<String>,
    )

    private suspend fun run(
        streaming: Boolean,
        pages: List<List<CloudItem>>,
        gatedPath: String? = null,
    ): Outcome {
        val syncRoot = TestScratch.directory("ud-superseded-root")
        val db = StateDatabase(TestScratch.directory("ud-superseded-db").resolve("state.db"))
        db.initialize()
        try {
            val provider = FakeCloudProvider()
            for (item in pages.flatten().filter { !it.deleted && it.path.isNotEmpty() }) provider.files[item.path] = ByteArray(3)
            provider.deltaPages = pages.mapIndexed { i, items -> items to "c${i + 1}" }
            if (streaming && gatedPath != null) {
                val gate = CompletableDeferred<Unit>()
                provider.downloadGates[gatedPath] = gate
                Timer(true).schedule(OPEN_GATE_AFTER_MS) { gate.complete(Unit) }
            }
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = syncRoot,
                conflictPolicy = ConflictPolicy.KEEP_BOTH,
                reporter = ProgressReporter.Silent,
                streamingReconciliation = streaming,
            ).syncOnce()
            val files = Files.walk(syncRoot).use { s -> s.filter { Files.isRegularFile(it) }.map { "/" + syncRoot.relativize(it) }.toList() }
            return Outcome(
                rows = db.getAllEntries().map { it.path to it.remoteId.orEmpty() }.sortedBy { it.first },
                files = files.toSet(),
                downloads = provider.downloadByIdCalls.map { it.second } + provider.downloadByPathCalls,
            )
        } finally {
            db.close()
        }
    }

    private suspend fun assertStreamingMatchesBuffered(
        pages: List<List<CloudItem>>,
        gatedPath: String,
    ): Outcome {
        val buffered = run(streaming = false, pages = pages)
        val streamed = run(streaming = true, pages = pages, gatedPath = gatedPath)
        assertEquals(buffered.rows, streamed.rows, "state rows: streaming vs buffered")
        assertEquals(buffered.files, streamed.files, "local files: streaming vs buffered")
        return streamed
    }

    @Test
    fun `a pathless tombstone on a later page ends an untracked id's earlier live occurrence (streaming matches buffered)`() =
        runTest {
            // X is delivered at /n, two pages later its tombstone arrives with no path and X has no row.
            val out =
                assertStreamingMatchesBuffered(
                    pages =
                        listOf(
                            listOf(item("X", "/n")),
                            listOf(item("Y", "/y")),
                            listOf(item("Z", "/z")),
                            listOf(item("X", "").copy(deleted = true)),
                        ),
                    gatedPath = "/n",
                )
            assertEquals(emptyList(), out.rows.filter { it.second == "X" }, "no row for the deleted X")
            assertFalse("/n" in out.files, "the deleted item is not downloaded")
            assertTrue(out.rows.map { it.first }.containsAll(listOf("/y", "/z")), "the unrelated ids are untouched")
            assertTrue(out.files.containsAll(listOf("/y", "/z")), "the unrelated ids are downloaded")
        }

    @Test
    fun `a later page that supersedes an id cancels the transfer an earlier page planned (streaming matches buffered)`() =
        runTest {
            // X is delivered at /p, two pages later it is at /q: the download of /p must not finish.
            val out =
                assertStreamingMatchesBuffered(
                    pages =
                        listOf(
                            listOf(item("X", "/p")),
                            listOf(item("Y", "/y")),
                            listOf(item("Z", "/z")),
                            listOf(item("X", "/q")),
                        ),
                    gatedPath = "/p",
                )
            assertEquals(listOf("/q"), out.rows.filter { it.second == "X" }.map { it.first }, "X stands at its last path only")
            assertFalse("/p" in out.files, "the superseded path is not downloaded")
            assertTrue(out.files.containsAll(listOf("/q", "/y", "/z")), "the current paths are downloaded")
        }

    @Test
    fun `a later tombstone at the same path cancels the transfer an earlier page planned (streaming matches buffered)`() =
        runTest {
            val out =
                assertStreamingMatchesBuffered(
                    pages =
                        listOf(
                            listOf(item("X", "/p")),
                            listOf(item("Y", "/y")),
                            listOf(item("Z", "/z")),
                            listOf(item("X", "/p").copy(deleted = true)),
                        ),
                    gatedPath = "/p",
                )
            assertEquals(emptyList(), out.rows.filter { it.second == "X" }, "no row for the deleted X")
            assertFalse("/p" in out.files, "the deleted item is not downloaded")
        }

    @Test
    fun `guard - an id seen alive on both pages at one path still plans one transfer`() =
        runTest {
            val pages = listOf(listOf(item("X", "/p")), listOf(item("Y", "/y")), listOf(item("Z", "/z")), listOf(item("X", "/p")))
            val buffered = run(streaming = false, pages = pages)
            val streamed = run(streaming = true, pages = pages)
            assertEquals(buffered.rows, streamed.rows)
            assertEquals(listOf("/p"), streamed.rows.filter { it.second == "X" }.map { it.first })
            assertEquals(1, streamed.downloads.count { it == "/p" }, "one transfer for the repeated item")
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

    private companion object {
        const val OPEN_GATE_AFTER_MS = 4_000L
    }
}
