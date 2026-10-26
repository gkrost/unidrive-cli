package org.krost.unidrive.internxt

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Candidate P4 of the audit: the folder-uuid cache must follow the drive. It is filled by every path walk and by every create,
 * and nothing emptied it: another device that renames `docs` to `old-docs` and creates a new `docs` left every listing and
 * upload under `/A/docs` going to the old folder until the process restarted. The change feed names the renamed folder, so the
 * poll that delivers it must drop what the cache holds for it.
 */
class InternxtFolderCacheStaleTest {
    @Test
    fun `a folder renamed elsewhere and replaced by a new one of the old name is walked afresh after the poll`() =
        runTest {
            val tmp = Files.createTempDirectory("ud-cache-stale-")
            try {
                val drive = UploadDrive()
                drive.folders += UploadDriveFolder("fa", "A", "root-folder-uuid")
                drive.folders += UploadDriveFolder("fd1", "docs", "fa")
                drive.files += UploadDriveFile("x0", "old", "txt", 1, "f0", "fd1")
                val provider = rootedInternxtProvider(tmp, drive.engine)
                try {
                    assertEquals(listOf("old.txt"), provider.listChildren("/A/docs").map { it.name })
                    // Another device: mv docs old-docs; mkdir docs; a file into the new docs.
                    drive.folders[drive.folders.indexOfFirst { it.uuid == "fd1" }] =
                        UploadDriveFolder("fd1", "old-docs", "fa", updatedAt = "2026-10-02T10:00:00.000Z")
                    drive.folders += UploadDriveFolder("fd2", "docs", "fa", updatedAt = "2026-10-02T10:00:00.000Z")
                    drive.files += UploadDriveFile("x1", "new", "txt", 1, "f1", "fd2", updatedAt = "2026-10-02T10:00:00.000Z")

                    provider.delta("2026-10-01T12:00:00Z", null, null)

                    assertEquals(listOf("new.txt"), provider.listChildren("/A/docs").map { it.name })
                } finally {
                    provider.close()
                }
            } finally {
                tmp.toFile().deleteRecursively()
            }
        }

    @Test
    fun `a batch create answered in another order still pairs each folder with its name`() =
        runTest {
            val tmp = Files.createTempDirectory("ud-cache-batch-")
            try {
                val drive = UploadDrive()
                drive.batchAnswerOrder = { it.reversed() }
                val provider = rootedInternxtProvider(tmp, drive.engine)
                try {
                    val made = provider.createFolders(listOf("/alpha", "/beta"))
                    assertEquals(listOf("alpha", "beta"), made.map { it.name })
                    assertEquals(drive.folders.first { it.plainName == "alpha" }.uuid, made[0].id)
                    // The walk that follows reaches the folders the names were given to.
                    val walked = provider.createFolder("/alpha/inner")
                    assertEquals(drive.folders.first { it.plainName == "alpha" }.uuid, drive.folders.first { it.plainName == "inner" }.parentUuid)
                    assertEquals("inner", walked.name)
                } finally {
                    provider.close()
                }
            } finally {
                tmp.toFile().deleteRecursively()
            }
        }
}
