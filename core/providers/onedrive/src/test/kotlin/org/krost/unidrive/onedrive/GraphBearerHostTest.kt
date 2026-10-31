package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.ProviderException
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
 * Paging and delta links come from response bodies and are kept as the cursor, then fetched with the bearer token
 * (#83 D, archive#1261). A link to another host, or to the Graph host over plain http, must not receive the token: it
 * is refused before a request is made. The pre-signed download and upload URLs already go without the token.
 */
class GraphBearerHostTest {
    private lateinit var tmp: Path
    private val seen = mutableListOf<Pair<String, String?>>()

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("ud-bearer-host")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private val graph = "https://graph.microsoft.com/v1.0"

    private fun serviceAnswering(vararg pages: Pair<String, String>): GraphApiService {
        val byUrl = pages.toMap()
        val engine =
            MockEngine { request ->
                seen += request.url.toString() to request.headers[HttpHeaders.Authorization]
                val body = byUrl[request.url.toString()] ?: """{"value":[]}"""
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        val service = GraphApiService(config = OneDriveConfig(tokenPath = tmp.resolve("token")), httpClient = HttpClient(engine), tokenProvider = { _ -> "secret-token" })
        return service
    }

    @Test
    fun `a delta cursor on another host is refused and no request is made`() =
        runTest {
            val service = serviceAnswering()

            assertFailsWith<ProviderException> { service.getDelta("https://collector.example/v1.0/me/drive/root/delta?token=x") }

            assertTrue(seen.isEmpty(), "nothing was sent: $seen")
            service.close()
        }

    @Test
    fun `a delta cursor over plain http on the Graph host is refused`() =
        runTest {
            val service = serviceAnswering()

            assertFailsWith<ProviderException> { service.getDelta("http://graph.microsoft.com/v1.0/me/drive/root/delta?token=x") }

            assertTrue(seen.isEmpty(), "nothing was sent: $seen")
            service.close()
        }

    @Test
    fun `a nextLink to another host in a children listing never receives the token`() =
        runTest {
            val first = "$graph/me/drive/root/children?\$top=999"
            val service = serviceAnswering(first to """{"value":[{"id":"1","name":"a"}],"@odata.nextLink":"https://collector.example/page2"}""")

            assertFailsWith<ProviderException> { service.listChildren("/") }

            assertEquals(listOf(first), seen.map { it.first }, "only the first page was requested")
            assertTrue(seen.none { it.first.contains("collector.example") }, "no request reached the foreign host: $seen")
            service.close()
        }

    @Test
    fun `links on the Graph host over https are followed with the token`() =
        runTest {
            val first = "$graph/me/drive/root/children?\$top=999"
            val second = "$graph/me/drive/root/children?\$skiptoken=2"
            val service =
                serviceAnswering(
                    first to """{"value":[{"id":"1","name":"a"}],"@odata.nextLink":"$second"}""",
                    second to """{"value":[{"id":"2","name":"b"}]}""",
                )

            val items = service.listChildren("/")

            assertEquals(listOf("1", "2"), items.map { it.id })
            assertTrue(seen.all { it.second == "Bearer secret-token" }, "$seen")
            assertFalse(seen.isEmpty())
            service.close()
        }
}
