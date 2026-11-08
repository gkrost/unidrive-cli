package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.testing.CloudProviderConformanceTest
import org.krost.unidrive.onedrive.model.Token
import java.nio.file.Files
import java.nio.file.Path

/**
 * The SPI's conformance contracts against [OneDriveProvider].
 *
 * The write contracts run against [SimilarNameGraph], the stateful fake that resolves by id and by path, so the
 * id-addressed contracts observe real outcomes: the item the id names is the one that moves or dies, and the item
 * at the handed path survives. The conditional-write contracts run against a scripted fake that emulates Graph's
 * `If-Match` (412 Precondition Failed on a mismatching token), the shape [OneDriveIfMatchTest] measured against the
 * live service — a stateful fake without conditional writes could not answer a stale token with the refusal the
 * contract pins. Structural and reload contracts run against the plain provider over a stored token file: reload
 * reads it in place, never the browser.
 */
class OneDriveConformanceTest : CloudProviderConformanceTest() {
    override val storedCredentialsRemovable: Boolean = true

    private lateinit var dir: Path
    private lateinit var graph: SimilarNameGraph

    private var guardMode = false
    private var guardSeededPath = ""
    private val guardEtag = "etag-conformance-1"

    override fun newProvider(): CloudProvider {
        dir = Files.createTempDirectory("conformance-od")
        val config = OneDriveConfig(tokenPath = dir)
        kotlinx.coroutines.runBlocking {
            OAuthService(config).use {
                it.saveToken(
                    Token(
                        accessToken = "a".repeat(64),
                        tokenType = "Bearer",
                        expiresAt = System.currentTimeMillis() + 3_600_000L,
                        refreshToken = "fake-refresh",
                    ),
                )
            }
        }
        return OneDriveProvider(config)
    }

    override fun newWriteProvider(): CloudProvider {
        guardMode = false
        dir = Files.createTempDirectory("conformance-od-write")
        graph = SimilarNameGraph()
        val config = OneDriveConfig(tokenPath = dir)
        val api = GraphApiService(config = config, httpClient = graph.client(), tokenProvider = { _, _ -> "test-token" })
        return OneDriveProvider(config, graphApi = api)
    }

    override fun newGuardProvider(): CloudProvider {
        guardMode = true
        dir = Files.createTempDirectory("conformance-od-guard")
        val config = OneDriveConfig(tokenPath = dir)
        val api = GraphApiService(config = config, httpClient = HttpClient(guardEngine()), tokenProvider = { _, _ -> "test-token" })
        return OneDriveProvider(config, graphApi = api)
    }

    override suspend fun seedFile(
        provider: CloudProvider,
        path: String,
        content: String,
    ): CloudItem {
        if (guardMode) {
            guardSeededPath = path
        } else {
            graph.seed(path, bytes = content.toByteArray())
        }
        return provider.getMetadata(path)
    }

    override fun localFile(content: String): Path =
        Files.createTempFile("conformance-local", ".txt").also { Files.writeString(it, content) }

    override fun clearStoredCredentials() {
        Files.delete(dir.resolve("token.json"))
    }

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    private fun driveItemJson(
        id: String,
        name: String,
        eTag: String,
    ): String =
        """{"id":"$id","name":"$name","size":5,"eTag":"$eTag","file":{"mimeType":"application/octet-stream"},""" +
            """"parentReference":{"path":"/drive/root:"}}"""

    private fun MockRequestHandleScope.preconditionFailed(): HttpResponseData =
        respond(
            """{"error":{"code":"resourceModified","message":"The precondition does not match the item's current state"}}""",
            HttpStatusCode.PreconditionFailed,
            jsonHeaders(),
        )

    /** Graph's conditional write: a content PUT or a DELETE carrying a mismatching `If-Match` is refused with 412. */
    private fun guardEngine(): MockEngine =
        MockEngine { request ->
            handleGuard(request)
        }

    private fun MockRequestHandleScope.handleGuard(request: HttpRequestData): HttpResponseData {
        val item = driveItemJson("conformance-id", guardSeededPath.trimStart('/'), guardEtag)
        return when {
            request.method == HttpMethod.Get && request.url.encodedPath.contains("/root:/") ->
                respond(item, HttpStatusCode.OK, jsonHeaders())
            request.method == HttpMethod.Put && request.url.encodedPath.endsWith("/content") ->
                if (request.headers[HttpHeaders.IfMatch] != guardEtag) preconditionFailed()
                else respond(item, HttpStatusCode.OK, jsonHeaders())
            request.method == HttpMethod.Delete ->
                if (request.headers[HttpHeaders.IfMatch] != guardEtag) preconditionFailed()
                else respond("", HttpStatusCode.NoContent)
            else ->
                respond(
                    """{"error":{"code":"invalidRequest","message":"unexpected ${request.method} ${request.url.encodedPath}"}}""",
                    HttpStatusCode.BadRequest,
                    jsonHeaders(),
                )
        }
    }
}
