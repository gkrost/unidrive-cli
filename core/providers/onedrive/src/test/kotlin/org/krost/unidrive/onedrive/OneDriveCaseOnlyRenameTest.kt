package org.krost.unidrive.onedrive

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * #309: a local rename that changes only the case (`case1.txt` to `CASE1.txt`) is one remote move. OneDrive renames an
 * item in place by a PATCH of its name; the folder is the same, so no parent reference (and no parent lookup) is sent.
 */
class OneDriveCaseOnlyRenameTest {
    @Test
    fun `a case-only rename is one PATCH of the name`() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            val engine =
                MockEngine { request ->
                    seen += request
                    respond(
                        """{"id":"item-1","name":"CASE1.txt","size":5,"file":{"mimeType":"text/plain"}}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val service =
                GraphApiService(
                    config = OneDriveConfig(tokenPath = Files.createTempDirectory("od-case-rename")),
                    engine = engine,
                    tokenProvider = { "token" },
                )

            service.moveItem("item-1", "/docs/CASE1.txt", oldPath = "/docs/case1.txt")

            assertEquals(listOf("PATCH"), seen.map { it.method.value }, "one request: ${seen.map { "${it.method.value} ${it.url}" }}")
            val body: JsonObject = Json.parseToJsonElement((seen.single().body as TextContent).text).jsonObject
            assertEquals("CASE1.txt", body["name"]?.jsonPrimitive?.content)
            assertFalse("parentReference" in body, "same folder: no parent reference in $body")
            service.close()
        }
}
