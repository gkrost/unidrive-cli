package org.krost.unidrive.internxt

import kotlinx.coroutines.test.runTest
import io.ktor.http.HttpStatusCode
import org.krost.unidrive.ParentGoneException
import org.krost.unidrive.PermanentUploadFailureException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#134: an upload is refused as `parent_gone` only when a second look through the complete listings, taken
 * without the folder cache, does not find the folder either. A listing that lags behind the cloud (archive#132, UD-357), or a
 * cached ancestor that no longer leads where its name does, used to turn into a permanent refusal of a file whose folder is
 * live; the refusal was then stored for six hours and re-offered at every open.
 */
class InternxtParentGoneRelookTest {
    private val tmp: Path = Files.createTempDirectory("ud-parent-relook-")

    private fun source(): Path = tmp.resolve("f.txt").also { Files.write(it, ByteArray(10) { 0x41 }) }

    private fun newDrive(): UploadDrive =
        UploadDrive().also {
            it.folders += UploadDriveFolder("fa", "A", "root-folder-uuid")
            it.folders += UploadDriveFolder("fd1", "docs", "fa")
        }

    private fun provider(drive: UploadDrive): InternxtProvider = rootedInternxtProvider(tmp, drive.engine).also { it.parentRelistDelayMs = 0 }

    private fun cleanUp() {
        tmp.toFile().deleteRecursively()
    }

    @Test
    fun `a folder the first listing leaves out while it lags is found by the second look and the file uploads`() =
        runTest {
            val drive = newDrive()
            drive.listingLag["fd1"] = 1 // the first listing of fa does not show docs yet
            val provider = provider(drive)
            try {
                val item = provider.upload(source(), "/A/docs/f.txt", existingRemoteId = null, onProgress = null)

                assertEquals("fd1", drive.files.single { it.uuid == item.id }.folderUuid, "the file went into the live folder")
                assertTrue(drive.requests.any { "/folders/v2/content/fa/folders" in it }, "the second look read the complete (cursor) listing: ${drive.requests}")
            } finally {
                provider.close()
                cleanUp()
            }
        }

    @Test
    fun `an empty file into a folder the first listing leaves out while it lags is created there`() =
        runTest {
            val drive = newDrive()
            drive.listingLag["fd1"] = 1
            val provider = provider(drive)
            try {
                val item = provider.upload(source().also { Files.write(it, ByteArray(0)) }, "/A/docs/zero.txt", existingRemoteId = null, onProgress = null)

                assertEquals("fd1", drive.files.single { it.uuid == item.id }.folderUuid)
            } finally {
                provider.close()
                cleanUp()
            }
        }

    @Test
    fun `a cached ancestor that no longer leads to the folder is dropped and the walk goes on from the current listing`() =
        runTest {
            val drive = newDrive()
            val provider = provider(drive)
            try {
                // /A is cached with its uuid; another device then renames it away and puts a new A with docs in its place.
                assertEquals(emptyList(), provider.listChildren("/A").filter { !it.isFolder })
                drive.folders[drive.folders.indexOfFirst { it.uuid == "fa" }] = UploadDriveFolder("fa", "A-old", "root-folder-uuid")
                drive.folders.removeAll { it.uuid == "fd1" }
                drive.folders += UploadDriveFolder("fa2", "A", "root-folder-uuid")
                drive.folders += UploadDriveFolder("fd2", "docs", "fa2")

                val item = provider.upload(source(), "/A/docs/f.txt", existingRemoteId = null, onProgress = null)

                assertEquals("fd2", drive.files.single { it.uuid == item.id }.folderUuid, "the file went below the A the cloud has now")
                assertEquals("fd2", provider.getMetadata("/A/docs").id, "the cache follows the folders that were found")
            } finally {
                provider.close()
                cleanUp()
            }
        }

    @Test
    fun `a folder that stays out of the complete listing too is still a parent-gone refusal naming it`() =
        runTest {
            val drive = newDrive()
            drive.folders.removeAll { it.uuid == "fd1" }
            val provider = provider(drive)
            try {
                val e = assertFailsWith<ParentGoneException> { provider.upload(source(), "/A/docs/f.txt", existingRemoteId = null, onProgress = null) }

                assertEquals("/A/docs", e.parent)
                assertTrue(drive.files.isEmpty())
                assertTrue(drive.requests.any { "/folders/v2/content/fa/folders" in it }, "the refusal stands on the second look: ${drive.requests}")
            } finally {
                provider.close()
                cleanUp()
            }
        }

    @Test
    fun `a second look that cannot be completed is a retryable failure, not a refusal`() =
        runTest {
            val drive = newDrive()
            drive.folders.removeAll { it.uuid == "fd1" }
            drive.pagedListingFault = HttpStatusCode.ServiceUnavailable
            val provider = provider(drive)
            try {
                val e = assertFailsWith<Exception> { provider.upload(source(), "/A/docs/f.txt", existingRemoteId = null, onProgress = null) }

                assertFalse(e is PermanentUploadFailureException, "a listing that fails proves nothing: $e")
                assertTrue(e is InternxtApiException && e.statusCode == 503, "the listing's own failure is kept: $e")
            } finally {
                provider.close()
                cleanUp()
            }
        }
}
