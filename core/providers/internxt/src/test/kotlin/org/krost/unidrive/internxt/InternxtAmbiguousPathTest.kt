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
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.ProviderException
import org.krost.unidrive.internxt.model.InternxtFolder
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * archive#402: Internxt path-to-id resolution must fail closed on ambiguity. Where two
 * live items share a name, `getMetadata` / `resolveFolder` used to take the first
 * listing match — a delete or move resolved through them could hit the wrong twin.
 * They must throw instead, and the SPI by-id entry points (deleteById / moveById)
 * must address the requested uuid, never a same-named sibling.
 */
class InternxtAmbiguousPathTest {
    private val rootUuid = "root-folder-uuid"

    // Mock state, set per test.
    private var folderChildrenJson = "[]"
    private var folderFilesJson = "[]"
    private val fileMetaByUuid = mutableMapOf<String, String>() // uuid → JSON
    private val folderMetaByUuid = mutableMapOf<String, String>()
    private val folderContentsByUuid = mutableMapOf<String, String>()
    private val fileMeta404 = mutableSetOf<String>()
    private val folderMeta404 = mutableSetOf<String>()
    private val requestLog = mutableListOf<String>()
    private var trashBody: String? = null
    private var purgeFolderBody: String? = null

    private fun fileJson(
        uuid: String,
        plainName: String,
        type: String = "txt",
    ) = """{"uuid":"$uuid","plainName":"$plainName","type":"$type","size":"7","status":"EXISTS"}"""

    private fun folderJson(
        uuid: String,
        plainName: String,
        parent: String = rootUuid,
    ) = """{"uuid":"$uuid","plainName":"$plainName","parentUuid":"$parent","status":"EXISTS"}"""

