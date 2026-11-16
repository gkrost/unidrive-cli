package org.krost.unidrive.internxt

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The server refuses a folder create with 409 while any folder of that name holds the slot, a trashed one included. The
 * recovery that follows the 409 must only ever settle on a LIVE folder: content uploaded below a trashed folder is
 * invisible to the user and is purged with the trash. A trashed twin alone therefore ends in the original 409, and the
 * trashed folder is neither used, renamed, moved nor restored.
 */
class InternxtCreateFolderTrashedTwinTest {
    private fun withDrive(block: suspend (UploadDrive, InternxtProvider) -> Unit) =
        runTest {
            val tmp = Files.createTempDirectory("ud-folder-twin-")
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
    fun `a 409 whose only twin is trashed is rethrown and the trashed folder is left alone`() =
        withDrive { drive, provider ->
            drive.folders += UploadDriveFolder("trashed-docs", "docs", "root-folder-uuid", status = "TRASHED")

            val failure = assertFailsWith<InternxtApiException> { provider.createFolder("/docs") }

            assertEquals(409, failure.statusCode)
            // Nothing may touch the trashed folder: no rename, no move, no restore, no other write than the refused create.
            assertEquals(1, drive.requests.count { it == "POST /drive/folders" }, "only the refused create is sent")
            assertTrue(drive.requests.none { it.startsWith("PUT ") || it.startsWith("DELETE ") }, "no write request: ${drive.requests}")
            assertEquals(listOf("trashed-docs"), drive.folders.map { it.uuid }, "no folder was added or replaced")
            assertEquals("TRASHED", drive.folders.single().status)
            assertTrue(drive.renames.isEmpty())
        }

    @Test
    fun `a 409 with a live twin returns the live folder`() =
        withDrive { drive, provider ->
            drive.folders += UploadDriveFolder("live-docs", "docs", "root-folder-uuid")

            val folder = provider.createFolder("/docs")

            assertEquals("live-docs", folder.id)
            assertEquals(1, drive.requests.count { it == "POST /drive/folders" })
            assertTrue(drive.requests.none { it.startsWith("PUT ") || it.startsWith("DELETE ") }, "no write request: ${drive.requests}")
        }

    @Test
    fun `a 409 with a live and a trashed twin returns the live folder whichever the listing shows first`() =
        withDrive { drive, provider ->
            // The trashed row first: a pick that takes the first name match would settle on it.
            drive.folders += UploadDriveFolder("trashed-docs", "docs", "root-folder-uuid", status = "TRASHED")
            drive.folders += UploadDriveFolder("live-docs", "docs", "root-folder-uuid")

            val folder = provider.createFolder("/docs")

            assertEquals("live-docs", folder.id)
            assertFalse(drive.requests.any { it.startsWith("PUT ") || it.startsWith("DELETE ") }, "no write request: ${drive.requests}")
            assertEquals("TRASHED", drive.folders.first { it.uuid == "trashed-docs" }.status)
        }
}
