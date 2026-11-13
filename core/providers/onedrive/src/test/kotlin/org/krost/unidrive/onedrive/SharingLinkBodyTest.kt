package org.krost.unidrive.onedrive

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SharingLinkBodyTest {
    private val now = Instant.parse("2026-06-01T12:00:00Z")

    private fun body(
        expiryHours: Int,
        password: String? = null,
    ): JsonObject = Json.decodeFromString(JsonObject.serializer(), sharingLinkBody("view", "anonymous", expiryHours, password, now))

    @Test
    fun `a positive expiry is sent as expirationDateTime`() {
        assertEquals("2026-06-02T12:00:00Z", body(24)["expirationDateTime"]?.jsonPrimitive?.content)
    }

    @Test
    fun `an expiry of zero sends no expirationDateTime`() {
        val sent = body(0)

        assertFalse("expirationDateTime" in sent, "$sent")
        assertEquals("view", sent["type"]?.jsonPrimitive?.content)
        assertEquals("anonymous", sent["scope"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a password is sent only when given`() {
        assertEquals("pw", body(1, "pw")["password"]?.jsonPrimitive?.content)
        assertFalse("password" in body(1))
    }
}
