package org.krost.unidrive.cli

import kotlinx.serialization.json.JsonPrimitive
import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcAuthClient
import org.krost.unidrive.sync.IpcAuthException
import org.krost.unidrive.sync.IpcEndpoint
import org.krost.unidrive.sync.IpcLineClient
import org.krost.unidrive.sync.IpcLineRead
import org.krost.unidrive.sync.IpcServer
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import java.nio.channels.ByteChannel
import java.nio.file.Files

/**
 * `unidrive refresh` — thin client of the daemon's `refresh.run` IPC verb.
 *
 * Per spec docs/dev/specs/unidrive-daemon-design.md §3.3 + NG8: the pre-spec
 * standalone-JVM refresh path is removed. The daemon (`unidrive daemon run`)
 * must be running; this command connects to its socket, issues sync.subscribe,
 * then refresh.run, then streams progress events until the refresh.done
 * terminal event.
 *
 * Migration for cron jobs that called `unidrive refresh` standalone:
 *   unidrive daemon run <profile> & sleep 2 && unidrive refresh <profile> && unidrive daemon stop <profile>
 * Or move to a systemd-user-unit when phase-3 (BACKLOG follow-up) ships.
 */
@Command(
    name = "refresh",
    description = [
        "Update state.db with remote changes via the running daemon's refresh.run RPC. " +
            "Resumes from the last sync cursor; use --reset to re-enumerate from scratch.",
    ],
    mixinStandardHelpOptions = true,
)
class RefreshCommand : Runnable, CommandLine.IExitCodeGenerator {
    /** 1 when a `--reset` was refused, declined or could not copy state.db aside (#100); the other failures exit the JVM. */
    private var refusedExitCode: Int = 0

    override fun getExitCode(): Int = refusedExitCode

    @ParentCommand
    lateinit var parent: Main

    @Parameters(
        index = "0",
        arity = "0..1",
        paramLabel = "<profile>",
        description = ["Profile name (alternative to the global -p option)"],
    )
    var profilePositional: String? = null

    @Option(
        names = ["--reset"],
        description = [
            "Clear state.db on the daemon and re-enumerate the full cloud tree. " +
                "Recovers from poisoned delta cursors (e.g. when the cloud was modified " +
                "while sync was offline and the cursor returned only the recent delta). " +
                "Asks first (--yes for scripts); state.db is copied aside first, as " +
                "state-before-reset-<UTC time>.db next to it.",
        ],
    )
    var reset: Boolean = false

    @Option(names = ["-y", "--yes"], description = ["With --reset: do not ask first (state.db is still copied aside)"])
    var yes: Boolean = false

    override fun run() {
        applyPositionalProfile(parent, profilePositional)
        val profile = parent.resolveCurrentProfile()
        val socketPath = IpcServer.defaultSocketPath(profile.name)

        // #100: asked before anything else, so a refused or declined reset starts no daemon either.
        if (reset) {
            StateReset.refusal("refresh", profile.name, yes)?.let {
                System.err.println("unidrive refresh: $it")
                refusedExitCode = 1
                return
            }
        }

        // archive#142: the first client connection starts the profile's daemon — `unidrive refresh` works on a
        // fresh machine without the operator starting a daemon by hand. A stale socket file (a killed
        // daemon left it behind) also lands here: the probe connects before it believes.
        DaemonAutospawn.ensureDaemonRunning(
            profileName = profile.name,
            configDir = parent.providerConfigDir(),
        )
        if (!Files.exists(socketPath)) {
            System.err.println(
                "unidrive refresh: daemon for profile '${profile.name}' is not running.",
            )
            System.err.println(
                "Start it first: `unidrive -p ${profile.name} daemon run` (in another terminal).",
            )
            System.exit(1)
            return
        }

        // #100: the daemon clears state.db when it runs the reset; the copy is taken just before the request, through
        // SQLite, so it is consistent while the daemon writes. Stderr: stdout carries the daemon's JSON events.
        if (reset) {
            val backup =
                try {
                    StateReset.backup(parent.providerConfigDir().resolve("state.db"))
                } catch (e: java.io.IOException) {
                    System.err.println("unidrive refresh: state.db could not be copied aside (${e.message}); nothing was reset.")
                    refusedExitCode = 1
                    return
                }
            if (backup != null) System.err.println("unidrive refresh: state.db copied to $backup before the reset.")
        }

        try {
            // refresh.run is an admin verb: a full-scope connection (docs/dev/specs/ipc-authentication.md).
            val endpoint = IpcEndpoint(socketPath, parent.providerConfigDir(), profile.name)
            val exit =
                IpcAuthClient.connect(endpoint, IpcAuth.Scope.FULL).use { channel ->
                    runRefresh(channel, reset, { println(it) }, { System.err.println(it) })
                }
            if (exit != 0) System.exit(exit)
        } catch (e: IpcAuthException) {
            System.err.println("unidrive refresh: ${e.message}")
            System.exit(1)
        } catch (e: java.io.IOException) {
            System.err.println("unidrive refresh: failed to communicate with daemon: ${e.message}")
            System.err.println("Daemon may have crashed. Restart with: `unidrive -p ${profile.name} daemon run`.")
            System.exit(1)
        }
    }

}


/**
 * The refresh exchange on an authenticated connection: subscribe, `refresh.run`, then the events up to `refresh.done`.
 * The event lines go to [out], whole and as the daemon wrote them, and the problems to [err]; the result is the exit
 * code, 0 only when the `refresh.done` line says ok.
 *
 * The daemon may push an event in the same write as a reply, so one [IpcLineClient] reads the whole exchange: what
 * comes after a reply line is the next line, not lost. A line with an `event` member is an event; the first line
 * without one, after the request, is its reply.
 */
internal fun runRefresh(
    channel: ByteChannel,
    reset: Boolean,
    out: (String) -> Unit,
    err: (String) -> Unit,
): Int {
    val lines = IpcLineClient(channel)

    // Subscribe to progress events. Nothing of ours runs yet, so an event before the reply is not ours to print.
    lines.writeLine("""{"verb":"sync.subscribe"}""")
    do {
        val line = (lines.readLine() as? IpcLineRead.Line)?.text ?: return daemonClosed(err)
    } while (line.asJsonObjectOrNull()?.containsKey("event") == true)

    // Issue refresh.run (with optional reset flag — F9 fix).
    lines.writeLine(if (reset) """{"verb":"refresh.run","reset":true}""" else """{"verb":"refresh.run"}""")
    var replied = false
    while (true) {
        val line = (lines.readLine() as? IpcLineRead.Line)?.text ?: return daemonClosed(err)
        val json = line.asJsonObjectOrNull()
        if (json == null || !json.containsKey("event")) {
            if (replied) continue
            replied = true
            if (json?.isOkReply() != true) {
                err("unidrive refresh: daemon rejected refresh.run: $line")
                return 1
            }
            continue
        }
        // Print progress events as they arrive (one per line)
        out(line)
        if ((json["event"] as? JsonPrimitive)?.content != "refresh.done") continue
        if (json.isOkReply()) return 0
        // archive#1037: a reset that met a running enumeration (the daemon's own poll, say) did not run.
        if ((json["error"] as? JsonPrimitive)?.content == "busy") {
            err("unidrive refresh: a refresh is already running, so nothing was reset; try again when it has finished.")
        }
        return 1
    }
}

// EOF — daemon shut down per spec F9
private fun daemonClosed(err: (String) -> Unit): Int {
    err("unidrive refresh: daemon shut down before refresh completed")
    return 1
}
