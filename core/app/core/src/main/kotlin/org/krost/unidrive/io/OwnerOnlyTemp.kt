package org.krost.unidrive.io

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * A new temp folder in [parent] that only the current user can reach, and what is created in it afterwards (archive#1054 S4).
 * `Files.createTempDirectory` gives `rwx------` on POSIX but on Windows the folder inherits the ACL of [parent]: a temp
 * folder with an extra grant (one was seen in practice) then exposed a copy of state.db, which lists every path, to
 * whoever holds that grant. The folder is made owner-only ([OwnerOnly.requireDirectory], inheritable) before anything is
 * written into it. Throws [IOException], with the folder removed, when that fails.
 */
public fun createOwnerOnlyTempDirectory(
    parent: Path,
    prefix: String,
): Path {
    val dir = Files.createTempDirectory(parent, prefix)
    try {
        OwnerOnly.requireDirectory(dir)
    } catch (e: IOException) {
        runCatching { Files.deleteIfExists(dir) }
        throw e
    }
    return dir
}
