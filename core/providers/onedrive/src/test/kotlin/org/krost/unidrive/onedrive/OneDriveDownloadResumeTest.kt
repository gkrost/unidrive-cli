package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.QuickXorHash
import org.krost.unidrive.testing.TestScratch
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #845 (#748-6): a OneDrive download that breaks in the middle of the body keeps what arrived and asks for the rest with a
 * `Range` request against the download URL, instead of starting from byte 0. The size check and the quickXorHash check run
 * over the whole result, so a resume that is wrong fails closed.
 *
 * The fake CDN breaks the first body after [BREAK_AT] bytes and then behaves as each test says.
 */
class OneDriveDownloadResumeTest {
    private val itemId = "item-1"
    private val cdnUrl = "https://fake-cdn.sharepoint.com/dl/item-1"

    // A body that is not a multiple of anything: 4000 bytes of a pattern with no period that divides the break point.
    private val contentA = ByteArray(4_000) { ((it * 7 + it / 13) % 251).toByte() }
    private val contentB = ByteArray(4_000) { ((it * 11 + it / 7 + 3) % 251).toByte() }

    private var content = contentA
    private var eTag = "etag-A"
    private val contentRequests = mutableListOf<String?>() // the Range header of each content GET (null: none)
    private lateinit var dir: Path
    private lateinit var destination: Path

    // What the fake CDN does with the n-th content request (0-based), when the test does not say otherwise.
    private var onContent: suspend MockRequestHandleScope.(Int, String?) -> HttpResponseData = { n, range -> default(n, range) }

    @AfterTest
    fun tearDown() {
        if (this::dir.isInitialized) dir.toFile().deleteRecursively()
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun brokenBody(
        bytes: ByteArray,
        from: Int,
        upTo: Int,
    ): ByteReadChannel =
        GlobalScope
            .writer(autoFlush = true) {
                channel.writeFully(bytes, from, upTo - from)
                delay(150) // real time, on the writer's own dispatcher: the reader takes what was flushed before the reset
                channel.cancel(IOException("connection reset"))
            }.channel

    private fun MockRequestHandleScope.fullBody(bytes: ByteArray) =
        respond(
            bytes,
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType to listOf("application/octet-stream"), HttpHeaders.ContentLength to listOf(bytes.size.toString())),
        )

    private fun MockRequestHandleScope.rangedBody(
        bytes: ByteArray,
        from: Int,
        totalOverride: Int = bytes.size,
    ) = respond(
        bytes.copyOfRange(from, bytes.size),
        HttpStatusCode.PartialContent,
        headersOf(
            HttpHeaders.ContentType to listOf("application/octet-stream"),
            HttpHeaders.ContentLength to listOf((bytes.size - from).toString()),
            HttpHeaders.ContentRange to listOf("bytes $from-${bytes.size - 1}/$totalOverride"),
        ),
    )

    private fun MockRequestHandleScope.default(
        n: Int,
        range: String?,
    ): HttpResponseData {
        if (n == 0) {
            return respond(
                brokenBody(content, 0, BREAK_AT),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/octet-stream"), HttpHeaders.ContentLength to listOf(content.size.toString())),
            )
        }
        val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
        return if (from == null) fullBody(content) else rangedBody(content, from)
    }

    private fun itemJson(): String =
        """{"id":"$itemId","name":"f.bin","size":${content.size},"eTag":"$eTag","@microsoft.graph.downloadUrl":"$cdnUrl",""" +
            """"file":{"hashes":{"quickXorHash":"${QuickXorHash.of(content.inputStream())}"}}}"""

