package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.krost.unidrive.InsufficientRemoteSpaceException
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * #16: an upload session tells OneDrive the file's size, so a drive without room refuses at the create (507) before a
 * byte is sent; and a 507 anywhere in an upload is the engine's typed space refusal ([InsufficientRemoteSpaceException],
 * which defers the item), never a 5xx the chunk loop retries.
 */
class OneDriveRemoteSpaceRefusalTest {
    private lateinit var dir: Path
    private lateinit var localFile: Path
    private val requests = mutableListOf<String>()
    private var sessionBody: String? = null

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
                            respond("""{"id":"item-1","name":"big.bin","size":1234,"file":{}}""", chunkStatus, json)
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

    private fun chunkPuts() = requests.count { it.startsWith("PUT upload.example") }

    @Test
    fun `the upload session asks OneDrive for a quota check with the file's size`() =
        runTest {
            service().use { it.uploadLargeFile(localFile, "/big.bin") }

            val item = Json.parseToJsonElement(sessionBody!!).jsonObject["item"]!!.jsonObject
            assertEquals(1234L, item["fileSize"]?.jsonPrimitive?.long, "the create names the size: $sessionBody")
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
