package org.krost.unidrive.cli

import org.krost.unidrive.sync.ProcessLock

/**
 * The lines printed when [acquiring] cannot take the profile lock because [holder] already has it (#85).
 * [windows] selects `taskkill` or `kill`. `unidrive sync` and the daemon both print this list.
 */
internal fun lockContentionLines(
    holder: ProcessLock.HolderInfo?,
    profile: String,
    acquiring: ProcessLock.Mode,
    windows: Boolean,
): List<String> {
    val holderDesc =
        when {
            holder?.mode == ProcessLock.Mode.SYNC ->
                "Another `unidrive sync` is running for profile '$profile'"
            holder?.mode == ProcessLock.Mode.DAEMON && acquiring == ProcessLock.Mode.DAEMON ->
                "Another `unidrive daemon` already serves profile '$profile'"
            holder?.mode == ProcessLock.Mode.DAEMON ->
                "Profile '$profile' is currently in use by `unidrive daemon`"
            holder != null && holder.mode == null && holder.rawMode != null ->
                "Profile '$profile' is held by an unidrive process running in " +
                    "unknown mode '${holder.rawMode}' (this binary may be older than the holder)"
            else ->
                "Another unidrive process is using profile '$profile'"
        }
    val pidPart = if (holder != null) " (PID ${holder.pid})" else ""
    val lines = mutableListOf("$holderDesc$pidPart.")
    when {
        acquiring == ProcessLock.Mode.SYNC && holder?.mode == ProcessLock.Mode.DAEMON ->
            lines += "Stop the daemon first: `unidrive -p $profile daemon stop`."
        acquiring == ProcessLock.Mode.SYNC && holder != null ->
            lines += "Stop it with `${stopCommand(holder.pid, windows)}`, or wait for it to finish."
        acquiring == ProcessLock.Mode.SYNC ->
            lines += "Stop it first, or wait for it to finish."
        acquiring == ProcessLock.Mode.DAEMON && holder?.mode == ProcessLock.Mode.SYNC -> {
            lines += "Stop the sync watcher first: `${stopCommand(holder.pid, windows)}` (or Ctrl-C its terminal)."
            lines +=
                "Sync and daemon are mutually exclusive per profile " +
                "(see docs/dev/specs/unidrive-daemon-design.md)."
        }
        acquiring == ProcessLock.Mode.DAEMON && holder?.mode == ProcessLock.Mode.DAEMON ->
            lines += "Stop the running daemon first: `unidrive -p $profile daemon stop`."
        acquiring == ProcessLock.Mode.DAEMON && holder != null ->
            lines += "Stop it with `${stopCommand(holder.pid, windows)}`, or wait for it to exit."
    }
    return lines
}

private fun stopCommand(
    pid: Long,
    windows: Boolean,
): String = if (windows) "taskkill /PID $pid /F" else "kill $pid"
