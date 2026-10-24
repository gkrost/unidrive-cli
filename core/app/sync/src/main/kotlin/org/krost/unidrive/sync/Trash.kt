package org.krost.unidrive.sync

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * FreeDesktop.org Trash spec implementation.
 * Moves files to $XDG_DATA_HOME/Trash/ with .trashinfo metadata.
 * Preserves the original if trash is unavailable.
 */
object Trash {
    private val log = LoggerFactory.getLogger(Trash::class.java)
    private val TRASH_DATE_FORMAT =
        DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss")
            .withZone(ZoneId.systemDefault())

    /**
     * Move a file or directory to the FreeDesktop Trash.
     * Returns true if trashed; failures are propagated to the caller.
     */
    fun trash(path: Path): Boolean {
        return trashInto(path, resolveTrashDir())
    }

    @Suppress("NestedBlockDepth", "ThrowsCount", "TooGenericExceptionCaught")
    internal fun trashInto(
        path: Path,
        trashDirectory: Path?,
        copy: (Path, Path) -> Unit = ::copyTree,
    ): Boolean {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true

        val trashDir =
            trashDirectory ?: run {
                throw IOException("No trash directory available for $path")
            }

        val filesDir = trashDir.resolve("files")
        val infoDir = trashDir.resolve("info")
        Files.createDirectories(filesDir)
        Files.createDirectories(infoDir)

        // Generate unique trash name (append .2, .3 etc. on collision)
        val baseName = path.fileName.toString()
        var trashName = baseName
        var counter = 2
        while (Files.exists(filesDir.resolve(trashName)) || Files.exists(infoDir.resolve("$trashName.trashinfo"))) {
            val dot = baseName.lastIndexOf('.')
            trashName =
                if (dot > 0) {
                    "${baseName.substring(0, dot)}.$counter${baseName.substring(dot)}"
                } else {
                    "$baseName.$counter"
                }
            counter++
        }

        // Write .trashinfo metadata
        val trashInfo =
            """
            |[Trash Info]
            |Path=${path.toAbsolutePath()}
            |DeletionDate=${TRASH_DATE_FORMAT.format(Instant.now())}
            """.trimMargin() + "\n"

        val info = infoDir.resolve("$trashName.trashinfo")
        val destination = filesDir.resolve(trashName)
        Files.writeString(info, trashInfo, StandardOpenOption.CREATE_NEW)
        var complete = false
        try {
            val sameStore = Files.getFileStore(path) == Files.getFileStore(filesDir)
            if (sameStore) {
                try {
                    Files.move(path, destination)
                    complete = true
                    return true
                } catch (e: IOException) {
                    // A failed move may have left a destination; never overwrite it.
                    if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw e
                }
            }
            val staging = Files.createTempDirectory(filesDir, ".unidrive-trash-")
            try {
                val stagedCopy = staging.resolve("item")
                copy(path, stagedCopy)
                Files.move(stagedCopy, destination)
                complete = true
            } finally {
                deletePermanently(staging)
            }
            deletePermanently(path)
            return true
        } catch (e: Exception) {
            if (!complete) {
                try {
                    Files.deleteIfExists(info)
                } catch (cleanup: Exception) {
                    e.addSuppressed(cleanup)
                }
            }
            log.warn("Failed to trash {}: {}", path, e.message)
            throw e
        }
    }

    private fun copyTree(source: Path, destination: Path) {
        Files.walkFileTree(
            source,
            object : SimpleFileVisitor<Path>() {
                private fun target(path: Path): Path =
                    source.relativize(path).fold(destination) { parent, part -> parent.resolve(part.toString()) }

                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.copy(dir, target(dir), LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.copy(file, target(file), LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    internal fun resolveTrashDir(): Path? {
        val xdgDataHome = System.getenv("XDG_DATA_HOME")
        val trashDir =
            if (xdgDataHome != null) {
                Paths.get(xdgDataHome, "Trash")
            } else {
                val home = System.getenv("HOME") ?: System.getProperty("user.home") ?: return null
                Paths.get(home, ".local", "share", "Trash")
            }
        return trashDir
    }

    private fun deletePermanently(path: Path) {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.walkFileTree(
                path,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(
                        dir: Path,
                        exc: java.io.IOException?,
                    ): FileVisitResult {
                        if (exc != null) throw exc
                        Files.deleteIfExists(dir)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        } else {
            Files.deleteIfExists(path)
        }
    }
}
