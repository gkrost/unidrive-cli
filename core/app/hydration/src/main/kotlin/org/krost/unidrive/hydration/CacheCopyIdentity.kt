package org.krost.unidrive.hydration

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * The file system's identity of the file at [path] (an inode on POSIX), or null where the platform has none (Windows) or the
 * file cannot be read. A dehydrate's staleness refusal compares it beside (mtime, size): a download swaps a new file in, and a
 * fresh copy written in the millisecond of the vetted one, with the same size, shares that pair while being another file.
 */
internal fun fileKeyOf(path: Path): Any? = runCatching { Files.readAttributes(path, BasicFileAttributes::class.java).fileKey() }.getOrNull()

/** What a dehydrate saw of the cache copy when it vetted it: its (mtime, size) [stat] (null: no copy) and its file [key]. */
internal data class CacheCopyIdentity(
    val stat: Pair<Long, Long>?,
    val key: Any?,
)
