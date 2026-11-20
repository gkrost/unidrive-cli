package org.krost.unidrive.dropbox

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.PermanentDownloadFailureException
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * One download is one request (gkrost/unidrive-cli#841; #748 finding 8): no `files/get_metadata` first. The download's
 * own `path/not_found` is what reads as "gone". If these tests are removed or loosened, every download silently costs
 * a second request against Dropbox's rate limits again.
 */
class DropboxDownloadRequestCountTest {
    private val dir = TestScratch.directory("dropbox-download-count")

    @Test
    fun `a download is one files-download request and no metadata request`() =
        runTest {
            val fake = FakeDropbox()
            fake.on("files/download") {
                FakeDropbox.Answer(bytes = "HELLO".toByteArray(), headers = mapOf(DropboxApi.API_RESULT to FakeDropbox.file("/f.txt", "id:1")))
            }
            val provider = FakeDropbox.provider(fake, dir)
            val destination = dir.resolve("f.txt")

            assertEquals(5L, provider.download("/f.txt", destination))
            assertEquals(5L, provider.downloadById("id:1", "/f.txt", dir.resolve("g.txt")))

            assertEquals(2, fake.callsTo("files/download").size)
            assertEquals(0, fake.callsTo("files/get_metadata").size, "no metadata request before a download")
            assertEquals("HELLO", Files.readString(destination))
        }

    @Test
    fun `a file gone from Dropbox is a permanent failure read from the download itself`() =
        runTest {
            val fake = FakeDropbox()
            fake.json("files/download", """{"error_summary":"path/not_found/.."}""", status = 409)
            val provider = FakeDropbox.provider(fake, dir)

            assertFailsWith<PermanentDownloadFailureException> { provider.download("/gone.txt", dir.resolve("gone.txt")) }

            assertEquals(1, fake.callsTo("files/download").size)
            assertEquals(0, fake.callsTo("files/get_metadata").size)
        }
}
