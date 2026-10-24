package org.krost.unidrive

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
         * The case behaviour of the file system that holds [dir], found by asking it: the nearest existing segment of
         * the path whose name has a cased letter is looked up again with its case swapped, and the two lookups name one
         * file only on a file system that folds case. Null when no segment can tell (no cased letter, nothing exists,
         * an I/O error).
         */
        fun ofDirectory(dir: Path): CaseSensitivity? {
            var p: Path? = dir.toAbsolutePath().normalize()
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
            System.getProperty("os.name", "").lowercase(java.util.Locale.ROOT).let { os ->
                if (os.contains("win") || os.contains("mac")) INSENSITIVE_PRESERVING else SENSITIVE
            }

        private fun swapCase(name: String): String =
            buildString(name.length) {
                for (c in name) append(if (c.isUpperCase()) c.lowercaseChar() else if (c.isLowerCase()) c.uppercaseChar() else c)
            }
    }
}
