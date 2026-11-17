package org.krost.unidrive.testing

import org.krost.unidrive.io.LinkedEntries
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
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

    /**
     * Deletes [root] and what is below it, best effort. A symbolic link or a Windows junction a test made is deleted
     * itself and never entered ([LinkedEntries.deleteTree]: `walkFileTree` alone descends into a junction, and so does
     * Kotlin's `File.deleteRecursively`), so a scratch directory that holds a junction to a directory the test does not
     * own cannot take that directory's files with it. Public for its own test.
     */
    fun deleteTree(root: Path) {
        LinkedEntries.deleteTree(root, ::delete)
    }

    private fun delete(path: Path) {
        if (runCatching { Files.deleteIfExists(path) }.isSuccess) return
        // A read-only entry (a test that made one) refuses deletion on Windows: clear the flag (of the entry itself,
        // not of what a link leads to) and try once more.
        runCatching {
            Files.getFileAttributeView(path, DosFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)?.setReadOnly(false)
            Files.deleteIfExists(path)
        }
    }
}
