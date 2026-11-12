package org.krost.unidrive.cli

import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcAuthClient
import org.krost.unidrive.sync.IpcEndpoint
import org.krost.unidrive.sync.IpcLineClient
import org.krost.unidrive.sync.IpcLineRead
import java.io.IOException

/** What [ipcRequest] came back with. */
internal sealed interface IpcReply {
    /** The first line the daemon wrote after the request. */
    data class Line(
        val text: String,
    ) : IpcReply

    /** The request was written; the daemon closed (or reset) the connection without a reply line. */
    data object Closed : IpcReply

    /** The request was written; no whole reply line arrived within the deadline. */
    data object TimedOut : IpcReply

    /**
     * The connection was made, but the request was not written in full within the deadline (or the write failed): it
     * carries no newline, so the daemon never dispatched it (gkrost/unidrive-cli#442).
     */
    data object NotWritten : IpcReply
}

/**
 * One request and its reply line on a fresh authenticated connection, bounded by [timeoutMs] from the moment the
 * handshake is done: a daemon that is stuck answers [IpcReply.TimedOut] instead of holding its client forever. The one
 * round trip of the CLI's verb clients (gkrost/unidrive-cli#442); each maps the answers to its own outcome. Throws
 * [org.krost.unidrive.sync.IpcAuthException] and [IOException] only when the connection cannot be made, as
 * [IpcAuthClient.connect] does; an I/O failure after that is [IpcReply.NotWritten] or [IpcReply.Closed].
 */
internal fun ipcRequest(
    endpoint: IpcEndpoint,
    scope: IpcAuth.Scope,
    line: String,
    timeoutMs: Long,
): IpcReply =
    IpcAuthClient.connect(endpoint, scope).use { channel ->
        // connect leaves the channel blocking; a blocking read cannot give up at a deadline.
        channel.configureBlocking(false)
        val deadline = System.currentTimeMillis() + timeoutMs
        val lines = IpcLineClient(channel)
        val written =
            try {
                lines.writeLine(line, deadline)
            } catch (_: IOException) {
                false
            }
        if (!written) return IpcReply.NotWritten
        try {
            when (val read = lines.readLine(deadline)) {
                is IpcLineRead.Line -> IpcReply.Line(read.text)
                IpcLineRead.Closed -> IpcReply.Closed
                IpcLineRead.TimedOut -> IpcReply.TimedOut
            }
        } catch (_: IOException) {
            // A daemon closing its sockets can reset the connection before its reply is read (notably on Windows).
            IpcReply.Closed
        }
    }

/** The daemon accepted the connection but did not answer within [timeoutMs]. */
internal class IpcTimeoutException(
    val timeoutMs: Long,
) : IOException("the daemon did not answer within ${(timeoutMs + 999) / 1000} s")
