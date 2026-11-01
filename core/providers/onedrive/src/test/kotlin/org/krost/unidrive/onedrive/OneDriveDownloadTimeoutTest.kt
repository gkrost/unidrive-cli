package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.HttpDefaults
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The client's flat `HttpDefaults.REQUEST_TIMEOUT_MS` (10 minutes) is counted up to the end of the `execute` block, so
 * it cuts a download that needs longer however steadily the bytes arrive: 5 GiB at 6 MiB/s is 14 minutes, and every
 * retry meets the same cap. Internxt sizes the cap of a download from the declared size (`downloadRequestTimeoutMs`);
 * uploads of every provider do too (`UploadTimeoutPolicy`).
 */
class OneDriveDownloadTimeoutTest {
    @Test
    fun `a download of a big file is not under the flat 10 minute request cap`() =
        runTest {
            val fiveGiB = 5L * 1024 * 1024 * 1024
            val contentTimeouts = mutableListOf<Long?>()
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    if (url.startsWith("https://cdn.example/")) {
                        contentTimeouts += request.getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis
                        respond(content = ByteArray(4), status = HttpStatusCode.OK)
                    } else {
                        respond(
                            content =
                                """{"id":"item-1","name":"big.bin","size":$fiveGiB,"file":{"mimeType":"application/octet-stream"},
                                   "@microsoft.graph.downloadUrl":"https://cdn.example/big"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }
            val service =
                GraphApiService(
                    config = OneDriveConfig(),
                    // The same timeout plugin as graphHttpClient: the flat defaults, nothing per request.
                    httpClient =
                        HttpClient(engine) {
                            install(HttpTimeout) {
                                connectTimeoutMillis = HttpDefaults.CONNECT_TIMEOUT_MS
                                socketTimeoutMillis = HttpDefaults.SOCKET_TIMEOUT_MS
                                requestTimeoutMillis = HttpDefaults.REQUEST_TIMEOUT_MS
                            }
                        },
                    tokenProvider = { "tok" },
                )

            // The 4-byte answer is shorter than the declared size: the outcome does not matter, the request's timeout does.
            runCatching { service.downloadFile("item-1", Files.createTempFile("od-timeout", ".bin")) }

            val timeout = assertNotNull(contentTimeouts.firstOrNull(), "the content GET carried a request timeout")
            assertTrue(
                timeout > HttpDefaults.REQUEST_TIMEOUT_MS,
                "a 5 GiB download is sized from its declared size, not the flat ${HttpDefaults.REQUEST_TIMEOUT_MS} ms (got $timeout)",
            )
        }
}
