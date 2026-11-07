package org.krost.unidrive.hydration

import org.krost.unidrive.sync.PathNormalizer
import org.krost.unidrive.sync.StateDatabase
import java.util.concurrent.ConcurrentHashMap

/**
 * gkrost/unidrive-cli#24: the spelling a client used first for each name, so that a second spelling of the same name is
 * refused instead of silently being the first one.
 *
 * The engine keys every path in NFC (archive#171): `caf` + U+00E9 and `cafe` + U+0301 are one row and one cloud path, and a
 * client that holds both as two files would otherwise have the second one's bytes replace the first one's content in the
 * cloud. Owner decision (2026-10-03): refuse such pairs. This remembers, per name below the root, the spelling of the
 * first mutating verb that named it; a later verb that names it by a different spelling clashes. Names that are plain
 * ASCII cannot have a twin and are not tracked.
 *
 * The record lives in state.db ([StateDbNameSpellingStore]): a daemon restart loads it back, so a pair whose first
 * mutation after the restart is the other member still clashes. A [store] of null is the in-memory guard alone (the
 * test harness; the daemon always passes one).
 */
internal class NameSpellings(private val store: NameSpellingStore? = null) {
    // NFC path (a name and the folders above it, one entry each) -> the raw path as first named.
    private val seen = ConcurrentHashMap<String, String>()

    init {
        store?.load()?.let { seen.putAll(it) }
    }

    /** True when [rawPath] names a name or a folder above it by another spelling than the one recorded for it. */
    fun clashes(rawPath: String): Boolean = walk(rawPath) { nfc, raw -> seen[nfc]?.let { it != raw } == true }

    /**
     * Atomically claims the spelling [rawPath] uses for every name in it that could have a twin, before the verb
     * runs: each claim is a [ConcurrentHashMap.putIfAbsent] — the check and the reservation in one step — so two
     * verbs naming one name by different spellings cannot both pass the guard however their calls interleave
     * (gkrost/unidrive-cli#452). Answers null when a recorded spelling clashes, with nothing of this call left
     * behind; otherwise the claimed (NFC prefix, raw prefix) pairs, for [commit] when the verb succeeds and
     * [rollback] when it does not: a failed verb must not leave its spelling claimed, or the other spelling stays
     * refused for nothing. The claim is the memory half only — the store is written on success, by [commit].
     */
    fun claim(rawPath: String): List<Pair<String, String>>? {
        val claimed = ArrayList<Pair<String, String>>()
        val clash = walk(rawPath) { nfc, raw ->
            val known = seen.putIfAbsent(nfc, raw)
            if (known == null) {
                claimed += nfc to raw
                false
            } else {
                known != raw
            }
        }
        if (clash) {
            rollback(claimed)
            return null
        }
        return claimed
    }

    /** Persists a succeeded verb's claim: the store is written for every claimed name this map still holds. */
    fun commit(claim: List<Pair<String, String>>) {
        val persisted = store ?: return
        for ((nfc, raw) in claim) {
            seen.putIfAbsent(nfc, raw)
            if (seen[nfc] == raw) persisted.record(nfc, raw)
        }
    }

    /** Takes a failed verb's claim back: only entries that still hold the claimed spelling are removed. */
    fun rollback(claim: List<Pair<String, String>>) {
        for ((nfc, raw) in claim) seen.remove(nfc, raw)
    }

    /** The name at [rawPath] and everything below it is gone (or has moved away): its spellings are free again. */
    fun forget(rawPath: String) {
        val nfc = PathNormalizer.nfc(rawPath.trimEnd('/'))
        if (nfc.isEmpty()) return
        // The in-memory record mirrors the store (loaded whole at start, every new spelling written), so the store is asked only when
        // something was dropped here: an unlink, rmdir or rename of an ASCII name must not cost a database write.
        if (seen.keys.removeIf { it == nfc || it.startsWith("$nfc/") }) store?.forgetUnder(nfc)
    }

    // Calls [visit] with (NFC prefix, raw prefix) for every prefix of the path whose last name is not plain ASCII;
    // stops at, and answers, the first visit that says true.
    private inline fun walk(
        rawPath: String,
        visit: (nfcPrefix: String, rawPrefix: String) -> Boolean,
    ): Boolean {
        val raw = StringBuilder()
        val nfc = StringBuilder()
        for (name in rawPath.split('/')) {
            if (name.isEmpty()) continue
            raw.append('/').append(name)
            nfc.append('/').append(PathNormalizer.nfc(name))
            if (name.all { it.code < ASCII_LIMIT }) continue
            if (visit(nfc.toString(), raw.toString())) return true
        }
        return false
    }

    private companion object {
        const val ASCII_LIMIT = 128
    }
}

/** The persisted half of [NameSpellings]: what a daemon run records and the next one loads back. */
interface NameSpellingStore {
    /**
     * Every recorded spelling, as [NameSpellings] loads them at start. A store drops the records of names that no longer exist
     * first: a name removed by other means than the IPC verbs (deleted in the cloud, reaped by a sync) must not refuse its
     * other spelling for ever, as a restart used to clear it.
     */
    fun load(): Map<String, String>

    /** The first spelling [rawPath] of the name whose NFC form is [nfcPath]; the first one wins. */
    fun record(
        nfcPath: String,
        rawPath: String,
    )

    /** The name at [nfcPath] and everything below it is gone: its spellings are free again. */
    fun forgetUnder(nfcPath: String)
}

/** [NameSpellingStore] on the profile's state.db (the `name_spellings` table, gkrost/unidrive-cli#24). */
class StateDbNameSpellingStore(private val db: StateDatabase) : NameSpellingStore {
    override fun load(): Map<String, String> {
        db.pruneNameSpellings()
        return db.loadNameSpellings()
    }

    override fun record(
        nfcPath: String,
        rawPath: String,
    ) {
        db.recordNameSpelling(nfcPath, rawPath)
    }

    override fun forgetUnder(nfcPath: String) {
        db.forgetNameSpellings(nfcPath)
    }
}
