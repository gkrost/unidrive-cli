package org.krost.unidrive.http

/**
 * The part of a server's error body that an exception text may quote: short, on one line, and without what the body may
 * hold beside the reason. An exception text becomes a log line, an IPC reply, an audit entry and a notification, and a
 * gateway in front of a route can echo whatever it holds (a token, a key, a signed URL). [truncateErrorBody] passes a
 * JSON body through whole; this is for a text that must stay bounded whatever the body is.
 *
 * The reason stays readable: the field names, short words and numbers (a `retry_after`, an error class such as
 * `MissingUploadsError`, "Bucket entry ... not found"). Masked: the value of a field named like a secret, a bearer token,
 * an e-mail address, a UUID and a long hex id, any long opaque string, and the query of every URL ([UrlRedaction]). The
 * cut comes last, so it never leaves half a secret behind.
 */
object ErrorBodyExcerpt {
    const val DEFAULT_MAX_CHARS = 300

    // The regexes below see at most this much of a body: what lies after it is never quoted anyway.
    private const val SCAN_LIMIT = 16_384

    private const val REDACTED = "<redacted>"
    private const val ID = "<id>"

    private val whitespace = Regex("""\s+""")

    // A JSON string field whose name says it holds a secret; the value goes, the name stays.
    private val secretField =
        Regex(
            """(?i)("[^"\\]*(?:token|secret|password|passwd|mnemonic|jwt|key|auth|credential|signature|cookie|session)[^"\\]*"\s*:\s*)"(?:[^"\\]|\\.)*"""",
        )

    // A JWT (base64url of a JSON object, `eyJ`), with or without its further segments.
    private val bearerToken = Regex("""eyJ[A-Za-z0-9_-]{8,}(?:\.[A-Za-z0-9_-]*)*""")
    private val email = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val uuid = Regex("""(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b""")
    private val hexId = Regex("""(?i)\b[0-9a-f]{24,}\b""")

    // A run of 32 or more characters of a token alphabet that mixes upper case, lower case and digits, as a random key or
    // token does: no word of a reason looks like that, and neither does a slug or a documentation path (`error-524`).
    private const val TOKEN_CHAR = "[A-Za-z0-9+/_-]"
    private val opaque =
        Regex("""(?<!$TOKEN_CHAR)(?=$TOKEN_CHAR*[A-Z])(?=$TOKEN_CHAR*[a-z])(?=$TOKEN_CHAR*\d)$TOKEN_CHAR{32,}={0,2}""")

    /** [body] as an exception text may quote it: at most [maxChars] characters plus the length note. */
    fun of(
        body: String,
        maxChars: Int = DEFAULT_MAX_CHARS,
    ): String {
        if (body.isBlank()) return "<empty body>"
        val shaped = truncateErrorBody(body.take(SCAN_LIMIT))
        val oneLine = whitespace.replace(shaped.trim(), " ")
        val masked = mask(oneLine)
        if (masked.length <= maxChars && body.length <= SCAN_LIMIT) return masked
        return masked.take(maxChars).trimEnd() + " … [${body.length} chars]"
    }

    private fun mask(text: String): String {
        var out = secretField.replace(text) { "${it.groupValues[1]}\"$REDACTED\"" }
        out = UrlRedaction.redact(out)
        out = bearerToken.replace(out, REDACTED)
        out = email.replace(out, "<email>")
        out = uuid.replace(out, ID)
        out = hexId.replace(out, ID)
        out = opaque.replace(out, REDACTED)
        return out
    }
}
