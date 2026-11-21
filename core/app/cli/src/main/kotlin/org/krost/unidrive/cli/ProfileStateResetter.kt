package org.krost.unidrive.cli

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.krost.unidrive.engine.RemoteGather
import org.krost.unidrive.sync.ProcessLock
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncConfig
import org.krost.unidrive.sync.isValidProfileName
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * `profile reset-state` (gkrost/unidrive-cli#863): the safe state reset of a profile whose account was emptied or changed.
 * It works on the profile's own files only, with no daemon and no cloud call, and never touches the local sync root or
 * mount root.
 *
 * It refuses while a process holds the profile (the process lock, or a socket that answers), while uploads that never
 * reached the cloud are pending, and while a rename intent, a replace intent or an upload handle is on record. The
 * profile lock is held from the first look to the last file, so no daemon can start in between.
 *
 * What goes: every tombstone of `state.db` (a tombstone a delete through the mount stamped within the lagging-listing window
 * stays, or a listing that lags would bring the item back), and `state.db` is rewritten so the space goes back; with
 * `all`, the alive rows and `sync_state` go too, after a copy of `state.db` is put aside ([StateReset.backup]). The staged scan
 * (`scan-staging.db`) and the transfer history (`telemetry.db`) are deleted: the next scan stages again and the history
 * starts empty. The result reports rows and bytes before and after for each of the three files.
 */
@Suppress("ThrowsCount") // every refusal is a LifecycleError with its own token
internal object ProfileStateResetter {
    private val log = LoggerFactory.getLogger(ProfileStateResetter::class.java)

    private const val STATE_FILE = "state.db"
    private const val MB = 1_048_576L
    private val SIDE_SUFFIXES = SqliteFiles.SIDE_SUFFIXES

    /** What [reset] did, as the one JSON object the command prints and the numbers its text form prints. */
    class Result(
        val json: JsonObject,
        val files: List<FileReport>,
        val tombstonesRemoved: Long,
        val aliveRowsRemoved: Long,
        val backup: Path?,
        val warnings: List<String>,
    )

    /** One file: its size and rows before and after. Rows are null where the file is not there or could not be read. */
    class FileReport(
        val name: String,
        val bytesBefore: Long,
        val bytesAfter: Long,
        val rowsBefore: Long?,
        val rowsAfter: Long?,
    )

    /** A file's size (with its SQLite sidecars) and rows, taken before anything changes. */
    private class Measured(
        val file: Path,
    ) {
        val bytes: Long = SqliteFiles.sizeWithSides(file)
        val rows: Long? = SqliteFiles.countRows(file)
    }

    /**
     * Resets the state of [name] under [configDir]. [all] also drops the alive rows. [confirm] is asked once every refusal
     * has been checked and before anything changes; false cancels and returns null. Throws [LifecycleError] for every refusal.
     */
    fun reset(
        configDir: Path,
        name: String,
        all: Boolean,
        confirm: () -> Boolean = { true },
    ): Result? {
        val profileDir = configDir.resolve(name).also { checkProfile(configDir, name, it) }
        when (ProfileRemover.holderOf(profileDir, ProfileRemoveHooks.socketPath(name))) {
            ProfileRemover.Holder.DAEMON ->
                throw LifecycleError("daemon_running", "a daemon is running for profile '$name'; stop it first; nothing was reset")
            ProfileRemover.Holder.OTHER ->
                throw LifecycleError("profile_in_use", "profile '$name' is in use by another unidrive process; stop it first; nothing was reset")
            ProfileRemover.Holder.NONE -> Unit
        }
        // From here on a racing start is refused by the lock this process holds.
        val guard = ProcessLock(profileDir.resolve(".lock"))
        if (!guard.tryLock(ProcessLock.Mode.SYNC)) {
            throw LifecycleError("profile_in_use", "profile '$name' was taken by another unidrive process; nothing was reset")
        }
        try {
            return resetLocked(profileDir, name, all, confirm)
        } finally {
            guard.unlock()
        }
    }

    /** The profile must be configured and have a state folder. */
    private fun checkProfile(
        configDir: Path,
        name: String,
        profileDir: Path,
    ) {
        val configPath = configDir.resolve("config.toml")
        if (!Files.exists(configPath)) throw LifecycleError("config_not_found", "no config.toml in $configDir")
        if (!isValidProfileName(name)) throw LifecycleError("invalid_profile_name", "'$name' is not a valid profile name")
        val raw =
            try {
                SyncConfig.parseRaw(Files.readString(configPath), configPath.toString())
            } catch (_: Exception) {
                // The parser's message can quote the offending config line, which may hold a credential.
                throw LifecycleError("config_invalid", "config.toml could not be parsed")
            }
        if (name !in raw.providers) throw LifecycleError("profile_not_found", "profile '$name' is not configured")
        if (!Files.isDirectory(profileDir)) throw LifecycleError("state_not_found", "profile '$name' has no state folder yet; nothing to reset")
    }

    private fun resetLocked(
        profileDir: Path,
        name: String,
        all: Boolean,
        confirm: () -> Boolean,
    ): Result? {
        val stateDb = profileDir.resolve(STATE_FILE)
        // Measured first: the gate and resetAll open (and so can create or grow) the files.
        val state = Measured(stateDb)
        val staging = Measured(profileDir.resolve(StateDatabase.SCAN_STAGING_FILE_NAME))
        val telemetry = Measured(profileDir.resolve(DaemonRuntime.HISTORY_FILE_NAME))
        val tombstones = SqliteFiles.scalar(stateDb, "SELECT COUNT(*) FROM sync_entries WHERE status <> 'EXISTS'") ?: 0L
        val alive = SqliteFiles.scalar(stateDb, "SELECT COUNT(*) FROM sync_entries WHERE status = 'EXISTS'") ?: 0L

        refuseIfBusy(profileDir, name, all)
        if (!confirm()) return null
        val warnings = mutableListOf<String>()
        var backup: Path? = null
        var tombstonesRemoved = 0L
        if (Files.exists(stateDb)) {
            if (all) {
                backup = backupOrRefuse(stateDb)
                wipeAll(stateDb, name)
                tombstonesRemoved = tombstones
            } else {
                tombstonesRemoved = deleteTombstones(stateDb)
            }
            if (!vacuum(stateDb)) {
                warnings += "state.db could not be rewritten (too little free space, or a failed rewrite); its free pages are reused by the next writes"
            }
        }
        val aliveRemoved = if (all) alive else 0L
        // The staged scan and the history go whole: the next scan stages again, the history starts empty.
        val reports =
            listOf(
                FileReport(STATE_FILE, state.bytes, SqliteFiles.sizeWithSides(stateDb), state.rows, SqliteFiles.countRows(stateDb)),
                deleteFile(staging, warnings),
                deleteFile(telemetry, warnings),
            )
        log.info(
            "profile '{}': state reset: {} tombstone(s) and {} alive row(s) removed; {}",
            name,
            tombstonesRemoved,
            aliveRemoved,
            reports.joinToString { "${it.name} ${it.bytesBefore / MB} -> ${it.bytesAfter / MB} MB" },
        )
        val json = ProfileStateResetReport.resultJson(name, all, tombstonesRemoved, aliveRemoved, reports, backup, warnings)
        return Result(json, reports, tombstonesRemoved, aliveRemoved, backup, warnings)
    }

    /** The refusals that need the files: pending uploads, and the rename, replace and upload operations on record. */
    private fun refuseIfBusy(
        profileDir: Path,
        name: String,
        all: Boolean,
    ) {
        val stateDb = profileDir.resolve(STATE_FILE)
        if (!Files.exists(stateDb)) return
        // The pending-upload gate of `profile remove`: refuses what is on its way, and drops rows whose file is gone.
        ProfileRemover.refuseIfUploadsPending(profileDir, name)
        val split = PendingGate.split(stateDb, profileDir.parent, name)
        // The alive rows go with --all, and a failed upload is only a row: it is the one record of a file that never reached the cloud.
        if (all && split != null && split.waiting.isNotEmpty()) {
            throw LifecycleError(
                "uploads_pending",
                "profile '$name' still has ${split.waiting.size} upload(s) that never reached the cloud (failed ones included); " +
                    "--all would drop their rows; nothing was reset",
            )
        }
        val operations = operationsOnRecord(stateDb, name)
        if (operations > 0) {
            throw LifecycleError(
                "operations_pending",
                "profile '$name' still has $operations rename, replace or upload operation(s) on record that the " +
                    "daemon finishes at its next start; start it and let it settle first; nothing was reset",
                details = buildJsonObject { put("operations", operations) },
            )
        }
    }

    private fun operationsOnRecord(
        stateDb: Path,
        name: String,
    ): Int {
        val db = StateDatabase(stateDb, readOnly = true)
        return try {
            db.initialize()
            db.renameIntents().size + db.replaceIntents().size + db.uploadHandles().size
        } catch (e: SQLException) {
            throw LifecycleError("state_unreadable", "the state of profile '$name' could not be read; nothing was reset", e)
        } catch (e: IllegalStateException) {
            throw LifecycleError("state_unreadable", "the state of profile '$name' could not be read; nothing was reset", e)
        } finally {
            db.close()
        }
    }

    private fun backupOrRefuse(stateDb: Path): Path? =
        try {
            StateReset.backup(stateDb)
        } catch (e: IOException) {
            throw LifecycleError("backup_failed", "state.db could not be copied aside (${e.message}); nothing was reset", e)
        }

    /** `sync --reset`'s clearing: every row, `sync_state`, and the staged scan. */
    private fun wipeAll(
        stateDb: Path,
        name: String,
    ) {
        val db = StateDatabase(stateDb)
        try {
            db.initialize()
            db.resetAll()
        } catch (e: SQLException) {
            throw LifecycleError("state_unreadable", "the state of profile '$name' could not be cleared (${e.javaClass.simpleName})", e)
        } finally {
            db.close()
        }
    }

    /**
     * Deletes every tombstone (DELETED or TRASHED) in one transaction and returns how many went; alive rows, `sync_state` and
     * the intent tables are not touched (no operation is on record, [refuseIfBusy]). A tombstone that a delete through the
     * mount stamped within [RemoteGather.LAGGING_LISTING_WINDOW] stays: a listing that lags may still report its item. The
     * bound is cut to the second and lowered by one, as `recentlyDeletedRemoteIds` does, so a tombstone a moment older may stay,
     * never the reverse.
     */
    private fun deleteTombstones(stateDb: Path): Long {
        val bound = Instant.now().minus(RemoteGather.LAGGING_LISTING_WINDOW).truncatedTo(ChronoUnit.SECONDS).minusSeconds(1).toString()
        val sql = "DELETE FROM sync_entries WHERE status <> 'EXISTS' AND (mount_deleted_at IS NULL OR mount_deleted_at < ?)"
        return SqliteFiles.withSqlite(stateDb) { c ->
            c.prepareStatement(sql).use { st ->
                st.setString(1, bound)
                st.executeUpdate().toLong()
            }
        } ?: throw LifecycleError("state_unreadable", "state.db could not be opened for the reset; nothing was reset")
    }

    /** Rewrites [stateDb] (`VACUUM`, then the WAL is truncated) so the space of the deleted rows goes back to the disk. */
    private fun vacuum(stateDb: Path): Boolean {
        val usable = runCatching { Files.getFileStore(stateDb.toAbsolutePath().parent).usableSpace }.getOrDefault(Long.MAX_VALUE)
        if (usable < 2 * SqliteFiles.sizeWithSides(stateDb)) return false
        return SqliteFiles.withSqlite(stateDb) { c ->
            c.createStatement().use { st ->
                st.execute("VACUUM")
                st.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)").use { it.next() }
            }
            true
        } ?: false
    }

    /** Deletes [measured]'s file and the SQLite sidecars next to it, and reports the sizes and rows it held. */
    private fun deleteFile(
        measured: Measured,
        warnings: MutableList<String>,
    ): FileReport {
        for (suffix in SIDE_SUFFIXES) {
            val target = measured.file.resolveSibling(measured.file.fileName.toString() + suffix)
            try {
                Files.deleteIfExists(target)
            } catch (e: IOException) {
                warnings += "${target.fileName}: could not be deleted (${e.javaClass.simpleName})"
            }
        }
        val after = SqliteFiles.sizeWithSides(measured.file)
        val rowsAfter = if (after == 0L) measured.rows?.let { 0L } else SqliteFiles.countRows(measured.file)
        return FileReport(measured.file.fileName.toString(), measured.bytes, after, measured.rows, rowsAfter)
    }

}

