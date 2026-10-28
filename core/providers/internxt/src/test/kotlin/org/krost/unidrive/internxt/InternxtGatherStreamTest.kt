package org.krost.unidrive.internxt

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ScanContext
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#35: a full gather through the cursor listings must not hold the account. With a staging it can read
 * back (ScanContext.stagedPage) it stages each page as it arrives, keeps the folders, and hands the files over in chunks
 * built from the staged rows; without one it behaves as it always did. The gather is measured by what it holds (the most
 * files one chunk carried, the chunks the engine saw) and by what it returns (the same items as the gather that holds
 * everything).
 */
class InternxtGatherStreamTest {
    private class Killed : RuntimeException("killed by the test")

    /**
     * The engine's staging as a test double, with the read-back the engine offers: rows keyed by id (a row staged twice is
     * one row), every marker, and the pages in id order. [probe] is read at each stored page, so a test can tell how far the
     * listing had come when it was stored.
     */
    private class ReadBackStaging(
        var dieAfterPages: Int? = null,
        var probe: () -> Int = { 0 },
    ) {
        val rows = ConcurrentHashMap<String, CloudItem>()
        val markers: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val probes: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        private val pages = AtomicInteger()

        val persistPage: suspend (List<CloudItem>, String) -> Unit = { items, marker ->
            items.forEach { rows[it.id] = it }
            markers += marker
            probes += probe()
            val die = dieAfterPages
            if (die != null && pages.incrementAndGet() >= die) throw Killed()
        }

        // What the engine's load returns of a stored row: the cloud identity, the tags and the version, no path of its own.
        private fun loaded(row: CloudItem) = row.copy(path = "/${row.name}", created = null, mimeType = null)

        private fun page(
            afterId: String?,
            limit: Int,
        ): List<CloudItem> =
            rows.values
                .sortedBy { it.id }
                .filter { afterId == null || it.id > afterId }
                .take(limit)
                .map(::loaded)

        /** The context of the next attempt: the last marker, the rows stored so far, and the read-back. */
        fun context(readBack: Boolean = true): ScanContext =
            ScanContext(
                resumeMarker = markers.lastOrNull(),
                resumedItems = rows.values.sortedBy { it.id }.map(::loaded),
                persistPage = persistPage,
                stagedPage = if (readBack) ::page else null,
            )
    }

    /** What the engine does with a provider: the first call, then the cursor of each page that says there is more. */
    private suspend fun gather(
        provider: InternxtProvider,
        context: ScanContext,
    ): List<DeltaPage> {
        val pages = mutableListOf<DeltaPage>()
        var page = provider.delta(null, null, context)
        pages += page
        while (page.hasMore) {
            page = provider.delta(page.cursor, null, context)
            pages += page
        }
        return pages
    }

    private fun List<DeltaPage>.items(): List<CloudItem> = flatMap { it.items }

    private fun byId(items: List<CloudItem>): Map<String, CloudItem> = items.associateBy { it.id }

    // ---- a large account --------------------------------------------------------------------------------------------

    private val folderCount = 30
    private val fileCount = 12_000
    private val filePageSize = 500
    private val chunk = 400

    // 30 folders on 3 pages and 12 000 files on 24 pages; a file hangs in a folder, or at the root. The files come in uuid
    // order, the folders in no order the tree has.
    private fun largeDrive(): DriveMock {
        val folders = (0 until folderCount).map { folder("d%02d".format(it), "D%02d".format(it), ROOT) }.reversed()
        val files =
            (0 until fileCount).map { i ->
                file("f%05d".format(i), "n%05d".format(i), "txt", if (i % 7 == 6) ROOT else "d%02d".format(i % folderCount), size = i % 1000)
            }
        return DriveMock(folders.chunked(10), files.chunked(filePageSize))
    }

    private fun expectedPath(i: Int): String = if (i % 7 == 6) "/n%05d.txt".format(i) else "/D%02d/n%05d.txt".format(i % folderCount, i)

