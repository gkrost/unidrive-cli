package org.krost.unidrive.internxt

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ScanContext
import org.krost.unidrive.internxt.model.FolderContentResponse
import org.krost.unidrive.internxt.model.InternxtFile
import org.krost.unidrive.internxt.model.InternxtFolder
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#194: a file a gather rebuilds from its staged row must name the stored object its bytes are
 * (`CloudItem.contentId`, `<fileId>|<size>`), as the file a fresh listing delivers does. #193 carried the fileId in the
 * payload of the cursor hand-over; the offset listing, the tree walk, a cursor gather that holds its files and the rows
 * the hand-over takes without a payload rebuilt the file from the row alone and delivered it with no contentId, so the
 * row it reached had none and a rename of the file read as new bytes. A row staged by an earlier version keeps none.
 */
class InternxtResumedContentIdTest {
    private fun fileContentIds(page: DeltaPage) = page.items.filter { !it.isFolder }.associate { it.id to it.contentId }

    private fun filesWithIds() = fakeAccountFiles().map { it.copy(fileId = "fid-${it.uuid}") }

    @Test
    fun `a resumed offset listing gives every file the contentId a fresh one gives`() =
        runTest {
            // The server does not serve the sync listings, so the poll keeps the offset path (as in InternxtResumedVersionTokenTest).
            val drive = FakeDrive(fakeAccountFolders(), filesWithIds(), syncStatus = 404)
            val since = "2026-07-01T00:00:00.000Z"
            val staging = FakeStaging()
            val fresh = internxtProviderOn(drive.engine).delta(since, null, staging.context())
            assertTrue(staging.rows.values.any { !it.isFolder }, "files were staged")

            // The staged pages and the offsets are all there is: every file of this page comes from its staged row.
            val resumed = internxtProviderOn(drive.engine).delta(since, null, staging.context())

            assertTrue(fileContentIds(fresh).isNotEmpty())
            assertTrue(fileContentIds(fresh).all { (id, cid) -> cid == "fid-$id|1" }, "the fresh listing: ${fileContentIds(fresh)}")
            assertEquals(fileContentIds(fresh), fileContentIds(resumed))
        }

    @Test
    fun `a resumed cursor listing that holds its files gives every file the contentId a fresh one gives`() =
        runTest {
            val fresh = internxtProviderOn(FakeDrive(fakeAccountFolders(), filesWithIds()).engine).delta(null, null, FakeStaging().context())

            val drive = FakeDrive(fakeAccountFolders(), filesWithIds(), wall = "a2")
            val staging = FakeStaging()
            assertFailsWith<InternxtApiException> { internxtProviderOn(drive.engine).delta(null, null, staging.context()) }
            // The process dies once both streams are stored: every file of the resumed listing comes from the staged rows.
            staging.dieWhen = { raw -> decodeCursorMarker(raw)!!.let { it.folders.done && it.files.done } }
            assertFailsWith<StagingKilled> { internxtProviderOn(drive.engine).delta(null, null, staging.context()) }
            staging.dieWhen = null

            val resumed = internxtProviderOn(drive.engine).delta(null, null, staging.context())

            assertTrue(fileContentIds(fresh).values.all { it != null && it.startsWith("fid-") }, "the fresh listing: ${fileContentIds(fresh)}")
            assertEquals(fileContentIds(fresh), fileContentIds(resumed))
        }

