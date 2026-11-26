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
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.RemoteContentRefusedDownloadException
import org.krost.unidrive.testing.TestScratch
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #845 (#747-7, #41): an Internxt download whose shard transfer breaks in the middle of the body keeps the plaintext that was
 * decrypted so far and asks the storage host for the rest with `Range: bytes=N-`. AES-CTR keeps the length, so a plaintext
 * offset is the ciphertext offset. The shard hash (over the whole ciphertext) and the size check still run over the whole
 * result: the kept prefix is re-encrypted into the digest, so a prefix that is not the bytes of this version fails the hash.
 *
 * Driven through the provider, so the real retry (`retryShardCommit`: bridge info, key and IV again, a fresh cipher) runs;
 * only the transport is faked. The fake storage host breaks the first body after [breakAt] bytes.
 */
class InternxtDownloadResumeTest {
    private val fileUuid = "file-uuid-1"
    private val fileId = "object-a"
    private val bucket = "6928426c1a2316b856c9ab81"
    private val rootUuid = "root-folder-uuid"
    private val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val crypto = InternxtCrypto()

    // Known plaintexts of one size: the second is "the file as it is after it was replaced".
    private val plaintextA = ByteArray(5_000) { ((it * 31 + it / 17) % 251).toByte() }
    private val plaintextB = ByteArray(5_000) { ((it * 29 + it / 5 + 9) % 251).toByte() }

    private var plaintext = plaintextA
    private var index64 = "22".repeat(32)
    private var breakAt = 1_600

    private val shardRanges = mutableListOf<String?>() // the Range header of each shard GET (null: none)
    private lateinit var dir: Path
    private lateinit var destination: Path

    // What the fake storage host does with the n-th shard request, when the test does not say otherwise.
    private var onShard: suspend MockRequestHandleScope.(Int, String?) -> HttpResponseData = { n, range -> defaultShard(n, range) }

    @AfterTest
    fun tearDown() {
        if (this::dir.isInitialized) dir.toFile().deleteRecursively()
    }

    private fun encrypted(content: ByteArray): ByteArray {
        val indexBytes = InternxtCrypto.hexToBytes(index64)
        val fileKey = crypto.deriveFileKey(crypto.deriveBucketKey(crypto.mnemonicToSeed(mnemonic), bucket), indexBytes)
        return crypto.createContentEncryptCipher(fileKey, indexBytes.copyOfRange(0, 16)).doFinal(content)
    }

    private fun sha256Hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @OptIn(DelicateCoroutinesApi::class)
    private fun brokenBody(
        bytes: ByteArray,
        upTo: Int,
    ): ByteReadChannel =
        GlobalScope
            .writer(autoFlush = true) {
                channel.writeFully(bytes, 0, upTo)
                delay(150) // real time, on the writer's own dispatcher: the reader takes what was flushed before the reset
                channel.cancel(IOException("connection reset"))
            }.channel

