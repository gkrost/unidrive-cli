package org.krost.unidrive.dropbox

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The request shape of a session upload (gkrost/unidrive-cli#841; #748 finding 10): the first chunk rides on the start,
 * the last on the finish, so no request of a session is empty and a file of N chunks costs N requests. Chunks are
 * streamed from their byte range of the file (no per-chunk array). If these tests are removed or loosened, every large
 * file silently costs two empty requests again.
 */
class DropboxSessionUploadShapeTest {
    private val dir = TestScratch.directory("dropbox-session-shape")

    private fun uploadOf(content: String): FakeDropbox {
        val fake = FakeDropbox()
        fake.json("files/upload_session/start", """{"session_id":"s1"}""")
        fake.json("files/upload_session/append_v2", "null")
        fake.json("files/upload_session/finish", FakeDropbox.file("/big.bin", "id:3", size = content.length.toLong()))
        val provider = FakeDropbox.provider(fake, dir, { it.copy(simpleUploadMaxBytes = 4, uploadChunkBytes = 4) })
        val local = dir.resolve("big.bin")
        Files.writeString(local, content)
        kotlinx.coroutines.runBlocking { provider.upload(local, "/big.bin", null, null, null) }
        return fake
    }

    private fun sessionBodies(fake: FakeDropbox) =
        fake.calls.filter { it.endpoint.startsWith("files/upload_session/") }.map { it.endpoint.substringAfterLast('/') to it.body.toString(Charsets.UTF_8) }

    @Test
    fun `a session upload sends no request without a body`() =
        runTest {
            val fake = uploadOf("0123456789")

            assertEquals(listOf("start" to "0123", "append_v2" to "4567", "finish" to "89"), sessionBodies(fake))
        }

    @Test
    fun `a file of whole chunks is a start and a finish and nothing else`() =
        runTest {
            val fake = uploadOf("01234567")

            assertEquals(listOf("start" to "0123", "finish" to "4567"), sessionBodies(fake))
            assertEquals(4L, fake.callsTo("files/upload_session/finish").single().args!!["cursor"].toString().let { Regex("\"offset\":(\\d+)").find(it)!!.groupValues[1].toLong() })
        }

    @Test
    fun `every request of a session carries bytes`() =
        runTest {
            val fake = uploadOf("0123456789ABCDEFGHIJ")

            assertTrue(sessionBodies(fake).all { it.second.isNotEmpty() }, "an empty request was sent: ${sessionBodies(fake)}")
            assertEquals(5, sessionBodies(fake).size, "20 bytes in chunks of 4 are five requests")
        }
}
