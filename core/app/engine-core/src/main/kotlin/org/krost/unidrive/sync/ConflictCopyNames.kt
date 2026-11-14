package org.krost.unidrive.sync

import org.krost.unidrive.io.NameLimit
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * archive#737: the name of a local conflict copy, the scheme keep-both uses for the user's side copy
 * (`SyncEngine.applyKeepBoth`): `<stem>.conflict-local-<yyyyMMdd'T'HHmm>.<ext>`, or without the
 * extension when the name has none. [n] > 1 adds a free-name counter, `<stem>.conflict-local-<stamp> (n).<ext>`,
 * so a second copy in the same minute never lands on the first. The remote merge names a pending upload it moves
 * aside the same way (`MergeDisplacement`).
 *
 * It works on the leaf name only, so a dot in a parent folder's name is never taken for the
 * extension, and a dotfile (`.bashrc`) keeps its whole name as the stem.
 */
object ConflictCopyNames {
    fun localCopyName(
        leaf: String,
        now: Instant,
        n: Int = 1,
    ): String {
        val stamp =
            DateTimeFormatter
                .ofPattern("yyyyMMdd'T'HHmm")
                .withZone(ZoneId.systemDefault())
                .format(now)
        val dot = leaf.lastIndexOf('.')
        val stem = if (dot > 0) leaf.substring(0, dot) else leaf
        val ext = if (dot > 0) leaf.substring(dot + 1) else ""
        val counter = if (n > 1) " ($n)" else ""
        val suffix = ".conflict-local-$stamp$counter"
        val tail = if (ext.isNotEmpty()) "$suffix.$ext" else suffix
        // cli#724: the stem is cut (as keep-both's own copy name does) so the whole name fits one path component.
        return NameLimit.fitStem(stem, tail.length) + tail
    }
}
