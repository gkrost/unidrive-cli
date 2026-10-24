package org.krost.unidrive.internxt.model

import kotlinx.serialization.Serializable

// The answers of `GET sharings/folders` and `GET sharings/files` carry, beside every item, what no listing may keep: the
// owner's bridge credentials (`credentials`), the sharing's `encryptionKey`, a navigation `token`, and the owner's e-mail
// address and avatar. For a legacy public link that key together with the link code (`encryptedCode` is the code under the
// mnemonic) opens the whole account mnemonic. The same `credentials`, `token` and `role` sit once more at the top of the
// answer.
//
// The wire models below therefore declare only what a reference needs, and the JSON reads with unknown keys ignored
// (InternxtJson): a key that is not declared is skipped while parsing and never exists as a value, so no `toString()`, log
// line, reference or row can show it. A field added here is a decision: the secrets tests (InternxtSharingsSecretsTest)
// fail on every new field name until it is looked at, and each new field wants a canary in the fixtures.

/**
 * The answer of one page of either route: the items are in `folders` on the folders route and in `files` on the files route.
 * The array of the route that was asked is required and the other may be absent, so neither has an empty list as its default: a
 * missing or null array reads as `null`, and `InternxtApiService.decodeSharingsPage` refuses the page when the one it asked for
 * is `null`. A default of `emptyList()` would turn an answer with no array (a body of another shape, an error object) into a
 * page with no items, which the paging reads as the end of a complete listing.
 */
@Serializable
internal data class SharedItemsPageWire(
    val folders: List<SharedItemWire>? = null,
    val files: List<SharedItemWire>? = null,
)

/**
 * One item of a sharings answer, folder or file. `sharedWithMe` has no default on purpose: an item that does not say whose
 * it is would be listed as one of the account's own or dropped by a guess, so the page it is on fails to read instead.
 */
@Serializable
internal data class SharedItemWire(
    val uuid: String,
    val plainName: String? = null,
    val sharedWithMe: Boolean,
    // Files carry EXISTS, TRASHED or DELETED; a folder may carry no status at all and says deleted/removed instead.
    val status: String? = null,
    val deleted: Boolean = false,
    val removed: Boolean = false,
) {
    /** The server lists only live items on both routes; anything else that gets through is dropped here. */
    val isLive: Boolean get() = !deleted && !removed && (status == null || status == "EXISTS")
}

/** One thing the account shares by itself: the uuid of the real drive item, its kind and its plain name. */
internal data class SharedRef(
    val uuid: String,
    val isFolder: Boolean,
    val plainName: String?,
)

/** What [org.krost.unidrive.internxt.InternxtApiService.listSharedByMe] answers. */
internal sealed interface SharedByMe {
    /**
     * [refs]: the folders first, then the files, each in the order the server first served it and once each.
     * [truncated]: more files exist than [SharedRefCollector.cap] allows; the list of files is cut there.
     * [foldersCut]: more folders exist than [SharedRefCollector.cap] allows; the folders route was not read further.
     * The text form names the counts and nothing else: a list of names has no business in a log line.
     */
    data class Listed(
        val refs: List<SharedRef>,
        val truncated: Boolean,
        val foldersCut: Boolean = false,
    ) : SharedByMe {
        override fun toString(): String = "Listed(refs=${refs.size}, truncated=$truncated, foldersCut=$foldersCut)"
    }

    /** The gateway has no such route (HTTP 404 or 405 on the first page): no sharings, not a failure. */
    data object Unsupported : SharedByMe
}

/**
 * Collects the references out of the pages of the two routes. The server's paging over this query has no stable order, so a
 * page can repeat an item that an earlier page served and skip one (a later listing finds it); an item is therefore taken
 * once, by uuid, wherever it turns up. Only the account's own live items become references; at most [cap] of them are files:
 * the file after the last allowed makes the list [SharedByMe.Listed.truncated]. The folders have a cap of their own, so that a
 * long run of them does not crowd the files out of the read ([SharedByMe.Listed.foldersCut]).
 */
internal class SharedRefCollector(
    val cap: Int,
) {
    private val refs = LinkedHashMap<String, SharedRef>()
    private val seen = HashSet<String>()
    private var files = 0
    private var folders = 0

    /** True once [cap] folders are kept: the folders route is not read further, and the list is cut (see [SharedByMe.Listed.foldersCut]). */
    var foldersFull: Boolean = false
        private set

    var truncated: Boolean = false
        private set

    /** Takes one page and answers how many of its items no earlier page had served (foreign and dropped ones count). */
    fun offer(
        items: List<SharedItemWire>,
        isFolder: Boolean,
    ): Int {
        var fresh = 0
        for (item in items) {
            if (!seen.add(item.uuid)) continue
            fresh++
            if (item.sharedWithMe || !item.isLive) continue
            // The cap bounds the files and, apart, the folders, so that a long run of folders stops being read (foldersFull)
            // and still leaves the files to be read.
            if (isFolder) {
                if (folders >= cap) {
                    foldersFull = true
                    break
                }
                folders++
            } else {
                if (files >= cap) {
                    truncated = true
                    break
                }
                files++
            }
            refs[item.uuid] = SharedRef(item.uuid, isFolder, item.plainName?.takeIf { it.isNotBlank() })
        }
        return fresh
    }

    fun result(): SharedByMe.Listed = SharedByMe.Listed(refs.values.toList(), truncated, foldersFull)
}
