package org.krost.unidrive.internxt

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.ScanContext
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The folder walk that takes over from a cursor listing which failed server-side stores its progress, so a walk that is
 * stopped part way resumes from its staged rows, in the same process and after a restart. Its marker carries the cursor
 * listing's marker unchanged, and a walk that cannot be resumed gives the scan back to the cursor listing. Served by the
 * fake drive of the listing tests ([DriveMock]).
 */
class InternxtWalkHandoverTest {
    private class Killed : RuntimeException("killed by the test")

    // The engine's staging as a test double: rows upserted by id, every marker kept, and the context of the next attempt
    // with the last marker and the rows as staging returns them (cloud identity only). It dies after storing the first
    // page whose marker [dieWhen] is true for, the way a crashed daemon stops.
    private class FakeStaging {
        val rows = ConcurrentHashMap<String, CloudItem>()
        val markers: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var dieWhen: ((String) -> Boolean)? = null

        val persistPage: suspend (List<CloudItem>, String) -> Unit = { items, marker ->
            items.forEach { rows[it.id] = it }
            markers += marker
            if (dieWhen?.invoke(marker) == true) {
                dieWhen = null
                throw Killed()
            }
        }

        fun context(): ScanContext {
            val staged = rows.values.sortedBy { it.id }.map { it.copy(path = "/${it.name}", created = null, mimeType = null) }
            return ScanContext(markers.lastOrNull(), staged, persistPage)
        }
    }

    // A drive whose cursor listing fails server-side (files, page 2) and whose walk has two folders below the root,
    // so a walk can be stopped part way: the root listed and stored, its folders not yet.
    private val twoFolderWalk =
        mapOf(
            "root" to
                """{"children":[{"uuid":"w","plainName":"w","status":"EXISTS"},{"uuid":"v","plainName":"v","status":"EXISTS"}],""" +
                    """"files":[{"uuid":"wt","plainName":"wt","type":"txt","size":"9","status":"EXISTS"}]}""",
            "w" to """{"children":[],"files":[{"uuid":"wf","plainName":"wf","type":"txt","size":"3","status":"EXISTS"}]}""",
            "v" to """{"children":[],"files":[]}""",
        )
    private val twoFolderWalkPaths = setOf("/w", "/v", "/wt.txt", "/w/wf.txt")

    private fun walledDrive() = DriveMock(tree = twoFolderWalk, failure = { s, page -> if (s == "files" && page == 2) Failure.Status(524) else null })

    private fun walkContentRequests(requests: List<String>) =
        requests.filter { it.contains("/folders/content/") }.map { it.substringAfterLast('/').substringBefore('?') }

    // The walk that took over from a failed cursor listing is killed after it stored the root's listing.
    private suspend fun killedHandover(
        staging: FakeStaging,
        provider: InternxtProvider,
        drive: DriveMock,
    ) {
        staging.dieWhen = { decodeTreeMarker(it) != null }
        assertFailsWith<Killed> { provider.delta(null, null, staging.context()) }
        assertEquals(listOf("root"), walkContentRequests(drive.other), "the walk had listed the root when it was killed")
    }

    @Test
    fun `a walk that took over from a failed cursor listing resumes from its staged rows after a restart, the cursor marker kept`() =
        runTest {
            val staging = FakeStaging()
            val walled = walledDrive()
            killedHandover(staging, provider(walled.engine), walled)
            val walkMarker = assertNotNull(decodeTreeMarker(staging.markers.last()), "the walk stored its own marker")
            val cursorAtHandover = assertNotNull(decodeCursorMarker(walkMarker.takenOverFrom), "and carries the cursor marker")
            assertEquals(walled.cursorOf("files", 2), cursorAtHandover.files.cursor)

            // A new process: no listing-unavailable window. The stored scan is the walk's, so the walk goes on.
            val restarted = walledDrive()
            val page = provider(restarted.engine).delta(null, null, staging.context())

            assertEquals(twoFolderWalkPaths, page.items.map { it.path }.toSet())
            assertTrue(page.complete)
            assertTrue(restarted.seen.isEmpty(), "the cursor listing is not asked again: ${restarted.seen.map { it.query }}")
            assertEquals(setOf("w", "v"), walkContentRequests(restarted.other).toSet(), "only the folders still outstanding are listed")
            assertEquals(walkMarker.startedAt, page.cursor, "the resumed walk keeps the instant the walk began")
            val last = assertNotNull(decodeTreeMarker(staging.markers.last()))
            assertEquals(walkMarker.takenOverFrom, last.takenOverFrom, "the cursor marker is still intact")
            assertTrue(staging.rows.values.any(::isCursorStaged), "and so are the cursor listing's rows")
        }

    @Test
    fun `a walk that took over from a failed cursor listing resumes in the same process too`() =
        runTest {
            val staging = FakeStaging()
            val walled = walledDrive()
            val provider = provider(walled.engine)
            killedHandover(staging, provider, walled)
            val requestsBefore = walled.other.size

            val page = provider.delta(null, null, staging.context())

            assertEquals(twoFolderWalkPaths, page.items.map { it.path }.toSet())
            assertEquals(setOf("w", "v"), walkContentRequests(walled.other.drop(requestsBefore)).toSet(), "only the outstanding folders")
            assertNotNull(decodeCursorMarker(assertNotNull(decodeTreeMarker(staging.markers.last())).takenOverFrom), "the cursor marker is kept")
        }

    @Test
    fun `a walk that cannot resume hands the scan back to the cursor listing it took over from`() =
        runTest {
            val staging = FakeStaging()
            val walled = walledDrive()
            killedHandover(staging, provider(walled.engine), walled)
            // The rows the walk staged are gone while its marker claims a listed root: the walk cannot trust its marker.
            // The cursor listing's marker it carries is still good, so the cursor listing goes on from the cursor of page 2.
            staging.rows.values.removeIf(::isTreeStaged)

            val healthy = DriveMock()
            val page = provider(healthy.engine).delta(null, null, staging.context())

            assertEquals(standardPaths, page.items.map { it.path }.toSet())
            assertTrue(page.complete)
            assertEquals(listOf(2, 3), healthy.seen.filter { it.stream == "files" }.map { healthy.pageOf("files", it.cursor) }, "files go on from the cursor of page 2")
            assertTrue(healthy.other.isEmpty(), "no walk: ${healthy.other}")
        }
}
