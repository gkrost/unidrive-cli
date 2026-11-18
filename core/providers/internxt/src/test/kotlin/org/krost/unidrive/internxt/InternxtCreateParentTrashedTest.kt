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
 */
class InternxtCreateParentTrashedTest {
    private fun withDrive(block: suspend (UploadDrive, InternxtProvider, Path) -> Unit) =
        runTest {
            val tmp = TestScratch.directory("ud-parent-trashed-")
            try {
                val drive = UploadDrive()
                drive.folders += UploadDriveFolder("docs-old", "docs", "root-folder-uuid")
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

    private fun local(tmp: Path, name: String): Path = tmp.resolve(name).also { Files.writeString(it, "hello world") }

    private fun UploadDrive.trashDocsBehindTheCache() {
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
    fun `a 404 with another message keeps its own type`() =
        withDrive { drive, provider, tmp ->
            drive.createFault = { _, _ -> HttpStatusCode.NotFound }
            drive.createFaultBody = """{"message":"Bucket not found","statusCode":404}"""

            val failure = assertFailsWith<InternxtApiException> { provider.upload(local(tmp, "a.txt"), "/docs/a.txt", existingRemoteId = null, onProgress = null) }

            assertEquals(404, failure.statusCode)
        }
}
