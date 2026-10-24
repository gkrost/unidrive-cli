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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * archive#578: multipart upload of a large file — parts in parallel up to the configured
 * cap, one part's retry ladder independent of the others, per-part resume from the
 * upload tombstone, a finish call that is never sent with an incomplete part list,
 * and the assembled ciphertext decrypting back to the plaintext (concurrency 1's
 * byte-for-byte property, proven through decryption rather than a re-implementation).
 */
class InternxtMultipartUploadTest {
    private val bucket = "6928426c1a2316b856c9ab81"
    private val tmp: Path = Files.createTempDirectory("ud-multipart-")

    // Wire record: every bridge/PUT request, the part bodies by part index, the finish bodies.
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val partBodies: ConcurrentHashMap<Int, ByteArray> = ConcurrentHashMap()
    private val finishBodies: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
    private val startBodySizes: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    private val putInFlight = AtomicInteger()
    private val putMaxInFlight = AtomicInteger()
    private val putStarted = ConcurrentHashMap<Int, AtomicInteger>()

    // Failure injection: a part index -> how many more times its PUT answers 500 before succeeding.
    private val failPart: ConcurrentHashMap<Int, AtomicInteger> = ConcurrentHashMap()

    // Failure injection: a part index -> how many more times its PUT answers 403 (a dead presigned URL).
    private val failPart403: ConcurrentHashMap<Int, AtomicInteger> = ConcurrentHashMap()

    // Failure injection: how many more times the finish endpoint answers 409 MissingUploadsError.
    private val failFinish409 = AtomicInteger(0)

    // One per started multipart session, so a test can tell sessions apart (upload-id-1, upload-id-2, ...).
    private val multipartStarts = AtomicInteger(0)

    // Parallelism gate: every `gateSize`-th concurrently-in-flight PUT waits until the group is up.
    private val gateSize = AtomicInteger(0)
    private var gate = CompletableDeferred<Unit>()

    @AfterTest
    fun cleanUp() {
        resetWire()
        tmp.toFile().deleteRecursively()
    }

    private fun resetWire() {
        requests.clear()
        partBodies.clear()
        finishBodies.clear()
        startBodySizes.clear()
        putInFlight.set(0)
        putMaxInFlight.set(0)
        putStarted.clear()
        failPart.clear()
        failPart403.clear()
        failFinish409.set(0)
        multipartStarts.set(0)
        gateSize.set(0)
        gate = CompletableDeferred()
    }

    private fun newProvider(
        cutoffMb: Int = 0,
        chunkMb: Int = 1,
        concurrency: Int = 2,
    ): InternxtProvider {
        val provider =
            InternxtProvider(
                InternxtConfig(
                    tokenPath = tmp,
                    uploadCutoffMb = cutoffMb,
                    uploadChunkMb = chunkMb,
                    uploadConcurrency = concurrency,
                ),
            )
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
        httpField.set(api, HttpClient(MockEngine { request -> handle(request) }))
        return provider
    }

