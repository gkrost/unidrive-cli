package org.krost.unidrive.sync

import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/** The `yyyyMMdd'T'HHmmss'Z'` folder name trash and file versions share (#222). */
internal object UtcSecondStamp {
    private val format: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    fun encode(instant: Instant): String = format.format(instant)

    fun decode(name: String): Instant? =
        try {
            format.parse(name, Instant::from)
        } catch (_: Exception) {
            null
        }
}

/** Deletes [dir] and its empty parents, and stops at [stopAt] without deleting it. */
internal fun deleteEmptyParentsUntil(
    dir: Path,
    stopAt: Path,
) {
    var current = dir
    while (emptyDirectoryStrictlyUnder(current, stopAt)) {
        current.deleteIfExists()
        current = current.parent ?: break
    }
}

private fun emptyDirectoryStrictlyUnder(
    dir: Path,
    stopAt: Path,
): Boolean {
    if (dir == stopAt) return false
    if (!dir.exists() || !dir.isDirectory()) return false
    return dir.listDirectoryEntries().isEmpty()
}