    @Test
    fun `a large account is staged page by page and handed over in chunks, no chunk holding more than the chunk size`() =
        runTest {
            val drive = largeDrive()
            val staging = ReadBackStaging(probe = { drive.seen.size })
            val provider = provider(drive.engine)
            provider.gatherChunkRows = chunk

            val pages = gather(provider, staging.context())

            val files = pages.items().filter { !it.isFolder }
            assertEquals(fileCount, files.size, "every file once")
            assertEquals(fileCount, files.map { it.id }.toSet().size)
            for (i in 0 until fileCount) assertEquals(expectedPath(i), byId(files)["f%05d".format(i)]?.path, "path of file $i")
            assertEquals(folderCount, pages.items().count { it.isFolder })
            assertTrue(pages.last().complete)
            assertFalse(pages.last().hasMore)
            assertTrue(pages.dropLast(1).all { it.hasMore }, "only the last page ends the gather")

            // The files were handed over in chunks of at most the chunk size, not in one piece.
            assertTrue(pages.size >= fileCount / chunk, "chunks: ${pages.size}")
            val fileChunks = pages.map { page -> page.items.count { !it.isFolder } }
            assertTrue(fileChunks.all { it <= chunk }, "no chunk is larger than $chunk files: ${fileChunks.max()}")
            assertTrue(provider.gatherPeakFileRows in 1..chunk, "the provider held at most $chunk files at once: ${provider.gatherPeakFileRows}")

            // Every page was stored as it arrived: the first page was stored before the last page was asked for.
            val requests = drive.seen.size
            assertEquals(3 + 24, requests, "one request per page, no more after the last")
            assertTrue(staging.probes.first() < requests, "the first page was stored while the listing was still running: ${staging.probes.first()} of $requests")
            assertEquals(requests, staging.markers.size, "one stored page and one marker per request")
            assertEquals(fileCount + folderCount, staging.rows.size)
        }

    @Test
    fun `the cursor of a chunk is not a poll cursor and the last page hands the engine the cursor of the listing`() =
        runTest {
            val drive = largeDrive()
            val staging = ReadBackStaging()
            val provider = provider(drive.engine)
            provider.gatherChunkRows = chunk

            val pages = gather(provider, staging.context())

            assertTrue(pages.dropLast(1).all { it.cursor.startsWith(GATHER_CURSOR_PREFIX) })
            val marker = decodeCursorMarker(staging.markers.last())
            assertNotNull(marker)
            assertEquals(marker.startedAt, pages.last().cursor, "the listing's cursor is the instant it began, as without the hand-over")
        }

    // ---- the result is the result -----------------------------------------------------------------------------------

    // Rows with the fields a thin fixture does not exercise: a creation time, a size the server did not send, a file at the
    // root, one that names no folder, a name with dots, and an item that changes while the listing runs.
    private fun fullFile(
        uuid: String,
        name: String,
        type: String?,
        folder: String?,
        size: String?,
        creation: String?,
        modification: String,
    ) = """{"uuid":"$uuid","fileId":"fid-$uuid","plainName":"$name",""" + (type?.let { "\"type\":\"$it\"," } ?: "") +
        (size?.let { "\"size\":\"$it\"," } ?: "") + (folder?.let { "\"folderUuid\":\"$it\"," } ?: "") +
        (creation?.let { "\"creationTime\":\"$it\"," } ?: "") +
        """"bucket":"b","status":"EXISTS","modificationTime":"$modification","updatedAt":"2026-10-01T10:00:00.456Z"}"""

    private fun smallDrive() =
        DriveMock(
            standardFolderPages,
            listOf(
                listOf(
                    fullFile("g1", "top", "txt", ROOT, "7", "2025-01-02T03:04:05.000Z", "2026-09-01T10:00:00.000Z"),
                    fullFile("g2", "archive.tar", "gz", "d1", "9", null, "2026-09-01T10:00:09Z"),
                ),
                listOf(
                    fullFile("g3", "nosize", "bin", "d2", null, "2024-12-31T23:59:59.999Z", "2026-09-02T10:00:00.5Z"),
                    fullFile("g4", "nofolder", "txt", null, "1", null, "2026-09-03T10:00:00Z"),
                    fullFile("g5", "typeless", null, "d4", "2", null, "2026-09-03T10:00:00.250Z"),
                ),
                listOf(
                    fullFile("g1", "top", "txt", ROOT, "70", "2025-01-02T03:04:05.000Z", "2026-09-04T10:00:00.000Z"),
                ),
            ),
        )

    @Test
    fun `on a small account the hand-over gives exactly the items the gather that holds everything gives`() =
        runTest {
            val held = provider(smallDrive().engine).delta(null, null, quiet())
            val staging = ReadBackStaging()

            val pages = gather(provider(smallDrive().engine), staging.context())

            assertEquals(1, pages.size, "a small account is one page, as before")
            val page = pages.single()
            assertFalse(page.hasMore)
            assertEquals(held.items.sortedBy { it.id }, page.items.sortedBy { it.id }, "the items, field by field")
            assertEquals(held.complete, page.complete)
            assertEquals(held.unreachableIds, page.unreachableIds)
            assertEquals(held.skippedPaths, page.skippedPaths)
            assertEquals(70L.toString(), page.items.single { it.id == "g1" }.hash?.substringAfterLast('|'), "the later version of a file that changed wins")
            assertEquals(held.items.map { it.id }.toSet().size, page.items.size, "every uuid once")
        }

