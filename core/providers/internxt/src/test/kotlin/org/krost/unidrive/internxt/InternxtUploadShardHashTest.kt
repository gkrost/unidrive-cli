package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * archive#946: a new upload records RIPEMD-160(SHA-256(ciphertext)) as the shard hash in `files/finish`, 40 hex, like the official clients
 * (it used to record the plain SHA-256, 64 hex, which the official clients do not expect). The test hashes the ciphertext the provider
 * really PUT and compares: the value on the wire is derived from those bytes, and the download verification accepts it.
 */
class InternxtUploadShardHashTest {
    private val bucket = "6928426c1a2316b856c9ab81"
    private val shardUrl = "https://shard-host.invalid/hash-put"
    private val putBodies: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
    private val finishHashes: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val tmp: Path = Files.createTempDirectory("ud-shardhash-")

    @AfterTest
    fun cleanUp() {
        tmp.toFile().deleteRecursively()
    }

    private suspend fun drain(content: io.ktor.http.content.OutgoingContent): ByteArray =
        when (content) {
            is io.ktor.http.content.ByteArrayContent -> content.bytes()
            is io.ktor.http.content.TextContent -> content.bytes()
            is io.ktor.http.content.OutgoingContent.ReadChannelContent -> content.readFrom().toByteArray()
            is io.ktor.http.content.OutgoingContent.WriteChannelContent ->
                coroutineScope {
                    val channel = io.ktor.utils.io.ByteChannel(autoFlush = true)
                    val writer =
                        async(kotlinx.coroutines.Dispatchers.IO) {
                            try {
                                content.writeTo(channel)
                            } finally {
                                channel.flushAndClose()
                            }
                        }
                    val bytes = channel.toByteArray()
                    writer.await()
                    bytes
                }
            else -> error("unexpected OutgoingContent type: ${content.javaClass}")
        }

    private fun newProvider(): InternxtProvider {
        val provider = InternxtProvider(InternxtConfig(credentialProtector = null, tokenPath = tmp))
        val authField = InternxtProvider::class.java.getDeclaredField("authService")
        authField.isAccessible = true
        val authService = authField.get(provider)
        val credsField = authService.javaClass.getDeclaredField("credentials")
        credsField.isAccessible = true
        val payloadB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":9999999999}""".toByteArray())
        credsField.set(
            authService,
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
        val apiField = InternxtProvider::class.java.getDeclaredField("api")
        apiField.isAccessible = true
        val api = apiField.get(provider) as InternxtApiService
        val httpField = InternxtApiService::class.java.getDeclaredField("httpClient")
        httpField.isAccessible = true
        (httpField.get(api) as? HttpClient)?.close()
        val json = headersOf("Content-Type", "application/json")
        httpField.set(
            api,
            HttpClient(
                MockEngine { request ->
                    val url = request.url.toString()
                    when {
                        url.contains("/v2/buckets/") && url.contains("/files/start") ->
                            respond("""{"uploads":[{"index":0,"uuid":"shard-1","url":"$shardUrl"}]}""", HttpStatusCode.OK, json)
                        url == shardUrl -> {
                            putBodies += drain(request.body)
                            respond("", HttpStatusCode.OK)
                        }
                        url.contains("/v2/buckets/") && url.contains("/files/finish") -> {
                            val text = String(drain(request.body), Charsets.UTF_8)
                            finishHashes += Json.parseToJsonElement(text).jsonObject["shards"]!!.jsonArray.single().jsonObject["hash"]!!.jsonPrimitive.content
                            respond("""{"id":"bucket-entry-1","index":"${"aa".repeat(32)}","bucket":"$bucket","name":"enc"}""", HttpStatusCode.OK, json)
                        }
                        request.url.encodedPath == "/drive/files" && request.method == HttpMethod.Post ->
                            respond("""{"uuid":"new-uuid","plainName":"x","type":"txt","size":"1","bucket":"$bucket","status":"EXISTS"}""", HttpStatusCode.OK, json)
                        else -> respond("""{"error":"unexpected ${request.method.value} $url"}""", HttpStatusCode.NotFound, json)
                    }
                },
            ),
        )
        return provider
    }

    @Test
    fun `a new upload records RIPEMD-160 of the SHA-256 of the ciphertext it sent, 40 hex`() =
        runTest {
            val provider = newProvider()
            try {
                val local = tmp.resolve("letter.txt").also { Files.write(it, ByteArray(5000) { i -> (i % 251).toByte() }) }

                provider.upload(local, "/letter.txt", existingRemoteId = null, onProgress = null)

                val ciphertext = putBodies.single()
                val sha256 = MessageDigest.getInstance("SHA-256").digest(ciphertext)
                val recorded = finishHashes.single()
                assertEquals(40, recorded.length, "the shard hash is RIPEMD-160(SHA-256), 40 hex, not the plain SHA-256 (64)")
                assertEquals(Ripemd160.digest(sha256).joinToString("") { "%02x".format(it) }, recorded)
                // the round trip: what the upload recorded is what the download verification checks the same ciphertext against
                val expectation = ShardHashExpectation.parse(recorded)
                assertIs<ShardHashExpectation.Ripemd160OfSha256>(expectation)
                assertTrue(expectation.matches(sha256))
            } finally {
                provider.close()
            }
        }

    @Test
    fun `an object an older build stored with a plain SHA-256 is still verified on download`() {
        val sha256 = MessageDigest.getInstance("SHA-256").digest("ciphertext of an older upload".toByteArray())
        val oldRecord = sha256.joinToString("") { "%02x".format(it) }

        val expectation = ShardHashExpectation.parse(oldRecord)

        assertIs<ShardHashExpectation.Sha256>(expectation)
        assertTrue(expectation.matches(sha256))
    }
}
