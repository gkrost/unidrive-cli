package org.krost.unidrive.sync

import java.nio.file.Files
import java.nio.file.Path

/**
 * Whether a SQLite `VACUUM` of a WAL-mode file has the disk space it needs (gkrost/unidrive-cli#863, #865). The rewrite
 * first builds a copy of the live pages in SQLite's temp directory ([sqliteTempDir], not beside the file: on a tmpfs
 * `/tmp` that copy is RAM), then writes it back through the WAL in the file's own folder. So the folder needs room for
 * twice the file (the WAL and the file it is checkpointed into) and the temp directory room for the live pages; when both
 * are on one filesystem, it needs both at once.
 */
internal class RewriteRoom(
    private val usableSpace: (Path) -> Long = { Files.getFileStore(it).usableSpace },
    private val tempDir: () -> Path? = ::sqliteTempDir,
    private val sameStore: (Path, Path) -> Boolean = ::onSameFileStore,
) {
    /**
     * True when [file] (now [fileBytes] on disk, with its WAL, [liveBytes] of it in use) can be rewritten, false when a
     * filesystem has too little space, null when a space read failed and nothing can be said.
     */
    fun check(
        file: Path,
        fileBytes: Long,
        liveBytes: Long,
    ): Boolean? =
        runCatching {
            val folder = file.toAbsolutePath().parent
            val inFolder = 2 * fileBytes
            // No temp directory found: SQLite falls back to the working directory; the folder stands in for it.
            val temp = tempDir()
            if (temp == null || sameStore(folder, temp)) {
                usableSpace(folder) >= inFolder + liveBytes
            } else {
                usableSpace(folder) >= inFolder && usableSpace(temp) >= liveBytes
            }
        }.getOrNull()
}

/**
 * The directory SQLite puts its temporary files in, the way it chooses one: on Unix `SQLITE_TMPDIR`, `TMPDIR`, then
 * `/var/tmp`, `/usr/tmp`, `/tmp`, the first that is a writable directory (`unixTempFileDir`); on Windows the user's temp
 * folder, which `java.io.tmpdir` names. Null when none is usable.
 */
internal fun sqliteTempDir(): Path? {
    val candidates =
        if (System.getProperty("os.name").orEmpty().startsWith("Windows")) {
            listOfNotNull(System.getProperty("java.io.tmpdir"))
        } else {
            listOfNotNull(System.getenv("SQLITE_TMPDIR"), System.getenv("TMPDIR")) + listOf("/var/tmp", "/usr/tmp", "/tmp")
        }
    return candidates.asSequence().mapNotNull(::pathOrNull).firstOrNull { Files.isDirectory(it) && Files.isWritable(it) }
}

private fun pathOrNull(raw: String): Path? = runCatching { Path.of(raw) }.getOrNull()

// Unknown counts as the same store: the rule for one filesystem is the stricter one.
private fun onSameFileStore(
    a: Path,
    b: Path,
): Boolean = runCatching { Files.getFileStore(a) == Files.getFileStore(b) }.getOrDefault(true)
