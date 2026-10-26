package org.krost.unidrive.hydration

import org.krost.unidrive.sync.PathNormalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * gkrost/unidrive-cli#24: the spelling a client used first for each name, so that a second spelling of the same name is
 * refused instead of silently being the first one.
 *
 * The engine keys every path in NFC (archive#171): `caf` + U+00E9 and `cafe` + U+0301 are one row and one cloud path, and a
 * client that holds both as two files would otherwise have the second one's bytes replace the first one's content in the
 * cloud. Owner decision (2026-10-03): refuse such pairs. This remembers, per name below the root and per daemon run, the
 * spelling of the first mutating verb that named it; a later verb that names it by a different spelling clashes. Names
 * that are plain ASCII cannot have a twin and are not tracked.
 *
 * The record is in memory: after a restart the first spelling a client names wins again. A persisted spelling would
 * close that gap; it needs a state.db column and is not part of this guard.
 */
internal class NameSpellings {
    // NFC path (a name and the folders above it, one entry each) -> the raw path as first named.
    private val seen = ConcurrentHashMap<String, String>()

    /** True when [rawPath] names a name or a folder above it by another spelling than the one recorded for it. */
    fun clashes(rawPath: String): Boolean = walk(rawPath) { nfc, raw -> seen[nfc]?.let { it != raw } == true }

    /** Remembers the spelling [rawPath] uses for every name in it that could have a twin. */
    fun record(rawPath: String) {
        walk(rawPath) { nfc, raw ->
            seen.putIfAbsent(nfc, raw)
            false
        }
    }

    /** The name at [rawPath] and everything below it is gone (or has moved away): its spellings are free again. */
    fun forget(rawPath: String) {
        val nfc = PathNormalizer.nfc(rawPath.trimEnd('/'))
        if (nfc.isEmpty()) return
        seen.keys.removeIf { it == nfc || it.startsWith("$nfc/") }
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