    private fun bodyOf(request: HttpRequestData): JsonObject {
        val text =
            when (val b = request.body) {
                is io.ktor.http.content.ByteArrayContent -> String(b.bytes(), Charsets.UTF_8)
                is io.ktor.http.content.TextContent -> b.text
                else -> "{}"
            }
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun partIndexOf(request: HttpRequestData): Int = request.url.segments.last().substringAfterLast("part").toInt()

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        val url = request.url.toString()
        val path = request.url.encodedPath
        requests += "${request.method.value} $path${request.url.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: ""}"
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        return when {
            request.method == HttpMethod.Post && path.endsWith("/files/start") -> {
                startBodySizes += bodyOf(request)["uploads"]!!.jsonArray.single().jsonObject["size"]!!.jsonPrimitive.content.toLong()
                val numParts = request.url.parameters["multiparts"]?.toInt() ?: 1
                if (numParts == 1) {
                    // The single-part shape the singlePartUploadEntry path reads (.url).
                    respond(
                        """{"uploads":[{"index":0,"uuid":"sess-uuid","url":"http://ovh.invalid/shard"}]}""",
                        HttpStatusCode.OK,
                        json,
                    )
                } else {
                    val urls = (0 until numParts).joinToString(",") { "\"http://ovh.invalid/part$it\"" }
                    val uploadId = "upload-id-${multipartStarts.incrementAndGet()}"
                    respond(
                        """{"uploads":[{"index":0,"uuid":"sess-uuid","UploadId":"$uploadId","urls":[$urls]}]}""",
                        HttpStatusCode.OK,
                        json,
                    )
                }
            }
            request.method == HttpMethod.Put && (url.contains("/part") || url.contains("/shard")) -> handlePut(url, request, json)
            request.method == HttpMethod.Post && path.endsWith("/files/finish") -> {
                if (failFinish409.getAndDecrement() > 0) {
                    return respond("""{"error":"MissingUploadsError"}""", HttpStatusCode.Conflict, json)
                }
                val body = bodyOf(request)
                finishBodies += body
                respond("""{"id":"file-123","index":"${body["index"]?.jsonPrimitive?.content ?: ""}","bucket":"$bucket"}""", HttpStatusCode.OK, json)
            }
            path == "/drive/files" && request.method == HttpMethod.Post -> {
                val name = bodyOf(request)["plainName"]?.jsonPrimitive?.content ?: ""
                respond("""{"uuid":"new-uuid","plainName":"$name","type":"bin","size":"1","bucket":"$bucket","status":"EXISTS"}""", HttpStatusCode.OK, json)
            }
            path.startsWith("/drive/folders/content/") ->
                respond("""{"children":[],"files":[]}""", HttpStatusCode.OK, json)
            else -> respond("""{"error":"unexpected ${request.method.value} $path"}""", HttpStatusCode.NotFound, json)
        }
    }

