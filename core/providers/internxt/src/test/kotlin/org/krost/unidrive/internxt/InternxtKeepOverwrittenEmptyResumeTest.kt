package org.krost.unidrive.internxt

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * #747 finding 4, the empty-file path: a keep_overwritten replace of an empty file archives the prior file by rename and then
 * creates the new one. A create that fails after the rename must not strand the retry: it goes on to the create.
 */
class InternxtKeepOverwrittenEmptyResumeTest {
    @Test
    fun `a retry after a failed create of an empty file does not archive the archive again`() =
        runTest {
            val tmp = TestScratch.directory("ud-keep-empty-resume-")
            try {
                val local = TestScratch.file(tmp, "src-", ".bin").also { Files.write(it, ByteArray(0)) }
                val drive = UploadDrive(bucketEntryId = "bucket-entry-new")
                drive.files += UploadDriveFile("old-1", "doc", "txt", 5, "bucket-entry-old", "root-folder-uuid")
                drive.createFault = { call, _ -> if (call == 1) HttpStatusCode.BadGateway else null }
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

    @Test
    fun `a retry after a create that landed with its answer lost adopts the empty file`() =
        runTest {
            val tmp = TestScratch.directory("ud-keep-empty-landed-")
            try {
                val local = TestScratch.file(tmp, "src-", ".bin").also { Files.write(it, ByteArray(0)) }
                val drive = UploadDrive(bucketEntryId = "bucket-entry-new")
                drive.files += UploadDriveFile("old-1", "doc", "txt", 5, "bucket-entry-old", "root-folder-uuid")
                drive.createFault = { call, _ -> if (call == 1) HttpStatusCode.GatewayTimeout else null }
                drive.storeBeforeFault = true
                val provider = rootedInternxtProvider(tmp, drive.engine, keepOverwritten = true)
                try {
                    assertFailsWith<Exception> { provider.upload(local, "/doc.txt", existingRemoteId = "old-1", onProgress = null) }
                    assertEquals(1, drive.files.count { it.plainName == "doc" && it.type == "txt" && it.size == 0L }, "the first create landed: ${drive.files}")
                    val item = provider.upload(local, "/doc.txt", existingRemoteId = "old-1", onProgress = null)
                    assertEquals("doc.txt", item.name)
                    assertEquals(1, drive.renames.size, "the retry archives nothing again, renames: ${drive.renames}")
                    assertEquals(2, drive.files.size, "the archive and the adopted empty file: ${drive.files}")
                    val third = provider.upload(local, "/doc.txt", existingRemoteId = item.id, onProgress = null)
                    assertEquals("doc.txt", third.name, "the marker is discarded, so a later replace starts clean")
                } finally {
                    provider.close()
                }
            } finally {
                tmp.toFile().deleteRecursively()
            }
        }
}