    private fun MockRequestHandleScope.whole(ciphertext: ByteArray) =
        respond(
            ciphertext,
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType to listOf("application/octet-stream"), HttpHeaders.ContentLength to listOf(ciphertext.size.toString())),
        )

    private fun MockRequestHandleScope.ranged(
        ciphertext: ByteArray,
        from: Int,
    ) = respond(
        ciphertext.copyOfRange(from, ciphertext.size),
        HttpStatusCode.PartialContent,
        headersOf(
            HttpHeaders.ContentType to listOf("application/octet-stream"),
            HttpHeaders.ContentLength to listOf((ciphertext.size - from).toString()),
            HttpHeaders.ContentRange to listOf("bytes $from-${ciphertext.size - 1}/${ciphertext.size}"),
        ),
    )

    private fun MockRequestHandleScope.defaultShard(
        n: Int,
        range: String?,
    ): HttpResponseData {
        val ciphertext = encrypted(plaintext)
        if (n == 0) {
            return respond(
                brokenBody(ciphertext, breakAt),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/octet-stream"), HttpHeaders.ContentLength to listOf(ciphertext.size.toString())),
            )
        }
        val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
        return if (from == null) whole(ciphertext) else ranged(ciphertext, from)
    }

    private fun fileJson(): String =
        """{"uuid":"$fileUuid","plainName":"a","type":"bin","bucket":"$bucket","fileId":"$fileId","size":"${plaintext.size}","status":"EXISTS",""" +
            """"folderUuid":"$rootUuid","modificationTime":"2026-11-05T12:00:00.000Z","updatedAt":"2026-11-05T12:00:00.000Z"}"""

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        val path = request.url.encodedPath
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        return when {
            path == "/drive/files/$fileUuid/meta" && request.method == HttpMethod.Get -> respond(fileJson(), HttpStatusCode.OK, json)
            path == "/buckets/$bucket/files/$fileId/info" && request.method == HttpMethod.Get ->
                respond(
                    """{"bucket":"$bucket","id":"$fileId","index":"$index64","shards":[{"index":0,"hash":"${sha256Hex(encrypted(plaintext))}","url":"https://shard-host.invalid/get/$fileId"}]}""",
                    HttpStatusCode.OK,
                    json,
                )
            request.url.host == "shard-host.invalid" && request.method == HttpMethod.Get -> {
                val n = shardRanges.size
                val range = request.headers[HttpHeaders.Range]
                shardRanges += range
                this.onShard(n, range)
            }
            else -> respond("""{"error":"unexpected: ${request.method} ${request.url}"}""", HttpStatusCode.NotFound, json)
        }
    }

    private fun newProvider(): InternxtProvider {
        dir = TestScratch.directory("ud-resume")
        destination = dir.resolve("out.bin")
        val payloadB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":9999999999}""".toByteArray())
        return InternxtProvider(
            InternxtConfig(credentialProtector = null, tokenPath = TestScratch.file("ud-resume-token", ".tmp")),
            httpClient = HttpClient(MockEngine { request -> handle(request) }),
            credentials =
                org.krost.unidrive.internxt.model.InternxtCredentials(
                    jwt = "header.$payloadB64.signature",
                    mnemonic = mnemonic,
                    rootFolderId = rootUuid,
                    email = "test@example.invalid",
                    bridgeUser = "bridge-user",
                    bridgeUserId = "bridge-secret",
                    bucket = bucket,
                ),
        )
    }

    private fun folder(): List<String> = Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }

    private fun rangeFrom(header: String?): Int = header!!.removePrefix("bytes=").removeSuffix("-").toInt()

    @Test
    fun `a broken download resumes from the staged prefix with a Range request and yields identical bytes`() =
        runTest {
            val provider = newProvider()

            provider.downloadById(fileUuid, "/a.bin", destination)

            assertContentEquals(plaintext, Files.readAllBytes(destination))
            assertEquals(2, shardRanges.size, "one broken attempt and one resume: $shardRanges")
            assertNull(shardRanges[0])
            assertTrue(rangeFrom(shardRanges[1]) in 1..breakAt, "the retry asks for the rest after the bytes that arrived: ${shardRanges[1]}")
            assertEquals(listOf("out.bin"), folder(), "no partial or record is left behind")
        }

    @Test
    fun `an Internxt resume at a non-block-aligned offset decrypts to the right plaintext`() =
        runTest {
            // 1003 = 62 * 16 + 11: the resumed bytes start inside a cipher block. Whatever part of the 1003 arrived (the
            // reader may have taken fewer), an offset that is not a block multiple is the case under test.
            breakAt = 1_003
            val provider = newProvider()

            provider.downloadById(fileUuid, "/a.bin", destination)

            assertContentEquals(plaintext, Files.readAllBytes(destination), "the bytes decrypted after the resume continue the keystream where the prefix ended")
            val from = rangeFrom(shardRanges[1])
            assertTrue(from % 16 != 0, "the resume offset $from is not block-aligned")
        }

    @Test
    fun `a changed remote version discards the staged prefix and restarts from zero`() =
        runTest {
            // The first body breaks; before the retry fetches the bridge info the file is replaced by one of the same size
            // (another encryption index, other bytes). Same size is the case a length check alone would not catch.
            onShard = { n, range ->
                val first = defaultShard(n, range)
                if (n == 0) {
                    plaintext = plaintextB
                    index64 = "33".repeat(32)
                }
                first
            }
            val provider = newProvider()

            provider.downloadById(fileUuid, "/a.bin", destination)

            assertEquals(listOf<String?>(null, null), shardRanges, "a prefix of the old version is not continued: no Range on the retry")
            assertContentEquals(plaintextB, Files.readAllBytes(destination), "no byte of the old version is in the result")
            assertEquals(listOf("out.bin"), folder())
        }

    @Test
    fun `a server that answers 200 to a Range request restarts from zero, without appending`() =
        runTest {
            onShard = { n, range -> if (n == 0) defaultShard(0, range) else whole(encrypted(plaintext)) }
            val provider = newProvider()

            provider.downloadById(fileUuid, "/a.bin", destination)

            assertTrue(shardRanges[1]!!.startsWith("bytes="), "the retry did ask with Range: $shardRanges")
            assertContentEquals(plaintext, Files.readAllBytes(destination), "the whole body replaced the prefix; appending would have doubled its start")
            assertEquals(plaintext.size.toLong(), Files.size(destination))
        }

    @Test
    fun `the whole-file hash check still rejects a corrupted prefix`() =
        runTest {
            onShard = { n, range ->
                if (n == 1) {
                    // The kept prefix is damaged on disk between the broken attempt and the resume; the server's rest is right.
                    val partial = dir.resolve("out.bin.part.unidrive-tmp")
                    val bytes = Files.readAllBytes(partial)
                    bytes[0] = (bytes[0] + 1).toByte()
                    Files.write(partial, bytes)
                }
                defaultShard(n, range)
            }
            val provider = newProvider()

            assertFailsWith<RemoteContentRefusedDownloadException> { provider.downloadById(fileUuid, "/a.bin", destination) }

            assertFalse(Files.exists(destination), "a result that fails the shard hash is never published")
            assertTrue(shardRanges[1]!!.startsWith("bytes="), "the corrupt prefix was resumed, so the check had to catch it: $shardRanges")
            assertEquals(emptyList(), folder(), "the refused prefix is deleted, not kept for another try")
        }
}