    private fun newProvider(): InternxtProvider {
        val payload = """{"exp":9999999999}"""
        val payloadB64 =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.toByteArray())
        return InternxtProvider(
            InternxtConfig(credentialProtector = null, tokenPath = java.nio.file.Files.createTempFile("ud-amb-token", ".tmp")),
            httpClient = HttpClient(MockEngine { request -> handle(request) }),
            credentials =
                org.krost.unidrive.internxt.model.InternxtCredentials(
                    jwt = "header.$payloadB64.signature",
                    mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
                    rootFolderId = rootUuid,
                    email = "test@example.invalid",
                    bridgeUser = "bridge-user",
                    bridgeUserId = "bridge-secret",
                    bucket = "6928426c1a2316b856c9ab81",
                ),
        )
    }

    private fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        val path = request.url.encodedPath
        val method = request.method
        requestLog += "$method $path"
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        return when {
            path == "/drive/folders/content/$rootUuid" && method == HttpMethod.Get ->
                respond(
                    """{"children":$folderChildrenJson,"files":$folderFilesJson}""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            path.startsWith("/drive/folders/content/") && method == HttpMethod.Get ->
                respond(
                    folderContentsByUuid.getValue(path.removePrefix("/drive/folders/content/")),
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            path.startsWith("/drive/files/") && path.endsWith("/meta") && method == HttpMethod.Get -> {
                val uuid = path.removePrefix("/drive/files/").removeSuffix("/meta")
                when {
                    uuid in fileMeta404 -> respond("""{"error":"not found"}""", HttpStatusCode.NotFound, jsonHeaders)
                    else -> respond(fileMetaByUuid.getValue(uuid), HttpStatusCode.OK, jsonHeaders)
                }
            }
            path.startsWith("/drive/folders/") && path.endsWith("/meta") && method == HttpMethod.Get -> {
                val uuid = path.removePrefix("/drive/folders/").removeSuffix("/meta")
                when {
                    uuid in folderMeta404 -> respond("""{"error":"not found"}""", HttpStatusCode.NotFound, jsonHeaders)
                    else -> respond(folderMetaByUuid.getValue(uuid), HttpStatusCode.OK, jsonHeaders)
                }
            }
            path == "/drive/storage/trash/add" && method == HttpMethod.Post -> {
                trashBody = drainBody(request.body)
                respond("""{"items":[]}""", HttpStatusCode.OK, jsonHeaders)
            }
            path.startsWith("/drive/files/") && method == HttpMethod.Delete -> respond("", HttpStatusCode.OK, jsonHeaders)
            path == "/drive/folders" && method == HttpMethod.Delete -> {
                purgeFolderBody = drainBody(request.body)
                respond("", HttpStatusCode.OK, jsonHeaders)
            }
            path.startsWith("/drive/files/") && (method == HttpMethod.Patch || method == HttpMethod.Put) -> {
                val uuid = path.removePrefix("/drive/files/").removeSuffix("/meta")
                respond(fileJson(uuid, plainName = "moved", type = "txt"), HttpStatusCode.OK, jsonHeaders)
            }
            path.startsWith("/drive/folders/") && method == HttpMethod.Patch -> {
                val uuid = path.removePrefix("/drive/folders/")
                respond(folderJson(uuid, plainName = "moved"), HttpStatusCode.OK, jsonHeaders)
            }
            else -> respond("""{"error":"unexpected: $method $path"}""", HttpStatusCode.NotFound, jsonHeaders)
        }
    }

    private fun drainBody(content: OutgoingContent): String =
        when (content) {
            is TextContent -> content.text
            is ByteArrayContent -> content.bytes().decodeToString()
            else -> error("unexpected body type: ${content.javaClass}")
        }

    // ---- fail-closed resolution ----

    @Test
    fun `getMetadata with two same-named files throws instead of picking one`() =
        runTest {
            folderFilesJson =
                "[${fileJson("twin-a", "dup")},${fileJson("twin-b", "dup")}]"
            val provider = newProvider()
            val e = assertFailsWith<ProviderException> { provider.getMetadata("/dup.txt") }
            assertTrue("ambiguous" in (e.message ?: ""), "message must say ambiguous; got: ${e.message}")
            assertTrue("2" in (e.message ?: ""))
        }

    @Test
    fun `getMetadata with two same-named child folders throws instead of picking one`() =
        runTest {
            folderChildrenJson =
                "[${folderJson("folder-a", "dup")},${folderJson("folder-b", "dup")}]"
            val provider = newProvider()
            val e = assertFailsWith<ProviderException> { provider.getMetadata("/dup") }
            assertTrue("ambiguous" in (e.message ?: ""))
        }

    @Test
    fun `resolving THROUGH an ambiguous folder fails closed`() =
        runTest {
            // /dup exists twice at the root; a file under either is unaddressable by path.
            folderChildrenJson =
                "[${folderJson("folder-a", "dup")},${folderJson("folder-b", "dup")}]"
            val provider = newProvider()
            assertFailsWith<ProviderException> { provider.getMetadata("/dup/x.txt") }
        }

    @Test
    fun `a unique name still resolves`() =
        runTest {
            folderFilesJson = "[${fileJson("solo-uuid", "unique")}]"
            val provider = newProvider()
            val item = provider.getMetadata("/unique.txt")
            assertEquals("solo-uuid", item.id)
        }

    // ---- archive#1178: delete for good ----

    @Test
    fun `deletePermanentlyById purges the file uuid, never trashes, never touches the twin`() =
        runTest {
            fileMetaByUuid["twin-a"] = fileJson("twin-a", "dup")
            val provider = newProvider()
            provider.deletePermanentlyById("twin-a", "/dup.txt")
            assertTrue("DELETE /drive/files/twin-a" in requestLog, "log: $requestLog")
            assertTrue(requestLog.none { "twin-b" in it }, "the sibling twin must be untouched: $requestLog")
            assertEquals(null, trashBody, "a permanent delete must not go through the trash")
        }

    @Test
    fun `deletePermanentlyById purges a folder uuid through the folders endpoint`() =
        runTest {
            fileMeta404 += "folder-a"
            folderMetaByUuid["folder-a"] = folderJson("folder-a", "dup")
            val provider = newProvider()
            provider.deletePermanentlyById("folder-a", "/dup")
            val body = purgeFolderBody
            assertTrue(body != null && "folder-a" in body && """"folder"""" in body, "purge body: $body")
            assertEquals(null, trashBody)
        }

    @Test
    fun `a plain delete still trashes and never purges`() =
        runTest {
            fileMetaByUuid["twin-a"] = fileJson("twin-a", "dup")
            val provider = newProvider()
            provider.deleteById("twin-a", "/dup.txt")
            assertTrue(trashBody != null)
            assertTrue(requestLog.none { it.startsWith("DELETE") }, "the default path must never delete for good: $requestLog")
        }

    @Test
    fun `internxt declares the permanent-delete capability`() {
        assertTrue(newProvider().capabilities().any { it is org.krost.unidrive.Capability.PermanentDelete })
    }

    // ---- by-id operations ----

    @Test
    fun `deleteById trashes the file uuid without resolving by path`() =
        runTest {
            fileMetaByUuid["twin-a"] = fileJson("twin-a", "dup")
            val provider = newProvider()
            provider.deleteById("twin-a", "/dup.txt")
            val body = trashBody
            assertTrue(body != null && "twin-a" in body && """"file"""" in body, "trash body: $body")
            assertTrue("twin-b" !in body, "the sibling twin must be untouched")
        }

    @Test
    fun `deleteById falls back to the folder probe for a folder uuid`() =
        runTest {
            fileMeta404 += "folder-a" // not a file
            folderMetaByUuid["folder-a"] = folderJson("folder-a", "dup")
            val provider = newProvider()
            provider.deleteById("folder-a", "/dup")
            val body = trashBody
            assertTrue(body != null && "folder-a" in body && """"folder""" in body, "trash body: $body")
        }

    @Test
    fun `a known folder is resolved without probing file metadata`() =
        runTest {
            fileMeta404 += "folder-a"
            folderMetaByUuid["folder-a"] = folderJson("folder-a", "dup")
            newProvider().deleteById("folder-a", "/dup", isFolder = true)
            assertTrue("GET /drive/folders/folder-a/meta" in requestLog, "log: $requestLog")
            assertTrue("GET /drive/files/folder-a/meta" !in requestLog, "log: $requestLog")
            assertTrue(trashBody?.contains("folder-a") == true)
        }

    @Test
    fun `a stale folder hint falls back to the file with that identity`() =
        runTest {
            folderMeta404 += "twin-a"
            fileMetaByUuid["twin-a"] = fileJson("twin-a", "dup")
            newProvider().deleteById("twin-a", "/dup.txt", isFolder = true)
            assertTrue("GET /drive/folders/twin-a/meta" in requestLog)
            assertTrue("GET /drive/files/twin-a/meta" in requestLog)
            assertTrue(trashBody?.contains("\"file\"") == true)
        }

    // ---- gkrost/unidrive-cli#634: the refusal climb asks nothing where a listing already proved the chain ----

    @Test
    fun `a folder a listing walked through climbs no ancestors on delete`() =
        runTest {
            folderChildrenJson = "[${folderJson("docs-uuid", "docs")}]"
            folderContentsByUuid["docs-uuid"] =
                """{"children":[${folderJson("sub-uuid", "sub", "docs-uuid")}],"files":[]}"""
            folderMetaByUuid["sub-uuid"] = folderJson("sub-uuid", "sub", "docs-uuid")
            val provider = newProvider()
            provider.listChildren("/docs")
            provider.deleteById("sub-uuid", "/docs/sub", isFolder = true)
            assertTrue("GET /drive/folders/sub-uuid/meta" in requestLog, "log: $requestLog")
            assertTrue(
                "GET /drive/folders/docs-uuid/meta" !in requestLog,
                "the parent chain was seeded by the listing; log: $requestLog",
            )
            assertTrue(trashBody?.contains("sub-uuid") == true)
        }

    @Test
    fun `an unlisted path still climbs to prove the drive root`() =
        runTest {
            folderMetaByUuid["sub-uuid"] = folderJson("sub-uuid", "sub", "docs-uuid")
            folderMetaByUuid["docs-uuid"] = folderJson("docs-uuid", "docs")
            newProvider().deleteById("sub-uuid", "/docs/sub", isFolder = true)
            assertTrue("GET /drive/folders/docs-uuid/meta" in requestLog, "the climb is the fallback; log: $requestLog")
            assertTrue(trashBody?.contains("sub-uuid") == true)
        }

    @Test
    fun `a delta chain that reaches the root seeds the climb`() =
        runTest {
            val provider = newProvider()
            val folderMap =
                mapOf(
                    "docs-uuid" to InternxtFolder(uuid = "docs-uuid", plainName = "docs", parentUuid = rootUuid),
                    "sub-uuid" to InternxtFolder(uuid = "sub-uuid", plainName = "sub", parentUuid = "docs-uuid"),
                )
            provider.seedInsideDriveChains(
                listOf(folderItem("sub-uuid", parent = "docs-uuid")),
                folderMap,
                rootUuid,
                HashSet(),
            )
            folderMetaByUuid["sub-uuid"] = folderJson("sub-uuid", "sub", "docs-uuid")
            provider.deleteById("sub-uuid", "/docs/sub", isFolder = true)
            assertTrue("GET /drive/folders/docs-uuid/meta" !in requestLog, "log: $requestLog")
            assertTrue(trashBody?.contains("sub-uuid") == true)
        }

    @Test
    fun `a chain that ends at an unknown folder seeds nothing`() =
        runTest {
            val provider = newProvider()
            val folderMap =
                mapOf(
                    // docs hangs below an orphan top the pass does not know: nothing is proven.
                    "docs-uuid" to InternxtFolder(uuid = "docs-uuid", plainName = "docs", parentUuid = "orphan-uuid"),
                )
            provider.seedInsideDriveChains(
                listOf(folderItem("docs-uuid", parent = "orphan-uuid")),
                folderMap,
                rootUuid,
                HashSet(),
            )
            folderMetaByUuid["docs-uuid"] = folderJson("docs-uuid", "docs", "orphan-uuid")
            folderMetaByUuid["orphan-uuid"] =
                """{"uuid":"orphan-uuid","plainName":"orphan","parentUuid":null,"status":"EXISTS"}"""
            assertFailsWith<ProviderException> { newProvider().deleteById("docs-uuid", "/docs", isFolder = true) }
            assertTrue("GET /drive/folders/orphan-uuid/meta" in requestLog, "the climb still ran; log: $requestLog")
        }

    private fun folderItem(
        id: String,
        parent: String,
    ) = CloudItem(
        id = id,
        name = "f",
        path = "/f",
        size = 0,
        isFolder = true,
        modified = Instant.parse("2026-11-12T00:00:00Z"),
        created = Instant.parse("2026-11-12T00:00:00Z"),
        hash = null,
        mimeType = null,
        parentId = parent,
    )

    @Test
    fun `deleteById on an unknown uuid surfaces not-found`() =
        runTest {
            fileMeta404 += "gone-uuid"
            folderMeta404 += "gone-uuid"
            val provider = newProvider()
            val e = assertFailsWith<ProviderException> { provider.deleteById("gone-uuid", "/gone.txt") }
            assertTrue("not found" in (e.message ?: "").lowercase())
        }

    @Test
    fun `moveById patches the requested uuid, never a same-named sibling`() =
        runTest {
            fileMetaByUuid["twin-a"] = fileJson("twin-a", "dup")
            fileMetaByUuid["twin-b"] = fileJson("twin-b", "dup")
            val provider = newProvider()
            val moved = provider.moveById("twin-a", "/dup.txt", "/moved.txt")
            assertEquals("twin-a", moved.id)
            val patch =
                requestLog.firstOrNull { it.startsWith("PATCH /drive/files/twin-a") }
                    ?: requestLog.firstOrNull { it.startsWith("PUT /drive/files/twin-a") }
            assertTrue(patch != null, "expected a write to twin-a's endpoint; requests: $requestLog")
            assertTrue(
                requestLog.none { it.contains("twin-b") },
                "the sibling twin must not be touched; requests: $requestLog",
            )
        }
}
