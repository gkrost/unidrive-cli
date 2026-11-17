package org.krost.unidrive.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.krost.unidrive.io.OwnerOnly
import org.slf4j.LoggerFactory
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path

/**
 * gkrost/unidrive-cli#794: a daemon binds its socket once, and a cleanup of the temp folder (or anything else that deletes the
 * file) leaves it healthy but unreachable: nothing can connect, a stop through IPC fails, and the daemon says nothing. Every
 * [intervalMs] [run] checks that the socket file is still there and, when it is not, makes the folder again if that is gone
 * too and binds the file again; the connections already open are not touched. A failed attempt is reported once per episode
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
    private val meta: String? = runCatching { Files.readString(IpcSocketPaths.metaPathFor(socketPath)) }.getOrNull()

    suspend fun run() {
        var reported = false
        while (currentCoroutineContext().isActive) {
            delay(intervalMs)
            if (isClosing() || Files.exists(socketPath)) {
                if (reported && !isClosing()) {
                    log.info("IPC: the socket file {} is there again", socketPath)
                    reported = false
                }
                continue
            }
            if (!reported) {
                log.warn("IPC: the socket file {} is gone (a cleanup of its folder?); no client can connect until it is bound again", socketPath)
            }
            try {
                if (rebind()) {
                    rebinds++
                    log.info("IPC: bound the socket file {} again (rebind #{}); open connections were not touched", socketPath, rebinds)
                    reported = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!reported) log.error("IPC: could not bind the socket file {} again: {}; trying again every {} ms", socketPath, e.toString(), intervalMs)
                reported = true
            }
        }
    }

    /** Binds [socketPath] again with a new listener and hands it to [install]; false when the server was closed first. */
    private fun rebind(): Boolean {
        IpcSocketPaths.restoreSocketDirectory(socketPath)
        val next = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        try {
            next.bind(UnixDomainSocketAddress.of(socketPath))
            val restricted = OwnerOnly.restrictFile(socketPath)
            if (!restricted.restricted) log.warn("IPC: could not restrict the permissions of {}: {}", socketPath, restricted)
            meta?.let { runCatching { Files.writeString(IpcSocketPaths.metaPathFor(socketPath), it) } }
        } catch (e: Exception) {
            runCatching { next.close() }
            throw e
        }
        if (install(next)) return true
        runCatching { next.close() }
        IpcSocketPaths.deleteSocketFiles(socketPath)
        return false
    }
}
