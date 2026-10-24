package org.krost.unidrive.internxt

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.internxt.model.InternxtFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * #1039: the version token of a file ("<uuid>|<modificationTime>|<size>") is built from the string the server sent. A
 * resumed cursor or offset listing rebuilt the file from the staged row's `Instant` and its `toString()`, which drops
 * a zero fraction (`...09Z` for `...09.000Z`): the stored `remoteHash` of such a file differed from every later fresh
 * token, and the first edit through the mount (which forwards the hash as If-Match) conflicted with no remote change.
 * The fake drive sends `2026-07-01T10:00:00.000Z` for every row: a time with ms == 0.
 */
class InternxtResumedVersionTokenTest {
    private val serverTime = "2026-07-01T10:00:00.000Z"

    private fun fileHashes(page: DeltaPage) = page.items.filter { !it.isFolder }.associate { it.id to it.hash }

    @Test
    fun `a resumed cursor listing gives the files the version token a fresh one gives`() =
        runTest {
            val fresh = internxtProviderOn(FakeDrive(fakeAccountFolders(), fakeAccountFiles()).engine).delta(null, null, FakeStaging().context())

            val drive = FakeDrive(fakeAccountFolders(), fakeAccountFiles(), wall = "a2")
            val staging = FakeStaging()
            assertFailsWith<InternxtApiException> { internxtProviderOn(drive.engine).delta(null, null, staging.context()) }
            // The process dies once both streams are stored: every file of the resumed listing comes from the staged rows.
            staging.dieWhen = { raw -> decodeCursorMarker(raw)!!.let { it.folders.done && it.files.done } }
            assertFailsWith<StagingKilled> { internxtProviderOn(drive.engine).delta(null, null, staging.context()) }
            staging.dieWhen = null
            assertTrue(staging.rows.values.any { !it.isFolder }, "files were staged")

            val resumed = internxtProviderOn(drive.engine).delta(null, null, staging.context())

            assertEquals(fileHashes(fresh), fileHashes(resumed))
            assertTrue(fileHashes(resumed).values.all { "|$serverTime|" in it!! })
        }

    @Test
    fun `a resumed offset listing gives the files the version token a fresh one gives`() =
        runTest {
            // The poll rides the sync listings (#312); this test is about the offset listing's resume,
            // so the server does not serve them and the gather keeps the offset path.
            val drive = FakeDrive(fakeAccountFolders(), fakeAccountFiles(), syncStatus = 404)
            val since = "2026-07-01T00:00:00.000Z"
            val staging = FakeStaging()
            val fresh = internxtProviderOn(drive.engine).delta(since, null, staging.context())
            assertTrue(staging.rows.values.any { !it.isFolder }, "files were staged")

            // The staged pages and the offsets are all there is: the fake answers nothing past them.
            val resumed = internxtProviderOn(drive.engine).delta(since, null, staging.context())

            assertEquals(fileHashes(fresh), fileHashes(resumed))
            assertTrue(fileHashes(resumed).values.all { "|$serverTime|" in it!! })
        }

    private fun resumedFromTag(
        file: InternxtFile,
        tag: String?,
    ): InternxtFile =
        with(InternxtProvider) { file.toStagedCloudItem("root").copy(hash = tag).toResumedFile() }

    @Test
    fun `the tag of a staged file row gives back the time string as the server sent it`() {
        for (raw in listOf("2026-10-09T10:20:09.000Z", "2026-10-09T10:20:09.120Z", "2026-10-09T10:20:09.123Z", null)) {
            val file = InternxtFile(uuid = "u", plainName = "a", type = "txt", size = "3", modificationTime = raw)
            for (prefix in listOf(CURSOR_FILE_PREFIX, OFFSET_FILE_PREFIX, TREE_FILE_PREFIX)) {
                val resumed = resumedFromTag(file, stagedFileTag(prefix, file))
                assertEquals(InternxtProvider.internxtVersionToken(file), InternxtProvider.internxtVersionToken(resumed), "$prefix $raw")
            }
        }
    }

    @Test
    fun `a row staged without the tag by an earlier version still resumes from its instant`() {
        val file = InternxtFile(uuid = "u", plainName = "a", type = "txt", size = "3", modificationTime = "2026-10-09T10:20:09.123Z")
        assertEquals("2026-10-09T10:20:09.123Z", resumedFromTag(file, null).modificationTime)
        assertEquals("2026-10-09T10:20:09.123Z", resumedFromTag(file, CURSOR_STAGED_TAG).modificationTime)
    }

    @Test
    fun `the file tags are rows of their own listing and no other`() {
        val file = InternxtFile(uuid = "u", plainName = "a", type = "txt", size = "3", modificationTime = serverTime)
        val cursorRow = with(InternxtProvider) { file.toStagedCloudItem("root") }.copy(hash = stagedFileTag(CURSOR_FILE_PREFIX, file))
        val offsetRow = cursorRow.copy(hash = stagedFileTag(OFFSET_FILE_PREFIX, file))
        assertTrue(isCursorStaged(cursorRow) && !isTreeStaged(cursorRow))
        assertTrue(!isCursorStaged(offsetRow) && !isTreeStaged(offsetRow), "an offset row is neither a cursor nor a tree row")
        assertTrue(isCursorStaged(cursorRow.copy(hash = CURSOR_STAGED_TAG)), "the tag of an earlier version still counts")
        assertTrue(!isCursorStaged(cursorRow.copy(hash = CURSOR_ANCESTOR_TAG)), "an ancestor-only record is no live row")
    }
}
