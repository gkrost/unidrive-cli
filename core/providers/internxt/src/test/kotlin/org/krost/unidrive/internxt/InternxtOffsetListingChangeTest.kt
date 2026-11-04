package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.internxt.model.InternxtCredentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * #263: the offset-paged fallback of a folder listing (a gateway without the cursor route) counts positions in a list
 * that can change between two pages. An item removed before the offset moves every later item up one place, and the
 * item at the page boundary was never served, while the listing still ended as complete. The fake gateway here serves
 * the offset endpoint from a uuid-sorted list and removes an item between two pages.
 */
class InternxtOffsetListingChangeTest {
    private val credentials =
        InternxtCredentials(jwt = "jwt", mnemonic = "m", rootFolderId = "root", email = "e@example.invalid", bridgeUser = "bu", bridgeUserId = "bi")

    private class Gateway(
        count: Int,
        // The most items one page serves, as a gateway with a page cap does; the next page may change it.
        var cap: Int = Int.MAX_VALUE,
        // Called after each served offset page with the number of pages served so far; may change [rows] and [cap].
        val afterPage: Gateway.(Int) -> Unit = {},
    ) {
        val rows: MutableList<String> = (0 until count).map { "c%04d".format(it) }.toMutableList()
        val offsets = mutableListOf<Int>()
        var pages = 0

        val engine =
            MockEngine { request ->
                val path = request.url.encodedPath
                when {
                    path.contains("/folders/v2/content/") -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
                    path.contains("/folders/content/big/folders") -> {
                        val limit = request.url.parameters["limit"]!!.toInt()
                        val offset = request.url.parameters["offset"]!!.toInt()
                        offsets += offset
                        val page = rows.sorted().drop(offset).take(minOf(limit, cap))
                        pages++
                        afterPage(pages)
                        respond("""{"folders":[${page.joinToString(",") { folder(it, it, "big") }}]}""", HttpStatusCode.OK, jsonHeaders)
                    }
                    else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
                }
            }
    }

    private fun service(gateway: Gateway) =
        InternxtApiService(
            InternxtConfig(credentialProtector = null),
            credentialsProvider = { _, _ -> credentials },
            httpClient = HttpClient(gateway.engine),
        )

    @Test
    fun `an item removed before the offset between two pages does not hide the item at the page boundary`() =
        runTest {
            // 120 rows, pages of 50. After the first page, a row the first page already served is removed.
            val gateway = Gateway(120) { pages -> if (pages == 1) rows.remove("c0010") }

            val listed = service(gateway).getChildFoldersPaged("big").map { it.uuid }

            val surviving = gateway.rows.sorted()
            assertEquals(surviving, listed, "every surviving item exactly once, in order (offsets asked: ${gateway.offsets})")
            assertTrue("c0050" in listed, "the item at the boundary of the first page is listed")
        }

    @Test
    fun `an unchanged folder is listed with pages that overlap by one item`() =
        runTest {
            val gateway = Gateway(120)

            val listed = service(gateway).getChildFoldersPaged("big").map { it.uuid }

            assertEquals(gateway.rows, listed)
            assertEquals(
                listOf(0, 49, 98, 119, 120),
                gateway.offsets,
                "each page starts at the previous page's last item; the end is confirmed by the empty page after it",
            )
        }

    @Test
    fun `a persistent one-item page cap fails as incomplete instead of answering the first item as the folder`() =
        runTest {
            // Three rows, one served per page: the overlap never moves, so no full list can be read.
            val gateway = Gateway(3, cap = 1)

            val e = assertFailsWith<InternxtApiException> { service(gateway).getChildFoldersPaged("big") }

            assertEquals(503, e.statusCode, "incomplete, not the prefix [c0000]: ${e.message}")
        }

    @Test
    fun `a nonterminal page shortened to the overlap alone fails as incomplete instead of ending the listing`() =
        runTest {
            // The first page serves 50 rows; the second is cut to its overlap item while rows follow it.
            val gateway = Gateway(120) { pages -> cap = if (pages == 1) 1 else Int.MAX_VALUE }

            val e = assertFailsWith<InternxtApiException> { service(gateway).getChildFoldersPaged("big") }

            assertEquals(503, e.statusCode, "incomplete, not the 50 rows read so far: ${e.message}")
            assertEquals(listOf(0, 49, 50), gateway.offsets, "the overlap-only page asks the position after it, which holds a row")
        }

    @Test
    fun `a folder that changes under every read fails as incomplete instead of answering short`() =
        runTest {
            // Every second page (each read's second page) removes the first row: the overlap never holds.
            val gateway = Gateway(200) { pages -> if (pages % 2 == 1) rows.removeAt(0) }

            val e = assertFailsWith<InternxtApiException> { service(gateway).getChildFoldersPaged("big") }

            assertEquals(503, e.statusCode, "incomplete, as the other broken-listing guards: ${e.message}")
            assertEquals(1 + InternxtApiService.MAX_OFFSET_RESTARTS, gateway.offsets.count { it == 0 }, "the first read and its restarts")
        }

    @Test
    fun `an empty folder ends on its first page`() =
        runTest {
            val gateway = Gateway(0)

            assertEquals(emptyList(), service(gateway).getChildFoldersPaged("big"))
            assertEquals(listOf(0), gateway.offsets)
        }
}
