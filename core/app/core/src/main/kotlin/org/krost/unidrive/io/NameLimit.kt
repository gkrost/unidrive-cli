package org.krost.unidrive.io

/**
 * #1091: names the engine builds from a user's name (a conflict copy, an archived overwrite, a keep-both side copy) append
 * text to it. A name that was 232 characters long then came out at about 250 plus its extension, over the 255 UTF-16 units
 * one path component may hold: Explorer does not show it and every IPC verb on it answers `invalid_path`. The stem is cut
 * so that the whole name fits.
 */
public object NameLimit {
    /** The longest name one path component may have, in UTF-16 units (the engine's `invalid_path` limit, NTFS). */
    public const val MAX_NAME_UNITS: Int = 255

    /**
     * [stem], cut so that it and [tailUnits] more UTF-16 units (everything the caller appends: the suffix, a counter, the
     * extension) fit in [maxUnits]. The cut is on a code-point boundary: a surrogate pair is kept whole or dropped whole,
     * never split. A stem that already fits is returned as it is. When the tail alone is over the limit the stem is empty.
     */
    public fun fitStem(
        stem: String,
        tailUnits: Int,
        maxUnits: Int = MAX_NAME_UNITS,
    ): String {
        val room = (maxUnits - tailUnits).coerceAtLeast(0)
        if (stem.length <= room) return stem
        var end = room
        if (end > 0 && Character.isHighSurrogate(stem[end - 1]) && Character.isLowSurrogate(stem[end])) end--
        return stem.substring(0, end)
    }
}
