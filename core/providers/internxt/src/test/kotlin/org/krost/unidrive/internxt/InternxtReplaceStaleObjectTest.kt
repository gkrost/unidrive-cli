package org.krost.unidrive.internxt

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.PermanentUploadFailureException
import org.krost.unidrive.ProviderException
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Live findings (2026-11-18, Internxt test account) on the replace (`PUT /files/{uuid}`):
 *
 * - #747 finding 5: a repeat of a replace that already committed is answered 400 "New file ID is the same as the old file
 *   ID". When the file's meta shows the uploaded object (same fileId and size) the replace has landed and is adopted.
 * - The server accepts a replace naming an OLDER object (200), and the file is then left without one. A replace only ever
 *   names the object this upload finished; a finished object resumed from an earlier attempt that the file no longer
 *   names and the bridge no longer holds is never sent, and a replace whose answer and meta do not show the new object
 *   does not count as done.
 */
class InternxtReplaceStaleObjectTest {
    private val content = "hello world"

    private fun withDrive(block: suspend (UploadDrive, InternxtProvider, Path) -> Unit) =
        runTest {
            val tmp = TestScratch.directory("ud-replace-stale-")
            try {
                val drive = UploadDrive()
                drive.files += UploadDriveFile("file-r", "f", "txt", 3L, "old-entry", "root-folder-uuid")
                val provider = rootedInternxtProvider(tmp, drive.engine)
                try {
                    block(drive, provider, tmp)
                } finally {
                    provider.close()
                }
            } finally {
                tmp.toFile().deleteRecursively()
            }
        }

    private fun local(tmp: Path): Path = tmp.resolve("f.txt").also { if (!Files.exists(it)) Files.writeString(it, content) }

    private fun tombstoneCount(tmp: Path): Long {
        val dir = tmp.resolve("upload-tombstones")
        return if (Files.exists(dir)) Files.list(dir).use { s -> s.filter { it.toString().endsWith(".json") }.count() } else 0
    }

    @Test
    fun `a resumed finished upload that already replaced the file is adopted without a second replace`() =
        withDrive { drive, provider, tmp ->
            // Attempt 1: the replace commits, the answer is lost as a refusal; the tombstone stays at FINISH_DONE.
            drive.replaceFault = { call -> HttpStatusCode.UnprocessableEntity.takeIf { call == 1 } }
            drive.storeBeforeReplaceFault = true
            assertFailsWith<PermanentUploadFailureException> { provider.upload(local(tmp), "/f.txt", existingRemoteId = "file-r", onProgress = null) }
            drive.replaceFault = null

            val item = provider.upload(local(tmp), "/f.txt", existingRemoteId = "file-r", onProgress = null)

            assertEquals("file-r", item.id)
            assertEquals(1, drive.replaceFileIds.size, "the committed replace is not sent again: ${drive.replaceFileIds}")
        }

    @Test
    fun `a resumed finished object the file no longer names and the bridge no longer holds is never sent`() =
        withDrive { drive, provider, tmp ->
            drive.replaceFault = { call -> HttpStatusCode.UnprocessableEntity.takeIf { call == 1 } }
            drive.storeBeforeReplaceFault = true
            assertFailsWith<PermanentUploadFailureException> { provider.upload(local(tmp), "/f.txt", existingRemoteId = "file-r", onProgress = null) }
            // Another client replaced the file since; the server dropped our object with it.
            drive.files[0] = drive.files[0].copy(fileId = "newer-entry", size = 5L)
            drive.bridgeObjects = mutableSetOf("newer-entry")
            drive.replaceFault = null

            val failure = assertFailsWith<ProviderException> { provider.upload(local(tmp), "/f.txt", existingRemoteId = "file-r", onProgress = null) }

            assertFalse(failure is PermanentUploadFailureException, "a fresh upload can go: not permanent (${failure::class.simpleName})")
            assertEquals(1, drive.replaceFileIds.size, "the stale object is never sent: ${drive.replaceFileIds}")
            assertEquals("newer-entry", drive.files.single().fileId, "the file keeps its object")
            assertEquals(0L, tombstoneCount(tmp), "the stale resume state is dropped, the next attempt uploads afresh")
        }

    @Test
    fun `a replace whose answer and meta keep the old object does not count as done`() =
        withDrive { drive, provider, tmp ->
            drive.replaceKeepsOldFileId = true

            val failure = assertFailsWith<ProviderException> { provider.upload(local(tmp), "/f.txt", existingRemoteId = "file-r", onProgress = null) }

            assertTrue("old-entry" in failure.message.orEmpty(), "names what the server reports: ${failure.message}")
        }
}
