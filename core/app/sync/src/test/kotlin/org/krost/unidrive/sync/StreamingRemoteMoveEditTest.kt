package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A tracked item the cloud renames and edits is a move plus a content change in the streaming gather too, as in the
 * buffered one: the slice pairs it with its row by remote id, not by content. Planned as a download at the new path, the
 * old path's file kept no row and the next pass uploaded it as a new cloud item.
 */
class StreamingRemoteMoveEditTest {
    private data class Outcome(
        val rows: List<Pair<String, String?>>,
        val files: Map<String, String>,
        val uploads: List<String>,
    )

    // One sync pass: what the cloud holds, the delta pages it delivers, and what the user does locally before the pass.
    private class Pass(
        val files: Map<String, String>,
        val pages: List<List<CloudItem>>,
        val beforeLocal: (Path) -> Unit = {},
    )

    private suspend fun go(
        streaming: Boolean,
        passes: List<Pass>,
    ): Outcome {
        val syncRoot = TestScratch.directory("ud-move-edit-root")
        val db = StateDatabase(TestScratch.directory("ud-move-edit-db").resolve("state.db"))
        db.initialize()
        val provider = FakeCloudProvider()

        fun engine() =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = syncRoot,
                conflictPolicy = ConflictPolicy.KEEP_BOTH,
                reporter = ProgressReporter.Silent,
                streamingReconciliation = streaming,
            )
        for ((n, pass) in passes.withIndex()) {
            pass.beforeLocal(syncRoot)
            provider.files.clear()
            pass.files.forEach { (path, content) -> provider.files[path] = content.toByteArray() }
            provider.deltaPages = pass.pages.mapIndexed { i, page -> page to "c$n-$i" }
            engine().syncOnce()
        }
        val files =
            Files.walk(syncRoot).use { w ->
                w
                    .filter { Files.isRegularFile(it) }
                    .toList()
                    .associate { it.toString().removePrefix(syncRoot.toString()).replace('\\', '/') to String(Files.readAllBytes(it)) }
            }
        return Outcome(
            db.getAllEntries().map { stamp(it.path) to it.remoteId?.let(::stamp) }.sortedBy { it.first },
            files.mapKeys { stamp(it.key) }.toSortedMap(),
            provider.uploadedPaths.map(::stamp),
        )
    }

    // A conflict copy's name carries the time it was made; two runs agree on everything else.
    private fun stamp(path: String) = path.replace(Regex("""conflict-local-\d{8}T\d{6}"""), "conflict-local-T")

    private suspend fun bothAgree(passes: List<Pass>): Outcome {
        val buffered = go(streaming = false, passes)
        val streamed = go(streaming = true, passes)
        assertEquals(buffered, streamed, "streaming must match buffered")
        return streamed
    }

    private val tracked = Pass(mapOf("/p" to "AAA"), listOf(listOf(item("X", "/p", "AAA"))))
    private val idle = Pass(mapOf("/q" to "BBBB"), listOf(emptyList()))

    @Test
    fun `a tracked item the cloud renames and edits is moved and fetched anew, its old file is not uploaded (streaming matches buffered)`() =
        runTest {
            val moved = Pass(mapOf("/q" to "BBBB"), listOf(listOf(item("X", "/q", "BBBB"))))
            val outcome = bothAgree(listOf(tracked, moved, idle))
            assertEquals(listOf("/q" to "X"), outcome.rows)
            assertEquals(mapOf("/q" to "BBBB"), outcome.files)
            assertEquals(emptyList(), outcome.uploads)
        }

    @Test
    fun `a rename on one page and the edit on a later page of the same gather end as one moved item (streaming matches buffered)`() =
        runTest {
            val movedThenEdited =
                Pass(mapOf("/q" to "BBBB"), listOf(listOf(item("X", "/q", "AAA")), listOf(item("X", "/q", "BBBB"))))
            val outcome = bothAgree(listOf(tracked, movedThenEdited, idle))
            assertEquals(listOf("/q" to "X"), outcome.rows)
            assertEquals(mapOf("/q" to "BBBB"), outcome.files)
            assertEquals(emptyList(), outcome.uploads)
        }

    @Test
    fun `a rename on one pass and the edit on the next end as one moved item (streaming matches buffered)`() =
        runTest {
            val moved = Pass(mapOf("/q" to "AAA"), listOf(listOf(item("X", "/q", "AAA"))))
            val edited = Pass(mapOf("/q" to "BBBB"), listOf(listOf(item("X", "/q", "BBBB"))))
            val outcome = bothAgree(listOf(tracked, moved, edited, idle))
            assertEquals(listOf("/q" to "X"), outcome.rows)
            assertEquals(mapOf("/q" to "BBBB"), outcome.files)
            assertEquals(emptyList(), outcome.uploads)
        }

    @Test
    fun `an unuploaded local edit of the old name is kept as a conflict, not overwritten by the renamed item (streaming matches buffered)`() =
        runTest {
            val movedWhileEditedLocally =
                Pass(mapOf("/q" to "BBBB"), listOf(listOf(item("X", "/q", "BBBB")))) { root ->
                    val p = root.resolve("p")
                    Files.writeString(p, "LOCAL-EDIT")
                    Files.setLastModifiedTime(p, java.nio.file.attribute.FileTime.from(Instant.parse("2026-04-01T00:00:00Z")))
                }
            val outcome = bothAgree(listOf(tracked, movedWhileEditedLocally))
            assertTrue("LOCAL-EDIT" in outcome.files.values, "the local edit survives: ${outcome.files}")
            assertTrue("BBBB" in outcome.files.values, "the cloud's new content lands: ${outcome.files}")
            assertTrue("/p" !in outcome.files, "the old name is gone: ${outcome.files}")
        }

    private fun item(
        id: String,
        path: String,
        content: String,
    ) = CloudItem(
        id = id,
        name = path.substringAfterLast("/"),
        path = path,
        size = content.length.toLong(),
        isFolder = false,
        modified = Instant.parse("2026-03-28T12:00:00Z").plusSeconds(content.length.toLong()),
        created = Instant.parse("2026-03-28T10:00:00Z"),
        hash = "hash-$id-$content",
        mimeType = null,
    )
}
