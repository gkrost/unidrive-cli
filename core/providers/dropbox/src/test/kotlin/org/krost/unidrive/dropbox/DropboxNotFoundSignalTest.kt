package org.krost.unidrive.dropbox

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.ProviderException
import org.krost.unidrive.dropbox.FakeDropbox.Answer
import org.krost.unidrive.dropbox.FakeDropbox.Companion.file
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * archive#1256 A: Dropbox answers a missing path with HTTP 409 `path/not_found`, which was passed through as it came, so no
 * caller read it as absence. The metadata lookup turns it into the typed signal the engine's one rule
 * (`RemoteErrors.isNotFound`) reads: the `Item not found: ` message and an HTTP status of 404.
 */
class DropboxNotFoundSignalTest {
    private val dir = Files.createTempDirectory("dropbox-notfound")
    private val fake = FakeDropbox()
    private val provider = FakeDropbox.provider(fake, dir)

    @Test
    fun `a missing path is the typed not-found signal`() =
        runTest {
            fake.on("files/get_metadata") { Answer(409, """{"error_summary":"path/not_found/..","error":{".tag":"path","path":{".tag":"not_found"}}}""") }

            val failure = assertFailsWith<ProviderException> { provider.getMetadata("/missing.txt") }

            assertTrue(failure.message.orEmpty().startsWith("Item not found: "), failure.message)
            assertEquals(404, failure.httpStatus)
            assertTrue(failure.cause is DropboxApiException, "the Dropbox answer stays the cause")
        }

    @Test
    fun `another refusal is passed through as it came`() =
        runTest {
            fake.on("files/get_metadata") { Answer(409, """{"error_summary":"path/malformed_path/..","error":{".tag":"path","path":{".tag":"malformed_path"}}}""") }

            val failure = assertFailsWith<DropboxApiException> { provider.getMetadata("/x") }

            assertEquals(409, failure.status)
        }

    @Test
    fun `an existing path is returned`() =
        runTest {
            fake.json("files/get_metadata", file("/a.txt", "id:1"))

            assertTrue(provider.getMetadata("/a.txt").path.endsWith("a.txt"))
        }
}
