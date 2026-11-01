package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The server-side upload session of a file revision survives a failed attempt AND the replay that follows it, whatever
 * conflict behaviour and If-Match the replay asks for, as long as resuming cannot overwrite more than the replay allows:
 * a session created with `fail` never overwrites anything, so it is resumed by a create, a keep-both and a guarded
 * replace alike. A session created to replace (with or without a guard) is resumed only by the same replace; anything
 * else starts a fresh session, so the If-Match guard is never weakened. A changed local revision still discards it.
 */
class OneDriveSessionReplayTest {
    private lateinit var tokenDir: Path
    private val chunk = 10L * 1024 * 1024
    private val fileSize = 3 * chunk
    private val committedAfterFailure = 2 * chunk

    @BeforeTest
    fun setUp() {
        tokenDir = Files.createTempDirectory("ud-session-replay")
    }

    @AfterTest
    fun tearDown() {
        Files.walk(tokenDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    // Attempt 1 gets 20 MiB through, then the third chunk fails with a 5xx on every retry. The replay resumes or not.
    private class Script(
        val chunk: Long,
        val fileSize: Long,
    ) {
        var failing = true
        val sessionUrls = mutableListOf<String>()
        val conflictBehaviors = mutableListOf<String>()
        val ifMatchOnCreate = mutableListOf<String?>()
        val replayRanges = mutableListOf<String>()

        fun engine() =
            MockEngine { request ->
                val url = request.url.toString()
                when {
                    request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/createUploadSession") -> {
                        val body = (request.body as io.ktor.http.content.TextContent).text
                        conflictBehaviors += Regex("conflictBehavior\":\"(\\w+)\"").find(body)!!.groupValues[1]
                        ifMatchOnCreate += request.headers[HttpHeaders.IfMatch]
                        val sessionUrl = "https://upload.example/session-${sessionUrls.size + 1}"
                        sessionUrls += sessionUrl
                        respond("""{"uploadUrl":"$sessionUrl","expirationDateTime":"2030-01-01T00:00:00Z"}""", HttpStatusCode.OK, json())
                    }
                    request.method == HttpMethod.Put && url.startsWith("https://upload.example/") -> {
                        val range = request.headers["Content-Range"]!!
                        if (!failing) replayRanges += range
                        when {
                            range.startsWith("bytes ${2 * chunk}-") && failing ->
                                respond("""{"error":{"code":"serviceNotAvailable"}}""", HttpStatusCode.InternalServerError, json())
                            range.startsWith("bytes ${2 * chunk}-") ->
                                respond(
                                    """{"id":"big-id","name":"big.bin","size":$fileSize,"file":{},"parentReference":{"path":"/drive/root:"}}""",
                                    HttpStatusCode.Created,
                                    json(),
                                )
                            else -> respond("{}", HttpStatusCode.Accepted, json())
                        }
                    }
                    request.method == HttpMethod.Get && url.startsWith("https://upload.example/") -> {
                        val held = if (url == sessionUrls.firstOrNull()) 2 * chunk else 0L
                        respond("""{"nextExpectedRanges":["$held-"],"expirationDateTime":"2030-01-01T00:00:00Z"}""", HttpStatusCode.OK, json())
                    }
                    else -> respond("{}", HttpStatusCode.OK, json())
                }
            }

        private fun json() = headersOf(HttpHeaders.ContentType, "application/json")
    }

    private suspend fun failFirstAttempt(
        service: GraphApiService,
        local: Path,
        script: Script,
        conflictBehavior: String,
        ifMatch: String?,
    ) {
        assertFailsWith<GraphApiException> {
            service.uploadLargeFile(local, "/big.bin", conflictBehavior = conflictBehavior, ifMatchETag = ifMatch)
        }
        script.failing = false
    }

    @Test
    fun `a guarded replace replaying a failed create resumes the create's session at its offset`() =
        runTest {
            val script = Script(chunk, fileSize)
            val service = graphWith(script.engine())
            val local = localFile()
            failFirstAttempt(service, local, script, "fail", null)

            // The engine has meanwhile seen the item at the name and replays the upload as a replace of it.
            service.uploadLargeFile(local, "/big.bin", conflictBehavior = "replace", ifMatchETag = "\"stub-etag,1\"")

            assertEquals(listOf("fail"), script.conflictBehaviors, "no second session may be created for the same revision")
            assertEquals(listOf("bytes $committedAfterFailure-${fileSize - 1}/$fileSize"), script.replayRanges)
            service.close()
        }

    @Test
    fun `a keep-both replaying a failed create resumes the create's session at its offset`() =
        runTest {
            val script = Script(chunk, fileSize)
            val service = graphWith(script.engine())
            val local = localFile()
            failFirstAttempt(service, local, script, "fail", null)

            service.uploadLargeFile(local, "/big.bin", conflictBehavior = "rename")

            assertEquals(listOf("fail"), script.conflictBehaviors)
            assertEquals(listOf("bytes $committedAfterFailure-${fileSize - 1}/$fileSize"), script.replayRanges)
            service.close()
        }

    @Test
    fun `a create replaying a failed guarded replace starts a fresh session`() =
        runTest {
            val script = Script(chunk, fileSize)
            val service = graphWith(script.engine())
            val local = localFile()
            failFirstAttempt(service, local, script, "replace", "\"remote-etag,4\"")

            service.uploadLargeFile(local, "/big.bin", conflictBehavior = "fail")

            assertEquals(listOf("replace", "fail"), script.conflictBehaviors, "a replace session would overwrite what a create must keep")
            assertEquals(listOf<String?>("\"remote-etag,4\"", null), script.ifMatchOnCreate)
            assertEquals("bytes 0-${chunk - 1}/$fileSize", script.replayRanges.first())
            service.close()
        }

    @Test
    fun `a replace under a newer guard does not resume the session of the older guard`() =
        runTest {
            val script = Script(chunk, fileSize)
            val service = graphWith(script.engine())
            val local = localFile()
            failFirstAttempt(service, local, script, "replace", "\"remote-etag,4\"")

            service.uploadLargeFile(local, "/big.bin", conflictBehavior = "replace", ifMatchETag = "\"remote-etag,5\"")

            assertEquals(listOf<String?>("\"remote-etag,4\"", "\"remote-etag,5\""), script.ifMatchOnCreate)
            service.close()
        }

    @Test
    fun `a replay of the same guarded replace resumes its session`() =
        runTest {
            val script = Script(chunk, fileSize)
            val service = graphWith(script.engine())
            val local = localFile()
            failFirstAttempt(service, local, script, "replace", "\"remote-etag,4\"")

            service.uploadLargeFile(local, "/big.bin", conflictBehavior = "replace", ifMatchETag = "\"remote-etag,4\"")

            assertEquals(listOf("replace"), script.conflictBehaviors)
            assertEquals(listOf("bytes $committedAfterFailure-${fileSize - 1}/$fileSize"), script.replayRanges)
            service.close()
        }

    @Test
    fun `a changed local revision discards the create's session even for a keep-both`() =
        runTest {
            val script = Script(chunk, fileSize)
            val service = graphWith(script.engine())
            val local = localFile()
            failFirstAttempt(service, local, script, "fail", null)
            Files.setLastModifiedTime(local, java.nio.file.attribute.FileTime.fromMillis(Files.getLastModifiedTime(local).toMillis() + 60_000))

            service.uploadLargeFile(local, "/big.bin", conflictBehavior = "rename")

            assertEquals(listOf("fail", "rename"), script.conflictBehaviors)
            service.close()
        }

    private fun localFile(): Path {
        val f = Files.createTempFile(tokenDir, "big", ".bin")
        Files.write(f, ByteArray(fileSize.toInt()) { 5 })
        return f
    }

    private fun graphWith(engine: MockEngine): GraphApiService {
        val service = GraphApiService(config = OneDriveConfig(tokenPath = tokenDir), httpClient = HttpClient(engine), tokenProvider = { _ -> "test-token" })
        return service
    }
}
