package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#393: the encrypt pass is the one read of the source. It hashes the plaintext as it goes and reports
 * the SHA-256 on the answer ([org.krost.unidrive.CloudItem.sentSha256]), so the engine need not read the file again to
 * record what was sent; and the single PUT to the storage backend carries the ciphertext once, no more than the payload
 * (a stream cipher: as long as the plaintext).
 */
class InternxtUploadSourceReadTest {
    private val bucket = "6928426c1a2316b856c9ab81"
    private val shardUrl = "https://shard-host.invalid/read-once-put"
    private val putBodies: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    private val tmp: Path = Files.createTempDirectory("ud-393-")

    @AfterTest
    fun cleanUp() {
        tmp.toFile().deleteRecursively()
    }

    private fun newProvider(): InternxtProvider {
        val payloadB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":9999999999}""".toByteArray())
        val json = headersOf("Content-Type", "application/json")
        return InternxtProvider(
            InternxtConfig(credentialProtector = null, tokenPath = tmp),
            httpClient =
                HttpClient(
                    MockEngine { request ->
                        val url = request.url.toString()
                        when {
                            url.contains("/v2/buckets/") && url.contains("/files/start") ->
                                respond("""{"uploads":[{"index":0,"uuid":"shard-1","url":"$shardUrl"}]}""", HttpStatusCode.OK, json)
                            url == shardUrl -> {
                                putBodies += (request.body.contentLength ?: -1L)
                                respond("", HttpStatusCode.OK)
                            }
                            url.contains("/v2/buckets/") && url.contains("/files/finish") ->
                                respond("""{"id":"bucket-entry-1","index":"${"aa".repeat(32)}","bucket":"$bucket","name":"enc"}""", HttpStatusCode.OK, json)
                            request.url.encodedPath == "/drive/files" && request.method == HttpMethod.Post ->
                                respond("""{"uuid":"new-uuid","plainName":"x","type":"bin","size":"1","bucket":"$bucket","status":"EXISTS"}""", HttpStatusCode.OK, json)
                            else -> respond("""{"error":"unexpected ${request.method.value} $url"}""", HttpStatusCode.NotFound, json)
                        }
                    },
                ),
            credentials =
                org.krost.unidrive.internxt.model.InternxtCredentials(
                    jwt = "header.$payloadB64.signature",
                    mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
                    rootFolderId = "root-folder-uuid",
                    email = "test@example.invalid",
                    bridgeUser = "bridge-user",
                    bridgeUserId = "bridge-secret",
                    bucket = bucket,
                ),
        )
    }

    @Test
    fun `an upload reports the SHA-256 of the plaintext it read and sends the payload once`() =
        runTest {
            val bytes = ByteArray(100_000) { (it * 31 % 251).toByte() }
            val file = tmp.resolve("blob.bin").also { Files.write(it, bytes) }
            val provider = newProvider()
            try {
                val item = provider.upload(file, "/blob.bin", existingRemoteId = null, onProgress = null)

                val expected = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                assertEquals(expected, item.sentSha256, "the hash of the bytes the encrypt pass read")
                val wire = putBodies.single()
                assertTrue(wire in bytes.size.toLong()..(bytes.size * 105L / 100), "wire bytes $wire for a payload of ${bytes.size}")
            } finally {
                provider.close()
            }
        }

    @Test
    fun `an empty file reports no hash, there is nothing that was read`() =
        runTest {
            val file = tmp.resolve("empty.bin").also { Files.write(it, ByteArray(0)) }
            val provider = newProvider()
            try {
                val item = provider.upload(file, "/empty.bin", existingRemoteId = null, onProgress = null)
                assertEquals(null, item.sentSha256)
                assertNotNull(item)
            } finally {
                provider.close()
            }
        }
}
