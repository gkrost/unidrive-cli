package org.krost.unidrive.io

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NdjsonTest {
    // The characters a line reader may end a line at: LF, CR, and the three Unicode ones JSON allows raw.
    private val lineEnders = setOf('\n', '\r', '\u0085', ' ', ' ')

    @Test
    fun `plain text is just quoted`() {
        assertEquals("\"plain\"", ndjsonString("plain"))
        assertEquals("\"\"", ndjsonString(""))
    }

    @Test
    fun `quote backslash and control characters are escaped as kotlinx escapes them`() {
        assertEquals("\"q\\\"b\\\\\"", ndjsonString("q\"b\\"))
        assertEquals("\"\\u0001\\u001f\"", ndjsonString("\u0001\u001f"))
        val text = "tab\tcr\rlf\nbs\bff\u000c del\u007f café"
        assertEquals(JsonPrimitive(text).toString(), ndjsonString(text))
    }

    @Test
    fun `NEL and the line and paragraph separators are escaped`() {
        assertEquals("\"a\\u0085b\\u2028c\\u2029d\"", ndjsonString("a\u0085b c d"))
    }

    @Test
    fun `no character leaves a raw line ender or control character, and every one reads back`() {
        for (code in 0..0xFFFF) {
            val c = code.toChar()
            if (c in '\uD800'..'\uDFFF') continue
            val text = "a${c}b"
            val quoted = ndjsonString(text)
            assertTrue(quoted.none { it < ' ' || it in lineEnders }, "U+%04X left a raw line ender or control character: %s".format(code, quoted))
            assertEquals(text, Json.parseToJsonElement(quoted).jsonPrimitive.content, "U+%04X".format(code))
        }
    }

    @Test
    fun `surrogate pairs and a lone surrogate read back`() {
        for (text in listOf("smile😀", "lone\uD83D", "\uDE00first")) {
            assertEquals(text, Json.parseToJsonElement(ndjsonString(text)).jsonPrimitive.content)
        }
    }
}
