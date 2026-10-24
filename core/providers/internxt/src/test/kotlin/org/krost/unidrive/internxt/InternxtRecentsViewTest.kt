package org.krost.unidrive.internxt

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.Capability
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.ViewKind
import org.krost.unidrive.ViewRef
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The provider's side of the Recents view: `listView(RECENTS, limit)` asks `GET /files/recents?limit=` and answers the uuids
 * of the live files in the server's order. A gateway without the route is an unsupported view, not an error.
 */
class InternxtRecentsViewTest {
    private val recents = ViewKind.RECENTS
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private class Served(
        val status: HttpStatusCode,
        val body: String,
    )

    private fun file(
        uuid: String,
        status: String = "EXISTS",
        extra: String = "",
    ) = """{"uuid":"$uuid","plainName":"$uuid","status":"$status","isFavorite":false,"sharings":[],"thumbnails":[]$extra}"""

    private fun providerOn(
        served: Served,
        requests: MutableList<String> = CopyOnWriteArrayList(),
    ) = internxtProviderOn(
        MockEngine { request ->
            requests += request.url.encodedPath + "?" + request.url.encodedQuery
            respond(served.body, served.status, json)
        },
    )

    private fun ok(vararg files: String) = Served(HttpStatusCode.OK, files.joinToString(",", "[", "]"))

    private fun refs(result: CapabilityResult<List<ViewRef>>) = assertIs<CapabilityResult.Success<List<ViewRef>>>(result).value

    @Test
    fun `the provider declares the recents view`() {
        assertTrue(Capability.View(recents) in providerOn(ok()).capabilities())
    }

    @Test
    fun `the live files are the references, in the server's order, once each`() =
        runTest {
            val listed =
                refs(
                    providerOn(ok(file("b"), file("a"), file("b"), file("gone", "TRASHED"), file("c", extra = ",\"unknownKey\":1")))
                        .listView(recents, 10),
                )

            assertEquals(listOf(ViewRef("b", false), ViewRef("a", false), ViewRef("c", false)), listed)
        }

    @Test
    fun `the limit is sent, clamped to what the server accepts`() =
        runTest {
            val requests = CopyOnWriteArrayList<String>()
            val provider = providerOn(ok(), requests)

            provider.listView(recents, 25)
            provider.listView(recents, 0)
            provider.listView(recents, 50_000)

            assertEquals(
                listOf("/drive/files/recents?limit=25", "/drive/files/recents?limit=1", "/drive/files/recents?limit=1000"),
                requests,
            )
        }

    @Test
    fun `an empty list is a view with nothing in it`() =
        runTest {
            assertEquals(emptyList(), refs(providerOn(ok()).listView(recents, 10)))
        }

    @Test
    fun `the list is cut at the limit`() =
        runTest {
            val listed = refs(providerOn(ok(file("a"), file("b"), file("c"))).listView(recents, 2))

            assertEquals(listOf(ViewRef("a", false), ViewRef("b", false)), listed)
        }

    @Test
    fun `a gateway without the route is an unsupported view`() =
        runTest {
            for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed)) {
                val result = providerOn(Served(status, "{}")).listView(recents, 10)
                assertIs<CapabilityResult.Unsupported>(result, "status $status")
            }
        }

    @Test
    fun `any other failure throws`() =
        runTest {
            assertFailsWith<InternxtApiException> {
                providerOn(Served(HttpStatusCode.BadRequest, """{"message":"limit"}""")).listView(recents, 10)
            }
        }
}
