package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.ProviderException
import org.krost.unidrive.onedrive.model.FileSystemInfo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * archive#1256 C: the read paths fail through `requireSuccess`, which keeps Graph's `request-id` on the exception (so a
 * failure can be matched against Graph's logs) and truncates the error body (an HTML gateway page is not logged whole). The
 * write paths wrote their own non-2xx mappings and kept neither. Each write is run through the same two failures: a 500 with
 * a request id and a very long HTML body, and a 401.
 */
class GraphWriteErrorShapeTest {
    private lateinit var tokenDir: Path
    private lateinit var localFile: Path

    @BeforeTest
    fun setUp() {
        tokenDir = Files.createTempDirectory("ud-graph-write-error")
        localFile = tokenDir.resolve("local.bin")
        Files.write(localFile, "bytes".toByteArray())
    }

    @AfterTest
    fun tearDown() {
        tokenDir.toFile().deleteRecursively()
    }

    private class Write(
        val name: String,
        val failingMethod: String,
        val errorPrefix: String,
        val call: suspend GraphApiService.(Path) -> Unit,
    )

    private val writes =
        listOf(
            Write("uploadSimple", "PUT", "Upload failed") { uploadSimple("/a.txt", "x".toByteArray()) },
            Write("deleteItem", "DELETE", "Delete failed") { deleteItem("item-1") },
            Write("permanentDeleteItem", "POST", "Permanent delete failed") { permanentDeleteItem("item-1") },
            Write("createFolder", "POST", "Create folder failed") { createFolder("sub", "/") },
            Write("moveItem", "PATCH", "Move failed") { moveItem("item-1", "/b.txt", oldPath = "/a.txt") },
            Write("patchFileSystemInfo", "PATCH", "fileSystemInfo PATCH failed") {
                uploadSimple("/a.txt", "x".toByteArray(), fileSystemInfo = FileSystemInfo(lastModifiedDateTime = "2026-03-28T12:00:00Z"))
            },
            Write("createUploadSession", "POST", "Create upload session failed") { local -> uploadLargeFile(local, "/big.bin") },
        )

    private val htmlPage = "<html><body>" + "gateway error ".repeat(1_000) + "</body></html>"

    private val itemJson = """{"id":"item-1","name":"x","size":5,"file":{"mimeType":"application/octet-stream"}}"""

    private fun MockRequestHandleScope.success(request: HttpRequestData): HttpResponseData =
        when {
            request.method.value == "DELETE" -> respond("", HttpStatusCode.NoContent)
            request.url.encodedPath.endsWith("createUploadSession") ->
                respond(
                    """{"uploadUrl":"https://upload.example/session-1","expirationDateTime":"2099-01-01T00:00:00Z"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            else -> respond(itemJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

    private fun serviceFailingWith(
        write: Write,
        status: HttpStatusCode,
    ): GraphApiService {
        val engine =
            MockEngine { request ->
                if (request.method.value == write.failingMethod && request.url.host != "upload.example") {
                    respond(htmlPage, status, headersOf(HttpHeaders.ContentType to listOf("text/html"), "request-id" to listOf("REQ-1")))
                } else {
                    success(request)
                }
            }
        val service = GraphApiService(config = OneDriveConfig(tokenPath = tokenDir), httpClient = HttpClient(engine), tokenProvider = { _ -> "token" })
        return service
    }

    @Test
    fun `a failed write keeps Graph's request id and a truncated body`() {
        for (write in writes) {
            val service = serviceFailingWith(write, HttpStatusCode.InternalServerError)
            runTest {
                val failure = assertFailsWith<ProviderException>(write.name) { service.run { write.call(this, localFile) } }

                assertEquals("REQ-1", failure.requestId, "${write.name}: the Graph request id is on the exception")
                assertTrue(failure.message.orEmpty().startsWith(write.errorPrefix), "${write.name}: ${failure.message?.take(80)}")
                assertTrue(failure.message.orEmpty().length < 2_000, "${write.name}: the HTML body is cut, the message is ${failure.message?.length} characters")
                assertEquals(500, failure.httpStatus, write.name)
            }
            service.close()
        }
    }

    @Test
    fun `a refused token on a write keeps Graph's request id and a truncated body`() {
        for (write in writes) {
            val service = serviceFailingWith(write, HttpStatusCode.Unauthorized)
            runTest {
                val failure = assertFailsWith<AuthenticationException>(write.name) { service.run { write.call(this, localFile) } }

                assertEquals("REQ-1", failure.requestId, "${write.name}: the Graph request id is on the exception")
                assertTrue(failure.message.orEmpty().length < 2_000, "${write.name}: the HTML body is cut, the message is ${failure.message?.length} characters")
            }
            service.close()
        }
    }
}
