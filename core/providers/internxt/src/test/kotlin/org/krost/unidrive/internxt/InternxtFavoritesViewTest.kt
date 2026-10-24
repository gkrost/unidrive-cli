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
import org.krost.unidrive.ViewList
import org.krost.unidrive.ViewRef
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The provider's side of the Favorites view: `listView(FAVORITES, limit)` pages `GET /favorites` and answers the uuids of the
 * live favorite files. A gateway without the route is an unsupported view, not an error.
 */
class InternxtFavoritesViewTest {
    private val favorites = ViewKind.FAVORITES
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun file(
        uuid: String,
        status: String = "EXISTS",
    ) = """{"uuid":"$uuid","plainName":"$uuid","status":"$status","isFavorite":true,"unknownKey":1}"""

    private fun folder(
        uuid: String,
        deleted: Boolean = false,
    ) = """{"uuid":"$uuid","plainName":"$uuid","deleted":$deleted,"removed":false}"""

    private fun array(items: List<String>) = items.joinToString(",", "[", "]")

    /** A fake `/favorites` route over [files] and [folders]: honours `type`, `limit` and `offset`, records every query. */
    private class Fake(
        val files: List<String> = emptyList(),
        val folders: List<String> = emptyList(),
        val status: HttpStatusCode = HttpStatusCode.OK,
        val requests: MutableList<String> = CopyOnWriteArrayList(),
    )

    private fun providerOn(fake: Fake) =
        internxtProviderOn(
            MockEngine { request ->
                fake.requests += request.url.encodedPath + "?" + request.url.encodedQuery
                if (fake.status != HttpStatusCode.OK) return@MockEngine respond("""{"message":"no"}""", fake.status, json)
                val q = request.url.parameters
                val all = if (q["type"] == "folder") fake.folders else fake.files
                val offset = q["offset"]!!.toInt()
                val limit = q["limit"]!!.toInt()
                respond(array(all.drop(offset).take(limit)), HttpStatusCode.OK, json)
            },
        )

    private fun refs(result: CapabilityResult<List<ViewRef>>) = assertIs<CapabilityResult.Success<List<ViewRef>>>(result).value

    private fun uuids(n: Int) = (0 until n).map { "f%05d".format(it) }

    private fun offsets(fake: Fake) = fake.requests.map { Regex("offset=(\\d+)").find(it)!!.groupValues[1].toInt() }

    @Test
    fun `the provider declares the favorites view`() {
        assertTrue(Capability.View(favorites) in providerOn(Fake()).capabilities())
    }

    @Test
    fun `the live files are the references, sorted and paged by uuid, once each`() =
        runTest {
            val fake = Fake(files = listOf(file("a"), file("b"), file("a"), file("gone", "TRASHED"), file("c")))

            val listed = refs(providerOn(fake).listView(favorites, 5_000))

            assertEquals(listOf(ViewRef("a", false), ViewRef("b", false), ViewRef("c", false)), listed)
            assertEquals(listOf("/drive/favorites?type=file&limit=1000&offset=0&sort=uuid&order=ASC"), fake.requests)
        }

    @Test
    fun `two thousand three hundred files page as 1000, 1000 and 300`() =
        runTest {
            val fake = Fake(files = uuids(2300).map { file(it) })

            val listed = refs(providerOn(fake).listView(favorites, 5_000))

            assertEquals(2300, listed.size)
            assertFalse((listed as ViewList).truncated)
            assertEquals(listOf(0, 1000, 2000), offsets(fake))
        }

    @Test
    fun `a full last page is followed by an empty one that ends the stream`() =
        runTest {
            val fake = Fake(files = uuids(1000).map { file(it) })

            assertEquals(1000, refs(providerOn(fake).listView(favorites, 5_000)).size)
            assertEquals(listOf(0, 1000), offsets(fake))
        }

    @Test
    fun `no favorites is a view with nothing in it`() =
        runTest {
            assertEquals(emptyList(), refs(providerOn(Fake()).listView(favorites, 10)))
        }

    @Test
    fun `favorited folders are neither requested nor listed`() =
        runTest {
            val fake = Fake(files = listOf(file("a")), folders = listOf(folder("d")))

            assertEquals(listOf(ViewRef("a", false)), refs(providerOn(fake).listView(favorites, 10)))
            assertTrue(fake.requests.none { "type=folder" in it })
        }

    @Test
    fun `the api reads folders first when asked, and drops the deleted ones`() =
        runTest {
            val fake = Fake(files = listOf(file("a")), folders = listOf(folder("d"), folder("x", deleted = true)))

            val listed = apiOf(providerOn(fake)).listFavorites(includeFolders = true)!!

            assertEquals(listOf(ViewRef("d", true), ViewRef("a", false)), listed.refs)
            assertEquals(listOf("folder", "file"), fake.requests.map { Regex("type=(\\w+)").find(it)!!.groupValues[1] })
        }

    @Test
    fun `the list is cut at the cap of references and says so`() =
        runTest {
            val fake = Fake(files = uuids(InternxtApiService.MAX_FAVORITE_REFS + 1).map { file(it) })

            val listed = refs(providerOn(fake).listView(favorites, 10_000))

            assertEquals(InternxtApiService.MAX_FAVORITE_REFS, listed.size)
            assertTrue((listed as ViewList).truncated)
        }

    @Test
    fun `a run of folders past the cap is cut and leaves the files to be read`() =
        runTest {
            val fake = Fake(files = listOf(file("a")), folders = uuids(InternxtApiService.MAX_FAVORITE_REFS + 1).map { folder(it) })

            val listed = apiOf(providerOn(fake)).listFavorites(includeFolders = true)!!

            assertTrue(listed.truncated)
            assertEquals(InternxtApiService.MAX_FAVORITE_REFS, listed.refs.count { it.isFolder })
            assertEquals(listOf(ViewRef("a", false)), listed.refs.filter { !it.isFolder })
        }

    @Test
    fun `the list is cut at the limit and says so`() =
        runTest {
            val listed = refs(providerOn(Fake(files = listOf(file("a"), file("b"), file("c")))).listView(favorites, 2))

            assertEquals(listOf(ViewRef("a", false), ViewRef("b", false)), listed)
            assertTrue((listed as ViewList).truncated)
        }

    @Test
    fun `a gateway without the route is an unsupported view`() =
        runTest {
            for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed)) {
                val result = providerOn(Fake(status = status)).listView(favorites, 10)
                assertIs<CapabilityResult.Unsupported>(result, "status $status")
            }
        }

    @Test
    fun `any other failure throws`() =
        runTest {
            assertFailsWith<InternxtApiException> { providerOn(Fake(status = HttpStatusCode.BadRequest)).listView(favorites, 10) }
        }

    private fun apiOf(provider: InternxtProvider): InternxtApiService =
        InternxtProvider::class.java.getDeclaredField("api").also { it.isAccessible = true }.get(provider) as InternxtApiService
}
