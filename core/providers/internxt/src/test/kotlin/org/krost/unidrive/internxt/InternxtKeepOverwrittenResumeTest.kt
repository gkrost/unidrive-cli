package org.krost.unidrive.internxt

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * #747 finding 4: a keep_overwritten replace archives the prior file by rename and then creates the new one. A create that
 * fails after the rename must not strand the retry: the retry goes on to the create, it does not archive the archive.
 */
class InternxtKeepOverwrittenResumeTest {
    @Test
    fun `a retry after a failed create does not archive the archive again`() =
        runTest {
            val tmp = TestScratch.directory("ud-keep-resume-")
            try {
                val local = TestScratch.file(tmp, "src-", ".bin").also { Files.write(it, ByteArray(16) { b -> b.toByte() }) }
                val drive = UploadDrive(bucketEntryId = "bucket-entry-new")
                drive.files += UploadDriveFile("old-1", "doc", "txt", 5, "bucket-entry-old", "root-folder-uuid")
                drive.createFault = { call, _ -> if (call == 1) HttpStatusCode.BadRequest else null }
                val provider = rootedInternxtProvider(tmp, drive.engine, keepOverwritten = true)
                try {
                    assertFailsWith<Exception> { provider.upload(local, "/doc.txt", existingRemoteId = "old-1", onProgress = null) }
                    assertEquals(1, drive.renames.size, "the prior file is archived by the first attempt: ${drive.renames}")
                    val item = provider.upload(local, "/doc.txt", existingRemoteId = "old-1", onProgress = null)
                    assertEquals("doc.txt", item.name)
                    assertEquals(1, drive.renames.size, "the retry archives nothing again, renames: ${drive.renames}")
                    assertEquals(1, drive.files.count { it.plainName == "doc" && it.type == "txt" }, "one file holds the name: ${drive.files}")
                    assertEquals(2, drive.files.size, "the archive and the new file: ${drive.files}")
                } finally {
                    provider.close()
                }
            } finally {
                tmp.toFile().deleteRecursively()
            }
        }
}
