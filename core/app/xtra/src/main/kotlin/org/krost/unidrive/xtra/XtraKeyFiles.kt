package org.krost.unidrive.xtra

import org.krost.unidrive.io.OwnerOnly
import org.krost.unidrive.io.OwnerOnlyAtomicWrite
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Installs [bytes] as the new key file [keyPath] only if no file is there, atomically: two initializations (or imports)
 * racing for one key file leave exactly one key, the first to land, and the other refuses ([IllegalStateException]) before
 * its key encrypted anything. A replace would let the second silently overwrite the first, and every file the first key
 * already sealed would become unreadable.
 *
 * The bytes go first to an owner-only staged sibling (written, synced and checked by [verify] through
 * [OwnerOnlyAtomicWrite]), which is then hard-linked to [keyPath]: creating a link fails when the target exists, on
 * POSIX and on NTFS alike, so the existence check and the install are one step. A file system without hard links
 * (FAT, some shares) falls back to a move that refuses an existing target; on Windows that move is atomic too.
 */
internal fun createKeyFileExclusively(
    keyPath: Path,
    bytes: ByteArray,
    verify: (Path) -> Unit,
) {
    Files.createDirectories(keyPath.toAbsolutePath().parent)
    val staged = keyPath.resolveSibling("${keyPath.fileName}.new-${UUID.randomUUID()}")
    try {
        OwnerOnlyAtomicWrite.replace(staged, bytes, verify = verify)
        try {
            Files.createLink(keyPath, staged)
        } catch (e: FileAlreadyExistsException) {
            throw e
        } catch (_: UnsupportedOperationException) {
            Files.move(staged, keyPath)
        } catch (_: FileSystemException) {
            Files.move(staged, keyPath)
        }
    } catch (e: FileAlreadyExistsException) {
        throw IllegalStateException(
            "An X-tra key file already exists at $keyPath (another run created it first); this run's key was discarded " +
                "before it encrypted anything, and the existing key is unchanged.",
            e,
        )
    } finally {
        Files.deleteIfExists(staged)
    }
}

/**
 * Runs [action] holding the one lock every replacement of the key file [keyPath] takes, across threads and processes: a
 * monitor per path for this JVM (a file lock does not exclude two threads of one process) and an exclusive lock on the
 * persistent `<key file>.lock` beside it, the pattern of the config transaction. The key file is every encrypted file's only
 * key, so a replacement is a check, a backup of the key it retires and the replace as one step: two forced imports
 * interleaving would otherwise both retire the same key and the first import's key would be gone with no copy.
 */
internal fun <T> withKeyFileLock(
    keyPath: Path,
    action: () -> T,
): T {
    val path = keyPath.toAbsolutePath().normalize()
    val parent = path.parent ?: throw IOException("key path has no parent folder: $path")
    Files.createDirectories(parent)
    val lockPath = parent.resolve("${path.fileName}.lock")
    return synchronized(keyFileMonitors.computeIfAbsent(path) { Any() }) {
        createLockFile(lockPath)
        FileChannel.open(lockPath, StandardOpenOption.WRITE).use { channel ->
            OwnerOnly.requireFile(lockPath)
            channel.lock().use { action() }
        }
    }
}

private val keyFileMonitors = ConcurrentHashMap<Path, Any>()

private fun createLockFile(lockPath: Path) {
    if (Files.exists(lockPath)) return
    try {
        Files.createFile(lockPath, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    } catch (_: UnsupportedOperationException) {
        try {
            Files.createFile(lockPath)
        } catch (_: FileAlreadyExistsException) {
            // Another process created the persistent lock file.
        }
    } catch (_: FileAlreadyExistsException) {
        // Another process created the persistent lock file.
    }
}

/**
 * Copies the key file [keyPath] to a retired sibling `<name>.bak-<millis>` (the next free millisecond when that name is
 * taken, so a retired key is never overwritten by a later one) before a forced import replaces it, so the files the old
 * key encrypted stay decryptable after the import turns out to be the wrong one. Call it inside [withKeyFileLock].
 */
internal fun retireKeyFile(keyPath: Path) {
    var stamp = System.currentTimeMillis()
    var retired = keyPath.resolveSibling("${keyPath.fileName}.bak-$stamp")
    while (Files.exists(retired)) retired = keyPath.resolveSibling("${keyPath.fileName}.bak-${++stamp}")
    OwnerOnlyAtomicWrite.replace(retired, produce = { Files.readAllBytes(keyPath) })
}
