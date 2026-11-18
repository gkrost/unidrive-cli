package org.krost.unidrive.sync

/** How many " (n)" names keepBothCopyPath tries before it refuses. */
internal const val KEEP_BOTH_MAX_NAME_ATTEMPTS: Int = 1000

/**
 * archive#738: the keep-both side-copy path for [path] at [stamp]: `dir/stem.conflict-local-<stamp>.ext`, and
 * `dir/stem.conflict-local-<stamp> (n).ext` (n = 2, 3, …) while [isTaken] says the name is in use. The
 * extension is taken from the file name only, so a dot in a folder name never splits the path.
 * The stem is cut (archive#1091, on a code-point boundary) so the whole name stays within 255 UTF-16 units.
 * Throws [IllegalStateException] rather than reuse a taken name once [KEEP_BOTH_MAX_NAME_ATTEMPTS] are spent.
 */
internal fun keepBothCopyPath(
    path: String,
    stamp: String,
    isTaken: (String) -> Boolean,
): String {
    val cut = path.lastIndexOf('/')
    val dir = if (cut >= 0) path.substring(0, cut + 1) else ""
    val leaf = path.substring(cut + 1)
    val ext = leaf.substringAfterLast(".", "")
    val stem = if (ext.isNotEmpty()) leaf.substringBeforeLast(".") else leaf
    val dotExt = if (ext.isNotEmpty()) ".$ext" else ""
    for (n in 1..KEEP_BOTH_MAX_NAME_ATTEMPTS) {
        val counter = if (n == 1) "" else " ($n)"
        val tail = ".conflict-local-$stamp$counter$dotExt"
        val candidate = "$dir${org.krost.unidrive.io.NameLimit.fitStem(stem, tail.length)}$tail"
        if (!isTaken(candidate)) return candidate
    }
    error("no free keep-both copy name for $path after $KEEP_BOTH_MAX_NAME_ATTEMPTS attempts")
}
