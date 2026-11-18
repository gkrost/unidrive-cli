package org.krost.unidrive.internxt

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * gkrost/unidrive-cli#793: a 500, 502 or 504 answer to the file create (`POST /files`) does not say whether the server
 * committed the file. It is reconciled like a lost response: the parent is listed, and a file of that name carrying the
 * bucket entry just finished and the same size is the upload, adopted. Nothing found fails the item without a second
 * create (a blind repeat could duplicate a commit the listing does not show yet); the next pass reconciles again.
 */
class InternxtCreateServerErrorReconcileTest {
    private fun withDrive(block: suspend (UploadDrive, InternxtProvider, Path) -> Unit) =
        runTest {
            val tmp = TestScratch.directory("ud-create-5xx-")
            try {
                val drive = UploadDrive()
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

    private fun local(tmp: Path): Path = tmp.resolve("f.txt").also { Files.writeString(it, "hello world") }

    @Test
    fun `a create answered 500, 502 or 504 that the server committed is adopted without a second create`() {
        for (status in listOf(HttpStatusCode.InternalServerError, HttpStatusCode.BadGateway, HttpStatusCode.GatewayTimeout)) {
            withDrive { drive, provider, tmp ->
                drive.createFault = { call, _ -> status.takeIf { call == 1 } }
                drive.storeBeforeFault = true

                val item = provider.upload(local(tmp), "/f.txt", existingRemoteId = null, onProgress = null)

                assertEquals(1, drive.createCalls.get(), "$status: no second create")
                assertEquals(drive.files.single().uuid, item.id, "$status: the committed file is the upload")
                assertEquals("f.txt", item.name)
            }
        }
    }

    @Test
    fun `a create answered 502 that the server did not commit fails without a blind second create`() =
        withDrive { drive, provider, tmp ->
            drive.createFault = { call, _ -> HttpStatusCode.BadGateway.takeIf { call == 1 } }

            val failure = assertFailsWith<InternxtApiException> { provider.upload(local(tmp), "/f.txt", existingRemoteId = null, onProgress = null) }

            assertEquals(502, failure.statusCode)
            assertEquals(1, drive.createCalls.get(), "a negative reconcile never re-creates")
            assertEquals(0, drive.files.size)
        }

    @Test
    fun `a create answered 502 whose name holds other content is not adopted`() =
        withDrive { drive, provider, tmp ->
            drive.files += UploadDriveFile("other", "f", "txt", 11L, "another-bucket-entry", "root-folder-uuid")
            drive.createFault = { call, _ -> HttpStatusCode.BadGateway.takeIf { call == 1 } }

            val failure = assertFailsWith<InternxtApiException> { provider.upload(local(tmp), "/f.txt", existingRemoteId = null, onProgress = null) }

            assertEquals(502, failure.statusCode)
            assertEquals(1, drive.createCalls.get())
            assertEquals(listOf("other"), drive.files.map { it.uuid }, "the other file is left alone")
        }
}
