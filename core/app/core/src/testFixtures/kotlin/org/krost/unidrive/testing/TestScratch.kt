package org.krost.unidrive.testing

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.FileAttribute
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * The tests' scratch directories and files (#790). They are made like `Files.createTempDirectory` / `createTempFile`
 * make them, in the test JVM's temp dir, and every one is deleted when the test JVM exits: most of the suite left them
 * behind, and a few weeks of runs piled up millions of entries in the developer's temp dir.
 *
 * Deleted at JVM exit, not per test: a test that deletes its own scratch is unaffected (the entry is gone by then), and
 * one that keeps using a directory across tests of a class keeps it until the class's JVM ends. The sweep is best
 * effort (a file a test still holds open, as SQLite does on Windows, stays) and never throws.
 */
object TestScratch {
    private val created = ConcurrentLinkedDeque<Path>()

    init {
        Runtime.getRuntime().addShutdownHook(Thread({ sweep() }, "test-scratch-sweep"))
    }

    fun directory(
        prefix: String,
        vararg attributes: FileAttribute<*>,
    ): Path = Files.createTempDirectory(prefix, *attributes).also { created.addFirst(it) }

    fun directory(
        parent: Path,
        prefix: String,
        vararg attributes: FileAttribute<*>,
    ): Path = Files.createTempDirectory(parent, prefix, *attributes).also { created.addFirst(it) }

    fun file(
        prefix: String?,
        suffix: String? = null,
        vararg attributes: FileAttribute<*>,
    ): Path = Files.createTempFile(prefix, suffix, *attributes).also { created.addFirst(it) }

    fun file(
        parent: Path,
        prefix: String?,
        suffix: String?,
        vararg attributes: FileAttribute<*>,
    ): Path = Files.createTempFile(parent, prefix, suffix, *attributes).also { created.addFirst(it) }

    private fun sweep() {
        for (path in created) runCatching { deleteTree(path) }
    }

    // walkFileTree without FOLLOW_LINKS: a symlink or a junction a test made is deleted itself, its target is never
    // entered (a plain File.deleteRecursively would walk into it).
    private fun deleteTree(root: Path) {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
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
                    delete(dir)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    exc: IOException,
                ): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
    }

    private fun delete(path: Path) {
        if (runCatching { Files.deleteIfExists(path) }.isSuccess) return
        // A read-only entry (a test that made one) refuses deletion on Windows: clear the flag and try once more.
        runCatching {
            Files.getFileAttributeView(path, DosFileAttributeView::class.java)?.setReadOnly(false)
            Files.deleteIfExists(path)
        }
    }
}
