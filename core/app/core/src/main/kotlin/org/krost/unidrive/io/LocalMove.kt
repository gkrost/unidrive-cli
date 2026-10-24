package org.krost.unidrive.io

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Moves [source] to [target] like [Files.move]. A rename that only changes the case of a name (`report.txt` to
 * `Report.txt`, `docs` to `Docs`) names the same file on a case-insensitive file system, and the JDK then does
 * nothing: the name on disk stays as it was (#1086). That case goes through a name in between, in the same folder,
 * and is put back if the second step fails.
 *
 * [replaceExisting] is [StandardCopyOption.REPLACE_EXISTING] for every other move; a case-only rename has no
 * second file to replace.
 */
fun moveFile(
    source: Path,
    target: Path,
    replaceExisting: Boolean = false,
) {
    when {
        isCaseOnlyRename(source, target) -> moveThroughParkingName(source, target)
        replaceExisting -> Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        else -> Files.move(source, target)
    }
}

// Same path but for the case, and the file system agrees it is one file: case-insensitive. Two files that differ
// only by case (a case-sensitive file system) are not one file, so the plain move applies.
private fun isCaseOnlyRename(
    source: Path,
    target: Path,
): Boolean {
    val from = source.toString()
    val to = target.toString()
    return from != to &&
        from.equals(to, ignoreCase = true) &&
        Files.exists(target, LinkOption.NOFOLLOW_LINKS) &&
        runCatching { Files.isSameFile(source, target) }.getOrDefault(false)
}

@Suppress("TooGenericExceptionCaught") // whatever fails, the file is put back and the failure is rethrown unchanged
private fun moveThroughParkingName(
    source: Path,
    target: Path,
) {
    val parked = source.resolveSibling(stagingSiblingName(source.fileName.toString(), ".${UUID.randomUUID()}.tmp"))
    Files.move(source, parked)
    try {
        Files.move(parked, target)
    } catch (e: Exception) {
        runCatching { Files.move(parked, source) }
        throw e
    }
}
