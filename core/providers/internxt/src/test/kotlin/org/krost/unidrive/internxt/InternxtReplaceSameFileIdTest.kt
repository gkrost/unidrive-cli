package org.krost.unidrive.internxt

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.PermanentUploadFailureException
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
class InternxtReplaceSameFileIdTest {
    private val content = "hello world"

    private fun withDrive(block: suspend (UploadDrive, InternxtProvider, Path) -> Unit) =
        runTest {
            val tmp = TestScratch.directory("ud-replace-same-")
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

    @Test
    fun `a repeated replace answered 400 same file id is adopted when the meta shows the uploaded object`() =
        withDrive { drive, provider, tmp ->
            // The first PUT commits and its answer is a 502; the ladder repeats it and the server refuses the repeat.
            drive.replaceFault = { call -> HttpStatusCode.BadGateway.takeIf { call == 1 } }
            drive.storeBeforeReplaceFault = true

            val item = provider.upload(local(tmp), "/f.txt", existingRemoteId = "file-r", onProgress = null)

            assertEquals("file-r", item.id)
            assertEquals("bucket-entry-1", drive.files.single().fileId)
            assertEquals(listOf<String?>("bucket-entry-1", "bucket-entry-1"), drive.replaceFileIds.toList())
        }

    @Test
    fun `a 400 same file id whose meta shows another object stays a refusal`() =
        withDrive { drive, provider, tmp ->
            drive.files[0] = drive.files[0].copy(fileId = "bucket-entry-1", size = 99L) // same object id, other size

            assertFailsWith<PermanentUploadFailureException> { provider.upload(local(tmp), "/f.txt", existingRemoteId = "file-r", onProgress = null) }
        }
}
