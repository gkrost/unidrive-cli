package org.krost.unidrive.internxt

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
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.ContentProof
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * gkrost/unidrive#1219 through the provider: [InternxtProvider.contentMatchesLocal] with the real key derivation (the account's mnemonic,
 * the bucket of the FILE, the file's own index) over a mocked drive and bridge. The stored object is a real AES-CTR ciphertext whose shard
 * hash is recorded in the official clients' shape, and the same object is also served for a download: the proof and the download must agree
 * on the key, and the proof must never fetch the object.
 */
class InternxtContentMatchesLocalTest {
    private val fileUuid = "file-uuid-1"
    private val fileId = "bridge-file-id-1"
    private val bucket = "6928426c1a2316b856c9ab81"
    private val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val index = ByteArray(32) { (it * 3 + 9).toByte() }
    private val plain = ByteArray(300_000) { (it * 13 + it / 5).toByte() }
    private val requestLog = mutableListOf<String>()

    private val crypto = InternxtCrypto()
    private val fileKey = crypto.deriveFileKey(crypto.deriveBucketKey(crypto.mnemonicToSeed(mnemonic), bucket), index)
    private val ciphertext = crypto.createContentEncryptCipher(fileKey, index.copyOfRange(0, 16)).doFinal(plain)
    private val shardHash = Ripemd160.digest(MessageDigest.getInstance("SHA-256").digest(ciphertext)).joinToString("") { "%02x".format(it) }

    private fun provider(): InternxtProvider {
        val provider = InternxtProvider(InternxtConfig(credentialProtector = null, tokenPath = Files.createTempFile("ud-proof-token", ".tmp")))
        val authService = InternxtProvider::class.java.getDeclaredField("authService").also { it.isAccessible = true }.get(provider)
        val credsField = authService.javaClass.getDeclaredField("credentials").also { it.isAccessible = true }
        val payloadB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":9999999999}""".toByteArray())
        credsField.set(
            authService,
            org.krost.unidrive.internxt.model.InternxtCredentials(
                jwt = "header.$payloadB64.signature",
                mnemonic = mnemonic,
                rootFolderId = "root-folder-uuid",
                email = "test@example.invalid",
                bridgeUser = "bridge-user",
                bridgeUserId = "bridge-secret",
                bucket = "another-bucket-of-the-account",
            ),
        )
        val api = InternxtProvider::class.java.getDeclaredField("api").also { it.isAccessible = true }.get(provider) as InternxtApiService
        val clientField = InternxtApiService::class.java.getDeclaredField("httpClient").also { it.isAccessible = true }
        (clientField.get(api) as? HttpClient)?.close()
        clientField.set(api, HttpClient(MockEngine { request -> handle(request) }))
        return provider
    }

    private fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        val path = request.url.encodedPath
        requestLog += "${request.method} ${request.url.host}$path"
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        return when {
            path == "/drive/files/$fileUuid/meta" && request.method == HttpMethod.Get ->
                respond(
                    """{"uuid":"$fileUuid","bucket":"$bucket","fileId":"$fileId","size":"${plain.size}","encryptVersion":"03-aes","status":"EXISTS"}""",
                    HttpStatusCode.OK,
                    json,
                )
            path == "/buckets/$bucket/files/$fileId/info" && request.method == HttpMethod.Get ->
                respond(
                    """{"bucket":"$bucket","id":"$fileId","index":"${index.joinToString("") { "%02x".format(it) }}",""" +
                        """"shards":[{"index":0,"hash":"$shardHash","url":"https://shard-host.invalid/get/0"}]}""",
                    HttpStatusCode.OK,
                    json,
                )
            request.url.host == "shard-host.invalid" && request.method == HttpMethod.Get ->
                respond(ciphertext, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/octet-stream"))
            else -> respond("""{"error":"unexpected: ${request.method} ${request.url.host}$path"}""", HttpStatusCode.NotFound, json)
        }
    }

    private fun localFile(bytes: ByteArray): Path = Files.createTempFile("ud-proof-local", ".bin").also { Files.write(it, bytes) }

    @Test
    fun `the file of the stored bytes is proven the same with the key of the files own bucket, and nothing is downloaded`() =
        runTest {
            val answer = provider().contentMatchesLocal(fileUuid, "/docs/a.bin", localFile(plain))

            assertEquals(ContentProof(true, InternxtLocalContentProof.METHOD_SHARD_HASH), answer)
            assertTrue(requestLog.none { it.contains("shard-host.invalid") }, "the object was fetched: $requestLog")
        }

    @Test
    fun `a file that differs in one byte is proven different`() =
        runTest {
            val other = plain.copyOf().also { it[123_456] = (it[123_456] + 1).toByte() }

            assertEquals(ContentProof(false, InternxtLocalContentProof.METHOD_SHARD_HASH), provider().contentMatchesLocal(fileUuid, "/docs/a.bin", localFile(other)))
        }

    @Test
    fun `the proof and the download agree on the key, so the downloaded file is proven the same`() =
        runTest {
            val provider = provider()
            val downloaded = Files.createTempDirectory("ud-proof-dl").resolve("out.bin")

            provider.downloadById(fileUuid, "/docs/a.bin", downloaded)

            assertTrue(plain.contentEquals(Files.readAllBytes(downloaded)), "the fixture ciphertext decrypts to the plaintext")
            assertEquals(ContentProof(true, InternxtLocalContentProof.METHOD_SHARD_HASH), provider.contentMatchesLocal(fileUuid, "/docs/a.bin", downloaded))
        }

    @Test
    fun `a drive that does not know the file raises, it is never turned into a verdict`() =
        runTest {
            kotlin.test.assertFailsWith<InternxtApiException> { provider().contentMatchesLocal("no-such-file", "/gone.bin", localFile(plain)) }
        }
}
