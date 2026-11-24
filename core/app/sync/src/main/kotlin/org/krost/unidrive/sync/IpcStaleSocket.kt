package org.krost.unidrive.sync

import org.krost.unidrive.io.Os
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/**
 * gkrost/unidrive-cli#926 S5: the probe of a socket file a dead daemon may have left behind, and its removal; shared by
 * [IpcServer]'s start and `daemon stop` ([IpcSocketFiles.removeStaleSocketFiles]). The file is removed only when the connect
 * was refused (`ECONNREFUSED`; Windows answers "Invalid argument") and it is still the file that was probed (same
 * `unix:dev`/`unix:ino`, where the file system has them, and nobody listens on it once it is renamed aside): a socket a new
 * daemon bound in between is left alone.
 */
internal object IpcStaleSocket {
    enum class Outcome {
        /** Nothing was there. */
        ABSENT,

        /** Somebody listens on it: not ours to delete. */
        LIVE,

        /** Nobody listened, and the file was removed. */
        REMOVED,

        /** Nobody listened, but the file was replaced or removed after the probe: left alone. */
        CHANGED,

        /** The socket could not be classified safely. */
        UNKNOWN,
    }

    /**
     * Connects to [path]; when nobody listens and the file is still the probed one, removes it. Any other connect error is
     * thrown, never taken as "stale". [afterProbe] runs between the failed connect and the removal (a test seam).
     */
    fun removeIfStale(
        path: Path,
        windows: Boolean = Os.isWindows(),
        fileIdentity: (Path) -> Any? = ::fileKey,
        afterProbe: () -> Unit = {},
    ): Outcome {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Outcome.ABSENT
        val probed = fileIdentity(path)
        try {
            SocketChannel.open(UnixDomainSocketAddress.of(path)).close()
            return Outcome.LIVE
        } catch (_: ConnectException) {
            // ECONNREFUSED: nobody listening
        } catch (e: SocketException) {
            if (e.message.orEmpty().contains("No such file")) return Outcome.ABSENT // ENOENT: gone before the connect
            if (!(windows && e.message.orEmpty().contains("Invalid argument"))) throw e // Windows' answer for a stale socket
        }
        afterProbe()
        // Take the file out of the way under a private name first: what is renamed is then ours, whoever bound it. Comparing
        // dev + ino is not enough on its own, because a file system may give a successor the inode just freed.
        if (fileIdentity(path) != probed) return Outcome.CHANGED
        if (windows) { // no file identity, and a renamed Windows socket cannot be connected to: remove as before
            Files.deleteIfExists(path)
            return Outcome.REMOVED
        }
        val aside = temporarySibling(path) ?: return Outcome.UNKNOWN
        try {
            Files.createFile(aside)
        } catch (_: FileAlreadyExistsException) {
            return Outcome.UNKNOWN
        }
        try {
            Files.move(path, aside, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: NoSuchFileException) {
            Files.deleteIfExists(aside)
            return Outcome.CHANGED
        } catch (e: IOException) {
            Files.deleteIfExists(aside)
            throw e
        }
        val probe = probeRenamedSocket(aside)
        if (probe != Outcome.REMOVED) {
            // Restore a live or unclassified file, without replacing a socket bound meanwhile.
            runCatching { Files.move(aside, path) }
            return probe
        }
        Files.deleteIfExists(aside)
        return Outcome.REMOVED
    }

    private fun temporarySibling(path: Path): Path? {
        val prefixBytes = path.resolveSibling(".").toString().toByteArray(Charsets.UTF_8).size - 1
        // The JDK on Linux refuses 107-byte addresses, so keep one byte inside the OS limit.
        val available = IpcSocketPaths.MAX_UNIX_SOCKET_PATH_BYTES - 1 - prefixBytes
        if (available < MIN_TEMP_NAME) return null
        val token = UUID.randomUUID().toString().replace("-", "")
        return path.resolveSibling(".${token.take(minOf(available - 1, TEMP_TOKEN_LENGTH))}")
    }

    private fun probeRenamedSocket(path: Path): Outcome =
        try {
            SocketChannel.open(UnixDomainSocketAddress.of(path)).close()
            Outcome.CHANGED
        } catch (_: ConnectException) {
            Outcome.REMOVED
        } catch (_: IOException) {
            Outcome.UNKNOWN
        }

    private const val MIN_TEMP_NAME = 9
    private const val TEMP_TOKEN_LENGTH = 12

    // dev + ino on POSIX; null on Windows (no file key) and when the file is gone.
    private fun fileKey(path: Path): Any? =
        runCatching { Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey() }.getOrNull()
}
