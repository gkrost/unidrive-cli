package org.krost.unidrive

import org.krost.unidrive.io.Os
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * How a drive tells two names apart: whether `report.txt` and `Report.txt` are two items or one. A provider declares it
 * ([CloudProvider.caseSensitivity]); the engine folds case for identity wherever the provider or the local file system
 * folds it, so a case-only twin is one name and never a second file that lands on the first one.
 */
enum class CaseSensitivity {
    /** Names that differ only in case are two items (S3 keys, Internxt names, a POSIX file system). */
    SENSITIVE,

    /** Names that differ only in case are one item; the drive keeps the case it was given (OneDrive, Dropbox, NTFS). */
    INSENSITIVE_PRESERVING,
    ;

    /** True when two names that differ only in case are the same name here. */
    val foldsCase: Boolean get() = this == INSENSITIVE_PRESERVING

    companion object {
        /**
         * The case behaviour of [dir]'s own entries, found by asking the file system: a name is looked up again with its
         * case swapped, and the two lookups name one file only where case folds. The name is one of [dir]'s children
         * when it has one with a cased letter, because the rule that counts is the one of the directory the names live
         * in (a FAT stick mounted under an ext4 folder, an NTFS folder with its own case flag). Otherwise the nearest
         * existing segment of the path is asked, which answers for the directory that holds it. Nothing is written.
         * Null when nothing can tell (no cased letter, nothing exists, an I/O error).
         */
        fun ofDirectory(dir: Path): CaseSensitivity? {
            val abs = dir.toAbsolutePath().normalize()
            return ofChildren(abs) ?: ofNearestSegment(abs)
        }

        // How many entries of a directory are looked at for a name with a cased letter before the path is asked instead.
        private const val CHILDREN_ASKED = 64

        // Asked of [dir] itself through one of its first entries whose name has a cased letter; null when there is none.
        private fun ofChildren(dir: Path): CaseSensitivity? {
            if (!Files.isDirectory(dir)) return null
            return try {
                Files.newDirectoryStream(dir).use { entries ->
                    entries
                        .asSequence()
                        .take(CHILDREN_ASKED)
                        .mapNotNull { child ->
                            val name = child.fileName?.toString() ?: return@mapNotNull null
                            val swapped = swapCase(name)
                            if (swapped == name) null else probe(child, swapped)
                        }.firstOrNull()
                }
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }

        private fun ofNearestSegment(abs: Path): CaseSensitivity? {
            var p: Path? = abs
            while (p != null && p.fileName != null) {
                val swapped = swapCase(p.fileName.toString())
                if (swapped != p.fileName.toString() && Files.exists(p)) return probe(p, swapped)
                p = p.parent
            }
            return null
        }

        // Whether [path] and its sibling named [swapped] are one file; null when the file system does not say.
        private fun probe(
            path: Path,
            swapped: String,
        ): CaseSensitivity? =
            try {
                val variant = path.resolveSibling(swapped)
                if (Files.exists(variant) && Files.isSameFile(path, variant)) INSENSITIVE_PRESERVING else SENSITIVE
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }

        /** The case behaviour of the host's usual file system, for when [ofDirectory] cannot tell: Windows and macOS fold. */
        val host: CaseSensitivity =
            Os.of(System.getProperty("os.name")).let { os ->
                if (os.isWindows || os.isMac) INSENSITIVE_PRESERVING else SENSITIVE
            }

        private fun swapCase(name: String): String =
            buildString(name.length) {
                for (c in name) append(if (c.isUpperCase()) c.lowercaseChar() else if (c.isLowerCase()) c.uppercaseChar() else c)
            }
    }
}
