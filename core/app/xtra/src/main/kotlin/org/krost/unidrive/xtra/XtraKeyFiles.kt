package org.krost.unidrive.xtra

import org.krost.unidrive.io.OwnerOnlyAtomicWrite
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Installs [bytes] as the new key file [keyPath] only if no file is there, atomically: two initializations racing for
 * one key file leave exactly one key, the first to land, and the other refuses ([IllegalStateException]) before its key
 * encrypted anything. A replace would let the second silently overwrite the first, and every file the first key
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
            "An X-tra key file already exists at $keyPath (another initialization created it first); this run's new key " +
                "was discarded before it encrypted anything, and the existing key is unchanged.",
            e,
        )
    } finally {
        Files.deleteIfExists(staged)
    }
}
