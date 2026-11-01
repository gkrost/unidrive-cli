package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class OneDriveCommittedChunkRetryTest {
    private lateinit var tokenDir: Path

    @BeforeTest
    fun setUp() {
        tokenDir = Files.createTempDirectory("ud-committed-chunk")
    }

    @AfterTest
    fun tearDown() {
        Files.walk(tokenDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `a fully committed chunk is not retried with an empty invalid range`() =
        runTest {
            val fileSize = 11 * 1024 * 1024
            val local = Files.createTempFile(tokenDir, "large", ".bin")
            Files.write(local, ByteArray(fileSize) { 7 })
            val uploadUrl = "https://upload.example/committed-chunk"
            val contentRanges = mutableListOf<String?>()
            var sessionStatusRequests = 0
            var chunkPuts = 0
            val engine =
                MockEngine { request ->
                    when {
                        request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/createUploadSession") ->
                            respond(
                                """{"uploadUrl":"$uploadUrl","expirationDateTime":"2030-01-01T00:00:00Z"}""",
                                HttpStatusCode.OK,
                                jsonHeaders(),
                            )
                        request.method == HttpMethod.Put && request.url.toString() == uploadUrl -> {
                            contentRanges += request.headers["Content-Range"]
                            chunkPuts++
                            if (chunkPuts == 1) {
                                // The server committed the full first chunk; its response was lost.
                                respond("{}", HttpStatusCode.InternalServerError, jsonHeaders())
                            } else {
                                respond(driveItemJson("large-id", "large.bin", fileSize.toLong()), HttpStatusCode.Created, jsonHeaders())
                            }
                        }
                        request.method == HttpMethod.Get && request.url.toString() == uploadUrl -> {
                            sessionStatusRequests++
                            respond(
                                """{"nextExpectedRanges":["10485760-"],"expirationDateTime":"2030-01-01T00:00:00Z"}""",
                                HttpStatusCode.OK,
                                jsonHeaders(),
                            )
                        }
                        else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val service = graphWith(engine)

            val uploaded = service.uploadLargeFile(local, "/large.bin")

            assertEquals("large.bin", uploaded.name)
            assertEquals(1, sessionStatusRequests)
            assertEquals(2, chunkPuts)
            assertEquals(
                listOf<String?>("bytes 0-10485759/$fileSize", "bytes 10485760-11534335/$fileSize"),
                contentRanges,
            )
            service.close()
            Files.deleteIfExists(local)
        }

    // archive#1088 item 7: the server keeps the first half of a chunk whose response is lost. The retry has to send
    // only what the probe says is still missing, with a Content-Range that starts there.
    @Test
    fun `a chunk the server committed in part is resent from the committed offset`() =
        runTest {
            val fileSize = 20 * 1024 * 1024
            val half = 5 * 1024 * 1024
            val local = Files.createTempFile(tokenDir, "partial", ".bin")
            // Distinct bytes per 1 MiB block, so a resend from the wrong offset cannot go unnoticed.
            val content = ByteArray(fileSize) { (it / (1024 * 1024)).toByte() }
            Files.write(local, content)
            val uploadUrl = "https://upload.example/partial-commit"
            val contentRanges = mutableListOf<String?>()
            val bodies = mutableListOf<ByteArray>()
            var chunkPuts = 0
            val engine =
                MockEngine { request ->
                    when {
                        request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/createUploadSession") ->
                            respond(
                                """{"uploadUrl":"$uploadUrl","expirationDateTime":"2030-01-01T00:00:00Z"}""",
                                HttpStatusCode.OK,
                                jsonHeaders(),
                            )
                        request.method == HttpMethod.Put && request.url.toString() == uploadUrl -> {
                            contentRanges += request.headers["Content-Range"]
                            bodies += request.body.toByteArray()
                            chunkPuts++
                            when (chunkPuts) {
                                1 -> respond("{}", HttpStatusCode.ServiceUnavailable, jsonHeaders())
                                2 -> respond("""{"nextExpectedRanges":["10485760-"]}""", HttpStatusCode.Accepted, jsonHeaders())
                                else -> respond(driveItemJson("large-id", "large.bin", fileSize.toLong()), HttpStatusCode.Created, jsonHeaders())
                            }
                        }
                        request.method == HttpMethod.Get && request.url.toString() == uploadUrl ->
                            respond(
                                """{"nextExpectedRanges":["$half-"],"expirationDateTime":"2030-01-01T00:00:00Z"}""",
                                HttpStatusCode.OK,
                                jsonHeaders(),
                            )
                        else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val service = graphWith(engine)

            val uploaded = service.uploadLargeFile(local, "/large.bin")

            assertEquals("large.bin", uploaded.name)
            assertEquals(
                listOf<String?>(
                    "bytes 0-10485759/$fileSize",
                    "bytes $half-10485759/$fileSize",
                    "bytes 10485760-20971519/$fileSize",
                ),
                contentRanges,
            )
            assertEquals(half, bodies[1].size, "the resend carries only the bytes the server does not hold")
            assertContentEquals(content.copyOfRange(half, 10 * 1024 * 1024), bodies[1])
            assertContentEquals(content.copyOfRange(10 * 1024 * 1024, fileSize), bodies[2])
            service.close()
            Files.deleteIfExists(local)
        }

    @Test
    fun `a final chunk confirmed by the status probe returns the uploaded item`() =
        runTest {
            val fileSize = 5 * 1024 * 1024
            val local = Files.createTempFile(tokenDir, "final", ".bin")
            Files.write(local, ByteArray(fileSize) { 9 })
            val uploadUrl = "https://upload.example/committed-final-chunk"
            var chunkPuts = 0
            var metadataReads = 0
            val engine =
                MockEngine { request ->
                    when {
                        request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/createUploadSession") ->
                            respond(
                                """{"uploadUrl":"$uploadUrl","expirationDateTime":"2030-01-01T00:00:00Z"}""",
                                HttpStatusCode.OK,
                                jsonHeaders(),
                            )
                        request.method == HttpMethod.Put && request.url.toString() == uploadUrl -> {
                            chunkPuts++
                            respond("{}", HttpStatusCode.InternalServerError, jsonHeaders())
                        }
                        request.method == HttpMethod.Get && request.url.toString() == uploadUrl ->
                            respond(
                                """{"nextExpectedRanges":["$fileSize-"],"expirationDateTime":"2030-01-01T00:00:00Z"}""",
                                HttpStatusCode.OK,
                                jsonHeaders(),
                            )
                        request.method == HttpMethod.Get && request.url.toString() != uploadUrl -> {
                            metadataReads++
                            respond(driveItemJson("large-id", "large.bin", fileSize.toLong()), HttpStatusCode.OK, jsonHeaders())
                        }
                        else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val service = graphWith(engine)

            val uploaded = service.uploadLargeFile(local, "/large.bin")

            assertEquals("large.bin", uploaded.name)
            assertEquals(1, chunkPuts)
            assertEquals(1, metadataReads)
            service.close()
            Files.deleteIfExists(local)
        }

    private fun graphWith(engine: MockEngine): GraphApiService {
        val service = GraphApiService(config = OneDriveConfig(tokenPath = tokenDir), httpClient = HttpClient(engine), tokenProvider = { _ -> "test-token" })
        return service
    }

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    private fun driveItemJson(
        id: String,
        name: String,
        size: Long,
    ): String =
        """{"id":"$id","name":"$name","size":$size,"file":{"mimeType":"application/octet-stream"},"parentReference":{"path":"/drive/root:"}}"""
}
