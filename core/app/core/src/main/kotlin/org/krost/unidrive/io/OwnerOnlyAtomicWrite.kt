package org.krost.unidrive.io

import org.slf4j.LoggerFactory
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
 * The one way the engine replaces a file that holds secrets or paths (config.toml, the vault, the credential files, the
 * verify report) without a window in which it is torn, or readable by others (archive#1053, #222). The new bytes go to a
 * temp file in the same folder that is owner-only ([OwnerOnly]) before the first byte is written, reach the disk
 * (`channel.force(true)`: a power loss never leaves a renamed-but-empty file), are checked by the caller, and only then
 * replace the target in one atomic move. Whatever fails before the move leaves the target as it was and the temp file
 * removed.
 *
 * Permission policy: the file must end up owner-only, or the write is refused ([IOException], nothing written). A caller
 * that writes to a place the user chose, which may be a file system with neither POSIX modes nor ACLs (FAT, a share), and
 * that would rather keep the data than lose it passes [replace]'s `onUnrestricted`: the write then goes ahead and the
 * callback reports the [OwnerOnly.Outcome] that was not a restriction. Nothing else should: the engine's own files live in
 * folders it makes owner-only, so a failure there is a real problem, not a property of the destination.
 *
 * The caller creates the target's folder.
 */
public object OwnerOnlyAtomicWrite {
    private val log = LoggerFactory.getLogger(OwnerOnlyAtomicWrite::class.java)

    /**
     * @param produce the bytes, asked for once the owner-only temp file exists, so a secret is never serialized before
     *   there is a file that only the current user can read to put it in
     * @param onUnrestricted null (the default): refuse when the temp file cannot be made owner-only. Otherwise called with
     *   the outcome, and the write goes ahead
     * @param beforeCommit test seam, runs once the temp file is written and synced (a crash is an exception here)
     * @param verify throws when the temp file must not replace [target]
     */
    public fun replace(
        target: Path,
        produce: () -> ByteArray,
        onUnrestricted: ((OwnerOnly.Outcome) -> Unit)? = null,
        beforeCommit: (Path) -> Unit = {},
        verify: (Path) -> Unit = {},
    ) {
        replace(target, produce, onUnrestricted, beforeCommit, verify, OwnerOnly::restrictFile)
    }

    public fun replace(
        target: Path,
        bytes: ByteArray,
        onUnrestricted: ((OwnerOnly.Outcome) -> Unit)? = null,
        beforeCommit: (Path) -> Unit = {},
        verify: (Path) -> Unit = {},
    ) {
        replace(target, { bytes }, onUnrestricted, beforeCommit, verify, OwnerOnly::restrictFile)
    }

    /** [replace] with the restriction step replaced, so a test can make it fail the way a FAT volume does. */
    internal fun replace(
        target: Path,
        produce: () -> ByteArray,
        onUnrestricted: ((OwnerOnly.Outcome) -> Unit)?,
        beforeCommit: (Path) -> Unit,
        verify: (Path) -> Unit,
        restrict: (Path) -> OwnerOnly.Outcome,
    ) {
        val tmp = createTempFile(target, onUnrestricted, restrict)
        try {
            val bytes = produce()
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
                log.warn("Filesystem rejected ATOMIC_MOVE at {} — falling back to non-atomic replace", target)
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun createTempFile(
        target: Path,
        onUnrestricted: ((OwnerOnly.Outcome) -> Unit)?,
        restrict: (Path) -> OwnerOnly.Outcome,
    ): Path {
        val folder = target.toAbsolutePath().parent ?: throw IOException("path has no parent folder: $target")
        val prefix = tempPrefix(target.fileName.toString())
        val tmp =
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                val ownerOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
                Files.createTempFile(folder, prefix, ".tmp", ownerOnly)
            } else {
                Files.createTempFile(folder, prefix, ".tmp")
            }
        try {
            // Before any byte is written; Windows has no mode bits, so the inherited ACL becomes an owner-only one.
            val outcome = restrict(tmp)
            if (!outcome.restricted) {
                if (onUnrestricted == null) throw IOException("Could not restrict $tmp to the current user: $outcome")
                onUnrestricted(outcome)
            }
        } catch (e: Exception) {
            Files.deleteIfExists(tmp)
            throw e
        }
        return tmp
    }

    /**
     * The temp name is the target's name, a dot, the JDK's random number and `.tmp`. The target's name is cut to
     * [MAX_PREFIX_BYTES] bytes of UTF-8 (on a character boundary) so that a valid name close to the 255-byte limit of a
     * path component, which a user may choose for a report, still gets a temp name that fits next to it.
     */
    internal fun tempPrefix(fileName: String): String {
        var end = fileName.length
        while (end > 0 && fileName.substring(0, end).toByteArray(Charsets.UTF_8).size > MAX_PREFIX_BYTES) {
            end--
            if (end > 0 && Character.isLowSurrogate(fileName[end])) end-- // never between the halves of a character
        }
        return fileName.substring(0, end) + "."
    }

    private const val MAX_PREFIX_BYTES = 100
}
