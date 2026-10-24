package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.http.UploadTimeoutPolicy
import org.krost.unidrive.internxt.model.InternxtCredentials
import java.nio.file.Files
import java.nio.file.Path
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * #1065: the shared client caps every call at HttpDefaults.REQUEST_TIMEOUT_MS (600 s) and Ktor counts that cap up to
 * the end of the `execute` block, so a shard download that needs longer was cut however steadily it moved, and
 * retryShardCommit then started it again at byte 0, three times. The shard GET now sets its own request timeout
 * (scaled with the declared size, unbounded when the size is unknown); the read-idle socket timeout of the client
 * stays the stall detector. A transfer that did use up its budget is not started again at once.
 */
class InternxtDownloadTimeoutTest {
    private val chunk = ByteArray(1024) { (it % 251).toByte() }
    private val chunks = List(4) { chunk }
    private val totalBytes = chunks.sumOf { it.size }.toLong()

    private fun cipher(): Cipher =
        Cipher.getInstance("AES/CTR/NoPadding").also {
            it.init(Cipher.DECRYPT_MODE, SecretKeySpec(ByteArray(32) { b -> b.toByte() }, "AES"), IvParameterSpec(ByteArray(16)))
        }

    private fun tempDestination(): Path = Files.createTempDirectory("ud-dl-timeout").resolve("out.bin")

    // A real client engine: a MockEngine's response body is not tied to the call, so the request timeout cannot cut it.
    // The server sends 4 KiB over a second; the client allows a whole call 300 ms.
    private fun slowDownload(expectedSize: Long?) {
        val destination = tempDestination()
        LoopbackServer(tls = false) { exchange ->
            exchange.readRequestLine()
            exchange.respondStreamed(chunks, pauseMs = 250)
        }.use { server ->
            loopbackClient(server, socketTimeoutMs = 5_000, requestTimeoutMs = 300).use { client ->
                loopbackService(client, listingSocketMs = 5_000).use { api ->
                    val received =
                        runBlocking {
                            api.downloadFileStreaming("http://shard-host.invalid/get/shard-0", cipher(), destination, expectedSize = expectedSize)
                        }
                    assertEquals(totalBytes, received.bytesWritten)
                    assertEquals(totalBytes, Files.size(destination))
                }
            }
        }
    }

    @Test
    fun `a download that takes longer than the client request timeout still completes`() = slowDownload(expectedSize = totalBytes)

    @Test
    fun `a download of unknown size is not capped by the client request timeout either`() = slowDownload(expectedSize = null)

    @Test
    fun `the request timeout of a download scales with the declared size and is unbounded without one`() {
        assertEquals(UploadTimeoutPolicy.DEFAULT_FLOOR_MS, InternxtApiService.downloadRequestTimeoutMs(1L), "a small file keeps the 600 s floor")
        val twoGiB = 2L * 1024 * 1024 * 1024
        assertTrue(
            InternxtApiService.downloadRequestTimeoutMs(twoGiB) > 3_600_000L,
            "2 GiB must get far more than the 600 s the shared client allows",
        )
        assertEquals(HttpTimeoutConfig.INFINITE_TIMEOUT_MS, InternxtApiService.downloadRequestTimeoutMs(null))
        assertEquals(HttpTimeoutConfig.INFINITE_TIMEOUT_MS, InternxtApiService.downloadRequestTimeoutMs(0L))
    }

    @Test
    fun `a download that did use up its budget fails with a typed IOException`() =
        runBlocking {
            val destination = tempDestination()
            val client = HttpClient(MockEngine { throw HttpRequestTimeoutException("http://shard-host.invalid/get/shard-0", 600_000L) })
            mockService(client).use { api ->
                val e =
                    assertFailsWith<InternxtDownloadBudgetExhaustedException> {
                        api.downloadFileStreaming("http://shard-host.invalid/get/shard-0", cipher(), destination, expectedSize = 4096L)
                    }
                assertTrue(e is java.io.IOException, "stays an IOException: a later sync pass still retries it")
                assertTrue(e.cause is HttpRequestTimeoutException)
                assertTrue(Files.notExists(destination))
            }
        }

    private fun mockService(client: HttpClient): InternxtApiService =
        InternxtApiService(
            InternxtConfig(credentialProtector = null),
            credentialsProvider = { _ ->
                InternxtCredentials(jwt = "test-jwt", mnemonic = "test-mnemonic", rootFolderId = "test-root", email = "test@example.invalid")
            },
            httpClient = client,
        )

    // The provider-level view: one shard request, not three that each start at byte 0.
    @Test
    fun `a download whose budget ran out is not started again at byte 0`() {
        val fileUuid = "file-uuid-1"
        val fileId = "bridge-file-id-1"
        val bucket = "6928426c1a2316b856c9ab81"
        val index64 = "11".repeat(32)
        val requestLog = mutableListOf<String>()
        val destination = tempDestination()
        val provider = InternxtProvider(InternxtConfig(credentialProtector = null, tokenPath = Files.createTempFile("ud-dl-timeout-token", ".tmp")))
        val authField = InternxtProvider::class.java.getDeclaredField("authService")
        authField.isAccessible = true
        val authService = authField.get(provider)
        val credsField = authService.javaClass.getDeclaredField("credentials")
        credsField.isAccessible = true
        val payloadB64 =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString("""{"exp":9999999999}""".toByteArray())
        credsField.set(
            authService,
            InternxtCredentials(
                jwt = "header.$payloadB64.signature",
                mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
                rootFolderId = "root-folder-uuid",
                email = "test@example.invalid",
                bridgeUser = "bridge-user",
                bridgeUserId = "bridge-secret",
                bucket = bucket,
            ),
        )
        val engine =
            MockEngine { request: HttpRequestData ->
                requestLog += "${request.method} ${request.url.host}${request.url.encodedPath}"
                val json = headersOf(HttpHeaders.ContentType, "application/json")
                when {
                    request.url.encodedPath == "/drive/files/$fileUuid/meta" ->
                        respond("""{"uuid":"$fileUuid","bucket":"$bucket","fileId":"$fileId","size":"4096","status":"EXISTS"}""", HttpStatusCode.OK, json)
                    request.url.encodedPath == "/buckets/$bucket/files/$fileId/info" ->
                        respond(
                            """{"bucket":"$bucket","id":"$fileId","index":"$index64","shards":[{"index":0,"hash":"${"ab".repeat(32)}","url":"https://shard-host.invalid/get/shard-0"}]}""",
                            HttpStatusCode.OK,
                            json,
                        )
                    request.url.host == "shard-host.invalid" && request.method == HttpMethod.Get ->
                        throw HttpRequestTimeoutException("https://shard-host.invalid/get/shard-0", 600_000L)
                    else -> respond("{}", HttpStatusCode.NotFound, json)
                }
            }
        val apiField = InternxtProvider::class.java.getDeclaredField("api")
        apiField.isAccessible = true
        val api = apiField.get(provider) as InternxtApiService
        val httpClientField = InternxtApiService::class.java.getDeclaredField("httpClient")
        httpClientField.isAccessible = true
        (httpClientField.get(api) as? HttpClient)?.close()
        httpClientField.set(api, HttpClient(engine))

        assertFailsWith<java.io.IOException> {
            kotlinx.coroutines.test.runTest {
                provider.downloadById(fileUuid, "/big.bin", destination)
            }
        }

        assertEquals(1, requestLog.count { it.contains("shard-host.invalid") }, "no retry from byte 0: $requestLog")
    }
}