    @Test
    fun `rows a holding cursor gather staged keep their contentId when a later gather hands them over`() =
        runTest {
            val fresh = internxtProviderOn(FakeDrive(fakeAccountFolders(), filesWithIds()).engine).delta(null, null, FakeStaging().context())

            // The first attempt has no read-back: its file rows carry no hand-over payload.
            val drive = FakeDrive(fakeAccountFolders(), filesWithIds(), wall = "a2")
            val staging = FakeStaging()
            assertFailsWith<InternxtApiException> { internxtProviderOn(drive.engine).delta(null, null, staging.context()) }
            staging.dieWhen = { raw -> decodeCursorMarker(raw)!!.let { it.folders.done && it.files.done } }
            assertFailsWith<StagingKilled> { internxtProviderOn(drive.engine).delta(null, null, staging.context()) }
            staging.dieWhen = null

            // The next one hands the files over from the staging: the rows it takes without a payload (GatherStream.fileOf).
            val pages = mutableListOf<DeltaPage>()
            val context = staging.context().withReadBack(staging.rows)
            var page = internxtProviderOn(drive.engine).delta(null, null, context)
            pages += page
            while (page.hasMore) {
                page = internxtProviderOn(drive.engine).delta(page.cursor, null, context)
                pages += page
            }

            val handedOver = pages.flatMap { it.items }.filter { !it.isFolder }.associate { it.id to it.contentId }
            assertEquals(fileContentIds(fresh), handedOver)
        }

    // The context with the engine's read-back of the staging: the rows in id order, as the engine keeps them.
    private fun ScanContext.withReadBack(rows: ConcurrentHashMap<String, CloudItem>): ScanContext =
        copy(
            stagedPage = { afterId, limit ->
                rows.values
                    .sortedBy { it.id }
                    .filter { afterId == null || it.id > afterId }
                    .take(limit)
                    .map { it.copy(path = "/${it.name}", created = null, mimeType = null) }
            },
        )

    // ---- the tree walk ----------------------------------------------------------------------------------------------

    private val log = LoggerFactory.getLogger(InternxtResumedContentIdTest::class.java)

    private fun file(
        uuid: String,
        name: String,
    ) = InternxtFile(uuid = uuid, fileId = "fid-$uuid", plainName = name, size = "7", modificationTime = "2026-01-01T10:00:00.000Z")

    private val tree =
        mapOf(
            "root" to FolderContentResponse(listOf(InternxtFolder(uuid = "a", plainName = "a"), InternxtFolder(uuid = "b", plainName = "b")), listOf(file("top", "top.txt"))),
            "a" to FolderContentResponse(emptyList(), listOf(file("fa", "a.txt"))),
            "b" to FolderContentResponse(emptyList(), listOf(file("fb", "b.txt"))),
        )

    @Test
    fun `a resumed tree walk gives every staged file the contentId the listing gave`() =
        runTest {
            val rows = LinkedHashMap<String, CloudItem>()
            var marker: String? = null
            assertFailsWith<IllegalStateException> {
                InternxtProvider.collectScopedInventoryImpl(
                    getContents = { uuid -> if (uuid == "b") error("outage") else tree.getValue(uuid) },
                    driveRootUuid = "root",
                    scopeRoots = listOf("/"),
                    scanned = AtomicInteger(),
                    skipped = AtomicInteger(),
                    log = log,
                    concurrency = 1,
                    resume = null,
                    persist = { staged, startsDone ->
                        for (r in staged) rows[r.id] = r
                        marker = treeMarker("2026-10-09T10:00:00Z", listOf("/"), startsDone)
                    },
                )
            }

            val resume = assertNotNull(treeResumeOf(marker, rows.values.toList(), listOf("/")))

            assertEquals(setOf("top", "fa"), resume.files.map { it.uuid }.toSet(), "the files the first attempt staged")
            for (f in resume.files) {
                assertEquals("fid-${f.uuid}|7", InternxtProvider.internxtContentId(f), "file ${f.uuid}")
            }
        }

    // ---- rows of an earlier version ---------------------------------------------------------------------------------

    @Test
    fun `a row staged by an earlier version, with no stored object, resumes with no contentId as before`() {
        val file = InternxtFile(uuid = "u", fileId = "fid-u", plainName = "a", type = "txt", size = "3", modificationTime = "2026-10-09T10:20:09.000Z")
        val legacy = with(InternxtProvider) { file.toStagedCloudItem("root") }.copy(hash = stagedFileTag(OFFSET_FILE_PREFIX, file), version = null)

        assertNull(InternxtProvider.internxtContentId(with(InternxtProvider) { legacy.toResumedFile() }))
    }
}
