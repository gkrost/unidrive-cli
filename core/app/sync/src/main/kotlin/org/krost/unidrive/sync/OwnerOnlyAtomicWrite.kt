package org.krost.unidrive.sync

import org.krost.unidrive.io.OwnerOnly
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Replaces a secret file without a window in which it is torn or readable by others (archive#1053). The new bytes go to a
 * temp file in the same folder that is owner-only before the first byte is written ([OwnerOnly], as `CredentialStore`
 * does), reach the disk (`force`), are checked by the caller, and only then replace [target] in one atomic move. Whatever
 * fails before the move leaves [target] as it was and the temp file removed.
 */
internal object OwnerOnlyAtomicWrite {
    /**
     * @param beforeCommit test seam, runs once the temp file is written and synced (a crash is an exception here)
     * @param verify throws when the temp file must not replace [target]
     */
    fun replace(
        target: Path,
        bytes: ByteArray,
        beforeCommit: (Path) -> Unit = {},
        verify: (Path) -> Unit = {},
    ) {
        val tmp = createOwnerOnlyTempFile(target)
        try {
            FileChannel.open(tmp, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            beforeCommit(tmp)
            verify(tmp)
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun createOwnerOnlyTempFile(target: Path): Path {
        val folder = target.toAbsolutePath().parent
        val prefix = target.fileName.toString() + "."
        val tmp =
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                val ownerOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
                Files.createTempFile(folder, prefix, ".tmp", ownerOnly)
            } else {
                Files.createTempFile(folder, prefix, ".tmp")
            }
        try {
            // Before any secret is written; Windows has no mode bits, so the inherited ACL becomes an owner-only one.
            OwnerOnly.requireFile(tmp)
        } catch (e: IOException) {
            Files.deleteIfExists(tmp)
            throw e
        }
        return tmp
    }
}