    private fun newService(): GraphApiService {
        dir = TestScratch.directory("od-resume")
        destination = dir.resolve("f.bin")
        val engine =
            MockEngine { request: HttpRequestData ->
                if (request.url.toString() == cdnUrl) {
                    val n = contentRequests.size
                    val range = request.headers[HttpHeaders.Range]
                    contentRequests += range
                    this.onContent(n, range)
                } else {
                    respond(itemJson(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        return GraphApiService(config = OneDriveConfig(), httpClient = HttpClient(engine), tokenProvider = { _, _ -> "test-token" })
    }

    private fun folder(): List<String> = Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }

    @Test
    fun `a broken download resumes from the staged prefix with a Range request and yields identical bytes`() =
        runTest {
            val service = newService()

            val bytes = service.downloadFile(itemId, destination)

            assertEquals(content.size.toLong(), bytes)
            assertContentEquals(content, Files.readAllBytes(destination))
            assertEquals(2, contentRequests.size, "one broken attempt and one resume: $contentRequests")
            assertNull(contentRequests[0])
            val resumeFrom = contentRequests[1]!!.removePrefix("bytes=").removeSuffix("-").toInt()
            assertTrue(resumeFrom in 1..BREAK_AT, "the retry asks for the rest after the bytes that arrived, from $resumeFrom: ${contentRequests[1]}")
            assertTrue(contentRequests[1]!!.endsWith("-"), "an open-ended range: ${contentRequests[1]}")
            assertEquals(listOf("f.bin"), folder(), "no partial or record is left behind")
            service.close()
        }

    @Test
    fun `a changed remote version discards the staged prefix and restarts from zero`() =
        runTest {
            // First call: the body breaks, and the retry (a ranged GET) fails with a 500, so the call ends with the partial kept.
            onContent = { n, range ->
                when (n) {
                    0 -> default(0, range)
                    else -> respond("{}", HttpStatusCode.InternalServerError, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
            val service = newService()
            assertFailsWith<Exception> { service.downloadFile(itemId, destination) }
            assertTrue(folder().any { it.endsWith(".part.unidrive-tmp") }, "the prefix is kept for the next call: ${folder()}")

            // The file was replaced in the meantime: same size, other bytes, other etag.
            content = contentB
            eTag = "etag-B"
            contentRequests.clear()
            onContent = { n, range -> default(n + 1, range) }

            service.downloadFile(itemId, destination)

            assertEquals(listOf<String?>(null), contentRequests, "a prefix of the old version is not continued: no Range")
            assertContentEquals(contentB, Files.readAllBytes(destination), "no byte of the old version is in the result")
            assertEquals(listOf("f.bin"), folder())
            service.close()
        }

    @Test
    fun `a server that answers 200 to a Range request restarts from zero, without appending`() =
        runTest {
            onContent = { n, range -> if (n == 0) default(0, range) else fullBody(content) }
            val service = newService()

            service.downloadFile(itemId, destination)

            assertEquals(2, contentRequests.size)
            assertTrue(contentRequests[1]!!.startsWith("bytes="), "the retry did ask with Range: $contentRequests")
            assertContentEquals(content, Files.readAllBytes(destination), "the whole body replaced the prefix; appending would have doubled its start")
            assertEquals(content.size.toLong(), Files.size(destination))
            service.close()
        }

    @Test
    fun `a 206 for another range is not used and the object is asked for again without Range`() =
        runTest {
            onContent = { n, range ->
                when (n) {
                    0 -> default(0, range)
                    1 -> rangedBody(content, from = 0) // answers bytes 0-3999/4000 to a request for bytes=N-
                    else -> fullBody(content)
                }
            }
            val service = newService()

            service.downloadFile(itemId, destination)

            assertEquals(3, contentRequests.size, "$contentRequests")
            assertNull(contentRequests[2], "after an unusable answer the next request carries no Range")
            assertContentEquals(content, Files.readAllBytes(destination))
            service.close()
        }

    @Test
    fun `a 416 to a Range request drops the prefix and the same call completes from zero`() =
        runTest {
            onContent = { n, range ->
                if (n == 1) respond("", HttpStatusCode.RequestedRangeNotSatisfiable) else default(n, range)
            }
            val service = newService()

            service.downloadFile(itemId, destination)

            assertEquals(3, contentRequests.size, "$contentRequests")
            assertTrue(contentRequests[1]!!.startsWith("bytes="))
            assertNull(contentRequests[2], "after the 416 the next request carries no Range")
            assertContentEquals(content, Files.readAllBytes(destination))
            assertEquals(listOf("f.bin"), folder())
            service.close()
        }

    @Test
    fun `the whole-file hash check still rejects a corrupted prefix`() =
        runTest {
            // The kept prefix is damaged on disk between the broken attempt and the resume. The server's rest is right.
            val service = newService()
            onContent = { n, range ->
                if (n == 1) {
                    val partial = dir.resolve("f.bin.part.unidrive-tmp")
                    val bytes = Files.readAllBytes(partial)
                    bytes[0] = (bytes[0] + 1).toByte()
                    Files.write(partial, bytes)
                }
                // From the second ranged request on the server fails, so only a from-zero retry could have saved the call.
                if (n >= 2) respond("{}", HttpStatusCode.InternalServerError, headersOf(HttpHeaders.ContentType, "application/json")) else default(n, range)
            }

            assertFailsWith<Exception> { service.downloadFile(itemId, destination) }

            assertFalse(Files.exists(destination), "a result that fails the hash is never published")
            assertEquals(3, contentRequests.size, "$contentRequests")
            assertTrue(contentRequests[1]!!.startsWith("bytes="))
            assertNull(contentRequests[2], "after the rejected resume the retry starts from zero, not from the corrupt prefix")
            assertFalse(folder().any { it.endsWith(".part.unidrive-tmp") }, "the corrupt prefix is gone: ${folder()}")
            service.close()
        }

    private companion object {
        const val BREAK_AT = 1_500
    }
}
