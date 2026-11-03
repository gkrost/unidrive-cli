package org.krost.unidrive.onedrive

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.http.RequestMetricsTap
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bearer token must not leave Graph. A download of an item without a pre-authenticated URL asks
 * `/me/drive/items/{id}/content` with the bearer; Graph answers 302 to a CDN host. That second request, to
 * another authority, must carry no `Authorization`. The test runs through the production client ([graphHttpClient]),
 * so it pins the behaviour if the HTTP client or its redirect handling is ever replaced.
 */
class DownloadRedirectAuthorizationTest {
    @Test
    fun `a content redirect to another host is followed without the Authorization header`() =
        runTest {
            val seen = mutableListOf<Pair<String, String?>>()
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    seen += url to request.headers[HttpHeaders.Authorization]
                    when {
                        url.contains("cdn.example.net") ->
                            respond(
                                content = "hello",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType to listOf("application/octet-stream"), HttpHeaders.ContentLength to listOf("5")),
                            )
                        url.endsWith("/content") ->
                            respond(
                                content = "",
                                status = HttpStatusCode.Found,
                                headers = headersOf(HttpHeaders.Location, "https://cdn.example.net/blob/abc?tempauth=x"),
                            )
                        else ->
                            // Item metadata without @microsoft.graph.downloadUrl: the download takes the bearer path.
                            respond(
                                content = """{"id":"ITEM-1","name":"a.txt","size":5}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                    }
                }
            val service =
                GraphApiService(
                    config = OneDriveConfig(),
                    httpClient = graphHttpClient(RequestMetricsTap(), OneDriveConfig.DEFAULT_USER_AGENT, engine),
                    tokenProvider = { _, _ -> "secret-token" },
                )
            val dir = Files.createTempDirectory("redirect-auth")
            try {
                val dest = dir.resolve("a.txt")
                service.downloadFile("ITEM-1", dest)
                assertEquals("hello", Files.readString(dest))

                val first = seen.firstOrNull { it.first.endsWith("/items/ITEM-1/content") }
                val second = seen.firstOrNull { it.first.contains("cdn.example.net") }
                assertNotNull(first, "the content request must be made")
                assertNotNull(second, "the redirect must be followed")
                assertEquals("Bearer secret-token", first.second, "the first request carries the bearer")
                assertNull(second.second, "the request to another host must carry no Authorization, got ${second.second}")
                assertTrue(seen.none { it.first.contains("cdn.example.net") && it.second != null })
            } finally {
                runCatching { dir.toFile().deleteRecursively() }
            }
        }
}
