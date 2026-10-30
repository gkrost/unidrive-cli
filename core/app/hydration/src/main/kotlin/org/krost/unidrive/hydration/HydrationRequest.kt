package org.krost.unidrive.hydration

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One request line of the hydration verbs, parsed once as the JSON object it is. The fields are read by name from the TOP level
 * only, so a nested member or a value that merely spells a key is never mistaken for it. A member that is absent, or has another
 * type than the accessor asks for, reads as null (the caller applies its default or refuses with `missing_*`).
 *
 * A line that is not one JSON object has no request ([parse] is null) and is answered `missing_verb`. A member named twice keeps the
 * last, as `IpcServer.parseVerb` reads it (it refuses a repeated `verb`; the request fields are never repeated by a client).
 * The parser recurses once per level of nesting, so a line nested deeper than [MAX_DEPTH] (a request is a few levels deep; IpcServer
 * refuses the same line before a handler sees it) has no request either.
 */
internal class HydrationRequest private constructor(private val members: JsonObject) {
    /** A top-level string member's decoded text; null for a missing member or any other JSON type (null included). */
    fun string(key: String): String? = (members[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * An OPTIONAL flag. The bare JSON literals `true` / `false` (what System.Text.Json and serde_json emit) and, defensively,
     * the quoted words in any case. A missing member, a null or any other value is null, so the caller applies its default.
     */
    fun bool(key: String): Boolean? {
        val value = members[key] as? JsonPrimitive ?: return null
        val word = if (value.isString) value.content.lowercase() else value.content
        return when (word) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    /**
     * An OPTIONAL whole-number member in [range]: [default] when absent, null when present and anything else (a string,
     * null, a fraction, an exponent, a sign, a number past [range]).
     */
    fun count(key: String, default: Int, range: IntRange): Int? {
        val member = members[key] ?: return default
        val digits = (member as? JsonPrimitive)?.takeIf { !it.isString }?.content ?: return null
        if (digits.isEmpty() || digits.length > MAX_COUNT_DIGITS || !digits.all { it in '0'..'9' }) return null
        return digits.toLong().takeIf { it in range.first..range.last }?.toInt()
    }

    companion object {
        // A count longer than this cannot be an Int; read it no further.
        private const val MAX_COUNT_DIGITS = 10

        // The depth IpcServer.parseVerb allows a request line.
        private const val MAX_DEPTH = 64

        fun parse(line: String): HydrationRequest? {
            if (nestsDeeperThan(line, MAX_DEPTH)) return null
            return try {
                (Json.parseToJsonElement(line) as? JsonObject)?.let(::HydrationRequest)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        // Brackets inside strings do not nest; an escape inside a string hides the character after it.
        private fun nestsDeeperThan(line: String, limit: Int): Boolean {
            var depth = 0
            var inString = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                if (inString) {
                    if (c == '\\') i++ else if (c == '"') inString = false
                } else if (c == '"') {
                    inString = true
                } else if (c == '{' || c == '[') {
                    if (++depth > limit) return true
                } else if (c == '}' || c == ']') {
                    depth--
                }
                i++
            }
            return false
        }
    }
}
