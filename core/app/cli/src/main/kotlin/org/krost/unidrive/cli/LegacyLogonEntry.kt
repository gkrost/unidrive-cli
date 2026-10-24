package org.krost.unidrive.cli

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The Windows logon entry an earlier `:app:cli:deploy` wrote: `unidrive.vbs` in the user's Startup folder, which
 * ran the `unidrive-watch.cmd` restart loop around `unidrive autostart`. On Windows the tray client owns autostart,
 * so deploy writes neither file any more and removes them through this object, which
 * the deploy task loads from the freshly built shadow jar.
 *
 * A file is removed only when its content is what deploy wrote: a file of that name with any other content, and
 * every other file in the folder, is left alone.
 */
object LegacyLogonEntry {
    enum class Outcome { ABSENT, REMOVED, KEPT_FOREIGN, DELETE_FAILED }

    private const val STARTUP_FILE = "unidrive.vbs"
    private const val WATCH_FILE = "unidrive-watch.cmd"

    // Both files were a few hundred bytes; anything bigger is not ours and is not read.
    private const val MAX_BYTES = 4096L

    private const val VBS_FIRST_LINE = "Set ws = CreateObject(\"WScript.Shell\")"
    private const val VBS_RUN_PREFIX = "ws.Run \"\"\"\" & \""
    private const val VBS_RUN_SUFFIX = "\\\\unidrive\\\\$WATCH_FILE\" & \"\"\"\", 0, False"

    private const val WATCH_PREFIX = "@echo off\n:loop\npowershell -NoProfile -ExecutionPolicy Bypass -File \""
    private const val WATCH_AUTOSTART = "\\unidrive.ps1\" autostart"
    private const val WATCH_SUFFIX = "if \"%exit_code%\"==\"78\" exit /b 78\ntimeout /t 30 /nobreak >nul\ngoto loop"

    @JvmStatic
    fun removeFromStartup(startupDir: Path): Outcome = removeIf(startupDir.resolve(STARTUP_FILE), ::isDeployedVbs)

    @JvmStatic
    fun removeWatchWrapper(libDir: Path): Outcome = removeIf(libDir.resolve(WATCH_FILE), ::isDeployedWatchWrapper)

    private fun isDeployedVbs(content: String): Boolean {
        val lines = content.lines()
        if (lines.size != 2) return false
        val run = lines[1]
        if (lines[0] != VBS_FIRST_LINE || !run.startsWith(VBS_RUN_PREFIX) || !run.endsWith(VBS_RUN_SUFFIX)) return false
        val path = run.substring(VBS_RUN_PREFIX.length, run.length - VBS_RUN_SUFFIX.length)
        return '"' !in path
    }

    private fun isDeployedWatchWrapper(content: String): Boolean =
        content.startsWith(WATCH_PREFIX) && content.lines()[2].endsWith(WATCH_AUTOSTART) &&
            content.endsWith(WATCH_SUFFIX)

    private fun removeIf(file: Path, ours: (String) -> Boolean): Outcome {
        if (!Files.isRegularFile(file)) return Outcome.ABSENT
        return try {
            if (Files.size(file) > MAX_BYTES) return Outcome.KEPT_FOREIGN
            val content = Files.readString(file).replace("\r\n", "\n").trimEnd('\n')
            if (!ours(content)) return Outcome.KEPT_FOREIGN
            Files.delete(file)
            Outcome.REMOVED
        } catch (_: IOException) {
            Outcome.DELETE_FAILED
        }
    }
}
