package org.krost.unidrive.io

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Entries that lead somewhere else: a symbolic link, and a directory whose content lives elsewhere (a Windows junction or
 * a mounted volume). Every walk that deletes, copies, moves or scans a local tree treats such an entry as a leaf: it may
 * remove the entry itself, never what the entry leads to.
 *
 * `Files.walkFileTree` without `FOLLOW_LINKS` already stops at a symbolic link, but a junction is no symbolic link to the
 * JDK: read without following links, JDK 21 reports it as a directory (and as "other"), and the walk descends into it.
 * Kotlin's `File.deleteRecursively` and `walkTopDown` follow every link. A Cloud Files placeholder folder is a reparse
 * point too, but it is the folder itself and is walked as usual: only an entry whose real path is not its own counts.
 */
object LinkedEntries {
    /** True when [path] (with [attrs], read without following links) leads somewhere else. */
    fun leadsElsewhere(
        path: Path,
        attrs: BasicFileAttributes,
    ): Boolean {
        if (attrs.isSymbolicLink) return true
        // A plain directory is no reparse point: no real-path probe for it (the scanner meets every folder).
        if (!attrs.isDirectory || !attrs.isOther) return false
        return try {
            val parent = path.toAbsolutePath().parent ?: return false
            // Against the parent's real path, so 8.3 names, letter case and links above [path] do not count.
            path.toRealPath() != parent.toRealPath().resolve(path.fileName.toString())
        } catch (_: IOException) {
            // A junction whose target is gone cannot be resolved: it cannot be shown to be a plain folder either.
            true
        }
    }

    /** [leadsElsewhere] for [path], reading its attributes without following links; false when it does not exist. */
    fun leadsElsewhere(path: Path): Boolean {
        val attrs =
            try {
                Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                return false
            }
        return leadsElsewhere(path, attrs)
    }

    /**
     * Deletes [root] and everything below it, children first. An entry that [leadsElsewhere] is deleted itself (the link
     * or the junction), never descended into. [delete] removes one entry (a retry wrapper, say); a missing [root] is a no-op.
     */
    fun deleteTree(
        root: Path,
        delete: (Path) -> Unit = { Files.deleteIfExists(it) },
    ) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (!leadsElsewhere(dir, attrs)) return FileVisitResult.CONTINUE
                    delete(dir)
                    return FileVisitResult.SKIP_SUBTREE
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    dir: Path,
                    exc: IOException?,
                ): FileVisitResult {
                    if (exc != null) throw exc
                    delete(dir)
                    return FileVisitResult.CONTINUE
                }

                // The walk opens a folder before preVisitDirectory: a junction whose target is gone fails here.
                override fun visitFileFailed(
                    file: Path,
                    exc: IOException,
                ): FileVisitResult {
                    if (!leadsElsewhere(file)) throw exc
                    delete(file)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    /**
     * Every entry below [root] (not [root] itself) with its attributes, parents before children, without passing an entry
     * that [leadsElsewhere]: such an entry is handed to [onEntry] itself, its content is not. An entry that cannot be read
     * is left out. The replacement for `Files.walk` where the walk must stay inside the tree.
     *
     * [onUnobserved] is told every path whose content the walk did not see: the entry that leads elsewhere (its content is
     * left alone on purpose) and the entry that could not be read. A caller that reads absence as deletion must not read it
     * for anything at or below these paths.
     */
    fun forEachEntry(
        root: Path,
        onUnobserved: (Path) -> Unit = {},
        onEntry: (Path, BasicFileAttributes) -> Unit,
    ) {
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (dir != root) onEntry(dir, attrs)
                    if (!leadsElsewhere(dir, attrs)) return FileVisitResult.CONTINUE
                    if (dir != root) onUnobserved(dir)
                    return FileVisitResult.SKIP_SUBTREE
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (file != root) onEntry(file, attrs)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    exc: IOException,
                ): FileVisitResult {
                    onUnobserved(file)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }
}
