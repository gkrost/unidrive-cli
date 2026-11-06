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
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.StoredObject
import org.krost.unidrive.internxt.model.InternxtCredentials
import org.krost.unidrive.internxt.model.InternxtFile
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.EntryStatus
import org.krost.unidrive.sync.model.SyncEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * gkrost/unidrive-cli#414: the download hint #392/#402 keep in memory dies with the daemon. The row in state.db carries
 * what the hint needs (the stored object's bridge fileId, bucket, encryptVersion, size and the two timestamps), so a
 * download after a restart seeds the hint from the row and still skips `GET /files/{uuid}/meta` (about 160-190 ms on the
 * bench). The #402 guards stay: a trashed or deleted row never seeds a hint, and an answer of this run is never replaced
 * by the row's older one.
 */
class InternxtPersistedHintDownloadTest {
    private val fileUuid = "file-uuid-1"
    private val bucket = "6928426c1a2316b856c9ab81"
    private val rootUuid = "root-folder-uuid"
    private val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val index64 = "22".repeat(32)
    private val crypto = InternxtCrypto()
    private val modified = Instant.parse("2026-11-05T12:00:00Z")

    // The modification time as the (mock) answer writes it: a zero fraction, the form whose loss archive#1039 turned
    // into a false conflict — the row must carry it verbatim, or the seeded download's receipt names a write token no
    // fresh answer builds and the file's first edit conflicts with no remote change.
    private val modifiedRaw = "2026-11-05T12:00:00.000Z"

    // The stored objects the bridge holds, by fileId: their plaintext.
    private val objects = mutableMapOf<String, ByteArray>()

    // What the meta call answers (the authority the download falls back to), and what a listing of the root shows.
    private var metaFileId: String? = "object-a"
    private var metaSize = "0"
    private var listedFileId: String? = "object-a"
    private var listedSize = "0"

    private val requestLog = mutableListOf<String>()
    private lateinit var destination: Path
    private lateinit var stateDbFile: Path

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

    // The row an earlier StateDatabase instance persisted: written, closed, and read back by a fresh instance
    // (the simulated restart). Returns the new instance's row.
    private fun persistedRow(
        storedObject: StoredObject?,
        status: EntryStatus = EntryStatus.EXISTS,
    ): SyncEntry {
        stateDbFile = Files.createTempDirectory("ud-persisted-hint").resolve("state.db")
        StateDatabase(stateDbFile).also { it.initialize() }.let { db ->
            try {
                db.upsertEntry(
                    SyncEntry(
                        path = "/a.bin",
                        remoteId = fileUuid,
                        remoteHash = "h1",
                        remoteSize = storedObject?.size ?: 0,
                        remoteModified = modified,
                        localMtime = null,
                        localSize = null,
                        isFolder = false,
                        isPinned = false,
                        isHydrated = true,
                        lastSynced = modified,
                        status = status,
                        storedObject = storedObject,
                    ),
                )
            } finally {
                db.close()
            }
        }
        // The restart: a fresh instance reads the file the first one wrote. A trashed row is not in the alive view the
        // sync loop reads, so it is read through the recovery namespace.
        StateDatabase(stateDbFile).also { it.initialize() }.let { db ->
            try {
                return assertNotNull(db.getEntry("/a.bin") ?: db.recovery.trashedEntries().singleOrNull { it.path == "/a.bin" })
            } finally {
                db.close()
            }
        }
    }

    private fun newProvider(): InternxtProvider {
        destination = Files.createTempDirectory("ud-persisted-hint").resolve("out.bin")
        val payloadB64 =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString("""{"exp":9999999999}""".toByteArray())
        return InternxtProvider(
            InternxtConfig(credentialProtector = null, tokenPath = Files.createTempFile("ud-persisted-hint-token", ".tmp")),
            httpClient = HttpClient(MockEngine { request -> handle(request) }),
            credentials =
                InternxtCredentials(
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
                respond(fileJson(metaFileId, metaSize), HttpStatusCode.OK, jsonHeaders)
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

    private fun downloaded() = String(Files.readAllBytes(destination))

    // The engine's seed, the way Transfers hands a row's stored object to the provider before a download.
    private fun seedFrom(row: SyncEntry, provider: InternxtProvider) {
        row.downloadHint?.let { provider.seedStoredObject(checkNotNull(row.remoteId), it) }
    }

    @Test
    fun `a row an earlier StateDatabase persisted seeds the download after a restart, 0 meta calls`() =
        runTest {
            val size = store("object-a", "persisted content")
            val row = persistedRow(
                StoredObject(
                    id = "object-a",
                    bucket = bucket,
                    encoding = "03-aes",
                    size = size.toLong(),
                    modifiedRaw = modifiedRaw,
                    updatedAt = modified,
                ),
            )
            assertEquals("object-a", assertNotNull(row.storedObject).id, "the restart's fresh instance reads the hint back")
            assertEquals(modifiedRaw, row.storedObject?.modifiedRaw, "the restart's fresh instance reads the time string the answer sent")
            val provider = newProvider()

            seedFrom(row, provider)
            val receipt = provider.downloadByIdWithHash(fileUuid, "/a.bin", destination, null)

            assertEquals("persisted content", downloaded())
            assertEquals("object-a|$size", receipt.contentId, "the receipt names the version the row seeded")
            val freshToken = InternxtProvider.internxtVersionToken(InternxtFile(uuid = fileUuid, modificationTime = modifiedRaw, size = size))
            assertEquals(freshToken, receipt.itemHash, "the seeded receipt names the write token a fresh answer builds (archive#1039: one rebuilt from the parsed Instant flips the form, and the file's first edit conflicts)")
            assertEquals(0, metaCalls(), "the row's stored object answers without the meta call: $requestLog")
            assertEquals(1, infoCalls(), "$requestLog")
            assertEquals(1, requestLog.count { it.contains("shard-host.invalid") }, "$requestLog")
        }

    @Test
    fun `a row whose stored object changed downloads the new object`() =
        runTest {
            val staleSize = store("object-a", "stale")
            objects.remove("object-a") // the replaced object is gone from the bridge
            val row = persistedRow(
                StoredObject(
                    id = "object-a",
                    bucket = bucket,
                    encoding = "03-aes",
                    size = staleSize.toLong(),
                    modifiedRaw = modifiedRaw,
                    updatedAt = modified,
                ),
            )
            metaFileId = "object-b"
            metaSize = store("object-b", "replaced content")
            val provider = newProvider()

            seedFrom(row, provider)
            val receipt = provider.downloadByIdWithHash(fileUuid, "/a.bin", destination, null)

            assertEquals("replaced content", downloaded(), "the meta answer decides, as for a stale listing")
            assertEquals("object-b|16", receipt.contentId, "the receipt names the version that came down")
            assertEquals(1, metaCalls(), "the stale stored object sends the download to the meta call: $requestLog")
            assertEquals(2, infoCalls(), "$requestLog")
        }

    @Test
    fun `a trashed row leaves a fence - its stored object never seeds a download`() =
        runTest {
            val size = store("object-a", "of a trashed row")
            val row = persistedRow(
                StoredObject(
                    id = "object-a",
                    bucket = bucket,
                    encoding = "03-aes",
                    size = size.toLong(),
                    modifiedRaw = modifiedRaw,
                    updatedAt = modified,
                ),
                status = EntryStatus.TRASHED,
            )
            assertNull(row.downloadHint, "the row's hint columns stay, but a trashed row does not vouch for them")
            metaSize = store("object-a", "of a trashed row")
            val provider = newProvider()

            seedFrom(row, provider)
            provider.downloadById(fileUuid, "/a.bin", destination)

            assertEquals(1, metaCalls(), "without a seed the download takes the meta call: $requestLog")
            assertEquals("of a trashed row", downloaded())
        }

    @Test
    fun `an answer of this run is never replaced by the row's older stored object`() =
        runTest {
            val staleSize = store("object-a", "stale row hint")
            val row = persistedRow(
                StoredObject(
                    id = "object-a",
                    bucket = bucket,
                    encoding = "03-aes",
                    size = staleSize.toLong(),
                    modifiedRaw = modifiedRaw,
                    updatedAt = modified,
                ),
            )
            listedFileId = "object-b"
            listedSize = store("object-b", "listed fresh")
            val provider = newProvider()
            provider.listChildren("/") // the run's own answer, newer than the row's
            requestLog.clear()

            seedFrom(row, provider)
            provider.downloadByIdWithHash(fileUuid, "/a.bin", destination, null)

            assertEquals("listed fresh", downloaded(), "the listing's object wins over the older row")
            assertEquals(0, metaCalls(), "$requestLog")
        }
}
