package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.http.UploadWatchdogLimits
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * A chunk whose PUT breaks off (the connection is closed while the body is in flight, or the server never answers) is
 * retried as a chunk: the session is probed for the offset the server holds and the upload goes on from there. The
 * attempt, and the session's progress, are not thrown away for one dropped connection.
 */
class OneDriveChunkDropRetryTest {
    private lateinit var tokenDir: Path
    private val uploadUrl = "https://upload.example/chunk-drop"
    private val chunk = 10L * 1024 * 1024

    @BeforeTest
    fun setUp() {
        tokenDir = Files.createTempDirectory("ud-chunk-drop")
    }

    @AfterTest
    fun tearDown() {
        Files.walk(tokenDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `a chunk PUT that breaks with an IOException is resent from the server's offset in the same session`() =
        runTest {
            val fileSize = 3 * chunk
            val local = localFile(fileSize)
            val ranges = mutableListOf<String?>()
            var sessionsCreated = 0
            var probes = 0
            var dropped = false
            val engine =
                MockEngine { request ->
                    when {
                        request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/createUploadSession") -> {
                            sessionsCreated++
                            respond("""{"uploadUrl":"$uploadUrl","expirationDateTime":"2030-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHeaders())
                        }
                        request.method == HttpMethod.Put && request.url.toString() == uploadUrl -> {
                            val range = request.headers["Content-Range"]
                            ranges += range
                            when {
                                range == "bytes ${chunk}-${2 * chunk - 1}/$fileSize" && !dropped -> {
                                    dropped = true
                                    throw IOException("Failed to parse HTTP response: the server prematurely closed the connection")
                                }
                                range!!.startsWith("bytes ${2 * chunk}-") ->
                                    respond(driveItemJson(fileSize), HttpStatusCode.Created, jsonHeaders())
                                else -> respond("""{"nextExpectedRanges":["0-"]}""", HttpStatusCode.Accepted, jsonHeaders())
                            }
                        }
                        request.method == HttpMethod.Get && request.url.toString() == uploadUrl -> {
                            probes++
                            respond(
                                """{"nextExpectedRanges":["$chunk-"],"expirationDateTime":"2030-01-01T00:00:00Z"}""",
                                HttpStatusCode.OK,
                                jsonHeaders(),
                            )
                        }
                        else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val service = graphWith(engine)

            val uploaded = service.uploadLargeFile(local, "/big.bin", conflictBehavior = "fail")

            assertEquals("big.bin", uploaded.name)
            assertEquals(1, sessionsCreated, "one dropped chunk must not cost the session")
            assertEquals(1, probes, "the session is probed once after the drop")
            assertEquals(
                listOf<String?>(
                    "bytes 0-${chunk - 1}/$fileSize",
                    "bytes $chunk-${2 * chunk - 1}/$fileSize",
                    "bytes $chunk-${2 * chunk - 1}/$fileSize",
                    "bytes ${2 * chunk}-${3 * chunk - 1}/$fileSize",
                ),
                ranges,
            )
            service.close()
        }

    @Test
    fun `a chunk PUT the server never answers is cut by the upload watchdog and resent`() =
        runTest {
            val fileSize = 2 * chunk
            val local = localFile(fileSize)
            var sessionsCreated = 0
            var puts = 0
            val engine =
                MockEngine { request ->
                    when {
                        request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/createUploadSession") -> {
                            sessionsCreated++
                            respond("""{"uploadUrl":"$uploadUrl","expirationDateTime":"2030-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHeaders())
                        }
                        request.method == HttpMethod.Put && request.url.toString() == uploadUrl -> {
                            puts++
                            when (puts) {
                                1 -> respond("""{"nextExpectedRanges":["$chunk-"]}""", HttpStatusCode.Accepted, jsonHeaders())
                                2 -> awaitCancellation() // the silent server: no answer, no error, no close
                                else -> respond(driveItemJson(fileSize), HttpStatusCode.Created, jsonHeaders())
                            }
                        }
                        request.method == HttpMethod.Get && request.url.toString() == uploadUrl ->
                            respond(
                                """{"nextExpectedRanges":["$chunk-"],"expirationDateTime":"2030-01-01T00:00:00Z"}""",
                                HttpStatusCode.OK,
                                jsonHeaders(),
                            )
                        else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val service = graphWith(engine, UploadWatchdogLimits(idleWindowMs = 300, responseWaitFloorMs = 300, tickMs = 20))

            val uploaded = service.uploadLargeFile(local, "/big.bin", conflictBehavior = "fail")

            assertEquals("big.bin", uploaded.name)
            assertEquals(1, sessionsCreated)
            assertEquals(3, puts, "the silent PUT is cut and the chunk sent once more")
            service.close()
        }

    @Test
    fun `when every retry of a chunk breaks the attempt fails and the session stays stored for the next one`() =
        runTest {
            val fileSize = 2 * chunk
            val local = localFile(fileSize)
            val engine =
                MockEngine { request ->
                    when {
                        request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/createUploadSession") ->
                            respond("""{"uploadUrl":"$uploadUrl","expirationDateTime":"2030-01-01T00:00:00Z"}""", HttpStatusCode.OK, jsonHeaders())
                        request.method == HttpMethod.Put && request.url.toString() == uploadUrl ->
                            if (request.headers["Content-Range"]!!.startsWith("bytes 0-")) {
                                respond("""{"nextExpectedRanges":["$chunk-"]}""", HttpStatusCode.Accepted, jsonHeaders())
                            } else {
                                throw IOException("the server prematurely closed the connection")
                            }
                        request.method == HttpMethod.Get && request.url.toString() == uploadUrl ->
                            // The probe itself breaks too: the retry goes on from the offset it had.
                            throw IOException("connection reset")
                        else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val service = graphWith(engine)

            assertFailsWith<IOException> { service.uploadLargeFile(local, "/big.bin", conflictBehavior = "fail") }

            assertNotNull(UploadSessionStore(tokenDir).getSession("/big.bin"), "the session must survive for a resume")
            service.close()
        }

    private fun localFile(size: Long): Path {
        val f = Files.createTempFile(tokenDir, "big", ".bin")
        Files.write(f, ByteArray(size.toInt()) { 3 })
        return f
    }

    private fun graphWith(
        engine: MockEngine,
        limits: UploadWatchdogLimits = UploadWatchdogLimits.DEFAULT,
    ): GraphApiService {
        val service =
            GraphApiService(config = OneDriveConfig(tokenPath = tokenDir), uploadWatchdog = limits, tokenProvider = { _ -> "test-token" })
        val field = GraphApiService::class.java.getDeclaredField("httpClient")
        field.isAccessible = true
        (field.get(service) as? HttpClient)?.close()
        field.set(service, HttpClient(engine))
        return service
    }

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    private fun driveItemJson(size: Long): String =
        """{"id":"big-id","name":"big.bin","size":$size,"file":{"mimeType":"application/octet-stream"},"parentReference":{"path":"/drive/root:"}}"""
}
