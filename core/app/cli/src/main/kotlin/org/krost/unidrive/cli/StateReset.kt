package org.krost.unidrive.cli

import org.krost.unidrive.io.OwnerOnly
import org.krost.unidrive.sync.StateDatabase
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * `sync --reset` and `refresh --reset` throw away what a profile knows about its files, and the next pass reads the
 * whole drive again (#100). They ask first unless `--yes` is given, refuse when nobody can be asked (a script, a
 * service), and copy state.db aside before anything is cleared, so an operator can go back.
 */
internal object StateReset {
    private val log = LoggerFactory.getLogger(StateReset::class.java)

    /** The name of a copy starts with this, then the UTC time of the reset: `state-before-reset-20261028T101500Z.db`. */
    const val BACKUP_PREFIX = "state-before-reset-"

    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    /** A yes/no question on an interactive terminal, or null when there is none. Test seam; production never reassigns it. */
    var console: () -> ((String) -> Boolean)? = { systemConsoleConfirm() }

    /**
     * Null when the reset may go ahead: [yes] was given, or the operator answered yes. Otherwise the line that says
     * why nothing was reset; the command prints it to stderr and exits 1.
     */
    fun refusal(
        command: String,
        profileName: String,
        yes: Boolean,
    ): String? {
        if (yes) return null
        val confirm =
            console()
                ?: return "$command --reset clears the sync state of profile '$profileName' and needs a confirmation: " +
                    "run it on a terminal, or pass --yes. Nothing was reset."
        val question =
            "Clear the sync state of profile '$profileName' and read the whole drive again? " +
                "state.db is copied aside first."
        return if (confirm(question)) null else "Cancelled. Nothing was reset."
    }

    /**
     * A copy of [dbPath] next to it, `state-before-reset-<UTC time>.db` (`-2`, `-3`, ... when that name is taken),
     * restricted to the current user; null when the profile has no state.db yet. The copy is taken through SQLite
     * ([StateDatabase.copyInto]): consistent while the daemon writes, and one file that holds what the WAL held. Throws
     * [IOException] when the copy cannot be written; the caller then resets nothing.
     */
    fun backup(
        dbPath: Path,
        now: Instant = Instant.now(),
    ): Path? {
        if (!Files.exists(dbPath)) return null
        val stem = BACKUP_PREFIX + STAMP.format(now)
        val target =
            generateSequence(1) { it + 1 }
                .map { n -> dbPath.resolveSibling(if (n == 1) "$stem.db" else "$stem-$n.db") }
                .first { !Files.exists(it) }
        try {
            StateDatabase.copyInto(dbPath, target)
        } catch (e: SQLException) {
            runCatching { Files.deleteIfExists(target) }
            throw IOException(e.message, e)
        }
        // The copy lists every path of the drive, like state.db. SQLite creates it with its default mode.
        val outcome = OwnerOnly.restrictFile(target)
        if (!outcome.restricted) log.warn("Could not restrict {} to the current user: {}", target, outcome)
        return target
    }
}
