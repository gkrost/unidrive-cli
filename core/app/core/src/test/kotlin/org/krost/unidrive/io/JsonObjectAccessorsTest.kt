package org.krost.unidrive.io

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JsonObjectAccessorsTest {
    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun `string reads a JSON string and nothing else`() {
        val o = obj("""{"s":"text","e":"","n":5,"b":true,"z":null,"o":{"a":"x"},"a":["x"]}""")
        assertEquals("text", o.string("s"))
        assertEquals("", o.string("e"))
        for (key in listOf("n", "b", "z", "o", "a", "missing")) {
            assertNull(o.string(key), key)
        }
    }

    @Test
    fun `string decodes escapes and looks only at the top level`() {
        val o = obj("""{"s":"a\"b\\cé","nested":{"s":"inner"}}""")
        assertEquals("a\"b\\cé", o.string("s"))
        assertNull(o.string("inner"))
    }

    @Test
    fun `isTrue is the bare literal true and nothing else`() {
        val o = obj("""{"t":true,"f":false,"s":"true","u":"TRUE","one":1,"z":null,"o":{"a":true},"a":[true]}""")
        assertEquals(true, o.isTrue("t"))
        for (key in listOf("f", "s", "u", "one", "z", "o", "a", "missing")) {
            assertEquals(false, o.isTrue(key), key)
        }
    }
}
