package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.krost.unidrive.testing.TestScratch

/**
 * #69 finding 1: on OneDrive Personal, `createUploadSession` with `fail` creates the target at once as an empty file
 * under the real name; the last chunk fills that same item, a `DELETE` of the session removes it (measured on a test
 * account 2026-11-22). When the session is lost without that `DELETE`, the next create of the file collides with the
 * empty item. The script below replays that sequence:
 * - the session create reserves `stub-id`, the first chunk is refused terminally, the cancel fails: the stub stays;
 * - the next create meets `nameAlreadyExists`.
 * The create completes into the item only when it is the one this profile's session reserved (by id) and still empty.
 */
class OneDriveUnfinishedStubTest {
    private val size = 5 * 1024 * 1024
    private val stubETag = "\"stub-etag,1\""
    private lateinit var tokenDir: Path

    @BeforeTest
    fun setUp() {
        tokenDir = TestScratch.directory("ud-stub")
    }

    @AfterTest
    fun tearDown() {
        Files.walk(tokenDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun json() = headersOf(HttpHeaders.ContentType, "application/json")

    // The drive: [itemAtName] is what sits at /big.bin (null: nothing). A `fail` create on an empty name reserves the
    // stub; one on a taken name is a 409. [refuseChunks]: chunk PUTs are refused with 403 and the cancel with 500.
    private inner class Drive {
        var itemAtName: Pair<String, Long>? = null
        var refuseChunks = false
        val behaviors = mutableListOf<String>()
        val ifMatch = mutableListOf<String?>()

        fun engine() =
            MockEngine { request ->
                val url = request.url.toString()
                when {
                    request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/createUploadSession") -> {
                        val body = (request.body as io.ktor.http.content.TextContent).text
                        val behavior = Regex("conflictBehavior\":\"(\\w+)\"").find(body)!!.groupValues[1]
                        behaviors += behavior
                        ifMatch += request.headers[HttpHeaders.IfMatch]
                        if (behavior == "fail" && itemAtName != null) {
                            respond("""{"error":{"code":"nameAlreadyExists","message":"name exists"}}""", HttpStatusCode.Conflict, json())
                        } else {
                            if (behavior == "fail") itemAtName = "stub-id" to 0L
                            respond("""{"uploadUrl":"https://upload.example/s","expirationDateTime":"2030-01-01T00:00:00Z"}""", HttpStatusCode.OK, json())
                        }
                    }
                    request.method == HttpMethod.Delete && url.startsWith("https://upload.example/") ->
                        respond("{}", HttpStatusCode.InternalServerError, json())
                    request.method == HttpMethod.Put && url.startsWith("https://upload.example/") ->
                        if (refuseChunks) {
                            respond("""{"error":{"code":"accessDenied","message":"no"}}""", HttpStatusCode.Forbidden, json())
                        } else {
                            val id = if (behaviors.last() == "rename") "copy-id" else itemAtName!!.first
                            if (behaviors.last() != "rename") itemAtName = id to size.toLong()
                            respond(
                                """{"id":"$id","name":"big.bin","size":$size,"file":{},"parentReference":{"path":"/drive/root:"}}""",
                                HttpStatusCode.Created,
                                json(),
                            )
                        }
                    request.method == HttpMethod.Get && request.url.encodedPath.endsWith("root:/big.bin") -> {
                        val (id, itemSize) = itemAtName ?: return@MockEngine respond(
                            """{"error":{"code":"itemNotFound","message":"none"}}""",
                            HttpStatusCode.NotFound,
                            json(),
                        )
                        respond(
                            """{"id":"$id","name":"big.bin","size":$itemSize,"eTag":${kotlinx.serialization.json.JsonPrimitive(stubETag)},
                               "file":{"mimeType":"application/octet-stream","hashes":{"quickXorHash":"AAAAAAAAAAAAAAAAAAAAAAAAAAA="}},
                               "parentReference":{"path":"/drive/root:"}}""",
                            HttpStatusCode.OK,
                            json(),
                        )
                    }
                    // cli#950: the parent listing that tells a true duplicate from a fold: the exact name is there.
                    request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/children") ->
                        respond("""{"value":[{"id":"existing-id","name":"big.bin","size":1,"file":{}}]}""", HttpStatusCode.OK, json())
                    else -> respond("{}", HttpStatusCode.OK, json())
                }
            }
    }

    private fun graphFor(drive: Drive) =
        GraphApiService(config = OneDriveConfig(tokenPath = tokenDir), httpClient = HttpClient(drive.engine()), tokenProvider = { _, _ -> "test-token" })

    private fun localFile(): Path = TestScratch.file("ud-stub", ".bin").also { Files.write(it, ByteArray(size) { 7 }) }

    // The first upload of the file is cut short after its session reserved the stub, and the cancel fails.
    private suspend fun cutShortUpload(drive: Drive, local: Path) {
        drive.refuseChunks = true
        val provider = OneDriveProvider(graphApi = graphFor(drive))
        try {
            assertFailsWith<Exception> { provider.upload(local, "/big.bin", existingRemoteId = null) }
        } finally {
            provider.close()
        }
        drive.refuseChunks = false
        drive.behaviors.clear()
        drive.ifMatch.clear()
    }

    @Test
    fun `a fail session create records the id of the empty item it reserved`() =
        runTest {
            val drive = Drive()
            val local = localFile()
            try {
                cutShortUpload(drive, local)

                assertEquals("stub-id" to 0L, drive.itemAtName, "the stub stays when the cancel fails")
                assertTrue(UploadReservationStore(tokenDir).holds("/big.bin", "stub-id"), "the reservation is recorded by id")
            } finally {
                Files.deleteIfExists(local)
            }
        }

    @Test
    fun `a create meeting its own reserved empty item completes into it by id`() =
        runTest {
            val drive = Drive()
            val local = localFile()
            try {
                cutShortUpload(drive, local)
                val provider = OneDriveProvider(graphApi = graphFor(drive))
                val result = try {
                    provider.upload(local, "/big.bin", existingRemoteId = null)
                } finally {
                    provider.close()
                }

                assertEquals(listOf("fail", "replace"), drive.behaviors, "the stub is completed, no keep-both copy")
                assertEquals(listOf(null, stubETag), drive.ifMatch, "the replace is guarded by the stub's eTag")
                assertEquals("stub-id", result.id)
                assertEquals("big.bin", result.name)
            } finally {
                Files.deleteIfExists(local)
            }
        }

    @Test
    fun `a completed upload releases its reservation`() =
        runTest {
            val drive = Drive()
            val local = localFile()
            val provider = OneDriveProvider(graphApi = graphFor(drive))
            try {
                provider.upload(local, "/big.bin", existingRemoteId = null)

                assertEquals(listOf("fail"), drive.behaviors)
                assertFalse(UploadReservationStore(tokenDir).holds("/big.bin", "stub-id"), "a full item is nobody's reservation")
            } finally {
                provider.close()
                Files.deleteIfExists(local)
            }
        }

    @Test
    fun `a create meeting an empty item no session of this profile reserved keeps both`() =
        runTest {
            val drive = Drive().apply { itemAtName = "stub-id" to 0L }
            val local = localFile()
            val provider = OneDriveProvider(graphApi = graphFor(drive))
            try {
                val result = provider.upload(local, "/big.bin", existingRemoteId = null)

                assertEquals(listOf("fail", "rename"), drive.behaviors, "someone's empty file is never overwritten")
                assertEquals("copy-id", result.id)
                assertEquals("stub-id" to 0L, drive.itemAtName)
            } finally {
                provider.close()
                Files.deleteIfExists(local)
            }
        }

    @Test
    fun `a create meeting an empty item of another id than the reserved one keeps both`() =
        runTest {
            val drive = Drive()
            val local = localFile()
            try {
                cutShortUpload(drive, local)
                // The stub was removed and someone created an empty file of the same name.
                drive.itemAtName = "other-id" to 0L
                val provider = OneDriveProvider(graphApi = graphFor(drive))
                try {
                    provider.upload(local, "/big.bin", existingRemoteId = null)
                } finally {
                    provider.close()
                }

                assertEquals(listOf("fail", "rename"), drive.behaviors)
                assertEquals("other-id" to 0L, drive.itemAtName)
            } finally {
                Files.deleteIfExists(local)
            }
        }

    @Test
    fun `a create meeting its reserved item once it holds bytes keeps both`() =
        runTest {
            val drive = Drive()
            val local = localFile()
            try {
                cutShortUpload(drive, local)
                // Another client filled the reserved item meanwhile: it is no longer an unfinished target.
                drive.itemAtName = "stub-id" to 1234L
                val provider = OneDriveProvider(graphApi = graphFor(drive))
                try {
                    provider.upload(local, "/big.bin", existingRemoteId = null)
                } finally {
                    provider.close()
                }

                assertEquals(listOf("fail", "rename"), drive.behaviors)
                assertEquals("stub-id" to 1234L, drive.itemAtName)
            } finally {
                Files.deleteIfExists(local)
            }
        }
}
