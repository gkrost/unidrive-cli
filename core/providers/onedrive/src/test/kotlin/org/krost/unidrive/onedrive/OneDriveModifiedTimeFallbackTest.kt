package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Graph's `fileSystemInfo` carries the client-supplied times. A client that never set them leaves them missing, malformed or
 * at an epoch (1970-01-01, or 0001-01-01 as .NET writes a default date): such a time says nothing, and the item's own
 * server-stamped `lastModifiedDateTime` / `createdDateTime` is used instead. A real `fileSystemInfo` time still wins.
 */
class OneDriveModifiedTimeFallbackTest {
    private val serverModified = "2026-03-04T05:06:07Z"
    private val serverCreated = "2026-01-02T03:04:05Z"

    private suspend fun deltaItem(body: String): CloudItem {
        val engine = MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val provider = OneDriveProvider(graphApi = GraphApiService(config = OneDriveConfig(), httpClient = HttpClient(engine), tokenProvider = { _, _ -> "test-token" }))
        try {
            return assertNotNull(provider.delta(null).items.find { it.id == "F1" })
        } finally {
            provider.close()
        }
    }

    private suspend fun itemWith(fileSystemInfo: String?): CloudItem {
        val fsi = fileSystemInfo?.let { ",\"fileSystemInfo\":$it" } ?: ""
        return deltaItem(
            """
            {"value":[{"id":"F1","name":"a.txt","size":3,"file":{"mimeType":"text/plain"},
              "createdDateTime":"$serverCreated","lastModifiedDateTime":"$serverModified"$fsi,
              "parentReference":{"path":"/drive/root:"}}],
             "@odata.deltaLink":"https://graph.microsoft.com/v1.0/me/drive/root/delta?token=T"}
            """.trimIndent(),
        )
    }

    @Test
    fun `a real fileSystemInfo time wins over the server time`() =
        runTest {
            val item = itemWith("""{"lastModifiedDateTime":"2025-05-06T07:08:09Z","createdDateTime":"2025-01-01T00:00:00Z"}""")
            assertEquals(Instant.parse("2025-05-06T07:08:09Z"), item.modified)
            assertEquals(Instant.parse("2025-01-01T00:00:00Z"), item.created)
        }

    @Test
    fun `a missing fileSystemInfo falls back to the server time`() =
        runTest {
            val item = itemWith(null)
            assertEquals(Instant.parse(serverModified), item.modified)
            assertEquals(Instant.parse(serverCreated), item.created)
        }

    @Test
    fun `a unix epoch fileSystemInfo time falls back to the server time`() =
        runTest {
            val item = itemWith("""{"lastModifiedDateTime":"1970-01-01T00:00:00Z","createdDateTime":"1970-01-01T00:00:00Z"}""")
            assertEquals(Instant.parse(serverModified), item.modified)
            assertEquals(Instant.parse(serverCreated), item.created)
        }

    @Test
    fun `a year one fileSystemInfo time falls back to the server time`() =
        runTest {
            val item = itemWith("""{"lastModifiedDateTime":"0001-01-01T00:00:00Z","createdDateTime":"0001-01-01T00:00:00Z"}""")
            assertEquals(Instant.parse(serverModified), item.modified)
            assertEquals(Instant.parse(serverCreated), item.created)
        }

    @Test
    fun `an unparsable fileSystemInfo time falls back to the server time`() =
        runTest {
            val item = itemWith("""{"lastModifiedDateTime":"not a date","createdDateTime":""}""")
            assertEquals(Instant.parse(serverModified), item.modified)
            assertEquals(Instant.parse(serverCreated), item.created)
        }

    @Test
    fun `a fileSystemInfo that names only one of the times keeps the other from the server`() =
        runTest {
            val item = itemWith("""{"lastModifiedDateTime":"2025-05-06T07:08:09Z"}""")
            assertEquals(Instant.parse("2025-05-06T07:08:09Z"), item.modified)
            assertEquals(Instant.parse(serverCreated), item.created)
        }

    @Test
    fun `no usable time anywhere stays unknown`() =
        runTest {
            val item =
                deltaItem(
                    """
                    {"value":[{"id":"F1","name":"a.txt","size":3,"file":{"mimeType":"text/plain"},
                      "fileSystemInfo":{"lastModifiedDateTime":"1970-01-01T00:00:00Z"},"parentReference":{"path":"/drive/root:"}}],
                     "@odata.deltaLink":"https://graph.microsoft.com/v1.0/me/drive/root/delta?token=T"}
                    """.trimIndent(),
                )
            assertNull(item.modified)
        }
}