/** Plain JDBC looks at SQLite files of a stopped profile: sizes with their sidecars, row counts, one connection. */
internal object SqliteFiles {
    val SIDE_SUFFIXES = listOf("", "-wal", "-shm")

    /** The size of [file] with its `-wal` and `-shm`; 0 for a file that is not there. */
    fun sizeWithSides(file: Path): Long =
        SIDE_SUFFIXES.sumOf { suffix ->
            val p = file.resolveSibling(file.fileName.toString() + suffix)
            if (Files.isRegularFile(p)) Files.size(p) else 0L
        }

    /** The rows of all user tables of the SQLite file [file]; null when it is not there or cannot be read. */
    fun countRows(file: Path): Long? =
        withSqlite(file) { c ->
            val tables = mutableListOf<String>()
            c.createStatement().use { st ->
                st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").use { rs ->
                    while (rs.next()) tables += rs.getString(1)
                }
            }
            tables.sumOf { t -> count(c, "SELECT COUNT(*) FROM \"${t.replace("\"", "\"\"")}\"") }
        }

    fun scalar(
        file: Path,
        sql: String,
    ): Long? = withSqlite(file) { c -> count(c, sql) }

    private fun count(
        c: Connection,
        sql: String,
    ): Long = c.createStatement().use { st -> st.executeQuery(sql).use { it.next(); it.getLong(1) } }

    /** Runs [body] on a plain connection to [file]; null when the file is not there or SQLite refuses. */
    fun <T> withSqlite(
        file: Path,
        body: (Connection) -> T,
    ): T? {
        if (!Files.isRegularFile(file)) return null
        return try {
            DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}").use(body)
        } catch (_: SQLException) {
            null
        }
    }
}

