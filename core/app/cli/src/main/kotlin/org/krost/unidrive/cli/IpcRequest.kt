package org.krost.unidrive.cli

import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcAuthClient
import org.krost.unidrive.sync.IpcEndpoint
import org.krost.unidrive.sync.IpcLineClient
import org.krost.unidrive.sync.IpcLineRead
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What [ipcRequest] came back with. */
internal sealed interface IpcReply {
    /** The first line the daemon wrote after the request. */
    data class Line(
        val text: String,
    ) : IpcReply

    /** The daemon closed the connection without a reply line. */
    data object Closed : IpcReply

    /** No whole reply line arrived within the deadline (or the request could not be written within it). */
    data object TimedOut : IpcReply
}

/**
 * One request and its reply line on a fresh authenticated connection, bounded by [timeoutMs] from the moment the
 * handshake is done: a daemon that is stuck answers [IpcReply.TimedOut] instead of holding its client forever. Throws
 * [org.krost.unidrive.sync.IpcAuthException] and [IOException], as [IpcAuthClient.connect] does.
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
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val lines = IpcLineClient(channel)
        if (!lines.writeLine(line, deadline)) return IpcReply.TimedOut
        when (val read = lines.readLine(deadline)) {
            is IpcLineRead.Line -> IpcReply.Line(read.text)
            IpcLineRead.Closed -> IpcReply.Closed
            IpcLineRead.TimedOut -> IpcReply.TimedOut
        }
    }

/** The daemon accepted the connection but did not answer within [timeoutMs]. */
internal class IpcTimeoutException(
    val timeoutMs: Long,
) : IOException("the daemon did not answer within ${(timeoutMs + 999) / 1000} s")
