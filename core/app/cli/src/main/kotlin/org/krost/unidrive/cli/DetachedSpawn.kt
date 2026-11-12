package org.krost.unidrive.cli

import org.krost.unidrive.io.Os
import java.nio.file.Files
import java.nio.file.Path

/**
 * gkrost/unidrive-cli#455: starts [command] so that it outlives the client's terminal. Redirecting the output alone does
 * not: the child stays in the terminal's foreground process group, so a Ctrl+C (SIGINT) or closing the terminal (SIGHUP)
 * stopped the daemon with the client. Off Windows the child runs under `setsid` (a session of its own, no controlling
 * terminal) and reads nothing from the client: stdin is the null device. `setsid` execs the command in the same process
 * (the child is no process group leader), so the returned [Process] is the daemon and its exit code is the daemon's. With
 * no `setsid` on the PATH the child starts as before. On Windows the JDK already starts a child without the parent's
 * console (CREATE_NO_WINDOW), so a Ctrl+C in that console does not reach it.
 */
internal fun startDetached(
    command: List<String>,
    logFile: Path,
    windows: Boolean = Os.isWindows(),
    setsid: () -> Path? = ::setsidOnPath,
): Process {
    Files.createDirectories(logFile.parent)
    val pb = ProcessBuilder(detachedCommand(command, windows, setsid))
    pb.redirectErrorStream(true)
    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
    if (!windows) pb.redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
    return pb.start()
}

/** [command] prefixed with `setsid` off Windows when there is one; unchanged otherwise. */
internal fun detachedCommand(
    command: List<String>,
    windows: Boolean,
    setsid: () -> Path?,
): List<String> {
    if (windows) return command
    val tool = setsid() ?: return command
    return listOf(tool.toString()) + command
}

/** The `setsid` executable on the PATH, or null. */
private fun setsidOnPath(): Path? =
    System.getenv("PATH").orEmpty()
        .split(java.io.File.pathSeparatorChar)
        .filter { it.isNotBlank() }
        .map { Path.of(it, "setsid") }
        .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
