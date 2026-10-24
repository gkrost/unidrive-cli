package org.krost.unidrive.internxt

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.FolderState
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * archive#580: [InternxtProvider.folderStateById] asks `GET /folders/{uuid}/meta`. Trashed and not found are answers; a call that
 * failed (a 5xx, a degraded gateway) is not one and propagates. A folder that is gone leaves the folder-uuid cache.
 */
class InternxtFolderStateTest {
    private val json = headersOf("Content-Type", "application/json")

    private fun meta(
        status: String = "EXISTS",
        removed: Boolean = false,
        deleted: Boolean = false,
    ) = """{"uuid":"$FOLDER","plainName":"Trashed","parentUuid":"root","status":"$status","removed":$removed,"deleted":$deleted}"""

    private fun providerAnswering(
        status: HttpStatusCode,
        body: String,
        calls: AtomicInteger = AtomicInteger(),
    ): InternxtProvider =
        internxtProviderOn(
            MockEngine { request ->
                if (request.url.encodedPath.endsWith("/folders/$FOLDER/meta")) {
                    calls.incrementAndGet()
                    respond(body, status, json)
                } else {
                    respond("""{"error":"unexpected ${request.url}"}""", HttpStatusCode.NotFound, json)
                }
            },
        )

    private fun InternxtProvider.cache(): FolderUuidCache =
        InternxtProvider::class.java.getDeclaredField("folderCache").also { it.isAccessible = true }.get(this) as FolderUuidCache

    // The folder by its name under the root, a folder below it, and an unrelated folder.
    private fun FolderUuidCache.seed() {
        put("root", "Trashed", FOLDER)
        put(FOLDER, "Sub", "sub-uuid")
        put("root", "Other", "other-uuid")
    }

    @Test
    fun `a trashed folder is TRASHED and leaves the folder cache`() =
        runTest {
            val provider = providerAnswering(HttpStatusCode.OK, meta(status = "TRASHED", removed = true))
            provider.cache().seed()

            assertEquals(FolderState.TRASHED, provider.folderStateById(FOLDER))
            assertEquals(null, provider.cache().get("root", "Trashed"), "the walk must not reach the trashed folder by its uuid")
            assertEquals(null, provider.cache().get(FOLDER, "Sub"), "nor a folder below it")
            assertEquals("other-uuid", provider.cache().get("root", "Other"), "an unrelated folder stays cached")
        }

    @Test
    fun `a folder with only the removed flag is TRASHED`() =
        runTest {
            assertEquals(FolderState.TRASHED, providerAnswering(HttpStatusCode.OK, meta(removed = true)).folderStateById(FOLDER))
        }

    @Test
    fun `a 404 or a folder deleted for good is NOT_FOUND`() =
        runTest {
            assertEquals(FolderState.NOT_FOUND, providerAnswering(HttpStatusCode.NotFound, """{"error":"Not Found"}""").folderStateById(FOLDER))
            assertEquals(FolderState.NOT_FOUND, providerAnswering(HttpStatusCode.OK, meta(status = "DELETED", deleted = true)).folderStateById(FOLDER))
        }

    @Test
    fun `a live folder is LIVE and stays cached`() =
        runTest {
            val provider = providerAnswering(HttpStatusCode.OK, meta())
            provider.cache().seed()

            assertEquals(FolderState.LIVE, provider.folderStateById(FOLDER))
            assertEquals(FOLDER, provider.cache().get("root", "Trashed"))
        }

    // A degraded gateway (archive#387) answers the meta with a 503: that proves nothing, so it is no answer at all.
    @Test
    fun `a 503 is not an answer and leaves the cache as it is`() =
        runTest {
            val provider = providerAnswering(HttpStatusCode.ServiceUnavailable, """{"error":"gateway"}""")
            provider.cache().seed()

            assertFails { provider.folderStateById(FOLDER) }
            assertEquals(FOLDER, provider.cache().get("root", "Trashed"))
        }

    private companion object {
        const val FOLDER = "trashed-folder-uuid"
    }
}
