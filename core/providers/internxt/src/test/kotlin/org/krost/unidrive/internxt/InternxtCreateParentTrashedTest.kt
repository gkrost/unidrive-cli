package org.krost.unidrive.internxt

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.ParentGoneException
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Live finding E3 t2/t3 (2026-11-18, mount mode): an upload below a folder that was just trashed went to the folder's
 * cached uuid, and the file create answered 404 {"message":"Folder not found"}. That answer is the parent gone: it is a
 * [ParentGoneException] for the parent, and the cached uuid is dropped, so the caller's remake of the parent and the next
 * upload resolve the folder afresh instead of reaching the trashed one again.
 *
 * gkrost/unidrive-cli#961: the answer is the parent gone only after a second look through the complete listings, taken
 * without the cache, does not find the folder either; a stale cached uuid for a live folder is a retryable failure.
 */
class InternxtCreateParentTrashedTest {
    private fun withDrive(block: suspend (UploadDrive, InternxtProvider, Path) -> Unit) =
        runTest {
            val tmp = TestScratch.directory("ud-parent-trashed-")
            try {
                val drive = UploadDrive()
                drive.folders += UploadDriveFolder("docs-old", "docs", "root-folder-uuid")
                val provider = rootedInternxtProvider(tmp, drive.engine).also { it.parentRelistDelayMs = 0 }
                try {
                    block(drive, provider, tmp)
                } finally {
                    provider.close()
                }
            } finally {
                tmp.toFile().deleteRecursively()
            }
        }

    private fun local(tmp: Path, name: String): Path = tmp.resolve(name).also { Files.writeString(it, "hello world") }

    // The folder is trashed: the create answers 404 and the listings no longer show it.
    private fun UploadDrive.trashDocsBehindTheCache() {
        folders.removeIf { it.uuid == "docs-old" }
        createFault = { _, _ -> HttpStatusCode.NotFound }
        createFaultBody = """{"message":"Folder not found","error":"Not Found","statusCode":404}"""
    }

    @Test
    fun `a file create answered 404 Folder not found is the parent gone`() =
        withDrive { drive, provider, tmp ->
            provider.upload(local(tmp, "a.txt"), "/docs/a.txt", existingRemoteId = null, onProgress = null) // caches docs
            drive.trashDocsBehindTheCache()

            val failure = assertFailsWith<ParentGoneException> { provider.upload(local(tmp, "b.txt"), "/docs/b.txt", existingRemoteId = null, onProgress = null) }

            assertEquals("/docs", failure.parent)
        }

    @Test
    fun `after a parent-gone create the next upload resolves the folder afresh`() =
        withDrive { drive, provider, tmp ->
            provider.upload(local(tmp, "a.txt"), "/docs/a.txt", existingRemoteId = null, onProgress = null) // caches docs-old
            drive.trashDocsBehindTheCache()
            assertFailsWith<ParentGoneException> { provider.upload(local(tmp, "b.txt"), "/docs/b.txt", existingRemoteId = null, onProgress = null) }

            // The caller remakes the parent: the trashed folder leaves the listing, a new one takes the name.
            drive.folders.removeIf { it.uuid == "docs-old" }
            drive.folders += UploadDriveFolder("docs-new", "docs", "root-folder-uuid")
            drive.createFault = null

            provider.upload(local(tmp, "b.txt"), "/docs/b.txt", existingRemoteId = null, onProgress = null)

            assertEquals("docs-new", drive.files.single { it.plainName == "b" }.folderUuid, "not the trashed folder's cached uuid")
        }

    @Test
    fun `a create 404 through a stale cached uuid whose folder is live is not refused and the upload is offered again`() =
        withDrive { drive, provider, tmp ->
            provider.upload(local(tmp, "a.txt"), "/docs/a.txt", existingRemoteId = null, onProgress = null) // caches docs-old
            // Another device replaced the folder: the cached uuid is stale, the listing has the live one.
            drive.folders.removeIf { it.uuid == "docs-old" }
            drive.folders += UploadDriveFolder("docs-new", "docs", "root-folder-uuid")
            drive.createFault = { call, _ -> HttpStatusCode.NotFound.takeIf { call == 1 } }
            drive.createFaultBody = """{"message":"Folder not found","error":"Not Found","statusCode":404}"""

            val failure = assertFailsWith<InternxtApiException> { provider.upload(local(tmp, "b.txt"), "/docs/b.txt", existingRemoteId = null, onProgress = null) }
            assertEquals(404, failure.statusCode, "retryable: not the parent gone, so no upload_refusal row")

            provider.upload(local(tmp, "b.txt"), "/docs/b.txt", existingRemoteId = null, onProgress = null)
            assertEquals("docs-new", drive.files.single { it.plainName == "b" }.folderUuid, "offered again with the fresh uuid")
        }

    @Test
    fun `a create 404 whose second look fails on a 5xx listing is a retryable failure, not a refusal`() =
        withDrive { drive, provider, tmp ->
            provider.upload(local(tmp, "a.txt"), "/docs/a.txt", existingRemoteId = null, onProgress = null) // caches docs-old
            drive.trashDocsBehindTheCache()
            drive.pagedListingFault = HttpStatusCode.ServiceUnavailable

            val failure = assertFailsWith<InternxtApiException> { provider.upload(local(tmp, "b.txt"), "/docs/b.txt", existingRemoteId = null, onProgress = null) }
            assertEquals(503, failure.statusCode, "the look that failed proves nothing: its own type, not the parent gone")

            // The listing is back and the folder really is gone: now the refusal stands.
            drive.pagedListingFault = null
            assertFailsWith<ParentGoneException> { provider.upload(local(tmp, "b.txt"), "/docs/b.txt", existingRemoteId = null, onProgress = null) }
        }

    @Test
    fun `a 404 with another message keeps its own type`() =
        withDrive { drive, provider, tmp ->
            drive.createFault = { _, _ -> HttpStatusCode.NotFound }
            drive.createFaultBody = """{"message":"Bucket not found","statusCode":404}"""

            val failure = assertFailsWith<InternxtApiException> { provider.upload(local(tmp, "a.txt"), "/docs/a.txt", existingRemoteId = null, onProgress = null) }

            assertEquals(404, failure.statusCode)
        }
}
