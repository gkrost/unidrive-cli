package org.krost.unidrive.cli

import org.krost.unidrive.sync.IpcEndpoint
import org.krost.unidrive.sync.IpcServer
import org.krost.unidrive.sync.SyncConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal fun awaitDeployedProcessExit(
    handle: ProcessHandle,
    timeoutMs: Long,
    exit: java.util.concurrent.CompletableFuture<ProcessHandle>,
): Boolean =
    try {
        exit.get(timeoutMs, TimeUnit.MILLISECONDS)
        !handle.isAlive
    } catch (_: TimeoutException) {
        false
    }

/**
 * #1194: the running engine processes a `deploy` must stop before it overwrites the jar. `ProcessHandle`'s command
 * line reads empty for them on Windows (measured: the old kill step matched nothing while two daemons held the jar,
 * and the copy failed on the lock), so the holders are found by their profiles' `.lock.pid` sidecars instead: every
 * live lock holder under the config dir is stopped — a daemon gracefully through the `daemon.shutdown` verb (clean
 * state.db close, socket cleanup), anything else (a sync, which has no IPC listener) by a forceful destroy, the same
 * last resort `daemon stop` uses. The deploy task loads this class from the freshly built jar and calls [stopAll].
 */
internal class DeployedProcessStopper(
    private val profileDirs: () -> List<Path>,
    private val lockPid: (Path) -> LockPidReadResult = ::readLockPid,
    private val holderAlive: (pid: Long, pidFile: Path) -> Boolean,
    private val requestShutdown: (IpcEndpoint) -> Boolean,
    private val awaitExit: (pid: Long, timeoutMs: Long) -> Boolean,
    private val destroyForcibly: (pid: Long) -> Unit,
) {
    /** One line per profile it acted on; empty when nothing held a profile. */
    fun stopAll(): List<String> = profileDirs().mapNotNull { dir -> dir.fileName?.toString()?.let { stopProfile(dir, it) } }

    private fun stopProfile(
        dir: Path,
        name: String,
    ): String? {
        val pidFile = dir.resolve(".lock.pid")
        return when (val lock = lockPid(pidFile)) {
            is LockPidReadResult.Absent -> null
            is LockPidReadResult.Malformed -> "profile '$name': unreadable .lock.pid ('${lock.raw}'); stop it by hand if it runs"
            is LockPidReadResult.Present ->
                if (!holderAlive(lock.contents.pid, pidFile)) null else stopHolder(dir, name, lock.contents)
        }
    }

    private fun stopHolder(
        dir: Path,
        name: String,
        holder: LockPidContents,
    ): String {
        val pid = holder.pid
        if (holder.modeToken == "daemon") {
            val outcome =
                stopDaemonProcess(
                    requestShutdown = { requestShutdown(IpcEndpoint(IpcServer.defaultSocketPath(name), dir, name)) },
                    awaitExit = { timeoutMs -> awaitExit(pid, timeoutMs) },
                    destroy = { destroyForcibly(pid) },
                    gracefulDeadlineMs = GRACEFUL_DEADLINE_MS,
                    forcedDeadlineMs = FORCED_DEADLINE_MS,
                )
            return when (outcome) {
                DaemonStopOutcome.GRACEFUL -> "stopped the daemon of '$name' (pid $pid) over IPC"
                DaemonStopOutcome.FORCED -> "terminated the daemon of '$name' (pid $pid): it did not stop gracefully"
                DaemonStopOutcome.FAILED -> "could NOT stop the daemon of '$name' (pid $pid); run `unidrive -p $name daemon stop` and re-deploy"
            }
        }
        val what = holder.modeToken ?: "process"
        destroyForcibly(pid)
        return if (awaitExit(pid, FORCED_DEADLINE_MS)) {
            "terminated the $what of '$name' (pid $pid)"
        } else {
            "could NOT stop the $what of '$name' (pid $pid); stop it by hand and re-deploy"
        }
    }

    companion object {
        private const val GRACEFUL_DEADLINE_MS: Long = 12_000

        private const val FORCED_DEADLINE_MS: Long = 5_000

        /** The production wiring for the config dir the engine itself resolves (UNIDRIVE_CONFIG_DIR, XDG, APPDATA). */
        @JvmStatic
        fun stopAllInDefaultConfigDir(): List<String> = stopAll(SyncConfig.defaultConfigDir())

        /** The production wiring: every profile directory under [configDir]. */
        @JvmStatic
        fun stopAll(configDir: Path): List<String> {
            // Keep the handle captured during validation. Looking the PID up again after a 12-second grace period
            // could target an unrelated process that has since inherited it.
            val holders = mutableMapOf<Long, ProcessHandle>()
            return DeployedProcessStopper(
                profileDirs = {
                    if (Files.isDirectory(configDir)) {
                        Files.list(configDir).use { stream -> stream.filter { Files.isDirectory(it) }.toList() }
                    } else {
                        emptyList()
                    }
                },
                holderAlive = { pid, pidFile ->
                    val handle = ProcessHandle.of(pid).orElse(null)
                    if (handle != null && handle.isAlive && isLockHolderAlive(pid, pidFile)) {
                        holders[pid] = handle
                        true
                    } else {
                        false
                    }
                },
                requestShutdown = { endpoint -> requestDaemonShutdown(endpoint) },
                awaitExit = { pid, timeoutMs -> holders[pid]?.let { awaitDeployedProcessExit(it, timeoutMs, it.onExit()) } ?: true },
                destroyForcibly = { pid -> holders[pid]?.takeIf { it.isAlive }?.destroyForcibly() },
            ).stopAll()
        }
    }
}