    // The PUT branch of [handle]: the single-part shard PUT, or one part with bookkeeping.
    private suspend fun MockRequestHandleScope.handlePut(
        url: String,
        request: HttpRequestData,
        json: io.ktor.http.Headers,
    ): HttpResponseData =
        if (url.contains("/shard")) {
            // The single-part PUT: no part bookkeeping.
            respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ETag, "\"etag-shard\""))
        } else {
            handlePartPut(request, json)
        }

    private suspend fun MockRequestHandleScope.handlePartPut(
        request: HttpRequestData,
        json: io.ktor.http.Headers,
    ): HttpResponseData {
        val index = partIndexOf(request)
        putStarted.computeIfAbsent(index) { AtomicInteger() }.incrementAndGet()
        val inFlight = putInFlight.incrementAndGet()
        putMaxInFlight.updateAndGet { old -> maxOf(old, inFlight) }
        val pending403 = failPart403[index]?.getAndDecrement() ?: 0
        if (pending403 > 0) {
            putInFlight.decrementAndGet()
            return respond("""{"error":"Forbidden"}""", HttpStatusCode.Forbidden, json)
        }
        val pending = failPart[index]?.getAndDecrement() ?: 0
        if (pending > 0) {
            putInFlight.decrementAndGet()
            return respond("""{"error":"overloaded"}""", HttpStatusCode.InternalServerError, json)
        }
        val bodyBytes =
            when (val b = request.body) {
                is io.ktor.http.content.OutgoingContent.WriteChannelContent -> drain(b)
                else -> ByteArray(0)
            }
        partBodies[index] = bodyBytes
        awaitParallelismGate(inFlight)
        putInFlight.decrementAndGet()
        return respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ETag, "\"etag-$index\""))
    }

    // Every gateSize-th concurrently-in-flight PUT waits until the group is up: a
    // sequential implementation hangs here, and a cap below the group never reaches it.
    private suspend fun awaitParallelismGate(inFlight: Int) {
        val gateSizeNow = gateSize.get()
        if (gateSizeNow <= 0) return
        try {
            if (inFlight >= gateSizeNow) {
                gate.complete(Unit)
            }
            kotlinx.coroutines.withTimeout(5_000) { gate.await() }
        } catch (_: Exception) {
            gate.complete(Unit) // never wedge a failing test
        }
    }

    private suspend fun drain(content: io.ktor.http.content.OutgoingContent.WriteChannelContent): ByteArray {
        val channel = io.ktor.utils.io.ByteChannel(autoFlush = true)
        val drained = java.util.concurrent.atomic.AtomicReference<ByteArray>()
        coroutineScope {
            // Read while the writer writes: the channel buffer is small, so a
            // reader that waits for the writer to finish deadlocks it.
            val reader = launch { drained.set(channel.readRemaining().readByteArray()) }
            launch {
                content.writeTo(channel)
                channel.flushAndClose()
            }
            reader.join()
        }
        return drained.get() ?: error("the part body was never drained")
    }

    private fun localFile(
        name: String,
        size: Int,
    ): Path = tmp.resolve(name).also { Files.write(it, ByteArray(size) { (it % 251).toByte() }) }

    private fun tombstoneOf(localPath: Path): UploadTombstone {
        val store = UploadTombstoneStore(tmp.resolve("upload-tombstones"))
        val hash = UploadTombstoneStore.pathHash(localPath.toAbsolutePath().toString())
        return store.read(hash) ?: error("no tombstone for $localPath")
    }

    // ---- the tests -------------------------------------------------------------------------------------------

    @Test
    fun `parts upload in parallel up to the cap and never beyond it`() =
        runTest {
            // 3.5 MB over 1 MB parts = 4 parts; the gate releases each pair of 2 only
            // once both are in flight, so a sequential implementation would hang and
            // time out — and maxInFlight must never exceed the cap.
            gateSize.set(2)
            val plaintext = localFile("big.bin", 3_500_000)
            val provider = newProvider(concurrency = 2)
            try {
                provider.upload(plaintext, "/big.bin", existingRemoteId = null, onProgress = null)
                assertEquals(2, putMaxInFlight.get(), "parts must run two at a time, never more: ${putMaxInFlight.get()}")
                assertEquals(4, partBodies.size, "every part PUT exactly once: ${putStarted.map { it.key to it.value.get() }}")
            } finally {
                provider.close()
            }
        }

    @Test
    fun `one part failing twice succeeds without re-putting the others`() =
        runTest {
            val plaintext = localFile("retry.bin", 2_500_000) // 3 parts
            failPart[1] = AtomicInteger(2)
            val provider = newProvider(concurrency = 3)
            try {
                provider.upload(plaintext, "/retry.bin", existingRemoteId = null, onProgress = null)
                assertEquals(3, partBodies.size)
                assertEquals(3, putStarted[1]!!.get(), "the failing part took all three attempts")
                assertEquals(1, putStarted[0]!!.get(), "part 0 was never re-put")
                assertEquals(1, putStarted[2]!!.get(), "part 2 was never re-put")
                assertEquals(1, finishBodies.size)
            } finally {
                provider.close()
            }
        }

    @Test
    fun `a finish is never sent while a part is still missing`() =
        runTest {
            val plaintext = localFile("dead.bin", 2_000_000) // 2 parts
            failPart[1] = AtomicInteger(99) // never succeeds
            val provider = newProvider(concurrency = 2)
            try {
                assertFailsWith<InternxtApiException> {
                    provider.upload(plaintext, "/dead.bin", existingRemoteId = null, onProgress = null)
                }
                assertTrue(finishBodies.isEmpty(), "no finish may be sent with an incomplete part list")
                val tomb = tombstoneOf(plaintext)
                assertEquals(1, tomb.multipart?.parts?.size, "part 0 is persisted as done in the tombstone")
            } finally {
                provider.close()
            }
        }

    @Test
    fun `a restart resumes from the tombstone and uploads only the missing parts`() =
        runTest {
            val plaintext = localFile("resume.bin", 2_000_000) // 2 parts
            failPart[1] = AtomicInteger(99)
            val first = newProvider(concurrency = 2)
            try {
                assertFailsWith<InternxtApiException> { first.upload(plaintext, "/resume.bin", existingRemoteId = null, onProgress = null) }
            } finally {
                first.close()
            }

            resetWire()
            val second = newProvider(concurrency = 2)
            try {
                second.upload(plaintext, "/resume.bin", existingRemoteId = null, onProgress = null)
                // The missing part only — and the session was reused, not re-started.
                assertEquals(listOf(1), partBodies.keys.toList(), "only the missing part is re-put: $putStarted")
                assertTrue(requests.none { it.contains("files/start") }, "the session's URLs are reused within the TTL")
                assertEquals(1, finishBodies.size)
                val shards = finishBodies.single()["shards"]!!.jsonArray.single().jsonObject
                assertEquals("upload-id-1", shards["UploadId"]?.jsonPrimitive?.content)
                val parts = shards["parts"]!!.jsonArray
                assertEquals(2, parts.size, "the finish carries the whole part list")
                assertEquals("etag-0", parts[0].jsonObject["ETag"]?.jsonPrimitive?.content, "part 0's ETag came from the tombstone")
            } finally {
                second.close()
            }
        }

    @Test
    fun `concurrency 1 assembles to ciphertext that decrypts to the original bytes`() =
        runTest {
            val plaintext = localFile("serial.bin", 2_500_000) // 3 parts, last short
            resetWire()
            val provider = newProvider(concurrency = 1, chunkMb = 1)
            try {
                provider.upload(plaintext, "/serial.bin", existingRemoteId = null, onProgress = null)
                assertEquals(1, putMaxInFlight.get(), "concurrency 1 is strictly sequential")
                val assembled = (0 until 3).flatMap { partBodies[it]!!.toList() }.toByteArray()
                // The staged ciphertext's size is the start call's part size; the index comes
                // back in the finish body (a successful upload discards its tombstone).
                val encryptedSize = startBodySizes.single()
                assertEquals(encryptedSize, assembled.size.toLong(), "the parts assemble to exactly the staged ciphertext")
                // Decrypt with the key the finish's index names and compare with the plaintext.
                val indexHex = finishBodies.single()["index"]!!.jsonPrimitive.content
                val indexBytes = InternxtCrypto.hexToBytes(indexHex)
                val crypto = InternxtCrypto()
                val creds = authServiceCreds(provider)
                val seed = crypto.mnemonicToSeed(creds.mnemonic)
                val bucketKey = crypto.deriveBucketKey(seed, creds.bucket)
                val fileKey = crypto.deriveFileKey(bucketKey, indexBytes)
                val cipher = crypto.createContentDecryptCipher(fileKey, indexBytes.copyOfRange(0, 16))
                val decrypted = javax.crypto.CipherInputStream(assembled.inputStream(), cipher).use { it.readBytes() }
                assertTrue(plaintext.toFile().readBytes().contentEquals(decrypted), "the assembled parts decrypt to the original bytes")
            } finally {
                provider.close()
            }
        }

    @Test
    fun `the finish hash is RIPEMD-160 of SHA-256 over the assembled ciphertext`() =
        runTest {
            val plaintext = localFile("wire-hash.bin", 2_500_000) // 3 parts, last short
            val provider = newProvider(concurrency = 2)
            try {
                provider.upload(plaintext, "/wire-hash.bin", existingRemoteId = null, onProgress = null)
                val shard = finishBodies.single()["shards"]!!.jsonArray.single().jsonObject
                val assembled = (0 until 3).flatMap { partBodies[it]!!.toList() }.toByteArray()
                val sha256Hex =
                    java.security.MessageDigest.getInstance("SHA-256").digest(assembled).joinToString("") { "%02x".format(it) }
                val expected = ShardHashWire.forFinish(sha256Hex)
                assertEquals(40, expected.length, "the wire hash is the 40-hex RIPEMD-160 form, not the 64-hex SHA-256")
                assertEquals(expected, shard["hash"]!!.jsonPrimitive.content)
            } finally {
                provider.close()
            }
        }

    @Test
    fun `a concurrency above the shared bridge budget is not silently capped`() =
        runTest {
            // 10 parts, the gate releases only at 8 in flight — through the shared
            // four-slot bridge budget the gate times out and max in flight stays at 4.
            gateSize.set(8)
            val plaintext = localFile("wide.bin", 9_500_000)
            val provider = newProvider(concurrency = 8)
            try {
                provider.upload(plaintext, "/wide.bin", existingRemoteId = null, onProgress = null)
                assertEquals(8, putMaxInFlight.get(), "eight parts run at once, not the bridge budget's four")
                assertEquals(10, partBodies.size, "every part PUT exactly once: ${putStarted.map { it.key to it.value.get() }}")
            } finally {
                gate.complete(Unit)
                provider.close()
            }
        }

    @Test
    fun `a session older than the URL TTL is dropped even when the sidecar was just written`() =
        runTest {
            val plaintext = localFile("stale-session.bin", 2_000_000) // 2 parts
            failPart[1] = AtomicInteger(99)
            val first = newProvider(concurrency = 2)
            try {
                assertFailsWith<InternxtApiException> { first.upload(plaintext, "/stale-session.bin", existingRemoteId = null, onProgress = null) }
            } finally {
                first.close()
            }

            // The session was issued past the URL TTL, but the sidecar says now — the exact
            // shape a write-time age check mistakes for fresh (every landed part rewrites it).
            val store = UploadTombstoneStore(tmp.resolve("upload-tombstones"))
            val hash = UploadTombstoneStore.pathHash(plaintext.toAbsolutePath().toString())
            val tomb = store.read(hash) ?: error("no tombstone after the failed attempt")
            val now = System.currentTimeMillis()
            store.write(
                hash,
                tomb.copy(
                    tombstoneWrittenAtMillis = now,
                    multipart = tomb.multipart!!.copy(issuedAtMillis = now - InternxtConfig.URL_TTL_MS - 60_000),
                ),
            )

            resetWire()
            val second = newProvider(concurrency = 2)
            try {
                second.upload(plaintext, "/stale-session.bin", existingRemoteId = null, onProgress = null)
                assertEquals(1, multipartStarts.get(), "the expired session was dropped and a fresh one started")
                assertEquals(listOf(0, 1), partBodies.keys.sorted(), "every part is re-put from the staged ciphertext")
                assertEquals(1, finishBodies.size)
            } finally {
                second.close()
            }
        }

    @Test
    fun `a sidecar whose session plan does not match the ciphertext is discarded, not resumed`() =
        runTest {
            val plaintext = localFile("bad-plan.bin", 2_000_000) // 2 parts
            failPart[1] = AtomicInteger(99)
            val first = newProvider(concurrency = 2)
            try {
                assertFailsWith<InternxtApiException> { first.upload(plaintext, "/bad-plan.bin", existingRemoteId = null, onProgress = null) }
            } finally {
                first.close()
            }

            // One URL for a two-part plan: resuming would index past the URL list.
            val store = UploadTombstoneStore(tmp.resolve("upload-tombstones"))
            val hash = UploadTombstoneStore.pathHash(plaintext.toAbsolutePath().toString())
            val tomb = store.read(hash) ?: error("no tombstone after the failed attempt")
            store.write(
                hash,
                tomb.copy(
                    multipart =
                        tomb.multipart!!.copy(
                            urls = tomb.multipart!!.urls.take(1),
                            issuedAtMillis = System.currentTimeMillis(),
                        ),
                ),
            )

            resetWire()
            val second = newProvider(concurrency = 2)
            try {
                second.upload(plaintext, "/bad-plan.bin", existingRemoteId = null, onProgress = null)
                assertEquals(1, multipartStarts.get(), "the mismatched session was discarded and a fresh one started")
                assertEquals(listOf(0, 1), partBodies.keys.sorted(), "every part is re-put")
                assertEquals(1, finishBodies.size)
            } finally {
                second.close()
            }
        }

    @Test
    fun `a part PUT refused with 403 restarts the session once and re-puts every part`() =
        runTest {
            val plaintext = localFile("dead-urls.bin", 2_000_000) // 2 parts
            failPart403[1] = AtomicInteger(1) // one dead-URL refusal, then the fresh session's URLs work
            val provider = newProvider(concurrency = 1) // part 0 lands, then part 1's PUT meets the 403
            try {
                provider.upload(plaintext, "/dead-urls.bin", existingRemoteId = null, onProgress = null)
                assertEquals(2, multipartStarts.get(), "a fresh session was started after the 403")
                assertEquals(2, putStarted[0]!!.get(), "part 0 was re-put in the fresh session")
                assertEquals(2, putStarted[1]!!.get(), "part 1's dead URL once, the fresh session's URL once")
                assertEquals(1, finishBodies.size)
            } finally {
                provider.close()
            }
        }

    @Test
    fun `a finish-409 with no landed entry discards the session and starts a fresh one`() =
        runTest {
            val plaintext = localFile("exhausted.bin", 2_000_000) // 2 parts
            failFinish409.set(1) // the first session's finish is refused as exhausted
            val provider = newProvider(concurrency = 2)
            try {
                provider.upload(plaintext, "/exhausted.bin", existingRemoteId = null, onProgress = null)
                assertEquals(2, multipartStarts.get(), "a fresh multipart session was started after the refusal")
                assertEquals(2, requests.count { it.endsWith("/files/finish") }, "the finish was sent twice: refused once, then the fresh session's")
                assertEquals(1, finishBodies.size, "only the successful finish is recorded")
                val freshShard = finishBodies.single()["shards"]!!.jsonArray.single().jsonObject
                assertEquals("upload-id-2", freshShard["UploadId"]?.jsonPrimitive?.content, "the second finish belongs to the fresh session")
                assertEquals(2, putStarted[0]!!.get(), "every part was re-put in the fresh session")
                assertEquals(2, putStarted[1]!!.get())
            } finally {
                provider.close()
            }
        }

    private fun authServiceCreds(provider: InternxtProvider?): org.krost.unidrive.internxt.model.InternxtCredentials {
        val p = provider ?: newProvider()
        val field = InternxtProvider::class.java.getDeclaredField("authService")
        field.isAccessible = true
        val auth = field.get(p)
        val credsField = auth.javaClass.getDeclaredField("credentials")
        credsField.isAccessible = true
        return credsField.get(auth) as org.krost.unidrive.internxt.model.InternxtCredentials
    }

    @Test
    fun `a file at or below the cutoff keeps the single-part start`() =
        runTest {
            // cutoff 1 MB, file exactly 1 MB (encrypted size is above 1 MB — the
            // cutoff applies to the ciphertext): force below-cutoff with a 0.5 MB file.
            val plaintext = localFile("small.bin", 500_000)
            val provider = newProvider(cutoffMb = 1, chunkMb = 1)
            try {
                provider.upload(plaintext, "/small.bin", existingRemoteId = null, onProgress = null)
                val start = requests.single { it.contains("files/start") }
                assertTrue(start.contains("multiparts=1"), "below the cutoff the start is single-part: $start")
                assertTrue(requests.none { it.contains("/part") }, "no part PUTs below the cutoff")
            } finally {
                provider.close()
            }
        }

    @Test
    fun `one byte above the cutoff goes multipart`() =
        runTest {
            val plaintext = localFile("over.bin", 1_048_577) // 1 MB + 1 byte
            val provider = newProvider(cutoffMb = 1, chunkMb = 1)
            try {
                provider.upload(plaintext, "/over.bin", existingRemoteId = null, onProgress = null)
                val start = requests.single { it.contains("files/start") }
                assertTrue(start.contains("multiparts="), "the start carries a part count")
                assertTrue(!start.contains("multiparts=1"), "above the cutoff the start is multipart: $start")
            } finally {
                provider.close()
            }
        }

    @Test
    fun `the provider reports and overrides its upload concurrency within bounds`() {
        val provider = newProvider(concurrency = 4)
        try {
            assertEquals(4, provider.multipartUploadConcurrency())
            assertTrue(provider.overrideUploadConcurrency(9))
            assertEquals(9, provider.multipartUploadConcurrency())
            assertFailsWith<IllegalArgumentException> { provider.overrideUploadConcurrency(0) }
            assertFailsWith<IllegalArgumentException> { provider.overrideUploadConcurrency(17) }
        } finally {
            provider.close()
        }
    }

    @Test
    fun `the factory refuses out-of-range or malformed upload keys`() {
        val factory = InternxtProviderFactory()
        val dir = Files.createTempDirectory("ud-mp-factory")
        try {
            fun create(vararg pairs: Pair<String, String?>) =
                factory.create(mapOf(*pairs), dir)

            assertFailsWith<IllegalArgumentException> { create("upload_concurrency" to "0") }
            assertFailsWith<IllegalArgumentException> { create("upload_concurrency" to "17") }
            assertFailsWith<IllegalArgumentException> { create("upload_concurrency" to "many") }
            assertFailsWith<IllegalArgumentException> { create("upload_chunk_mb" to "4") }
            assertFailsWith<IllegalArgumentException> { create("upload_cutoff_mb" to "99") }
            // Valid keys parse into the config.
            val provider = create("upload_concurrency" to "8", "upload_chunk_mb" to "16", "upload_cutoff_mb" to "250") as InternxtProvider
            assertEquals(8, provider.multipartUploadConcurrency())
            provider.close()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
