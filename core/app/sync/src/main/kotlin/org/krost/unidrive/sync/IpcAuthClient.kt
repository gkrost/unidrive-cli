package org.krost.unidrive.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.IOException
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** Where a profile's daemon listens and where its IPC token files are (the profile's config folder). */
data class IpcEndpoint(
    val socketPath: Path,
    val tokenDir: Path,
    val profileName: String,
)

/** The IPC handshake failed or the daemon could not be authenticated; the connection is closed. */
class IpcAuthException(
    message: String,
) : IOException(message)

/**
 * The client side of the IPC handshake (docs/dev/specs/ipc-authentication.md) for the engine's own
 * commands (`daemon status`, `daemon stop`, `ls`, `refresh`).
 *
 * [connect] reads the token file of the requested scope afresh at every call (a restarted daemon has
 * new tokens), runs `hello` / `hello.proof`, checks the daemon's proof with a constant-time comparison
 * and returns the authenticated connection in blocking mode. It fails closed with
 * [IpcAuthException]: a refused handshake is terminal (no retry), a closed connection fails at once,
 * and a daemon that cannot show the proof is not talked to.
 *
 * Without a token file nothing is sent to the daemon and the connection fails with [IpcAuthException]:
 * every unidrive daemon of this version issues its token files at start, so a missing file means the
 * socket does not belong to the daemon of this profile (or to an engine without IPC authentication).
 * There is no unauthenticated (protocol-1) fallback. A socket nobody listens on still fails as a plain
 * [IOException], as before.
 */
object IpcAuthClient {
    const val CLI_CLIENT: String = "unidrive-cli"
    const val HANDSHAKE_TIMEOUT_MS: Long = 5_000

    fun connect(
        endpoint: IpcEndpoint,
        scope: IpcAuth.Scope,
        client: String = CLI_CLIENT,
        timeoutMs: Long = HANDSHAKE_TIMEOUT_MS,
        // Test seam: the client nonce (the fixed test vectors use 00..0f).
        clientNonce: () -> ByteArray = { IpcAuth.randomBytes(IpcAuth.NONCE_BYTES) },
    ): SocketChannel {
        val tokenFile = IpcAuth.tokenFile(endpoint.tokenDir, scope)
        val key = readKeyOrNull(tokenFile)
        // Opened first so that a daemon that is not running still reads as an unreachable socket (an
        // IOException) to the callers; without a token nothing is sent on it.
        val channel = SocketChannel.open(UnixDomainSocketAddress.of(endpoint.socketPath))
        try {
            val token = requireToken(key, tokenFile)
            channel.configureBlocking(false)
            val deadline = System.currentTimeMillis() + timeoutMs
            handshake(channel, token, scope, endpoint.profileName, client, IpcAuth.encode(clientNonce()), tokenFile, deadline)
            channel.configureBlocking(true)
            return channel
        } catch (e: Throwable) {
            runCatching { channel.close() }
            if (e is IOException && e !is IpcAuthException) throw IpcAuthException("the IPC handshake failed: ${e.message}")
            throw e
        }
    }

    // Fails closed without a token: there is no unauthenticated (protocol-1) fallback.
    private fun requireToken(
        key: ByteArray?,
        tokenFile: Path,
    ): ByteArray =
        key ?: throw IpcAuthException(
            "no IPC token at $tokenFile: the daemon on this socket did not issue one (it is not the unidrive daemon of this " +
                "profile, or an engine without IPC authentication); restart the daemon",
        )

    /** The token, or null when the file does not exist; any other problem fails closed. */
    private fun readKeyOrNull(tokenFile: Path): ByteArray? =
        try {
            IpcAuth.readToken(tokenFile)
        } catch (_: NoSuchFileException) {
            if (Files.exists(tokenFile)) throw IpcAuthException("cannot read the IPC token file $tokenFile")
            null
        } catch (e: IOException) {
            throw IpcAuthException("cannot read the IPC token file $tokenFile: ${e.message}")
        }

    private fun handshake(
        channel: SocketChannel,
        key: ByteArray,
        scope: IpcAuth.Scope,
        profile: String,
        client: String,
        clientNonce: String,
        tokenFile: Path,
        deadline: Long,
    ) {
        val hello =
            """{"verb":"hello","protocol":${IpcAuth.PROTOCOL_VERSION},"scope":"${scope.wire}","client":${JsonPrimitive(client)},"nonce":"$clientNonce"}"""
        val step1 = request(channel, hello, deadline)
        val serverNonce = step1.string("snonce")
        if (step1.isOk() && step1.int("step") == 1 && serverNonce != null && IpcAuth.decode(serverNonce)?.size == IpcAuth.NONCE_BYTES) {
            val proof = IpcAuth.clientProof(key, scope.wire, profile, clientNonce, serverNonce)
            val step2 = request(channel, """{"verb":"hello.proof","proof":"$proof"}""", deadline)
            if (!step2.isOk()) throw refused(step2)
            val expected = IpcAuth.serverProof(key, scope.wire, profile, clientNonce, serverNonce, proof)
            val serverProof = step2.string("proof") ?: ""
            if (step2.string("scope") != scope.wire || !IpcAuth.constantTimeEquals(expected, serverProof)) {
                throw IpcAuthException(
                    "the daemon's proof does not match the IPC token; the socket may not belong to the unidrive daemon of this profile",
                )
            }
            return
        }
        if (step1.string("error") == "unknown_verb") {
            throw IpcAuthException(
                "the daemon does not support IPC authentication, but $tokenFile exists; restart the daemon",
            )
        }
        throw refused(step1)
    }

    private fun refused(reply: JsonObject): IpcAuthException {
        val error = reply.string("error")
        return if (error == "auth_failed") {
            IpcAuthException("the daemon rejected the IPC token (auth_failed); restart the daemon or the client that reads its token")
        } else {
            IpcAuthException("unexpected reply to the IPC handshake: ${error ?: "no error token"}")
        }
    }

    // One request line out, one reply line back, within [deadline]. The daemon answers nothing else on a
    // connection before it is authenticated, so no bytes may follow the reply line.
    private fun request(
        channel: SocketChannel,
        line: String,
        deadline: Long,
    ): JsonObject {
        val lines = IpcLineClient(channel, MAX_REPLY_BYTES)
        if (!lines.writeLine(line, deadline)) throw IpcAuthException("the daemon did not take the IPC handshake in time")
        val text =
            try {
                when (val read = lines.readLine(deadline)) {
                    is IpcLineRead.Line -> read.text
                    IpcLineRead.Closed -> throw IpcAuthException("the daemon closed the connection during the IPC handshake")
                    IpcLineRead.TimedOut -> throw IpcAuthException("no reply to the IPC handshake in time")
                }
            } catch (_: IpcLineTooLongException) {
                throw IpcAuthException("the reply to the IPC handshake is too long")
            }
        if (lines.pendingBytes() > 0) throw IpcAuthException("unexpected data after the reply to the IPC handshake")
        return runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: throw IpcAuthException("the reply to the IPC handshake is not a JSON object")
    }

    private const val MAX_REPLY_BYTES = 64 * 1024

    private fun JsonObject.isOk(): Boolean = (this["ok"] as? JsonPrimitive)?.content == "true"

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
}
