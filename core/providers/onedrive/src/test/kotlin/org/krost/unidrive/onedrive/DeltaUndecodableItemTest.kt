package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One delta item Graph sends in a shape the model cannot read (here a `size` that is not a number) is skipped, not the
 * whole page: the other items of the page come through and the cursor advances. The skipped item is not silent: its id
 * is named in [org.krost.unidrive.DeltaPage.unreachableIds], so the absence sweep of a full listing keeps its tracked
 * row instead of reaping it, and a skipped item without a readable id leaves the page incomplete. An answer whose
 * envelope does not decode is still retried and then fails.
 */
class DeltaUndecodableItemTest {
    private lateinit var tmp: Path
    private var deltaCalls = 0

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("ud-delta-undecodable")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private val deltaLink = "https://graph.microsoft.com/v1.0/me/drive/root/delta?token=NEXT"

    private fun file(id: String, name: String, size: String = "12") =
        """{"id": "$id", "name": "$name", "size": $size, "file": {"mimeType": "text/plain"},
           "parentReference": {"id": "R", "path": "/drive/root:", "driveId": "d"}}"""

    private fun page(vararg items: String): String = """{"value": [${items.joinToString(",")}], "@odata.deltaLink": "$deltaLink"}"""

    private fun providerOver(deltaBody: String): OneDriveProvider {
        val engine =
            MockEngine { request ->
                val json = headersOf(HttpHeaders.ContentType, "application/json")
                if (request.url.encodedPath.endsWith("/delta")) {
                    deltaCalls++
                    respond(deltaBody, HttpStatusCode.OK, json)
                } else {
                    respond("""{"error":{"code":"x","message":"unhandled"}}""", HttpStatusCode.BadRequest, json)
                }
            }
        val config = OneDriveConfig(tokenPath = tmp.resolve("token"))
        val api = GraphApiService(config = config, httpClient = HttpClient(engine), tokenProvider = { _, _ -> "test-token" })
        return OneDriveProvider(config, graphApi = api)
    }

    @Test
    fun `an item that does not decode is skipped and the rest of the page comes through`() =
        runTest {
            val provider = providerOver(page(file("A", "a.txt"), file("BAD", "b.txt", size = "\"not-a-number\""), file("C", "c.txt")))

            val result = provider.delta(cursor = null)

            assertEquals(listOf("A", "C"), result.items.map { it.id })
            assertEquals(deltaLink, result.cursor, "the cursor advances past the page")
            assertFalse(result.hasMore)
            assertEquals(setOf("BAD"), result.unreachableIds, "the skipped item is named, so its tracked row is held")
            assertTrue(result.complete)
            assertEquals(1, deltaCalls, "one bad item is no reason to fetch the page again")
            provider.close()
        }

    @Test
    fun `an item that does not decode and names no id leaves the page incomplete`() =
        runTest {
            val provider = providerOver(page(file("A", "a.txt"), """{"name": "x", "size": "not-a-number"}"""))

            val result = provider.delta(cursor = null)

            assertEquals(listOf("A"), result.items.map { it.id })
            assertEquals(deltaLink, result.cursor)
            assertFalse(result.complete, "an item that cannot even be named makes the listing incomplete")
            assertEquals(1, deltaCalls)
            provider.close()
        }

    @Test
    fun `an envelope that does not decode is retried and then fails`() =
        runTest {
            val provider = providerOver("""{"value": {"id": "A"}, "@odata.deltaLink": "$deltaLink"}""")

            assertFailsWith<SerializationException> { provider.delta(cursor = null) }
            assertEquals(3, deltaCalls)
            provider.close()
        }
}
