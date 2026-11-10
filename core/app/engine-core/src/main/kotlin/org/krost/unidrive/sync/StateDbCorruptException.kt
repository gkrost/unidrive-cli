package org.krost.unidrive.sync

import java.nio.file.Path
import java.sql.SQLException

/**
 * `state.db` is damaged: SQLite reports SQLITE_CORRUPT ("database disk image is malformed") or SQLITE_NOTADB
 * ("file is not a database"). Not an older schema, and not a newer one: a file the engine cannot read at all.
 *
 * Thrown by [StateDatabase.initialize] (gkrost/unidrive-cli#581) so a caller can say so in one line instead of
 * printing the driver's stack trace, and so `doctor` does not advise "run sync once to upgrade" for a damaged file.
 * [message] is that line: it names the file, the safe recovery and what the recovery costs.
 *
 * The engine does not move the file aside by itself. Whether a mount-mode daemon should (and keep a copy as
 * `state.db.corrupt-<time>`) is an open owner decision on #581; [recoveryAdvice] is the one place the wording lives.
 */
class StateDbCorruptException(
    val dbPath: Path,
    cause: Throwable,
) : IllegalStateException(describe(dbPath), cause) {
    companion object {
        /** The process exit code of a daemon that stopped because `state.db` is corrupt (sysexits `EX_DATAERR`). */
        const val EXIT_CODE: Int = 65

        private const val SQLITE_CORRUPT = 11
        private const val SQLITE_NOTADB = 26

        /** The recovery, as one sentence: what to do, what the engine then does, what it costs. */
        fun recoveryAdvice(dbPath: Path): String =
            "To recover, stop unidrive and move $dbPath (and its -wal and -shm files) aside; the engine then rebuilds the " +
                "state from the cloud listing at its next start. Local changes that were recorded only in the state and " +
                "not yet sent to the cloud are lost."

        private fun describe(dbPath: Path): String = "state.db at $dbPath is corrupt (the file is damaged, not an older version). ${recoveryAdvice(dbPath)}"

        /** True for the SQLite result codes SQLITE_CORRUPT and SQLITE_NOTADB (extended codes included), or the driver's text for them. */
        fun isCorruption(e: SQLException): Boolean {
            val primary = e.errorCode and 0xFF
            if (primary == SQLITE_CORRUPT || primary == SQLITE_NOTADB) return true
            val text = e.message ?: return false
            return "SQLITE_CORRUPT" in text || "SQLITE_NOTADB" in text ||
                "disk image is malformed" in text || "file is not a database" in text || "not a database" in text
        }

        /** [isCorruption] for [t] or anything in its cause chain; an already wrapped [StateDbCorruptException] counts. */
        fun isCorruption(t: Throwable?): Boolean {
            var cur = t
            var depth = 0
            while (cur != null && depth++ < 16) {
                if (cur is StateDbCorruptException) return true
                if (cur is SQLException && isCorruption(cur)) return true
                cur = cur.cause
            }
            return false
        }
    }
}
