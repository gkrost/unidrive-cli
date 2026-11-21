package org.krost.unidrive.cli

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.krost.unidrive.io.ndjsonString
import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcServer
import org.krost.unidrive.sync.ProfileMode
import org.krost.unidrive.telemetry.TransferHistory
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * The IPC surface a daemon serves before its provider exists (issue#796): the auth + socket come
 * up ahead of the provider build, the pre-provider verbs register, and a locked start binds and
 * waits for `vault.unlock` here. `daemon run` calls it from the lifecycle before the provider
 * tail; everything it returns feeds that tail.
 */
internal class PreProviderIpc(
    val server: IpcServer,
    /** The SupervisorJob of [serveScope]; the tail's finally cancels and joins it. */
    val serveJob: Job,
    val serveScope: CoroutineScope,
    val vaultRpc: VaultRpcHandler,
    val hydrationLink: DaemonHydrationLink,
    var transferHistory: TransferHistory?,
    var historyRpc: HistoryRpcHandler?,
    /** True when a locked start already bound the socket (the tail must not bind again). */
    val socketBoundEarly: Boolean,
    /**
     * True when the daemon was shut down while still locked (issue#796 review): the lifecycle must
     * not run its provider tail — it goes straight to cleanup, which the outer finally runs.
     */
    val shutDownWhileLocked: Boolean,
)

private val preProviderLog = LoggerFactory.getLogger(DaemonRuntime::class.java)

/**
 * Issues this start's IPC tokens (only the lock holder gets here, so a refused second start never
 * rotates a running daemon's tokens), creates the server, registers the verb set that exists
 * before the provider does — `daemon.status` delegates (the locked shape until the unlocked tail
 * swaps itself in), the vault verbs answer per gate phase (`vault_absent` on a daemon without
 * vault.enc), `daemon.shutdown` is provider-free — and, on a locked start, binds the socket and
 * waits for `vault.unlock` before returning: the provider tail (and with it every profile that
 * reads vaulted fields) runs when the verb answers.
 */
/**
 * The lifecycle seams of the pre-provider IPC: where the server registers itself, what the
 * delegating `daemon.status` answers before the tail swaps in, the start instant, and the close.
 */
internal class PreProviderHooks(
    val registerIpcServer: (IpcServer) -> Unit,
    val statusHandler: () -> (suspend () -> String)?,
    val startedAtMs: () -> Long,
    val setStartedAtMs: (Long) -> Unit,
    val close: () -> Unit,
    /** Suspends until the daemon's shutdown was requested (`daemon.shutdown` or a stop). */
    val awaitShutdown: suspend () -> Unit,
)

internal suspend fun servePreProviderIpc(
    ipcTokenDir: Path,
    profileName: String,
    socketPath: Path,
    profileMode: ProfileMode,
    vault: DaemonVault?,
    hooks: PreProviderHooks,
): PreProviderIpc {
    val ipcAuth = IpcAuth.issueOrReport(ipcTokenDir, profileName, BuildInfo.versionString())

    Files.createDirectories(socketPath.parent)
    val server = IpcServer(socketPath, auth = ipcAuth)
    hooks.registerIpcServer(server)

    // Use supervisorScope-with-explicit-cancel so the SERVE block returns
    // promptly on close(). A plain coroutineScope would wait for the
    // hydration.events.collect launch (infinite flow) and any handler
    // children spawned by IpcServer/HydrationIpcHandler to complete —
    // they don't. Solution: launch the long-lived children under a
    // dedicated SupervisorJob that we cancel before the scope exits.
    // The scope inherits the daemon's logContext from start() (gkrost/unidrive-cli#225).
    val serveJob = SupervisorJob()
    val serveScope = DaemonRuntime.serveScopeOf(kotlin.coroutines.coroutineContext, serveJob)
    // The engine's two hooks into the hydration layer (see DaemonHydrationLink). Declared outside the
    // tail's try: its finally joins the hydration uploads through it.
    val hydrationLink = DaemonHydrationLink()
    // archive#652: opened with the mount's hydration layer, closed (flushed) after its uploads have been joined.
    var transferHistory: TransferHistory? = null
    var historyRpc: HistoryRpcHandler? = null
    // Coroutine-debug probes (archive#613 ask 1): with UNIDRIVE_COROUTINE_DEBUG=1 the
    // daemon captures suspension stacks at start and dumps every live coroutine when a
    // coroutine-dump.trigger file appears in the profile folder — a hung upload becomes one
    // stack trace instead of a silent wait. Off by default; see CoroutineDebug.
    if (CoroutineDebug.enabled()) {
        CoroutineDebug.install()
        CoroutineDebug.watchForDumpRequests(ipcTokenDir, serveScope)
    }

    val vaultRpc = VaultRpcHandler(vault)
    server.registerHandler("daemon.status") { _, _ ->
        hooks.statusHandler()?.invoke()
            ?: lockedDaemonStatusJson(
                modeJson = ndjsonString(profileMode.wireName),
                capabilitiesJson = lockedCapabilitiesJson(profileMode),
                uptimeMs = System.currentTimeMillis() - hooks.startedAtMs(),
                clientsConnected = server.clientCount,
                ipcJson = ipcStatusJson(server.requestCounts()).toString(),
            )
    }
    server.registerHandler("vault.unlock") { _, json -> vaultRpc.unlockVerb(json) }
    server.registerHandler("vault.lock") { _, _ -> vaultRpc.lockVerb() }
    server.registerHandler("daemon.shutdown") { connId, _ ->
        server.scheduleAfterReply(connId) { hooks.close() }
        """{"ok":true}"""
    }

    var socketBoundEarly = false
    var shutDownWhileLocked = false
    val gate = vault?.gate
    if (gate != null && gate.locked) {
        // issue#796 (decision D6): a locked start serves the locked status and waits for
        // vault.unlock; the provider tail runs when the verb answers. The reason is the one line
        // in the start log a front-end reads. The wait also ends on the daemon's shutdown — a
        // still-locked daemon must honor daemon.shutdown (issue#796 review): it holds the profile
        // lock, the state database and the socket, and none of those may outlive the ack.
        server.start(serveScope)
        socketBoundEarly = true
        hooks.setStartedAtMs(System.currentTimeMillis())
        System.err.println("daemon locked, waiting for vault.unlock: ${gate.startReason}")
        preProviderLog.info("daemon: started locked ({}); profiles that read vaulted fields wait for vault.unlock", gate.startReason)
        val unlockArrived = CompletableDeferred<Unit>()
        val shutdownArrived = CompletableDeferred<Unit>()
        serveScope.launch { gate.awaitUnlock(); unlockArrived.complete(Unit) }
        serveScope.launch { hooks.awaitShutdown(); shutdownArrived.complete(Unit) }
        shutDownWhileLocked =
            kotlinx.coroutines.selects.select {
                unlockArrived.onAwait { false }
                shutdownArrived.onAwait { true }
            }
        if (shutDownWhileLocked) preProviderLog.info("daemon: shutting down while locked")
    }
    return PreProviderIpc(
        server, serveJob, serveScope, vaultRpc, hydrationLink, transferHistory, historyRpc, socketBoundEarly, shutDownWhileLocked,
    )
}
