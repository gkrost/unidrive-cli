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
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.PermanentDownloadFailureException
import org.krost.unidrive.RemoteObjectMissingDownloadException
import org.krost.unidrive.internxt.model.InternxtFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#392: a download of a listed Internxt file used to open with `GET /files/{uuid}/meta` (about 160 ms per
 * file, sequential, before the bridge info and the shard). A file a listing of this run described is read by that
 * description; the meta call stays the authority when the listed object turns out to be gone.
 */
class InternxtDownloadRoundTripsTest {
    private val fileUuid = "file-uuid-1"
    private val bucket = "6928426c1a2316b856c9ab81"
    private val rootUuid = "root-folder-uuid"
    private val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val index64 = "22".repeat(32)
    private val crypto = InternxtCrypto()

    // The stored objects the bridge holds, by fileId: their plaintext.
    private val objects = mutableMapOf<String, ByteArray>()

    // What a listing of the root shows, and what the meta call answers (null: the drive row is hard-deleted).
    private var listedFileId: String? = "object-a"
    private var listedSize = "0"
    private var metaFileId: String? = "object-a"
    private var metaSize = "0"
    private var metaGone = false

    private val requestLog = mutableListOf<String>()
    private lateinit var destination: Path

    private fun store(
        fileId: String,
        text: String,
    ): String {
        objects[fileId] = text.toByteArray()
        return text.length.toString()
    }

    private fun encrypted(plaintext: ByteArray): ByteArray {
        val indexBytes = InternxtCrypto.hexToBytes(index64)
        val fileKey = crypto.deriveFileKey(crypto.deriveBucketKey(crypto.mnemonicToSeed(mnemonic), bucket), indexBytes)
        return crypto.createContentEncryptCipher(fileKey, indexBytes.copyOfRange(0, 16)).doFinal(plaintext)
    }

    private fun sha256Hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun fileJson(
        fileId: String?,
        size: String,
    ): String {
        val id = fileId?.let { "\"fileId\":\"$it\"," } ?: ""
        return """{"uuid":"$fileUuid","plainName":"a","type":"bin","bucket":"$bucket",$id"size":"$size","status":"EXISTS",""" +
            """"folderUuid":"$rootUuid","modificationTime":"2026-11-05T12:00:00.000Z","updatedAt":"2026-11-05T12:00:00.000Z"}"""
    }

