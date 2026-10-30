package org.krost.unidrive.cli

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcEndpoint
import org.krost.unidrive.sync.IpcServer
import java.nio.file.Files
import java.nio.file.Path

/**
 * A real, authenticated [IpcServer] with only the verbs a test registers: the commands under test connect through
 * the engine's own client, so the handshake, the scopes and the line framing are the daemon's. The handlers return
 * the reply line; what a request looked like is theirs to record.
 */
internal class FakeIpcDaemon(
    private val profile: String = "fake_profile",
) : AutoCloseable {
    private val dir: Path = Files.createTempDirectory("fake-ipc-daemon")
    private val profileDir: Path = Files.createDirectories(dir.resolve("p"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val server: IpcServer
    val endpoint: IpcEndpoint

    init {
        val socketPath = dir.resolve("d.sock")
        val auth = IpcAuth.issue(profileDir, profile, "test-engine")
        server = IpcServer(socketPath, auth = auth)
        endpoint = IpcEndpoint(socketPath, profileDir, profile)
    }

    fun on(
        verb: String,
        handler: suspend (connectionId: String, request: String) -> String,
    ): FakeIpcDaemon {
        server.registerHandler(verb, handler)
        return this
    }

    fun start(): FakeIpcDaemon {
        server.start(scope)
        return this
    }

    override fun close() {
        runCatching { server.close() }
        scope.cancel()
        dir.toFile().deleteRecursively()
    }
}