/** The JSON and the text lines [ProfileStateResetter] reports. */
internal object ProfileStateResetReport {
    private const val MB = 1_048_576L

    fun resultJson(
        name: String,
        all: Boolean,
        tombstonesRemoved: Long,
        aliveRemoved: Long,
        reports: List<ProfileStateResetter.FileReport>,
        backup: Path?,
        warnings: List<String>,
    ): JsonObject =
        buildJsonObject {
            put("ok", true)
            put("profile", name)
            put("mode", if (all) "all" else "tombstones")
            put(
                "removed",
                buildJsonObject {
                    put("tombstones", tombstonesRemoved)
                    put("alive_rows", aliveRemoved)
                },
            )
            put("files", buildJsonObject { for (r in reports) put(r.name, fileJson(r)) })
            put("backup", backup?.fileName?.toString())
            put("warnings", JsonArray(warnings.map { JsonPrimitive(it) }))
        }

    private fun fileJson(r: ProfileStateResetter.FileReport): JsonObject =
        buildJsonObject {
            put("bytes_before", r.bytesBefore)
            put("bytes_after", r.bytesAfter)
            put("rows_before", r.rowsBefore)
            put("rows_after", r.rowsAfter)
            put("rows_removed", r.rowsBefore?.let { it - (r.rowsAfter ?: 0L) })
        }

    /** The lines the console form prints, one per file, then what went. */
    fun summaryLines(result: ProfileStateResetter.Result): List<String> =
        buildList {
            add("Removed ${result.tombstonesRemoved} tombstone row(s)" + if (result.aliveRowsRemoved > 0) " and ${result.aliveRowsRemoved} alive row(s)." else ".")
            for (f in result.files) {
                val rows = f.rowsBefore?.let { "; ${it - (f.rowsAfter ?: 0L)} of $it row(s) removed" } ?: ""
                add("  %-16s %8.1f MB -> %.1f MB%s".format(f.name, f.bytesBefore / MB.toDouble(), f.bytesAfter / MB.toDouble(), rows))
            }
            result.backup?.let { add("The previous state.db is saved as ${it.fileName}.") }
            result.warnings.forEach { add("Warning: $it") }
        }
}
