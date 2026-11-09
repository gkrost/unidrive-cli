package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.http.HttpRetryBudget
import org.krost.unidrive.internxt.model.InternxtCredentials
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A storage host that throttles a shard GET or a shard PUT (429 or 503 with `Retry-After`) is waited for as long as it
 * asks: the exception carries the hint, the bridge budget records it, and the retries wait at least that long instead
 * of 1 s and 2 s (#262).
 */
class InternxtShardRetryAfterTest {
    private val credentials =
        InternxtCredentials(jwt = "jwt", mnemonic = "m", rootFolderId = "root", email = "e@example.invalid", bridgeUser = "bu", bridgeUserId = "bi")

    private fun cipher(): Cipher =
        Cipher.getInstance("AES/CTR/NoPadding").also {
            it.init(Cipher.DECRYPT_MODE, SecretKeySpec(ByteArray(32) { b -> b.toByte() }, "AES"), IvParameterSpec(ByteArray(16)))
        }

    private fun TestScope.bridgeBudget() =
        HttpRetryBudget(maxConcurrency = 64, stormThreshold = 1, minSpacingMs = 0, stormSpacingMs = 0, clock = { currentTime })

    private fun service(
        engine: MockEngine,
        bridge: HttpRetryBudget,
    ) = InternxtApiService(
        InternxtConfig(credentialProtector = null),
        credentialsProvider = { _, _ -> credentials },
        bridgeBudget = bridge,
        httpClient = HttpClient(engine),
    )

    private val throttled = headersOf(HttpHeaders.RetryAfter, "3")

    @Test
    fun `a throttled shard GET carries the Retry-After and the bridge budget records it`() =
        runTest {
            val bridge = bridgeBudget()
            val api = service(MockEngine { respond("", HttpStatusCode.TooManyRequests, throttled) }, bridge)
            val destination = Files.createTempDirectory("ud-shard-retry").resolve("out.bin")

            val e =
                assertFailsWith<InternxtApiException> {
                    api.downloadFileStreaming("https://shard-host.invalid/get/shard-0", cipher(), destination, expectedSize = 16L, expectedShardHash = null)
                }

            assertEquals(429, e.statusCode)
            assertEquals(3_000L, e.retryAfterMs)
            assertTrue(bridge.resumeAfterEpochMs() - currentTime >= 3_000L, "the bridge circuit stays open for the host's wait")
        }

    // The shard PUT the provider uses (the streaming one): the service itself does not retry it — the
    // provider's shard-commit ladder does, on the hint this test pins — so a throttled PUT comes back as
    // one attempt whose exception carries the Retry-After, and the bridge budget records the wait.
    @Test
    fun `a throttled shard PUT carries the Retry-After and the bridge budget records it`() =
        runTest {
            val bridge = bridgeBudget()
            val puts = mutableListOf<Long>()
            val engine =
                MockEngine {
                    puts += currentTime
                    respond("", HttpStatusCode.ServiceUnavailable, throttled)
                }
            val shard = Files.createTempFile("ud-shard-retry", ".bin").also { Files.write(it, ByteArray(16)) }

            val e =
                assertFailsWith<InternxtApiException> {
                    service(engine, bridge).putEncryptedShardFromFile("https://shard-host.invalid/put/shard-0", shard, 16L)
                }

            assertEquals(1, puts.size, "one attempt: the retry is the provider ladder's, not the service's")
            assertEquals(503, e.statusCode)
            assertEquals(3_000L, e.retryAfterMs)
            assertTrue(bridge.resumeAfterEpochMs() - currentTime >= 3_000L, "the bridge circuit stays open for the host's wait")
        }

    // The provider's view: the shard-commit ladder of a download waits as long as the host asks, every attempt.
    @Test
    fun `the shard-commit retry of a download waits for the host's Retry-After`() =
        runTest {
            val shardGets = throttledShardDownloadAttempts(retryAfter = "3")

            assertEquals(3, shardGets.size, "three attempts: $shardGets")
            for (i in 1 until shardGets.size) {
                assertTrue(shardGets[i] - shardGets[i - 1] >= 3_000L, "attempt ${i + 1} waited ${shardGets[i] - shardGets[i - 1]} ms, not the 3 s asked for")
            }
        }

    // The shared one-second floor (#220) holds on the shard-commit ladder too: a host that answers `Retry-After: 0`
    // is not asked again after half a second.
    @Test
    fun `the shard-commit retry waits the one-second floor when the host's Retry-After is zero`() =
        runTest {
            val shardGets = throttledShardDownloadAttempts(retryAfter = "0")

            assertEquals(3, shardGets.size, "three attempts: $shardGets")
            for (i in 1 until shardGets.size) {
                assertTrue(shardGets[i] - shardGets[i - 1] >= 1_000L, "attempt ${i + 1} waited ${shardGets[i] - shardGets[i - 1]} ms, below the 1 s floor")
            }
        }

    // Downloads a one-shard file whose every shard GET is a 429 with the given Retry-After, and returns the
    // virtual time of each shard GET.
    private suspend fun TestScope.throttledShardDownloadAttempts(retryAfter: String): List<Long> {
        val throttledReply = headersOf(HttpHeaders.RetryAfter, retryAfter)
        val fileUuid = "file-uuid-1"
        val fileId = "bridge-file-id-1"
        val bucket = "6928426c1a2316b856c9ab81"
        val shardGets = mutableListOf<Long>()
        val payloadB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":9999999999}""".toByteArray())
        val engine =
            MockEngine { request: HttpRequestData ->
                val json = headersOf(HttpHeaders.ContentType, "application/json")
                when {
                    request.url.encodedPath == "/drive/files/$fileUuid/meta" ->
                        respond("""{"uuid":"$fileUuid","bucket":"$bucket","fileId":"$fileId","size":"16","status":"EXISTS"}""", HttpStatusCode.OK, json)
                    request.url.encodedPath == "/buckets/$bucket/files/$fileId/info" ->
                        respond(
                            """{"bucket":"$bucket","id":"$fileId","index":"${"11".repeat(32)}","shards":[{"index":0,"hash":"${"ab".repeat(32)}","url":"https://shard-host.invalid/get/shard-0"}]}""",
                            HttpStatusCode.OK,
                            json,
                        )
                    request.url.host == "shard-host.invalid" && request.method == HttpMethod.Get -> {
                        shardGets += currentTime
                        respond("", HttpStatusCode.TooManyRequests, throttledReply)
                    }
                    else -> respond("{}", HttpStatusCode.NotFound, json)
                }
            }
        val provider =
            InternxtProvider(
                InternxtConfig(credentialProtector = null, tokenPath = Files.createTempFile("ud-shard-retry-token", ".tmp")),
                httpClient = HttpClient(engine),
                credentials =
                    credentials.copy(
                        jwt = "header.$payloadB64.signature",
                        mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
                        bucket = bucket,
                    ),
            )

        assertFailsWith<Exception> {
            provider.downloadById(fileUuid, "/big.bin", Files.createTempDirectory("ud-shard-retry").resolve("out.bin"))
        }
        return shardGets
    }
}
