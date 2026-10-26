package org.krost.unidrive.internxt

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Candidate P5 of the audit: with keep_overwritten on, a replace renames the prior file to an archive name and creates the new
 * file under the original name. That create must be as safe to repeat as any other create: when it answers 409 because the
 * file did land (the commit raced the answer, or the first answer was lost), the landed file is the one wanted, and the call
 * must not fail with the prior content already archived.
 */
class InternxtKeepOverwrittenRetryTest {
    @Test
    fun `a create that finds its own file already landed after the archive rename adopts it`() =
        runTest {
            val tmp = Files.createTempDirectory("ud-keep-retry-")
            try {
                val local = Files.createTempFile(tmp, "src-", ".bin").also { Files.write(it, ByteArray(16) { b -> b.toByte() }) }
                val drive = UploadDrive(bucketEntryId = "bucket-entry-new")
                drive.files += UploadDriveFile("old-1", "doc", "txt", 5, "bucket-entry-old", "root-folder-uuid")
                // The first create commits server-side but answers 409 (a second create of the same file met the first).
                drive.createFault = { call, _ -> if (call == 1) HttpStatusCode.Conflict else null }
                drive.storeBeforeFault = true
                val provider = rootedInternxtProvider(tmp, drive.engine, keepOverwritten = true)
                try {
                    val item = provider.upload(local, "/doc.txt", existingRemoteId = "old-1", onProgress = null)
                    assertEquals("doc.txt", item.name)
                    assertEquals(1, drive.renames.size, "the prior file is archived once, renames: ${drive.renames}")
                    assertEquals(
                        1,
                        drive.files.count { it.plainName == "doc" && it.type == "txt" },
                        "exactly one file holds the name afterwards: ${drive.files}",
                    )
                    assertEquals(2, drive.files.size, "the archive and the new file: ${drive.files}")
                } finally {
                    provider.close()
                }
            } finally {
                tmp.toFile().deleteRecursively()
            }
        }
}