    private fun newProvider(): InternxtProvider {
        destination = Files.createTempDirectory("ud-roundtrips").resolve("out.bin")
        val payloadB64 =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString("""{"exp":9999999999}""".toByteArray())
        return InternxtProvider(
            InternxtConfig(credentialProtector = null, tokenPath = Files.createTempFile("ud-roundtrips-token", ".tmp")),
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

    private fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        val path = request.url.encodedPath
        val host = request.url.host
        requestLog += "${request.method} $host$path"
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        val infoPath = Regex("/buckets/$bucket/files/([^/]+)/info")
        return when {
            path == "/drive/folders/content/$rootUuid" && request.method == HttpMethod.Get ->
                respond("""{"children":[],"files":[${fileJson(listedFileId, listedSize)}]}""", HttpStatusCode.OK, jsonHeaders)
            path == "/drive/files/$fileUuid/meta" && request.method == HttpMethod.Get ->
                if (metaGone) {
                    respond("""{"error":"Bucket entry $fileUuid not found"}""", HttpStatusCode.NotFound, jsonHeaders)
                } else {
                    respond(fileJson(metaFileId, metaSize), HttpStatusCode.OK, jsonHeaders)
                }
            infoPath.matches(path) && request.method == HttpMethod.Get -> {
                val id = infoPath.matchEntire(path)!!.groupValues[1]
                val plaintext = objects[id]
                if (plaintext == null) {
                    respond("""{"error":"Bucket entry $id not found"}""", HttpStatusCode.NotFound, jsonHeaders)
                } else {
                    val hash = sha256Hex(encrypted(plaintext))
                    respond(
                        """{"bucket":"$bucket","id":"$id","index":"$index64","shards":[{"index":0,"hash":"$hash","url":"https://shard-host.invalid/get/$id"}]}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }
            }
            host == "shard-host.invalid" && request.method == HttpMethod.Get -> {
                val body = encrypted(objects.getValue(path.substringAfterLast('/')))
                respond(
                    body,
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType to listOf("application/octet-stream"), HttpHeaders.ContentLength to listOf(body.size.toString())),
                )
            }
            else -> respond("""{"error":"unexpected: ${request.method} $host$path"}""", HttpStatusCode.NotFound, jsonHeaders)
        }
    }

    private fun metaCalls() = requestLog.count { it.endsWith("/drive/files/$fileUuid/meta") }

    private fun infoCalls() = requestLog.count { it.endsWith("/info") }

    private fun shardCalls() = requestLog.count { it.contains("shard-host.invalid") }

    private fun downloaded() = String(Files.readAllBytes(destination))

    @Test
    fun `a listed file downloads without the meta call, one bridge info and one shard`() =
        runTest {
            listedSize = store("object-a", "listed content")
            val provider = newProvider()
            provider.listChildren("/")
            requestLog.clear()

            provider.downloadById(fileUuid, "/a.bin", destination)

            assertEquals("listed content", downloaded())
            assertEquals(0, metaCalls(), "the listing already named the stored object: $requestLog")
            assertEquals(1, infoCalls(), "$requestLog")
            assertEquals(1, shardCalls(), "$requestLog")
        }

    @Test
    fun `a file no answer of this run described still takes the meta call`() =
        runTest {
            metaSize = store("object-a", "unlisted content")
            val provider = newProvider()

            provider.downloadById(fileUuid, "/a.bin", destination)

            assertEquals("unlisted content", downloaded())
            assertEquals(1, metaCalls())
            assertEquals(1, infoCalls())
        }

    @Test
    fun `a file listed again with a new object downloads the new content without the meta call`() =
        runTest {
            listedSize = store("object-a", "old")
            val provider = newProvider()
            provider.listChildren("/")
            listedFileId = "object-b"
            listedSize = store("object-b", "new content")
            metaFileId = "object-b"
            metaSize = listedSize
            provider.listChildren("/")
            requestLog.clear()

            val receipt = provider.downloadByIdWithHash(fileUuid, "/a.bin", destination, null)

            assertEquals("new content", downloaded())
            assertEquals("object-b|11", receipt.contentId)
            assertEquals(0, metaCalls(), "$requestLog")
        }

    @Test
    fun `a file replaced after its listing, with the listed object gone, downloads the current object`() =
        runTest {
            listedSize = store("object-a", "old")
            val provider = newProvider()
            provider.listChildren("/")
            objects.remove("object-a")
            metaFileId = "object-b"
            metaSize = store("object-b", "replaced content")
            requestLog.clear()

            val receipt = provider.downloadByIdWithHash(fileUuid, "/a.bin", destination, null)

            assertEquals("replaced content", downloaded())
            assertEquals("object-b|16", receipt.contentId, "the receipt names the version that came down")
            assertEquals(1, metaCalls(), "the missing listed object sends the download to the meta call: $requestLog")
            assertEquals(2, infoCalls(), "$requestLog")
            assertEquals(1, shardCalls(), "$requestLog")
        }

    @Test
    fun `a file hard-deleted after its listing still fails as a permanent download failure`() {
        listedSize = store("object-a", "gone soon")
        val provider = newProvider()
        runTest { provider.listChildren("/") }
        objects.remove("object-a")
        metaGone = true

        val e = assertFailsWith<PermanentDownloadFailureException> { runTest { provider.downloadById(fileUuid, "/a.bin", destination) } }

        assertEquals(PermanentDownloadFailureException::class, e::class, "classified by the meta answer as before: ${e.message}")
        assertTrue("permanently gone" in e.message.orEmpty(), "got: ${e.message}")
        assertEquals(0, shardCalls())
    }

    @Test
    fun `a listed file whose drive row is unchanged but whose bridge object is missing stays a missing object`() {
        listedSize = "9"
        metaSize = "9"
        val provider = newProvider()
        runTest { provider.listChildren("/") }
        requestLog.clear()

        assertFailsWith<RemoteObjectMissingDownloadException> { runTest { provider.downloadById(fileUuid, "/a.bin", destination) } }

        assertEquals(1, metaCalls(), "the meta call confirms the drive row: $requestLog")
        assertEquals(1, infoCalls(), "the unchanged object is not asked for twice: $requestLog")
        assertEquals(0, shardCalls())
    }

    @Test
    fun `a listed empty file still downloads as an empty file`() =
        runTest {
            listedFileId = null
            listedSize = "0"
            metaFileId = null
            metaSize = "0"
            val provider = newProvider()
            provider.listChildren("/")
            requestLog.clear()

            val receipt = provider.downloadByIdWithHash(fileUuid, "/a.bin", destination, null)

            assertEquals(0L, receipt.bytesWritten)
            assertEquals(0L, Files.size(destination))
            assertEquals(0, infoCalls(), "an empty file has no stored object: $requestLog")
        }

    // ---- the hint store itself ---------------------------------------------------------------------------------

    private fun row(
        fileId: String?,
        updatedAt: String,
        status: String = "EXISTS",
    ) = InternxtFile(uuid = fileUuid, fileId = fileId, bucket = bucket, size = "3", status = status, updatedAt = updatedAt)

    @Test
    fun `an answer older than the hint does not bring the replaced object back`() {
        val hints = FileContentHints()
        hints.record(row("object-b", "2026-11-05T12:00:02.000Z"))
        hints.record(row("object-a", "2026-11-05T12:00:01.000Z"))

        assertEquals("object-b", hints.get(fileUuid)?.fileId)
    }

    @Test
    fun `a trashed row leaves no hint and fences older answers`() {
        val hints = FileContentHints()
        hints.record(row("object-a", "2026-11-05T12:00:01.000Z"))
        hints.record(row("object-a", "2026-11-05T12:00:02.000Z", status = "TRASHED"))
        assertNull(hints.get(fileUuid))

        hints.record(row("object-a", "2026-11-05T12:00:01.000Z"))
        assertNull(hints.get(fileUuid), "an answer from before the trash cannot fill the hint in again")
    }

    @Test
    fun `the hint store is bounded`() {
        val hints = FileContentHints(capacity = 2)
        listOf("u1", "u2", "u3").forEach { hints.record(InternxtFile(uuid = it, fileId = "f-$it", bucket = bucket, size = "1")) }

        assertEquals(2, hints.size())
        assertNull(hints.get("u1"), "the least recently used entry went first")
    }
}
