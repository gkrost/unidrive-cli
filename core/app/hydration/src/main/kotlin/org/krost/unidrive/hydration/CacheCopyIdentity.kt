package org.krost.unidrive.hydration

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import org.krost.unidrive.io.WindowsFileIdentity

/**
 * The file system's identity of the file at [path] (an inode on POSIX, the Win32 volume-serial-plus-file-index
 * triple on Windows, where the JDK's attributes answer no key), or null when the file cannot be read. A
 * dehydrate's staleness refusal compares it beside (mtime, size): a download swaps a new file in, and a fresh
 * copy written in the millisecond of the vetted one, with the same size, shares that pair while being another file.
 */
internal fun fileKeyOf(path: Path): Any? {
    val posix = runCatching { Files.readAttributes(path, BasicFileAttributes::class.java).fileKey() }.getOrNull()
    if (posix != null) return posix
    return WindowsFileIdentity.of(path)
}

/** What a dehydrate saw of the cache copy when it vetted it: its (mtime, size) [stat] (null: no copy) and its file [key]. */
internal data class CacheCopyIdentity(
    val stat: Pair<Long, Long>?,
    val key: Any?,
)
