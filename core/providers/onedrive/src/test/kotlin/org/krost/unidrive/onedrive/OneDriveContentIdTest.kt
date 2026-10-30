package org.krost.unidrive.onedrive

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#233: a OneDrive row names its bytes ([org.krost.unidrive.CloudItem.contentId]) so a client tells a rename from
 * new bytes. OneDrive stamps a rename as a new modification (`lastModifiedDateTime` moves, the `eTag` counter is bumped), so neither
 * names the bytes; the `file.hashes` of the item do, and a rename leaves them alone.
 */
class OneDriveContentIdTest {
    private val tmp = Files.createTempDirectory("ud-onedrive-content-id")

    @BeforeTest
    fun setUp() {
        Files.createDirectories(tmp)
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    private fun jsonString(s: String) = "\"" + s.replace("\"", "\\\"") + "\""

    private fun itemJson(
        id: String = "item-1",
        name: String = "doc.txt",
        size: Long = 49,
        eTag: String = "\"{AAA},2\"",
        modified: String = "2026-10-30T10:00:00Z",
        hashes: String? = """{"quickXorHash":"qx-1"}""",
        downloadUrl: String? = null,
    ): String {
        val file = """"file":{"mimeType":"text/plain"${hashes?.let { ""","hashes":$it""" } ?: ""}}"""
        val dl = downloadUrl?.let { ""","@microsoft.graph.downloadUrl":"$it"""" } ?: ""
        return """{"id":"$id","name":"$name","size":$size,"eTag":${jsonString(eTag)},"lastModifiedDateTime":"$modified",
                  $file,"parentReference":{"path":"/drive/root:"}$dl}"""
    }

    private fun providerWith(engine: MockEngine): OneDriveProvider {
        val provider = OneDriveProvider(OneDriveConfig(tokenPath = tmp))
        val graphApi = GraphApiService(config = OneDriveConfig(tokenPath = tmp), engine = engine, tokenProvider = { _ -> "test-token" })
        val field = OneDriveProvider::class.java.getDeclaredField("graphApi")
        field.isAccessible = true
        (field.get(provider) as? GraphApiService)?.close()
        field.set(provider, graphApi)
        return provider
    }

    private fun listing(vararg items: String) =
        MockEngine { _ -> respond("""{"value":[${items.joinToString(",")}]}""", HttpStatusCode.OK, jsonHeaders()) }

    @Test
    fun `a rename keeps the content id although the modified time and the eTag move`() =
        runTest {
            val before = itemJson(name = "r1.txt", eTag = "\"{AAA},2\"", modified = "2026-10-30T10:00:00Z")
            val after = itemJson(name = "r1-renamed.txt", eTag = "\"{AAA},3\"", modified = "2026-10-30T10:00:30Z")

            val first = providerWith(listing(before)).listChildren("/").single()
            val second = providerWith(listing(after)).listChildren("/").single()

            assertNotEquals(first.version, second.version, "the fake rename bumps the eTag, as OneDrive does")
            assertNotEquals(first.modified, second.modified, "and stamps a new modification time")
            assertNotNull(first.contentId, "a file with a hash names its bytes")
            assertEquals(first.contentId, second.contentId, "a rename leaves the bytes, so it leaves their name")
        }

    @Test
    fun `other bytes under the same name and size get another content id`() =
        runTest {
            val a = providerWith(listing(itemJson(hashes = """{"quickXorHash":"qx-1"}"""))).listChildren("/").single()
            val b = providerWith(listing(itemJson(hashes = """{"quickXorHash":"qx-2"}"""))).listChildren("/").single()

            assertNotNull(a.contentId)
            assertNotEquals(a.contentId, b.contentId, "a changed hash is changed bytes")
        }

    @Test
    fun `the same hash at another size is another content id`() =
        runTest {
            val a = providerWith(listing(itemJson(size = 49))).listChildren("/").single()
            val b = providerWith(listing(itemJson(size = 50))).listChildren("/").single()

            assertNotEquals(a.contentId, b.contentId, "the size is part of the name of the bytes")
        }

    @Test
    fun `a file with no hash gets no content id`() =
        runTest {
            val noHashes = providerWith(listing(itemJson(hashes = null))).listChildren("/").single()
            val emptyHashes = providerWith(listing(itemJson(hashes = "{}"))).listChildren("/").single()
            val blankHash = providerWith(listing(itemJson(hashes = """{"quickXorHash":""}"""))).listChildren("/").single()

            assertNull(noHashes.contentId, "no hashes facet: nothing to name the bytes by")
            assertNull(emptyHashes.contentId, "an empty hashes facet: nothing to name the bytes by")
            assertNull(blankHash.contentId, "a blank hash names nothing")
        }

    @Test
    fun `a folder gets no content id`() =
        runTest {
            val folder = """{"id":"f-1","name":"dir","size":0,"eTag":"\"{F},1\"","folder":{"childCount":0},"parentReference":{"path":"/drive/root:"}}"""

            assertNull(providerWith(listing(folder)).listChildren("/").single().contentId)
        }

    @Test
    fun `sha1 and sha256 name the bytes when quickXor is absent, and another algorithm never shares a name`() =
        runTest {
            val qx = providerWith(listing(itemJson(hashes = """{"quickXorHash":"h"}"""))).listChildren("/").single().contentId
            val sha1 = providerWith(listing(itemJson(hashes = """{"sha1Hash":"h"}"""))).listChildren("/").single().contentId
            val sha256 = providerWith(listing(itemJson(hashes = """{"sha256Hash":"h"}"""))).listChildren("/").single().contentId

            assertNotNull(qx)
            assertNotNull(sha1, "a personal drive's sha1Hash names the bytes")
            assertNotNull(sha256, "a business drive's sha256Hash names the bytes")
            assertEquals(3, setOf(qx, sha1, sha256).size, "the same digits under another algorithm are not the same name")
        }

    @Test
    fun `the quickXor hash is preferred when several are present`() =
        runTest {
            val both = providerWith(listing(itemJson(hashes = """{"quickXorHash":"q","sha1Hash":"s"}"""))).listChildren("/").single().contentId
            val onlyQuickXor = providerWith(listing(itemJson(hashes = """{"quickXorHash":"q"}"""))).listChildren("/").single().contentId

            assertNotNull(both)
            assertEquals(onlyQuickXor, both, "an extra hash in the answer does not rename the bytes")
        }

    @Test
    fun `metadata of one item names the bytes as its listing does`() =
        runTest {
            val engine = MockEngine { _ -> respond(itemJson(name = "x.txt"), HttpStatusCode.OK, jsonHeaders()) }
            val provider = providerWith(engine)

            val listed = providerWith(listing(itemJson(name = "x.txt"))).listChildren("/").single().contentId
            assertNotNull(listed)
            assertEquals(listed, provider.getMetadata("/x.txt").contentId)
        }

    @Test
    fun `the provider says its listings name the bytes, so the once-per-profile backfill covers OneDrive rows`() {
        val provider = OneDriveProvider(OneDriveConfig(tokenPath = tmp))

        assertTrue(provider.reportsContentIds)
        provider.close()
    }

    @Test
    fun `a download names the bytes it read, as a listing of that item does`() =
        runTest {
            val read = itemJson(name = "d.txt", hashes = """{"quickXorHash":"qx-read"}""", downloadUrl = "https://cdn.example/bytes")
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    when {
                        url == "https://cdn.example/bytes" ->
                            respond("x".repeat(49), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/octet-stream"))
                        url.contains("/me/drive/root:/") || url.contains("/me/drive/items/item-1") ->
                            respond(read, HttpStatusCode.OK, jsonHeaders())
                        else -> error("unexpected URL: $url")
                    }
                }
            val provider = providerWith(engine)
            val listed = providerWith(listing(itemJson(name = "d.txt", hashes = """{"quickXorHash":"qx-read"}"""))).listChildren("/").single()

            val byPath = provider.downloadWithHash("/d.txt", tmp.resolve("a.bin"))
            val byId = provider.downloadByIdWithHash("item-1", "/d.txt", tmp.resolve("b.bin"))

            assertNotNull(listed.contentId)
            assertEquals(listed.contentId, byPath.contentId, "the receipt names the version that came down")
            assertEquals(listed.contentId, byId.contentId, "also the id-addressed read")
            provider.close()
        }

    @Test
    fun `a download of a file replaced since it was listed names the version that came down`() =
        runTest {
            val listedAs = providerWith(listing(itemJson(hashes = """{"quickXorHash":"qx-old"}"""))).listChildren("/").single()
            val current = itemJson(hashes = """{"quickXorHash":"qx-new"}""", downloadUrl = "https://cdn.example/bytes")
            val engine =
                MockEngine { request ->
                    val url = request.url.toString()
                    when {
                        url == "https://cdn.example/bytes" ->
                            respond("x".repeat(49), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/octet-stream"))
                        else -> respond(current, HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val provider = providerWith(engine)

            val receipt = provider.downloadWithHash("/doc.txt", tmp.resolve("c.bin"))

            assertNotNull(receipt.contentId)
            assertNotEquals(listedAs.contentId, receipt.contentId, "the row must not keep the name of bytes the copy does not hold")
            provider.close()
        }
}
