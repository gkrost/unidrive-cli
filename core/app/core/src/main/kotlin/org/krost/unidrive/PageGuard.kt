package org.krost.unidrive

/**
 * Shared guard for provider paging loops (#82 F, #441 item 12). A server or proxy that answers a page with a token,
 * link or cursor it was already asked for would otherwise be paged for ever; a repeat fails the listing as an
 * incomplete one, which the engine retries, and a page cap keeps even fresh tokens from going on for ever.
 * Each provider supplies its own message texts.
 *
 * What [maxPages] counts is the number of distinct tokens passed to [follow], and the call site decides which requests
 * those are. A loop that follows a token only for continuation pages (S3, Dropbox: page one is fetched without a
 * token) admits `maxPages + 1` fetches in all; a loop that follows the URL of every fetch, page one included (Graph),
 * admits `maxPages`. The two conventions predate this class and are kept as they were.
 */
class PageGuard(
    private val repeatedMessage: String,
    private val cappedMessage: String,
    private val maxPages: Int = 10_000,
) {
    private val seen = HashSet<String>()

    /** Admits the token the next page is to be asked with; a null token (no further page) is ignored. */
    fun follow(token: String?) {
        if (token == null) return
        if (!seen.add(token)) throw ProviderException(repeatedMessage)
        if (seen.size > maxPages) throw ProviderException(cappedMessage)
    }
}
