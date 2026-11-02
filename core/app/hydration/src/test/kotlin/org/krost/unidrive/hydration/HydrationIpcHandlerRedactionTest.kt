package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Error text a hydration reply or event carries goes through [org.krost.unidrive.http.UrlRedaction] first, as every
 * other outward sink of the engine does: a provider's exception message can quote a pre-authenticated request URL
 * (Ktor's timeout messages quote the whole URL), and its query is a credential.
 */
class HydrationIpcHandlerRedactionTest {
    private val timeout = "Request timeout has expired [url=https://shard.example/bucket/obj?sig=SECRET123, request_timeout=60000 ms]"
    private val redacted = "Request timeout has expired [url=https://shard.example/bucket/obj?<redacted>, request_timeout=60000 ms]"

    private fun errorOf(line: String): String = Json.parseToJsonElement(line).jsonObject.getValue("error").jsonPrimitive.content

    @Test
    fun `a failed open_read reply carries the error text without the URL query`() = runTest {
        val fake = ScriptedHydration().apply { open = OpenResult.Failed(HydrationError.Generic(timeout)) }

        val reply = HydrationIpcHandler(fake).handle("c1", """{"verb":"hydration.open_read","handle_id":"h1","path":"/a.bin"}""")

        assertFalse("SECRET123" in reply, reply)
        assertEquals(redacted, errorOf(reply))
    }

    @Test
    fun `a failed list reply carries the error text without the URL query`() = runTest {
        val fake = ScriptedHydration().apply { list = ListResult.Failed(HydrationError.Generic(timeout)) }

        val reply = HydrationIpcHandler(fake).handle("c1", """{"verb":"hydration.list","prefix":"/"}""")

        assertFalse("SECRET123" in reply, reply)
        assertEquals(redacted, errorOf(reply))
    }

    @Test
    fun `failed and completed events carry the error text without the URL query`() {
        val failed = serialiseHydrationEvent(HydrationEvent.Failed("/a.bin", HydrationError.Generic(timeout)))
        val completed =
            serialiseHydrationEvent(
                HydrationEvent.Completed(
                    "/a.bin",
                    "h1",
                    HydrationEvent.Completed.Direction.DOWNLOAD,
                    ok = false,
                    error = HydrationError.Generic(timeout),
                ),
            )

        for (line in listOf(failed, completed)) {
            assertFalse("SECRET123" in line, line)
            assertEquals(redacted, errorOf(line))
        }
    }

    @Test
    fun `stable error tokens pass unchanged`() = runTest {
        val fake = ScriptedHydration().apply { open = OpenResult.Failed(HydrationError.NotFound) }

        val reply = HydrationIpcHandler(fake).handle("c1", """{"verb":"hydration.open_read","handle_id":"h1","path":"/a.bin"}""")

        assertEquals("""{"ok":false,"error":"not_found"}""", reply.trim())
    }
}