    @Test
    fun `without a read-back the gather holds its files and answers in one page as it always did`() =
        runTest {
            val staging = ReadBackStaging()
            val provider = provider(DriveMock().engine)

            val pages = gather(provider, staging.context(readBack = false))

            assertEquals(1, pages.size)
            assertEquals(standardPaths, pages.single().items.map { it.path }.toSet())
            assertEquals(0, provider.gatherPeakFileRows, "nothing was handed over from the staging")
            assertTrue(staging.rows.values.filter { !it.isFolder }.all { it.version == null }, "a gather that holds its files stages no payload")
        }

    // ---- interruption -----------------------------------------------------------------------------------------------

    @Test
    fun `a listing killed part way resumes from the last stored page and gives the whole result, nothing skipped or doubled`() =
        runTest {
            val clean = gather(provider(largeDrive().engine).also { it.gatherChunkRows = chunk }, ReadBackStaging().context()).items()

            val first = largeDrive()
            val staging = ReadBackStaging(dieAfterPages = 13)
            runCatching { gather(provider(first.engine).also { it.gatherChunkRows = chunk }, staging.context()) }
                .also { assertTrue(it.exceptionOrNull() is Killed, "the daemon died after a stored page: ${it.exceptionOrNull()}") }
            val storedBeforeDeath = staging.rows.size
            assertTrue(storedBeforeDeath in 1 until fileCount + folderCount, "part of the account was staged: $storedBeforeDeath")
            staging.dieAfterPages = null

            val second = largeDrive()
            val resumed = gather(provider(second.engine).also { it.gatherChunkRows = chunk }, staging.context()).items()

            assertEquals(clean.size, resumed.size, "no item skipped or doubled")
            // The files come back from the staged payload, whole. A folder comes back as the staging keeps it (no top-level parent
            // id): the resume of the folders is not what is measured here.
            assertEquals(clean.filter { !it.isFolder }.sortedBy { it.id }, resumed.filter { !it.isFolder }.sortedBy { it.id })
            assertEquals(clean.filter { it.isFolder }.map(::essence).toSet(), resumed.filter { it.isFolder }.map(::essence).toSet())
            assertTrue(second.seen.size < 3 + 24, "the second attempt asked for the later pages only: ${second.seen.size}")
            assertEquals(fileCount + folderCount, staging.rows.size)
        }

    @Test
    fun `a gather that stops between two chunks is handed over again from the first chunk by the next attempt`() =
        runTest {
            val staging = ReadBackStaging()
            val provider = provider(largeDrive().engine).also { it.gatherChunkRows = chunk }
            val ctx = staging.context()
            val firstChunk = provider.delta(null, null, ctx)
            assertTrue(firstChunk.hasMore)
            val firstChunkIds = firstChunk.items.map { it.id }.toSet()
            // The engine is cancelled here: the next attempt has a provider of its own and the staging as it stands.

            val second = largeDrive()
            val all = gather(provider(second.engine).also { it.gatherChunkRows = chunk }, staging.context()).items()

            assertEquals(fileCount + folderCount, all.size)
            assertTrue(firstChunkIds.all { id -> all.any { it.id == id } }, "what the first chunk carried is handed over again")
            assertEquals(0, second.seen.size, "both streams were done: the resume asks for no page")
        }

    @Test
    fun `a continuation this provider does not hold lists the drive again from the stored cursors`() =
        runTest {
            val staging = ReadBackStaging()
            gather(provider(largeDrive().engine).also { it.gatherChunkRows = chunk }, staging.context())
            val drive = largeDrive()
            val provider = provider(drive.engine).also { it.gatherChunkRows = chunk }

            val pages = mutableListOf(provider.delta(GATHER_CURSOR_PREFIX + "another-provider", null, staging.context()))
            while (pages.last().hasMore) pages += provider.delta(pages.last().cursor, null, staging.context())

            assertEquals(fileCount + folderCount, pages.items().size)
            assertEquals(0, drive.seen.size, "the stored cursors say both streams are done")
        }

    @Test
    fun `rows an abandoned listing left in the scan are not handed over`() =
        runTest {
            val staging = ReadBackStaging()
            // A listing of another instant staged a file that the drive no longer has, and was not resumed (its marker is gone).
            val ghost =
                CloudItem("ghost", "ghost.txt", "/ghost.txt", 5, false, null, null, "cursor-listing|file|", null, parentId = null, version = stagedFilePayload("2020-01-01T00:00:00Z", fileOfJson("ghost")))
            staging.rows["ghost"] = ghost

            val pages = gather(provider(DriveMock().engine), staging.context())

            assertEquals(standardPaths, pages.items().map { it.path }.toSet())
            assertNull(pages.items().firstOrNull { it.id == "ghost" })
        }

    private fun fileOfJson(uuid: String) =
        org.krost.unidrive.internxt.model.InternxtJson.decodeFromString(
            org.krost.unidrive.internxt.model.InternxtFile.serializer(),
            file(uuid, "ghost", "txt", ROOT),
        )
}
