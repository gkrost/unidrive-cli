package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.krost.unidrive.InsufficientRemoteSpaceException
import org.krost.unidrive.hydration.CreateResult
import org.krost.unidrive.hydration.HydrationEvent
import org.krost.unidrive.hydration.HydrationImpl
import org.krost.unidrive.hydration.OpenResult
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * OneDrive Personal rejects an upload session that declares the file size. A quota refusal from the provider
 * still defers the item as [InsufficientRemoteSpaceException], including during chunk or simple uploads.
 */
class OneDriveRemoteSpaceRefusalTest {
    private lateinit var dir: Path
    private lateinit var localFile: Path
    private val requests = mutableListOf<String>()
    private var sessionBody: String? = null
    private var uploadedChunk: ByteArray? = null

    @BeforeTest
    fun setUp() {
        dir = TestScratch.directory("ud-onedrive-507")
        localFile = dir.resolve("local.bin")
        Files.write(localFile, ByteArray(1234) { it.toByte() })
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private val full = """{"error":{"code":"quotaLimitReached","message":"Insufficient Space Available"}}"""

    private fun service(
        createStatus: HttpStatusCode = HttpStatusCode.OK,
        chunkStatus: HttpStatusCode = HttpStatusCode.Created,
        simpleStatus: HttpStatusCode = HttpStatusCode.Created,
    ): GraphApiService {
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val engine =
            MockEngine { request ->
                val path = request.url.encodedPath
                requests += "${request.method.value} ${request.url.host}$path"
                when {
                    path.endsWith("createUploadSession") -> {
                        sessionBody = String((request.body as OutgoingContent.ByteArrayContent).bytes())
                        if (createStatus == HttpStatusCode.OK) {
                            respond("""{"uploadUrl":"https://upload.example/session-1","expirationDateTime":"2099-01-01T00:00:00Z"}""", createStatus, json)
                        } else {
                            respond(full, createStatus, json)
                        }
                    }
                    request.url.host == "upload.example" && request.method.value == "PUT" ->
                        if (chunkStatus == HttpStatusCode.Created) {
                            val bytes = request.body.toByteArray()
                            uploadedChunk = bytes
                            respond("""{"id":"item-1","name":"big.bin","size":${bytes.size},"file":{}}""", chunkStatus, json)
                        } else {
                            respond(full, chunkStatus, json)
                        }
                    path.endsWith("/content") ->
                        if (simpleStatus == HttpStatusCode.Created) {
                            respond("""{"id":"item-1","name":"a.txt","size":1,"file":{}}""", simpleStatus, json)
                        } else {
                            respond(full, simpleStatus, json)
                        }
                    else -> respond("", HttpStatusCode.NoContent)
                }
            }
        return GraphApiService(config = OneDriveConfig(tokenPath = dir.resolve("token")), httpClient = HttpClient(engine), tokenProvider = { _, _ -> "token" })
    }

    private inline fun withState(block: (StateDatabase) -> Unit) {
        val db = StateDatabase(dir.resolve("state.db"))
        try {
            db.initialize()
            block(db)
        } finally {
            db.close()
        }
    }

    private fun chunkPuts() = requests.count { it.startsWith("PUT upload.example") }

    @Test
    fun `the upload session create carries no fileSize, which OneDrive Personal refuses as invalidRequest`() =
        runTest {
            service().use { it.uploadLargeFile(localFile, "/big.bin") }

            val item = Json.parseToJsonElement(sessionBody!!).jsonObject["item"]!!.jsonObject
            assertNull(item["fileSize"], "a create with item.fileSize is a 400 on OneDrive Personal: $sessionBody")
        }

    @Test
    fun `an explicit client retry uploads the unchanged file refused before the upgrade`() = runBlocking {
        val path = "/big.bin"
        val content = ByteArray(5 * 1024 * 1024) { it.toByte() }
        val config = OneDriveConfig(tokenPath = dir.resolve("token"))
        // The real mount writes its refusal to disk. Seed the old refusal without needing the older provider.
        val provider = OneDriveProvider(config, graphApi = service())
        try {
            lateinit var cache: Path
            lateinit var refusal: String
            withState { db ->
                val engine = SyncEngine(provider, db, dir.resolve("sync"), cacheRoot = dir.resolve("cache"))
                val hydration = HydrationImpl(engine, db)
                try {
                    cache = assertIs<CreateResult.Ok>(hydration.create("client", "create", path)).cachePath
                    Files.write(cache, content)
                    db.upsertEntry(assertNotNull(db.getEntry(path)).copy(isHydrated = true))
                    refusal = "${Files.getLastModifiedTime(cache).toMillis()}|${content.size}|${System.currentTimeMillis() + 3_600_000}|old invalidRequest"
                    assertTrue(db.markUploadRefused(path, refusal))
                } finally {
                    hydration.shutdownUploads()
                }
            }

            withState { db ->
                val engine = SyncEngine(provider, db, dir.resolve("sync"), cacheRoot = dir.resolve("cache"))
                val hydration = HydrationImpl(engine, db)
                try {
                    assertEquals(refusal, db.uploadRefusal(path))
                    assertEquals(0, hydration.replayPendingUploads())
                    assertNull(sessionBody, "automatic replay still honors the refusal")
                    val completed = async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(30_000) {
                            hydration.events.filterIsInstance<HydrationEvent.Completed>().first { it.handleId == "client-retry" }
                        }
                    }

                    assertIs<OpenResult.Ok>(hydration.openForWrite("client", "client-retry", path, cache, null))
                    assertTrue(completed.await().ok)
                    val item = Json.parseToJsonElement(assertNotNull(sessionBody)).jsonObject["item"]!!.jsonObject
                    assertNull(item["fileSize"])
                    assertEquals(1, chunkPuts())
                    assertContentEquals(content, assertNotNull(uploadedChunk))
                    assertNull(db.uploadRefusal(path))
                } finally {
                    hydration.shutdownUploads()
                }
            }
        } finally {
            provider.close()
        }
    }

    @Test
    fun `a 507 on the session create is a space refusal and no chunk is sent`() =
        runTest {
            service(createStatus = HttpStatusCode.InsufficientStorage).use { api ->
                assertFailsWith<InsufficientRemoteSpaceException> { api.uploadLargeFile(localFile, "/big.bin") }
            }

            assertEquals(0, chunkPuts(), "nothing is uploaded after the early refusal: $requests")
        }

    @Test
    fun `a 507 on a chunk is a space refusal at once, not a 5xx the chunk loop retries`() =
        runTest {
            service(chunkStatus = HttpStatusCode.InsufficientStorage).use { api ->
                val refusal = assertFailsWith<InsufficientRemoteSpaceException> { api.uploadLargeFile(localFile, "/big.bin") }
                assertTrue(refusal.message.orEmpty().contains("507"), refusal.message)
            }

            assertEquals(1, chunkPuts(), "one chunk PUT, no retries of the refused chunk: $requests")
        }

    @Test
    fun `a 507 on a simple upload is a space refusal`() =
        runTest {
            service(simpleStatus = HttpStatusCode.InsufficientStorage).use { api ->
                assertFailsWith<InsufficientRemoteSpaceException> { api.uploadSimple("/a.txt", "x".toByteArray()) }
            }

            assertEquals(1, requests.count { it.endsWith("/content") }, "the refused PUT is not repeated: $requests")
        }
}
