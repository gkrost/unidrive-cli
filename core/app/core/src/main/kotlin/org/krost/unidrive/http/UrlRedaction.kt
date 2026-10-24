package org.krost.unidrive.http

/**
 * Takes the secrets out of URLs inside free text: the text of an exception that is about to become a log
 * line, an audit entry, an IPC message or a notification.
 *
 * Pre-authenticated URLs (a Graph `downloadUrl` or `uploadUrl`, an S3 presigned URL, an Internxt shard URL)
 * carry their capability in the query (`tempauth`, `sig`, `X-Amz-Signature`, `access_token`) or in a token
 * segment of the path, and Ktor's timeout exceptions quote the whole request URL in their message
 * (`Request timeout has expired [url=<URL>, request_timeout=<n> ms]`). The scheme, host and path stay: they say
 * what was being fetched. The query and fragment, user info and an `eyJ...` path segment go.
 */
object UrlRedaction {
    private const val REDACTED = "<redacted>"

    // A URL runs to white space, a quote or a bracket (Ktor writes `[url=<URL>, ...]`); a backslash ends it too, so
    // text that is already JSON-escaped keeps its escapes. An earlier `<redacted>` belongs to it, so redacting twice
    // changes nothing.
    private val url = Regex("""(?i)\b[a-z][a-z0-9+.-]*://(?:[^\s"'<>\[\]\\]|<redacted>)+""")
    private val userInfo = Regex("""(?i)^([a-z][a-z0-9+.-]*://)[^/?#@]*@""")

    // A bearer token as a path segment is base64url of a JSON object, which starts with `eyJ`.
    private val tokenSegment = Regex("""/eyJ[A-Za-z0-9_-]{16,}""")

    // A query pair quoted without its URL (a request echoed back, a log of the query alone).
    private val credentialPair =
        Regex(
            """(?i)\b(tempauth|sig|signature|x-amz-signature|x-amz-credential|x-amz-security-token|access_token|refresh_token|token)=[^&\s"'<>\[\]\\]+""",
        )

    // Punctuation after a URL in running text belongs to the sentence, not to the URL.
    private const val TRAILING_PUNCTUATION = ",.;:!)"

    /** [text] with every URL reduced to scheme, host and path, and every bare credential pair masked. */
    fun redact(text: String): String {
        if (!text.contains("://") && !text.contains('=')) return text
        return credentialPair.replace(url.replace(text) { redactUrl(it.value) }) { "${it.groupValues[1]}=$REDACTED" }
    }

    private fun redactUrl(raw: String): String {
        val trailing = raw.takeLastWhile { it in TRAILING_PUNCTUATION }
        val withoutUserInfo = userInfo.replace(raw.dropLast(trailing.length), "$1")
        val cut = withoutUserInfo.indexOfFirst { it == '?' || it == '#' }
        val head = if (cut < 0) withoutUserInfo else withoutUserInfo.substring(0, cut)
        val tail = if (cut < 0) "" else "${withoutUserInfo[cut]}$REDACTED"
        return tokenSegment.replace(head, "/$REDACTED") + tail + trailing
    }
}
