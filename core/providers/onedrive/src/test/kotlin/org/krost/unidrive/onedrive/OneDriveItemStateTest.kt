package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.ItemRemoteState
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * gkrost/unidrive-cli#632: [OneDriveProvider.itemStateById] asks `GET /items/{id}`. A move keeps the item's id, so a
 * 200 says live (under whatever parent another client moved it to); a 404 answers NotFound — it cannot tell the
 * recycle bin from a delete for good, and an answer that proves nothing must not decide.
 */
class OneDriveItemStateTest {
    private val json = headersOf("Content-Type", "application/json")

    private fun providerAnswering(status: HttpStatusCode, body: String): OneDriveProvider {
        val config = OneDriveConfig(tokenPath = Files.createTempDirectory("ud-itemstate"))
        val service = GraphApiService(config = config, httpClient = io.ktor.client.HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("/items/rid-1")) {
                respond(body, status, json)
            } else {
                respond("""{"error":{"code":"unexpected","message":"${request.url.encodedPath}"}}""", HttpStatusCode.NotFound, json)
            }
        }), tokenProvider = { _, _ -> "test-token" })
        return OneDriveProvider(config, graphApi = service)
    }

    @Test
    fun `a live file is Live(false)`() = runTest {
        val p = providerAnswering(HttpStatusCode.OK, """{"id":"rid-1","name":"a.txt","size":1,"file":{}}""")
        assertEquals(ItemRemoteState.Live(false), p.itemStateById("rid-1"))
    }

    @Test
    fun `a live folder is Live(true)`() = runTest {
        val p = providerAnswering(HttpStatusCode.OK, """{"id":"rid-1","name":"d","folder":{}}""")
        assertEquals(ItemRemoteState.Live(true), p.itemStateById("rid-1"))
    }

    @Test
    fun `a 404 is NotFound, not a guess about the trash`() = runTest {
        val p = providerAnswering(HttpStatusCode.NotFound, """{"error":{"code":"itemNotFound","message":"gone"}}""")
        assertEquals(ItemRemoteState.NotFound, p.itemStateById("rid-1"))
    }
}
