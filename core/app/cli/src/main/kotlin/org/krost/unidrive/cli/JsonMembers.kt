package org.krost.unidrive.cli

/**
 * gkrost/unidrive-cli#442: one JSON object written member by member, each value a JSON text its own builder rendered. The
 * separators are written here, once: a member added to a hand-assembled reply such as `daemon.status` cannot leave the line
 * without its comma, and a null value leaves its member out. Keys are the code's own literals and are not escaped.
 * Single use: [render] closes the object.
 */
internal class JsonMembers(
    capacity: Int = 2048,
) {
    private val text = StringBuilder(capacity).append('{')
    private var empty = true

    fun put(
        key: String,
        json: String?,
    ): JsonMembers {
        if (json == null) return this
        if (!empty) text.append(',')
        empty = false
        text.append('"').append(key).append("\":").append(json)
        return this
    }

    fun render(): String = text.append('}').toString()
}
