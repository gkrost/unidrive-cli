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
 * no `setsid` on the PATH the child starts as before. On Windows stdin is the null device (`NUL`) as well, and every
 * standard handle goes to a file or the null device: the JDK starts a child with CREATE_NO_WINDOW only while none of its
 * standard handles is the parent's own (ProcessImpl_md.c), and with that flag the child gets a console of its own, so a
 * Ctrl+C or the closing of the client's console does not reach it. A client that runs inside a job object that kills its
 * members on close would still take the daemon with it (that needs CREATE_BREAKAWAY_FROM_JOB, which the JDK does not set).
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
    pb.redirectInput(ProcessBuilder.Redirect.from(nullDevice(windows)))
    return pb.start()
}

/** The null device of the platform: what the detached child reads as stdin. */
internal fun nullDevice(windows: Boolean): java.io.File = java.io.File(if (windows) "NUL" else "/dev/null")

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
