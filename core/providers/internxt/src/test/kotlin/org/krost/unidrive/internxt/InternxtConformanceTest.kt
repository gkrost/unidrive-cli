package org.krost.unidrive.internxt

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
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.testing.CloudProviderConformanceTest
import java.nio.file.Files
import java.nio.file.Path

/**
 * The SPI's conformance contracts against [InternxtProvider] over a scripted HTTP fake.
 *
 * A scripted backend holds no state of its own, so the id-addressed contracts read the evidence off the recorded
 * requests: the trash went to the uuid, the rename went to the uuid, and never to the item at the handed path. The
 * write token is compared against fresh metadata before anything is sent (archive#464), so the upload-guard contract
 * needs only the metadata endpoint: a stale token refuses before the file is encrypted and shipped. The delete's
 * token is accepted and ignored by declaration (the routine delete trashes), and the suite pins exactly that. The
 * reload contract pins the decision that closed the fail-open default: reload reads the stored credentials only,
 * never the console prompt of `authenticate`.
 */
class InternxtConformanceTest : CloudProviderConformanceTest() {
    override val statefulBackend: Boolean = false
    override val guardedDelete: Boolean = false
    override val storedCredentialsRemovable: Boolean = true

    // The root folder id `internxtProviderOn` signs the write provider in with.
    private val rootUuid = "root"

    private lateinit var dir: Path
    private lateinit var requestLog: MutableList<String>
    private val fileMetaByUuid = mutableMapOf<String, String>()
    private val trashBodies = mutableListOf<String>()
    private val renameTargets = mutableListOf<String>()
    private var folderFilesJson = "[]"
    private var fileCounter = 0

    override fun newProvider(): CloudProvider {
        dir = Files.createTempDirectory("conformance-ix")
        storeCredentials()
        return InternxtProvider(InternxtConfig(credentialProtector = null, tokenPath = dir))
    }

    override fun newWriteProvider(): CloudProvider {
        requestLog = mutableListOf()
        fileMetaByUuid.clear()
        trashBodies.clear()
        renameTargets.clear()
        folderFilesJson = "[]"
        fileCounter = 0
        return internxtProviderOn(scriptedEngine(), InternxtConfig(credentialProtector = null, tokenPath = Files.createTempFile("conformance-ix-token", ".tmp")))
    }

    override suspend fun seedFile(
        provider: CloudProvider,
        path: String,
        content: String,
    ): CloudItem {
        fileCounter += 1
        val uuid = "conformance-file-$fileCounter"
        fileMetaByUuid[uuid] = fileJson(uuid, path.substringAfterLast('/').removeSuffix(".txt"))
        folderFilesJson = fileMetaByUuid.values.joinToString(",", "[", "]")
        return provider.getMetadata(path)
    }

    override fun localFile(content: String): Path =
        Files.createTempFile("conformance-local", ".txt").also { Files.writeString(it, content) }

    override fun recordedDeleteTargets(): List<String> =
        trashBodies.flatMap { UUID_IN_BODY.findAll(it).map { m -> m.groupValues[1] } }

    override fun recordedMoveSources(): List<String> = renameTargets.map { it.removePrefix("/drive/files/").removeSuffix("/meta") }

    override fun clearStoredCredentials() {
        Files.delete(dir.resolve("credentials.json"))
    }

    private fun storeCredentials() {
        val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":9999999999}""".toByteArray())
        val credentials =
            org.krost.unidrive.internxt.model.InternxtCredentials(
                jwt = "header.$payload.signature",
                mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
                rootFolderId = rootUuid,
                email = "conformance@example.invalid",
                bridgeUser = "bridge-user",
                bridgeUserId = "bridge-secret",
                bucket = "conformance-bucket",
            )
        Files.createDirectories(dir)
        Files.writeString(
            dir.resolve("credentials.json"),
            kotlinx.serialization.json.Json.encodeToString(org.krost.unidrive.internxt.model.InternxtCredentials.serializer(), credentials),
        )
    }

    private fun fileJson(
        uuid: String,
        plainName: String,
    ): String =
        """{"uuid":"$uuid","plainName":"$plainName","type":"txt","size":"7","folderUuid":"$rootUuid","status":"EXISTS",""" +
            """"modificationTime":"2026-07-01T10:00:00.000Z","updatedAt":"2026-07-01T10:00:00.000Z"}"""

    private fun folderJson(uuid: String): String =
        """{"uuid":"$uuid","plainName":"root","parentUuid":null,"status":"EXISTS",""" +
            """"modificationTime":"2026-07-01T10:00:00.000Z","updatedAt":"2026-07-01T10:00:00.000Z"}"""

    private fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        val path = request.url.encodedPath
        val method = request.method
        requestLog += "$method $path"
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        return when {
            path == "/drive/folders/content/$rootUuid" && method == HttpMethod.Get ->
                respond("""{"children":[],"files":$folderFilesJson}""", HttpStatusCode.OK, jsonHeaders)
            path.startsWith("/drive/files/") && path.endsWith("/meta") && method == HttpMethod.Get -> {
                val uuid = path.removePrefix("/drive/files/").removeSuffix("/meta")
                fileMetaByUuid[uuid]
                    ?.let { respond(it, HttpStatusCode.OK, jsonHeaders) }
                    ?: respond("""{"error":"not found"}""", HttpStatusCode.NotFound, jsonHeaders)
            }
            path.startsWith("/drive/folders/") && path.endsWith("/meta") && method == HttpMethod.Get ->
                respond(folderJson(path.removePrefix("/drive/folders/").removeSuffix("/meta")), HttpStatusCode.OK, jsonHeaders)
            path == "/drive/storage/trash/add" && method == HttpMethod.Post -> {
                trashBodies += drainBody(request.body)
                respond("""{"items":[]}""", HttpStatusCode.OK, jsonHeaders)
            }
            path.startsWith("/drive/files/") && path.endsWith("/meta") && (method == HttpMethod.Put || method == HttpMethod.Patch) -> {
                renameTargets += path
                val uuid = path.removePrefix("/drive/files/").removeSuffix("/meta")
                respond(fileJson(uuid, "conformance-moved"), HttpStatusCode.OK, jsonHeaders)
            }
            else -> respond("""{"error":"unexpected: $method $path"}""", HttpStatusCode.NotFound, jsonHeaders)
        }
    }

    private fun scriptedEngine(): MockEngine =
        MockEngine { request ->
            handle(request)
        }

    private companion object {
        val UUID_IN_BODY = Regex("\"uuid\":\"([^\"]+)\"")
    }

    private fun drainBody(content: OutgoingContent): String =
        when (content) {
            is TextContent -> content.text
            is ByteArrayContent -> content.bytes().decodeToString()
            else -> error("unexpected body type: ${content.javaClass}")
        }
}
