package org.krost.unidrive.internxt

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.krost.unidrive.testing.TestScratch

/** A folder create answered 409 never settles on a twin whose status is DELETED either; only a live twin counts. */
class InternxtCreateFolderDeletedTwinTest {
    private fun withDrive(block: suspend (UploadDrive, InternxtProvider) -> Unit) =
        runTest {
            val tmp = TestScratch.directory("ud-folder-deleted-twin-")
            try {
                val drive = UploadDrive()
                val provider = rootedInternxtProvider(tmp, drive.engine)
                try {
                    block(drive, provider)
                } finally {
                    provider.close()
                }
            } finally {
                tmp.toFile().deleteRecursively()
            }
        }

    @Test
    fun `a 409 whose only twin is deleted is rethrown`() =
        withDrive { drive, provider ->
            drive.folders += UploadDriveFolder("deleted-docs", "docs", "root-folder-uuid", status = "DELETED")

            val failure = assertFailsWith<InternxtApiException> { provider.createFolder("/docs") }

            assertEquals(409, failure.statusCode)
        }

    @Test
    fun `a 409 with a deleted twin listed first returns the live folder`() =
        withDrive { drive, provider ->
            drive.folders += UploadDriveFolder("deleted-docs", "docs", "root-folder-uuid", status = "DELETED")
            drive.folders += UploadDriveFolder("live-docs", "docs", "root-folder-uuid")

            assertEquals("live-docs", provider.createFolder("/docs").id)
        }
}
