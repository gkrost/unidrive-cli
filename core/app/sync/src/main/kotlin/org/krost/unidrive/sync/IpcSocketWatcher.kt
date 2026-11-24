package org.krost.unidrive.sync

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.krost.unidrive.io.OwnerOnly
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** `unix:dev` and `unix:ino` of [path] (not following a link); null where the file system has no `unix` view (Windows). */
private fun fileIdentity(path: Path): Any? =
    runCatching {
        listOf(Files.getAttribute(path, "unix:dev", LinkOption.NOFOLLOW_LINKS), Files.getAttribute(path, "unix:ino", LinkOption.NOFOLLOW_LINKS))
    }.getOrNull()

/**
 * gkrost/unidrive-cli#794: a daemon binds its socket once, and a cleanup of the temp folder (or anything else that deletes the
 * file) leaves it healthy but unreachable: nothing can connect, a stop through IPC fails, and the daemon says nothing. Every
 * [intervalMs] [run] checks that the socket file is still the one it bound (gkrost/unidrive-cli#926 S2: the same `unix:dev` and
 * `unix:ino` where the file system has them, else that it is there) and, when it is not, checks the folder (making it again
 * if it is gone) and binds the file again; the connections already open are not touched. A failed attempt is reported once per episode
 * and tried again on the next round.
 *
 * The swap of the new listener into the server is [install], which the server does under its own lock and which answers false
 * when the server was closed first ([isClosing]).
 */
internal class IpcSocketWatcher(
    private val socketPath: Path,
    private val intervalMs: Long,
    private val isClosing: () -> Boolean,
    private val install: (ServerSocketChannel) -> Boolean,
) {
    private val log = LoggerFactory.getLogger(IpcSocketWatcher::class.java)

    /** How often the socket file had to be bound again. */
    @Volatile
    var rebinds: Int = 0
        private set

    // The profile name a hashed socket's sidecar holds, kept to write it again with the file.
    private val meta: String? = runCatching { Files.readString(IpcSocketFiles.metaPathFor(socketPath)) }.getOrNull()

    // The identity of the socket file this server bound; the watcher is made after the first bind, so it is that file's.
    private var bound: Any? = fileIdentity(socketPath)

    suspend fun run() {
        var reported = false
        while (currentCoroutineContext().isActive) {
            delay(intervalMs)
            if (isClosing() || isOurs()) {
                if (reported && !isClosing()) {
                    log.info("IPC: the socket file {} is there again", socketPath)
                    reported = false
                }
                continue
            }
            if (!reported) {
                log.warn("IPC: the socket file {} is gone or replaced; no client can reach this daemon until it is bound again", socketPath)
            }
            val failure = rebindOrFailure() ?: continue
            if (!reported) log.error("IPC: could not bind the socket file {} again: {}; trying again every {} ms", socketPath, failure.toString(), intervalMs)
            reported = true
        }
    }

    /** One attempt: null when it bound the file again or the server was closed first, else what went wrong (an I/O problem, or a folder it refuses to use). */
    private fun rebindOrFailure(): Exception? {
        try {
            if (rebind()) {
                rebinds++
                log.info("IPC: bound the socket file {} again (rebind #{}); open connections were not touched", socketPath, rebinds)
            }
            return null
        } catch (e: IOException) {
            return e
        } catch (e: IllegalStateException) {
            return e
        }
    }

    private fun isOurs(): Boolean {
        if (!Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS)) return false
        return bound == null || fileIdentity(socketPath) == bound
    }

    /** Binds [socketPath] again with a new listener and hands it to [install]; false when the server was closed first. */
    private fun rebind(): Boolean {
        IpcSocketPaths.restoreSocketDirectory(socketPath)
        // A file in its place that is not the one this server bound: the folder passed its checks, so it is ours to clear.
        Files.deleteIfExists(socketPath)
        val next = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        var installed = false
        try {
            next.bind(UnixDomainSocketAddress.of(socketPath))
            bound = fileIdentity(socketPath)
            val restricted = OwnerOnly.restrictFile(socketPath)
            if (!restricted.restricted) log.warn("IPC: could not restrict the permissions of {}: {}", socketPath, restricted)
            meta?.let { runCatching { Files.writeString(IpcSocketFiles.metaPathFor(socketPath), it) } }
            installed = install(next)
        } finally {
            if (!installed) runCatching { next.close() } // not bound, or the server was closed first: nothing keeps the listener
        }
        if (!installed) IpcSocketFiles.deleteSocketFiles(socketPath) // closed first: the file this attempt bound goes with it
        return installed
    }
}
