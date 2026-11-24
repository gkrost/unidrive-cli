package org.krost.unidrive.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.HexFormat

/** The watcher binds a unique source address so the accept loop can identify its own probe before admitting clients. */
internal class IpcSocketProbe(private val socketPath: Path) {
    private class Pending(val address: UnixDomainSocketAddress) {
        val accepted = CompletableDeferred<Unit>()
    }

    @Volatile
    private var pending: Pending? = null

    @Volatile
    var successes: Int = 0
        private set

    fun accept(channel: SocketChannel): Boolean {
        val probe = pending ?: return false
        if (channel.remoteAddress != probe.address) return false
        channel.close()
        probe.accepted.complete(Unit)
        return true
    }

    /** Null when the probe could not bind its own source address. */
    suspend fun leadsHere(): Boolean? {
        val token = ByteArray(PROBE_NAME_BYTES).also(random::nextBytes)
        val path = socketPath.toAbsolutePath().resolveSibling(".p-${HexFormat.of().formatHex(token)}")
        val probe = Pending(UnixDomainSocketAddress.of(path))
        var bound = false
        try {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.bind(probe.address)
                bound = true
                pending = probe
                channel.connect(UnixDomainSocketAddress.of(socketPath))
                if (withTimeoutOrNull(PROBE_WAIT_MS) { probe.accepted.await() } == null) return false
                successes++
                return true
            }
        } catch (_: IOException) {
            return if (bound) false else null
        } finally {
            pending = null
            if (bound) runCatching { Files.deleteIfExists(path) }
        }
    }

    private companion object {
        val random = SecureRandom()
        const val PROBE_NAME_BYTES = 6
        const val PROBE_WAIT_MS = 2_000L
    }
}
