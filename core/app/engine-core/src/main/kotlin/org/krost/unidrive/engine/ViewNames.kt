package org.krost.unidrive.engine

import org.krost.unidrive.sync.PathNormalizer

/**
 * The names inside a view. A view is flat: an entry is called by the last segment of its real item's path, so two
 * items from different folders can want the same name. [assign] gives them distinct ones, deterministically.
 *
 * **The winner** of a name is a pure function of the items that want it, never of the order the provider listed them
 * in (a Recents list reorders all the time and a name must not flip with it): a folder beats a file, then the smaller
 * remote id wins (the rule of the gather, [RemoteGather.admit], without its tie-break on the id state.db tracks, which
 * a flat view has no use for). The winner keeps the plain name.
 *
 * **The others** are decorated `stem (dup-<id8>).ext`, `<id8>` being the first 8 characters of the remote id (a folder
 * has no extension: `stem (dup-<id8>)`). Two names are the same name by [NameKey] of their NFC form, so NFC twins and,
 * where the file system folds case, case twins collide like everywhere else. A decorated name that still collides
 * (with a plain name that happens to look like it, or with a twin that shares the 8 characters) takes more characters of
 * its id, up to the whole id. It stays within 255 UTF-16 units (the limit `invalid_path` enforces on a segment) and
 * within 255 UTF-8 bytes (what a POSIX file system holds): the stem is shortened, never the id suffix.
 */
object ViewNames {
    /** An item of a view that wants a name: its provider id, the name of its real row, and whether it is a folder. */
    class Candidate(
        val id: String,
        val name: String,
        val isFolder: Boolean,
    )

    /** The most UTF-16 units a name has (and the most UTF-8 bytes): the limit `invalid_path` puts on a path segment. */
    const val MAX_NAME_LENGTH: Int = 255

    /** How many characters of the remote id a decorated name carries, unless it needs more to be told apart. */
    const val ID_CHARS: Int = 8

    // Past this the extension is no extension (and the id suffix always fits next to what is left of the name).
    private const val MAX_EXTENSION_CHARS = 32
    private const val MAX_ID_CHARS = 64
    private const val FORBIDDEN = "<>:\"/\\|?*"

    /**
     * The name of every candidate, by remote id. A candidate listed twice (same id) counts once. The result does not
     * depend on the order of [candidates].
     */
    fun assign(
        candidates: Collection<Candidate>,
        foldCase: Boolean = NameKey.platformFoldsCase,
    ): Map<String, String> {
        val ordered =
            candidates
                .sortedWith(compareBy<Candidate> { !it.isFolder }.thenBy { it.id }.thenBy { it.name })
                .distinctBy { it.id }
        val names = LinkedHashMap<String, String>(ordered.size * 2)
        val taken = HashSet<String>()
        val losers = ArrayList<Candidate>()
        for (c in ordered) {
            if (taken.add(keyOf(c.name, foldCase))) names[c.id] = c.name else losers.add(c)
        }
        // Decided after every plain name is known, and in a fixed order, so the result is one function of the set.
        for (c in losers) {
            names[c.id] = decorated(c, taken, foldCase)
        }
        return names
    }

    private fun decorated(
        c: Candidate,
        taken: MutableSet<String>,
        foldCase: Boolean,
    ): String {
        val id = c.id.map { if (it in FORBIDDEN || it < ' ') '_' else it }.joinToString("")
        var chars = minOf(ID_CHARS, id.length)
        var tag = id.take(chars)
        var extra = 1
        while (true) {
            val name = decorate(c.name, tag, c.isFolder)
            if (taken.add(keyOf(name, foldCase))) return name
            if (chars < minOf(id.length, MAX_ID_CHARS)) {
                chars++
                tag = id.take(chars)
            } else {
                // The whole id is not enough (a plain name spelled like the decoration): a counter ends it.
                extra++
                tag = "${id.take(chars)}-$extra"
            }
        }
    }

    /** `stem (dup-<tag>).ext`, with the stem shortened until the name fits. */
    internal fun decorate(
        name: String,
        tag: String,
        isFolder: Boolean,
    ): String {
        val dot = name.lastIndexOf('.')
        val hasExtension = !isFolder && dot > 0 && name.length - dot <= MAX_EXTENSION_CHARS
        var stem = if (hasExtension) name.substring(0, dot) else name
        val extension = if (hasExtension) name.substring(dot) else ""
        val suffix = " (dup-$tag)$extension"
        while (stem.isNotEmpty() && !fits(stem + suffix)) {
            val cut = if (stem.length >= 2 && stem.last().isLowSurrogate() && stem[stem.length - 2].isHighSurrogate()) 2 else 1
            stem = stem.dropLast(cut)
        }
        return stem + suffix
    }

    private fun fits(name: String): Boolean = name.length <= MAX_NAME_LENGTH && name.toByteArray(Charsets.UTF_8).size <= MAX_NAME_LENGTH

    private fun keyOf(
        name: String,
        foldCase: Boolean,
    ): String = NameKey.of(PathNormalizer.nfc(name), foldCase)
}
