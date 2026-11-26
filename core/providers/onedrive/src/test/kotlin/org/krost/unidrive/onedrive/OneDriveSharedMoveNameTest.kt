package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.NameRefusedException
import org.krost.unidrive.testing.TestScratch
import java.net.URLDecoder
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#950 review of #979 (P1): a rename of a shared-with-me item that the drive answers 409 must look for the
 * colliding sibling in the item's own drive, below the item's own parent, never in the caller's `/me/drive`: a shared item
 * is renamed in place and its siblings live in the other user's drive.
 */
class OneDriveSharedMoveNameTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val dirs = mutableListOf<Path>()
    private val providers = mutableListOf<OneDriveProvider>()
    private val sharedId = SharedItemRef("D1", "I1").id

    @AfterTest
    fun cleanUp() {
        providers.forEach { it.close() }
        dirs.forEach { it.toFile().deleteRecursively() }
    }

    private fun item(name: String) = """{"id":"x-$name","name":"$name","size":5,"file":{}}"""

    /** [conflict] is the body of the 409 the rename is answered with; [shared] and [own] list the two drives' folders. */
    private fun provider(
        conflict: String,
        shared: List<String>,
        own: List<String>,
        requests: MutableList<String>,
    ): OneDriveProvider {
        val engine =
            MockEngine { request ->
                val path = URLDecoder.decode(request.url.encodedPath, Charsets.UTF_8)
                requests += "${request.method.value} $path"
                when {
                    request.method.value == "PATCH" -> respond(conflict, HttpStatusCode.Conflict, json)
                    path == "/v1.0/drives/D1/items/I1" ->
                        respond("""{"id":"I1","name":"f.bin","size":5,"eTag":"e1","file":{},"parentReference":{"id":"P1","driveId":"D1"}}""", HttpStatusCode.OK, json)
                    path == "/v1.0/drives/D1/items/P1/children" -> respond("""{"value":[${shared.joinToString(",") { item(it) }}]}""", HttpStatusCode.OK, json)
                    path.endsWith("/children") -> respond("""{"value":[${own.joinToString(",") { item(it) }}]}""", HttpStatusCode.OK, json)
                    else -> respond("""{"error":{"code":"itemNotFound","message":"none"}}""", HttpStatusCode.NotFound, json)
                }
            }
        val dir = TestScratch.directory("ud-shared-move-name").also { dirs.add(it) }
        val config = OneDriveConfig(tokenPath = dir, includeShared = true)
        val service = GraphApiService(config = config, httpClient = HttpClient(engine), tokenProvider = { _, _ -> "test-token" })
        return OneDriveProvider(config, graphApi = service).also { providers.add(it) }
    }

    private val plainConflict = """{"error":{"code":"nameAlreadyExists","message":"Name already exists"}}"""
    private val similarConflict = """{"error":{"code":"nameAlreadyExists","message":"The specified item name is incompatible with a similar name on an existing item."}}"""

    @Test
    fun `a rename of a shared item onto an exact name in its own folder stays the 409 and never reads the own drive`() =
        runTest {
            val requests = mutableListOf<String>()
            // The caller's own drive holds nothing of the name: listed there, the 409 would look like a fold.
            val provider = provider(plainConflict, shared = listOf("report.bin"), own = listOf("decoy.bin"), requests = requests)

            val failure = assertFailsWith<GraphApiException> { provider.moveById(sharedId, "/Shared/f.bin", "/Shared/report.bin") }

            assertEquals(409, failure.statusCode)
            assertTrue(requests.none { it.startsWith("GET /v1.0/me/drive") }, "the own drive is not asked: $requests")
        }

    @Test
    fun `a rename of a shared item onto a folded name names the sibling of the shared folder`() =
        runTest {
            val requests = mutableListOf<String>()
            val provider = provider(plainConflict, shared = listOf("STRASSE.bin"), own = listOf("decoy.bin"), requests = requests)

            val refusal = assertFailsWith<NameRefusedException> { provider.moveById(sharedId, "/Shared/f.bin", "/Shared/Straße.bin") }

            assertEquals(NameRefusedException.SIMILAR_NAME, refusal.reason)
            assertEquals("STRASSE.bin", refusal.sibling)
            assertTrue(requests.none { it.startsWith("GET /v1.0/me/drive") }, "the own drive is not asked: $requests")
        }

    @Test
    fun `a similar-name refusal of a shared rename names the sibling of the shared folder, not one of the own drive`() =
        runTest {
            val requests = mutableListOf<String>()
            val provider = provider(similarConflict, shared = listOf("g-.bin"), own = listOf("g-other.bin"), requests = requests)

            val refusal = assertFailsWith<NameRefusedException> { provider.moveById(sharedId, "/Shared/f.bin", "/Shared/g-ⵜ.bin") }

            assertEquals("g-.bin", refusal.sibling)
            assertTrue(requests.none { it.startsWith("GET /v1.0/me/drive") }, "the own drive is not asked: $requests")
        }
}
