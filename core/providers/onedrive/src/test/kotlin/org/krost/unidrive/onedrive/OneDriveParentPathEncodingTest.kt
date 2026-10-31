package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.onedrive.model.percentDecode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Microsoft documents `itemReference.path` as percent-encoded: "The path value is a percent-encoded API path
 * relative to the target drive, for example: `/drive/root:/Documents/my%20file.docx`"
 * (https://learn.microsoft.com/en-us/graph/api/resources/itemreference, Remarks). The provider must hand the
 * engine the decoded path, the same text the drive uses for the name.
 */
class OneDriveParentPathEncodingTest {
    private fun mockedProvider(responseBody: String): OneDriveProvider {
        val engine =
            MockEngine { _ ->
                respond(
                    content = responseBody,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        val freshGraphApi = GraphApiService(config = OneDriveConfig(), httpClient = HttpClient(engine), tokenProvider = { _ -> "test-token" })
        val provider = OneDriveProvider(graphApi = freshGraphApi)
        return provider
    }

    private fun itemJson(parentPath: String) =
        """
        {"id":"ITEM-1","name":"report 1.txt","size":12,"file":{"mimeType":"text/plain"},
         "parentReference":{"path":"$parentPath"}}
        """.trimIndent()

    @Test
    fun `a delta item below a folder with a space has the decoded path`() =
        runTest {
            val body =
                """{"value":[${itemJson("/drive/root:/My%20Folder/Sch%C3%B6n")}],
                   "@odata.deltaLink":"https://graph.microsoft.com/v1.0/me/drive/root/delta?token=NEXT"}"""
            val provider = mockedProvider(body)

            val item = provider.delta(null).items.find { it.id == "ITEM-1" }

            assertNotNull(item)
            assertEquals("/My Folder/Schön/report 1.txt", item.path)
            provider.close()
        }

    @Test
    fun `only valid percent sequences are decoded`() {
        assertEquals("/a b/c+d", percentDecode("/a%20b/c+d"))
        assertEquals("/100%/x", percentDecode("/100%/x"))
        assertEquals("/50%2/x", percentDecode("/50%2/x"))
        assertEquals("/%zz", percentDecode("/%zz"))
        assertEquals("/%FF/x", percentDecode("/%FF/x"), "bytes that are not UTF-8 stay as they came")
        assertEquals("/😀", percentDecode("/%F0%9F%98%80"))
        assertEquals("/ü", percentDecode("/%C3%BC"))
    }

    @Test
    fun `a lookup of a file below a folder with a space is not read as another item`() =
        runTest {
            val provider = mockedProvider(itemJson("/drive/root:/My%20Folder"))

            val item = provider.getMetadata("/My Folder/report 1.txt")

            assertEquals("/My Folder/report 1.txt", item.path)
            provider.close()
        }
}
