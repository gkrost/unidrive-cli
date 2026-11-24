package org.krost.unidrive.sync

import org.krost.unidrive.io.Os
import java.net.ConnectException
import java.net.SocketException
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

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
    }

    /**
     * Connects to [path]; when nobody listens and the file is still the probed one, removes it. Any other connect error is
     * thrown, never taken as "stale". [afterProbe] runs between the failed connect and the removal (a test seam).
     */
    fun removeIfStale(
        path: Path,
        windows: Boolean = Os.isWindows(),
        afterProbe: () -> Unit = {},
    ): Outcome {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Outcome.ABSENT
        val probed = fileKey(path)
        try {
            SocketChannel.open(UnixDomainSocketAddress.of(path)).close()
            return Outcome.LIVE
        } catch (_: ConnectException) {
            // ECONNREFUSED: nobody listening
        } catch (e: SocketException) {
            if (e.message.orEmpty().contains("No such file")) return Outcome.ABSENT // ENOENT: gone before the connect
            if (!(windows &&e.message.orEmpty().contains("Invalid argument"))) throw e // Windows' answer for a stale socket
        }
        afterProbe()
        // Take the file out of the way under a private name first: what is renamed is then ours, whoever bound it. Comparing
        // dev + ino is not enough on its own, because a file system may give a successor the inode just freed.
        if (fileKey(path) != probed) return Outcome.CHANGED
        if (windows) { // no file identity, and a renamed Windows socket cannot be connected to: remove as before
            Files.deleteIfExists(path)
            return Outcome.REMOVED
        }
        val aside = path.resolveSibling("${path.fileName}.stale-${java.util.UUID.randomUUID()}")
        try {
            Files.move(path, aside, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.NoSuchFileException) {
            return Outcome.CHANGED
        }
        if (listening(aside)) {
            // A successor bound it after the probe: put it back (never over a file bound meanwhile) and leave it alone.
            runCatching { Files.move(aside, path) }
            return Outcome.CHANGED
        }
        Files.deleteIfExists(aside)
        return Outcome.REMOVED
    }

    private fun listening(path: Path): Boolean =
        try {
            SocketChannel.open(UnixDomainSocketAddress.of(path)).close()
            true
        } catch (_: java.io.IOException) {
            false
        }

    // dev + ino on POSIX; null on Windows (no file key) and when the file is gone.
    private fun fileKey(path: Path): Any? =
        runCatching { Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey() }.getOrNull()
}
