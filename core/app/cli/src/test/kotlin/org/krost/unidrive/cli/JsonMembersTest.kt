package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

// gkrost/unidrive-cli#442: the separators of the daemon.status reply are written in one place.
class JsonMembersTest {
    @Test
    fun `members keep their order, absent ones leave no separator, and the text is one JSON object`() {
        val text =
            JsonMembers()
                .put("ok", "true")
                .put("quota", null)
                .put("list", "[1,2]")
                .put("paused", null)
                .put("obj", """{"a":"b"}""")
                .render()

        assertEquals("""{"ok":true,"list":[1,2],"obj":{"a":"b"}}""", text)
        assertEquals(listOf("ok", "list", "obj"), Json.parseToJsonElement(text).jsonObject.keys.toList())
    }

    @Test
    fun `an object whose every member is absent is empty`() {
        assertEquals("{}", JsonMembers().put("a", null).render())
    }
}
