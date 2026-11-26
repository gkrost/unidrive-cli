package org.krost.unidrive.hydration

import kotlinx.serialization.json.JsonPrimitive
import org.krost.unidrive.sync.PathNormalizer

/**
 * Owner decision (2026-11-26) on gkrost/unidrive-cli#948: of two names that differ only in NFC/NFD form, the NFC spelling
 * always wins, whichever arrived first. A create, open_write or open_write_begin that names the NFC spelling of a file a
 * client first used in decomposed form replaces that earlier twin; the decomposed spelling arriving after an NFC one is a
 * `name_clash`, as before. Every `name_clash` names the sibling spelling it clashed with (`sibling`).
 *
 * No data is lost to the rule: a decomposed twin that holds bytes the cloud has not seen (never uploaded, an edit not yet
 * uploaded, an upload running) is not replaced. The NFC arrival is refused with `reason: unsynced_bytes` and the twin named.
 *
 * The settlement changes state only for a request that passed validation and that the verb commits: before the verb runs
 * only reads are made and a permit is left for the verb's own claim ([NameSpellings.claim]), which swaps the recorded
 * spelling provisionally and gives it back when the verb fails. A create whose row is still the twin's is repeated once the
 * twin is deleted. A folder is never settled.
 */
internal class NfcSpellingWins(
    private val spellings: NameSpellings,
    private val hydration: Hydration,
) {
    /** The refusal reply for [rawPath], naming the spelling it clashed with; [reason] is added when the clash is not a plain one. */
    fun clashReply(
        rawPath: String,
        reason: String? = null,
    ): String {
        val sibling = spellings.siblingOf(rawPath)?.let { ""","sibling":${JsonPrimitive(it)}""" } ?: ""
        val why = reason?.let { ""","reason":"$it"""" } ?: ""
        return """{"ok":false,"error":"${HydrationError.NAME_CLASH_TOKEN}"$sibling$why}"""
    }

    /** Runs [verb] (the dispatch of [verbName], whose request names [rawPath]), settling an earlier decomposed twin around it. */
    suspend fun around(
        verbName: String,
        rawPath: String?,
        verb: suspend () -> String,
    ): String {
        val creates =
            when (verbName) {
                "hydration.create" -> true
                "hydration.open_write", "hydration.open_write_begin" -> false
                else -> return verb()
            }
        val raw = rawPath ?: return verb()
        val nfcPath = PathNormalizer.nfc(raw)
        if (!permitted(raw, nfcPath)) return verb()
        refusal(raw, nfcPath)?.let { return it }
        spellings.permit(nfcPath)
        try {
            val reply = verb()
            if (!creates || !reply.contains(PATH_EXISTS)) return reply
            // The row at the name is the twin's: it goes (its bytes are all in the cloud), then the create runs again.
            return if (hydration.unlink(nfcPath) is UnlinkResult.Ok) verb() else clashReply(raw, UNSYNCED_BYTES)
        } finally {
            spellings.endPermit(nfcPath)
        }
    }

    // The NFC spelling of a file arrives where a client used another spelling of the name first, and nothing above it clashes.
    private suspend fun permitted(
        raw: String,
        nfcPath: String,
    ): Boolean {
        if (raw != nfcPath.trimEnd('/')) return false
        val twin = spellings.leafRecord(raw) ?: return false
        if (twin == raw || spellings.clashes(raw.substringBeforeLast('/'))) return false
        return (hydration.stat(nfcPath) as? StatResult.Ok)?.entry?.isFolder != true
    }

    private fun refusal(
        raw: String,
        nfcPath: String,
    ): String? = if (hydration.holdsUnsyncedBytesAt(nfcPath)) clashReply(raw, UNSYNCED_BYTES) else null

    companion object {
        const val UNSYNCED_BYTES = "unsynced_bytes"
        private const val PATH_EXISTS = "\"error\":\"path_exists\""
    }
}
