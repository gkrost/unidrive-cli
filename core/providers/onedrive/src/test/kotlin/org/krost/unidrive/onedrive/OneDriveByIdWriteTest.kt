package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `deleteById` and `moveById` carry the id the engine recorded for the row so that the item it planned the write for is
 * the item it writes to (archive#402). For an item in the caller's own drive both used to throw the id away and address
 * the path: when the item had been renamed away on another device and a new item created at its old path between the
 * gather and the apply, the write hit the new item (#82 B, archive#1260).
 *
 * The drive is [SimilarNameGraph], a fake Graph that resolves by id and by path.
 */
class OneDriveByIdWriteTest {
    private lateinit var tmp: Path
    private lateinit var graph: SimilarNameGraph
    private lateinit var provider: OneDriveProvider

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("ud-by-id-write")
        graph = SimilarNameGraph()
        val config = OneDriveConfig(tokenPath = tmp.resolve("token"))
        provider = OneDriveProvider(config)
        val api = GraphApiService(config = config, tokenProvider = { _ -> "test-token" })
        GraphApiService::class.java.getDeclaredField("httpClient").apply {
            isAccessible = true
            (get(api) as? HttpClient)?.close()
            set(api, graph.client())
        }
        OneDriveProvider::class.java.getDeclaredField("graphApi").apply {
            isAccessible = true
            (get(provider) as? GraphApiService)?.close()
            set(provider, api)
        }
    }

    @AfterTest
    fun tearDown() {
        provider.close()
        tmp.toFile().deleteRecursively()
    }

    // The race: the item the engine knows (A) is renamed away on another device, and a different item (B) takes its
    // old path before the engine applies its write. Returns A's id.
    private fun renameAwayAndReplace(
        path: String,
        renamedTo: String,
    ): String {
        val a = graph.seed(path, bytes = "old".toByteArray())
        val parent = assertNotNull(a.parent)
        parent.children.remove(a.name)
        a.name = renamedTo
        parent.children[renamedTo] = a
        graph.seed(path, bytes = "new".toByteArray())
        return a.id
    }

    @Test
    fun `deleteById deletes the item with the id, not the item that took its path`() =
        runTest {
            val idOfA = renameAwayAndReplace("/a.txt", "a-moved.txt")

            provider.deleteById(idOfA, "/a.txt")

            assertNull(graph.find("/a-moved.txt"), "the item the engine planned the delete for is gone")
            assertNotNull(graph.find("/a.txt"), "the new item at the old path is untouched")
        }

    @Test
    fun `deleteById of an id that is already gone deletes nothing at the path`() =
        runTest {
            val idOfA = renameAwayAndReplace("/a.txt", "a-moved.txt")
            provider.deleteById(idOfA, "/a.txt")

            provider.deleteById(idOfA, "/a.txt")

            assertNotNull(graph.find("/a.txt"), "a repeat of the delete must not reach the item that took the path")
        }

    @Test
    fun `moveById renames the item with the id, not the item that took its path`() =
        runTest {
            val idOfA = renameAwayAndReplace("/a.txt", "a-moved.txt")

            val moved = provider.moveById(idOfA, "/a.txt", "/c.txt")

            assertEquals(idOfA, moved.id)
            assertNotNull(graph.find("/c.txt"), "the item with the id is at the new path")
            assertNull(graph.find("/a-moved.txt"))
            assertEquals("new", graph.find("/a.txt")?.bytes?.decodeToString(), "the new item at the old path stays where it is")
        }

    @Test
    fun `moveById of an id that is gone fails and does not move what sits at the path`() =
        runTest {
            val idOfA = renameAwayAndReplace("/a.txt", "a-moved.txt")
            provider.deleteById(idOfA, "/a.txt")

            assertFailsWith<GraphApiException> { provider.moveById(idOfA, "/a.txt", "/c.txt") }

            assertNotNull(graph.find("/a.txt"))
            assertNull(graph.find("/c.txt"))
        }

    @Test
    fun `the path based delete and move are unchanged`() =
        runTest {
            graph.seed("/x.txt", bytes = "x".toByteArray())
            graph.seed("/y.txt", bytes = "y".toByteArray())

            provider.delete("/x.txt")
            provider.move("/y.txt", "/z.txt")

            assertNull(graph.find("/x.txt"))
            assertNull(graph.find("/y.txt"))
            assertNotNull(graph.find("/z.txt"))
        }
}
