package org.krost.unidrive.cli

import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcAuthClient
import org.krost.unidrive.sync.IpcAuthException
import org.krost.unidrive.sync.IpcEndpoint
import org.krost.unidrive.sync.IpcServer
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
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
            IpcAuthClient.connect(endpoint, IpcAuth.Scope.FULL).use { channel ->
                // Subscribe to progress events
                channel.write(ByteBuffer.wrap(("""{"verb":"sync.subscribe"}""" + "\n").toByteArray()))
                readOneJsonReply(channel)  // discard sync.subscribe reply

                // Issue refresh.run (with optional reset flag — F9 fix).
                val refreshReq = if (reset) {
                    """{"verb":"refresh.run","reset":true}"""
                } else {
                    """{"verb":"refresh.run"}"""
                }
                channel.write(ByteBuffer.wrap((refreshReq + "\n").toByteArray()))
                val runReply = readOneJsonReply(channel)
                if (!runReply.contains("\"ok\":true")) {
                    System.err.println("unidrive refresh: daemon rejected refresh.run: $runReply")
                    System.exit(1)
                    return
                }

                // Stream events until refresh.done
                val collected = StringBuilder()
                while (!collected.contains("\"event\":\"refresh.done\"")) {
                    val buf = ByteBuffer.allocate(4096)
                    val n = channel.read(buf)
                    if (n <= 0) {
                        // EOF — daemon shut down per spec F9
                        System.err.println("unidrive refresh: daemon shut down before refresh completed")
                        System.exit(1)
                        return
                    }
                    buf.flip()
                    val chunk = String(buf.array(), 0, buf.limit())
                    collected.append(chunk)
                    // Print progress events as they arrive (one per line)
                    chunk.lineSequence().filter { it.isNotBlank() }.forEach { println(it) }
                }

                // Check terminal event for failure
                if (collected.contains("\"event\":\"refresh.done\"") && !collected.contains("\"ok\":true")) {
                    // archive#1037: a reset that met a running enumeration (the daemon's own poll, say) did not run.
                    if (collected.contains("\"error\":\"busy\"")) {
                        System.err.println("unidrive refresh: a refresh is already running, so nothing was reset; try again when it has finished.")
                    }
                    System.exit(1)
                }
            }
        } catch (e: IpcAuthException) {
            System.err.println("unidrive refresh: ${e.message}")
            System.exit(1)
        } catch (e: java.io.IOException) {
            System.err.println("unidrive refresh: failed to communicate with daemon: ${e.message}")
            System.err.println("Daemon may have crashed. Restart with: `unidrive -p ${profile.name} daemon run`.")
            System.exit(1)
        }
    }

    /** Read a single JSON reply terminated by newline. Returns the line without the newline. */
    private fun readOneJsonReply(channel: SocketChannel): String {
        val collected = StringBuilder()
        while (!collected.contains('\n')) {
            val buf = ByteBuffer.allocate(1024)
            val n = channel.read(buf)
            if (n <= 0) return collected.toString()
            buf.flip()
            collected.append(String(buf.array(), 0, buf.limit()))
        }
        return collected.toString().substringBefore('\n')
    }
}
