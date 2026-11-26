package org.krost.unidrive.hydration

import kotlinx.serialization.json.JsonPrimitive
import org.krost.unidrive.sync.PathNormalizer

/**
 * Owner decision (2026-11-26) on gkrost/unidrive-cli#948: of two names that differ only in NFC/NFD form, the NFC spelling
 * always wins, whichever arrived first. A create, open_write or open_write_begin that names the NFC spelling of a name a
 * client first used in decomposed form replaces that earlier twin; the decomposed spelling arriving after an NFC one is a
 * `name_clash`, as before. Every `name_clash` names the sibling spelling it clashed with (`sibling`).
 *
 * No data is lost to the rule: a decomposed twin that holds bytes the cloud has not seen (never uploaded, an edit not yet
 * uploaded, an upload running) is not replaced. The NFC arrival is refused with `reason: unsynced_bytes` and the twin named.
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

    /** [settle] for the verbs that bring a name in ([verb] with its `path` field), null for every other verb. */
    suspend fun refusal(
        verb: String,
        rawPath: String?,
    ): String? {
        val creates =
            when (verb) {
                "hydration.create" -> true
                "hydration.open_write", "hydration.open_write_begin" -> false
                else -> return null
            }
        return rawPath?.let { settle(it, PathNormalizer.nfc(it), creates) }
    }

    /**
     * Null: go on with the verb (the name had no earlier decomposed twin, or the twin is replaced by now). Else the refusal.
     * [path] is the logical path the verb works on; [creates] says the verb makes a new row, so the twin's row has to go first.
     */
    suspend fun settle(
        rawPath: String,
        path: String,
        creates: Boolean,
    ): String? {
        if (rawPath != PathNormalizer.nfc(rawPath.trimEnd('/'))) return null
        val twin = spellings.leafRecord(rawPath) ?: return null
        if (twin == rawPath || spellings.clashes(rawPath.substringBeforeLast('/'))) return null
        if (hydration.holdsUnsyncedBytesAt(path)) return clashReply(rawPath, UNSYNCED_BYTES)
        if (creates && hydration.stat(path) is StatResult.Ok && hydration.unlink(path) !is UnlinkResult.Ok) {
            return clashReply(rawPath, UNSYNCED_BYTES)
        }
        spellings.forget(rawPath)
        return null
    }

    companion object {
        const val UNSYNCED_BYTES = "unsynced_bytes"
    }
}
