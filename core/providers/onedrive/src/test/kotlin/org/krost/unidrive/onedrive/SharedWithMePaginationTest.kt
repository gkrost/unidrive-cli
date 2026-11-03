package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.ProviderException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `include_shared` must read every page of `sharedWithMe` (an item past page 1 would look deleted
 * to the engine) and must hash shared items the way the rest of the provider does
 * (`quickXorHash` first, `sha256Hash` as the fallback).
 */
class SharedWithMePaginationTest {
    private fun sharedItem(
        id: String,
        hashes: String = "",
    ): String = """{"id":"stub-$id","name":"n-$id","size":10,"remoteItem":{"id":"r-$id","parentReference":{"driveId":"d-$id"},"file":{"mimeType":"text/plain"$hashes}}}"""

    private fun page(
        items: List<String>,
        nextLink: String? = null,
    ): String {
        val next = if (nextLink != null) ""","@odata.nextLink":"$nextLink"""" else ""
        return """{"value":[${items.joinToString(",")}]$next}"""
    }

    private fun serviceWith(engine: MockEngine): GraphApiService {
        val service =
            GraphApiService(
                config = OneDriveConfig(),
                httpClient = HttpClient(engine),
                tokenProvider = { _ -> "test-token" },
            )
        return service
    }

    private fun providerWith(engine: MockEngine): OneDriveProvider {
        val provider = OneDriveProvider(OneDriveConfig(includeShared = true), graphApi = serviceWith(engine))
        return provider
    }

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun `listSharedWithMe follows nextLink and requests the server link verbatim`() =
        runTest {
            val seen = mutableListOf<String>()
            val engine =
                MockEngine { request ->
                    seen += request.url.toString()
                    val body =
                        when (seen.size) {
                            1 -> page(listOf(sharedItem("a"), sharedItem("b")), "https://graph.microsoft.com/v1.0/me/drive/sharedWithMe?\$skiptoken=PAGE2")
                            2 -> page(listOf(sharedItem("c")))
                            else -> error("unexpected request ${seen.size}")
                        }
                    respond(body, HttpStatusCode.OK, json)
                }
            val service = serviceWith(engine)

            val items = service.listSharedWithMe()

            assertEquals(listOf("stub-a", "stub-b", "stub-c"), items.map { it.id })
            assertEquals(2, seen.size)
            assertTrue(seen[1].contains("skiptoken=PAGE2"), "second request must be the nextLink, was ${seen[1]}")
            service.close()
        }

    @Test
    fun `listSharedWithMe fails instead of returning a partial list when the server repeats a nextLink`() =
        runTest {
            val loop = "https://graph.microsoft.com/v1.0/me/drive/sharedWithMe?\$skiptoken=LOOP"
            var calls = 0
            val engine =
                MockEngine { _ ->
                    calls++
                    check(calls <= 10) { "nextLink loop was not broken" }
                    respond(page(listOf(sharedItem("x$calls")), loop), HttpStatusCode.OK, json)
                }
            val service = serviceWith(engine)

            // A partial list would read as "everything after the cut was unshared": the walk must fail, not return it.
            assertFailsWith<ProviderException> { service.listSharedWithMe() }

            // First request (no skiptoken) returns LOOP; the second request returns LOOP again and ends the walk.
            assertEquals(2, calls)
            service.close()
        }

    @Test
    fun `deltaWithShared returns shared items from every page with quickXor kept and sha256 never taken for it`() =
        runTest {
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    val body =
                        when {
                            url.contains("sharedWithMe") && url.contains("skiptoken=PAGE2") ->
                                page(listOf(sharedItem("s3", ""","hashes":{"sha256Hash":"SHA3"}""")))
                            url.contains("sharedWithMe") ->
                                page(
                                    listOf(
                                        sharedItem("s1", ""","hashes":{"quickXorHash":"QX1","sha256Hash":"SHA1"}"""),
                                        sharedItem("s2", ""","hashes":{"quickXorHash":"QX2"}"""),
                                    ),
                                    "https://graph.microsoft.com/v1.0/me/drive/sharedWithMe?\$skiptoken=PAGE2",
                                )
                            else -> """{"value":[],"@odata.deltaLink":"https://graph.microsoft.com/v1.0/me/drive/root/delta?token=T"}"""
                        }
                    respond(body, HttpStatusCode.OK, json)
                }
            val provider = providerWith(engine)

            val result = provider.deltaWithShared(null)

            val page = assertIs<CapabilityResult.Success<org.krost.unidrive.DeltaPage>>(result).value
            val byId = page.items.associateBy { it.id }
            assertEquals(setOf("shared:d-s1:r-s1", "shared:d-s2:r-s2", "shared:d-s3:r-s3"), byId.keys)
            assertEquals("QX1", byId["shared:d-s1:r-s1"]?.hash)
            assertEquals("QX2", byId["shared:d-s2:r-s2"]?.hash)
            assertNull(byId["shared:d-s3:r-s3"]?.hash, "a sha256Hash is not a QuickXor: no hash when quickXorHash is absent")
            provider.close()
        }

    // The gather calls deltaWithShared once per delta page; listSharedWithMe is one full listing of
    // the shared set. It must run once per walk — on the page with hasMore == false — not once per
    // page on the same throttle budget, and the shared set must be delivered once, not per page.
    @Test
    fun `deltaWithShared fetches the shared set once per walk, on the last delta page`() =
        runTest {
            var sharedCalls = 0
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    val body =
                        when {
                            url.contains("sharedWithMe") -> {
                                sharedCalls++
                                page(listOf(sharedItem("s1")))
                            }
                            url.contains("skiptoken=P2") ->
                                """{"value":[],"@odata.nextLink":"https://graph.microsoft.com/v1.0/me/drive/root/delta?${'$'}skiptoken=P3"}"""
                            url.contains("skiptoken=P3") ->
                                """{"value":[],"@odata.deltaLink":"https://graph.microsoft.com/v1.0/me/drive/root/delta?token=T"}"""
                            else ->
                                """{"value":[],"@odata.nextLink":"https://graph.microsoft.com/v1.0/me/drive/root/delta?${'$'}skiptoken=P2"}"""
                        }
                    respond(body, HttpStatusCode.OK, json)
                }
            val provider = providerWith(engine)

            val first = assertIs<CapabilityResult.Success<org.krost.unidrive.DeltaPage>>(provider.deltaWithShared(null)).value
            val second = assertIs<CapabilityResult.Success<org.krost.unidrive.DeltaPage>>(provider.deltaWithShared(first.cursor)).value
            val third = assertIs<CapabilityResult.Success<org.krost.unidrive.DeltaPage>>(provider.deltaWithShared(second.cursor)).value

            assertTrue(first.hasMore && second.hasMore, "the walk runs three delta pages")
            assertTrue(!third.hasMore, "the third page ends the walk")
            assertTrue(first.items.isEmpty() && second.items.isEmpty(), "no shared items while more delta pages remain")
            assertEquals(listOf("shared:d-s1:r-s1"), third.items.map { it.id }, "the shared set is delivered once, on the last page")
            assertEquals(1, sharedCalls, "listSharedWithMe ran exactly once for the whole walk")
            provider.close()
        }
}
