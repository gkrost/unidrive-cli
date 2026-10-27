package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.ProviderException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A server or proxy that answers a page with the `@odata.nextLink` it was already asked for would otherwise be
 * fetched for ever, holding memory and a budget slot (#82 F). The listing fails as incomplete instead, which the
 * engine retries; a page cap keeps even fresh links from going on for ever. The transport is faked the same way
 * [ListChildrenPaginationTest] does it.
 */
class ListChildrenRepeatedLinkTest {
    private fun pageBody(
        items: Int,
        prefix: String,
        nextLink: String?,
    ): String {
        val entries = (0 until items).joinToString(",") { i -> """{"id":"$prefix-$i","name":"${prefix}_$i"}""" }
        val nextPart = if (nextLink != null) ""","@odata.nextLink":"$nextLink"""" else ""
        return """{"value":[$entries]$nextPart}"""
    }

    private fun installMockClient(
        service: GraphApiService,
        engine: MockEngine,
    ) {
        val field = GraphApiService::class.java.getDeclaredField("httpClient")
        field.isAccessible = true
        (field.get(service) as? HttpClient)?.close()
        field.set(service, HttpClient(engine))
    }

    private fun newService() = GraphApiService(config = OneDriveConfig(), tokenProvider = { _ -> "test-token" })

    @Test
    fun `a repeated nextLink fails the listing instead of fetching it again`() =
        runTest {
            val link = "https://graph.microsoft.com/v1.0/me/drive/root/children?%24skiptoken=PAGE2"
            var calls = 0
            val engine =
                MockEngine { _ ->
                    // Page 2 answers the link it was asked with; page 3 would end the listing for a guard-less loop.
                    val body =
                        when (calls++) {
                            0 -> pageBody(2, "p1", link)
                            1 -> pageBody(1, "p2", link)
                            else -> pageBody(0, "p3", null)
                        }
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            val service = newService()
            installMockClient(service, engine)

            val e = assertFailsWith<ProviderException> { service.listChildren("/") }

            assertTrue(e.message!!.contains("already"), "the failure must name the repeat: ${e.message}")
            assertEquals(2, calls, "the listing must stop at the page whose link repeats")
            service.close()
        }

    @Test
    fun `distinct links still list to the end`() =
        runTest {
            val link2 = "https://graph.microsoft.com/v1.0/me/drive/root/children?%24skiptoken=PAGE2"
            val link3 = "https://graph.microsoft.com/v1.0/me/drive/root/children?%24skiptoken=PAGE3"
            var calls = 0
            val engine =
                MockEngine { _ ->
                    val body =
                        when (calls++) {
                            0 -> pageBody(2, "p1", link2)
                            1 -> pageBody(2, "p2", link3)
                            else -> pageBody(1, "p3", null)
                        }
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            val service = newService()
            installMockClient(service, engine)

            val items = service.listChildren("/")

            assertEquals(5, items.size, "three pages with distinct links must all be read")
            assertEquals(3, calls)
            service.close()
        }
}
