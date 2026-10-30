package org.krost.unidrive.io

import kotlinx.serialization.json.JsonPrimitive

/**
 * [s] as one JSON string literal, quotes included: the one escaper for the lines of the IPC socket (replies and events) that are
 * built by hand. kotlinx escapes what JSON requires (quote, backslash, every control character below U+0020); NEL (U+0085),
 * LINE SEPARATOR (U+2028) and PARAGRAPH SEPARATOR (U+2029) are escaped as well, since some line readers end a line at them
 * although JSON allows them raw. So a value (a file name, an exception message, a provider's account name) can never split or
 * end an NDJSON line, and the literal reads back as exactly [s].
 *
 * Use it wherever a string goes into such a line, in place of `JsonPrimitive(s).toString()` or a hand-written escaper
 * (gkrost/unidrive-cli#222 item 1).
 */
public fun ndjsonString(s: String): String {
    val quoted = JsonPrimitive(s).toString()
    if (quoted.none { it in EXTRA_LINE_BREAKS }) return quoted
    val sb = StringBuilder(quoted.length + 10)
    for (c in quoted) {
        if (c in EXTRA_LINE_BREAKS) {
            sb.append('\\').append('u').append(Integer.toHexString(c.code).padStart(4, '0'))
        } else {
            sb.append(c)
        }
    }
    return sb.toString()
}

private val EXTRA_LINE_BREAKS = charArrayOf('\u0085', ' ', ' ')
