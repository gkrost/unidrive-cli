package org.krost.unidrive.sync

import org.krost.unidrive.engine.RemoteGather
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * gkrost/unidrive-cli#863: the retention of tombstones. A `DELETED` row is the engine's record that it removed or reaped an
 * item; nothing ever read it again after the lagging-listing window, so an emptied account left 296,681 of them in a 177 MB
 * state.db. A purge removes the ones last synced before a cutoff, and only those no pending operation still names. It
 * touches rows of `sync_entries` in the profile's own state.db and nothing else: no cloud call, no local file, no other table.
 */
private val log = LoggerFactory.getLogger("org.krost.unidrive.sync.TombstonePurge")

/**
 * The tombstone retention a profile without `tombstone_retention_days` gets, in days (see the config key). Ninety, not thirty: the
 * folder-delete guard passes over a listed ghost only while its row is DELETED (gkrost/unidrive-cli#853, #858), providers listed
 * such ghosts for 5 to 9 days with no known bound, and a purged row brings the guard's `remote_unobserved` refusal back.
 */
const val DEFAULT_TOMBSTONE_RETENTION_DAYS: Int = 90

/** Rows one transaction of a purge removes: bounded, so the monitor is given back between the chunks. */
internal const val PURGE_CHUNK_ROWS = 5_000

// A tombstone is selected only when none of these holds (the first SELECT of the purge): the row is still named by
//  - a rename or replace intent, by its cloud id or by a path (the intent finishes from it after a restart),
//  - an upload handle at its path (the replay reports under it),
//  - a pending upload (a `local:` row with real bytes), an unsynced edit (`edit_base`) or a remote change still to apply
//    locally (`pending_local`),
//  - a delete made through the mount inside the lagging-listing window, which hides a listing that still reports the item.
// A `last_synced` that is no ISO instant is not a date the purge can judge: the row stays.
private const val PURGEABLE_TOMBSTONES =
    "SELECT rowid FROM sync_entries WHERE status = 'DELETED' " +
        "AND last_synced GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T*' AND substr(last_synced, 1, 19) < ? " +
        "AND (mount_deleted_at IS NULL OR substr(mount_deleted_at, 1, 19) < ?) " +
        "AND pending_local IS NULL AND edit_base IS NULL " +
        "AND NOT (remote_id GLOB 'local:*' AND is_folder = 0 AND is_hydrated <> 0) " +
        "AND NOT EXISTS (SELECT 1 FROM rename_intents r WHERE r.remote_id = sync_entries.remote_id " +
        "OR r.old_path = sync_entries.path OR r.new_path = sync_entries.path) " +
        "AND NOT EXISTS (SELECT 1 FROM replace_intents p WHERE p.source_remote_id = sync_entries.remote_id " +
        "OR p.dest_remote_id = sync_entries.remote_id OR p.parking_path = sync_entries.path " +
        "OR p.source_path = sync_entries.path OR p.dest_path = sync_entries.path) " +
        "AND NOT EXISTS (SELECT 1 FROM upload_handles u WHERE u.path = sync_entries.path) " +
        "LIMIT ?"

// ISO text compares to the second at most (a stored instant may or may not carry a fraction): the bound is cut to the second
// and a stamp is cut the same way, so a row a moment newer than the bound is kept, never the reverse.
private fun secondsOf(at: Instant): String = at.truncatedTo(ChronoUnit.SECONDS).toString().take(ISO_SECONDS_LENGTH)

private const val ISO_SECONDS_LENGTH = 19

/**
 * Removes the `DELETED` rows last synced before [olderThan] that no pending operation names (see [PURGEABLE_TOMBSTONES]),
 * in chunks of [PURGE_CHUNK_ROWS] that each commit, and logs how many it removed. A delete made through the mount at or
 * after [keepMountDeletesSince] is kept whatever its age. A purge that removed rows forgets the once-per-file compaction
 * record ([StateDatabase.compactOnce]): the pages it freed may be given back, once more. Returns the rows removed; a
 * read-only, in-memory or snapshot handle removes none.
 */
fun StateDatabase.purgeTombstones(
    olderThan: Instant,
    keepMountDeletesSince: Instant = Instant.now().minus(RemoteGather.LAGGING_LISTING_WINDOW),
    chunkRows: Int = PURGE_CHUNK_ROWS,
): Int {
    require(chunkRows > 0) { "chunkRows must be positive" }
    if (!isWritable || isDisposable) return 0
    val started = System.nanoTime()
    var total = 0
    while (true) {
        val removed =
            withWriter("purgeTombstones") { c ->
                c.prepareStatement("DELETE FROM sync_entries WHERE rowid IN ($PURGEABLE_TOMBSTONES)").use { stmt ->
                    stmt.setString(1, secondsOf(olderThan))
                    stmt.setString(2, secondsOf(keepMountDeletesSince))
                    stmt.setInt(3, chunkRows)
                    stmt.executeUpdate()
                }
            }
        total += removed
        if (removed < chunkRows) break
    }
    if (total > 0) {
        withWriter("purgeTombstones") { c ->
            c.prepareStatement("DELETE FROM sync_state WHERE key = ?").use { stmt ->
                stmt.setString(1, StateDatabase.COMPACTED_KEY)
                stmt.executeUpdate()
            }
        }
        log.info(
            "Purged {} tombstone row(s) last synced before {} from {} in {} ms ({} left, kept for a pending operation or a newer date)",
            total,
            olderThan,
            file,
            (System.nanoTime() - started) / 1_000_000,
            tombstoneCount(),
        )
    }
    return total
}

/**
 * [purgeTombstones] for the profile's retention of [retentionDays] days before [now]; 0 or less keeps every tombstone
 * (the key `tombstone_retention_days`).
 */
fun StateDatabase.purgeExpiredTombstones(
    retentionDays: Int,
    now: Instant = Instant.now(),
): Int = if (retentionDays <= 0) 0 else purgeTombstones(now.minus(Duration.ofDays(retentionDays.toLong())), now.minus(RemoteGather.LAGGING_LISTING_WINDOW))

/** The number of `DELETED` rows. */
fun StateDatabase.tombstoneCount(): Int =
    readForReport("tombstoneCount") { c ->
        c.createStatement().use { stmt -> stmt.executeQuery("SELECT COUNT(*) FROM sync_entries WHERE status = 'DELETED'").use { it.next(); it.getInt(1) } }
    }

/** What the daemon's start did to state.db beyond opening it: the tombstones purged, and whether the file was rewritten. */
data class StartMaintenance(
    val purgedTombstones: Int,
    val compacted: Boolean,
)

/**
 * The maintenance of the daemon's start, before anything is served: the purge of tombstones older than [retentionDays]
 * days (0 = none), then the compaction, which is the one that gives the freed pages back (see [purgeTombstones]).
 */
fun StateDatabase.maintainAtStart(retentionDays: Int): StartMaintenance {
    val purged = purgeExpiredTombstones(retentionDays)
    return StartMaintenance(purged, compactOnce())
}

/**
 * What a reset removed and what it gave back, for the line the command prints and the daemon logs. The byte sizes count
 * the main file and its WAL, before the reset and after the files were rewritten.
 */
data class ResetReport(
    val keptRows: Int,
    val removedRows: Int,
    val removedTombstones: Int,
    val stateBytesBefore: Long,
    val stateBytesAfter: Long,
    val stagingBytesBefore: Long,
    val stagingBytesAfter: Long,
) {
    fun summary(): String =
        "removed $removedRows row(s) ($removedTombstones tombstone(s)), kept $keptRows; state.db ${mb(stateBytesBefore)} -> ${mb(stateBytesAfter)}, " +
            "scan-staging.db ${mb(stagingBytesBefore)} -> ${mb(stagingBytesAfter)}"

    private fun mb(bytes: Long): String = "${bytes / BYTES_PER_MB} MB"

    private companion object {
        const val BYTES_PER_MB = 1_048_576L
    }
}

/**
 * [StateDatabase.resetKeepingPending], then the removal is reported, and state.db is rewritten when [rewrite] is true. A daemon
 * that serves passes false: the rewrite holds the database's monitor for as long as it takes (#531), and the reset dropped the
 * `compacted_at` record, so the next start's compaction gives the pages back.
 */
fun StateDatabase.resetKeepingPendingReporting(
    rewrite: Boolean = true,
    alsoKeep: (org.krost.unidrive.sync.model.SyncEntry) -> Boolean = { false },
): ResetReport = reportingReset(rewrite) { resetKeepingPending(alsoKeep) }

/** [StateDatabase.resetAll], then the files are rewritten and the removal is reported. */
fun StateDatabase.resetAllReporting(): ResetReport =
    reportingReset(rewrite = true) {
        resetAll()
        0
    }

private fun StateDatabase.reportingReset(
    rewrite: Boolean,
    reset: () -> Int,
): ResetReport {
    val rowsBefore = allRowCount()
    val tombstonesBefore = tombstoneCount()
    val stateBefore = stateBytes(file)
    val stagingBefore = stagingBytes(file)
    val kept = reset()
    if (rewrite) rewriteIfFragmented(whenText = "after the reset")
    val stagingAfter = stagingBytes(file)
    return ResetReport(
        keptRows = kept,
        removedRows = rowsBefore - allRowCount(),
        removedTombstones = tombstonesBefore - tombstoneCount(),
        stateBytesBefore = stateBefore,
        stateBytesAfter = stateBytes(file),
        stagingBytesBefore = stagingBefore,
        stagingBytesAfter = stagingAfter,
    )
}

private fun StateDatabase.allRowCount(): Int =
    readForReport("allRowCount") { c ->
        c.createStatement().use { stmt -> stmt.executeQuery("SELECT COUNT(*) FROM sync_entries").use { it.next(); it.getInt(1) } }
    }

private fun stateBytes(stateDb: Path): Long =
    (listOf(stateDb.fileName.toString()) + StateDatabase.sidecarNames(stateDb.fileName.toString()))
        .filter { !it.endsWith("-shm") }
        .sumOf { sizeOf(stateDb.resolveSibling(it)) }

private fun stagingBytes(stateDb: Path): Long = StateDatabase.stagingFileNames().filter { !it.endsWith("-shm") }.sumOf { sizeOf(stateDb.resolveSibling(it)) }

private fun sizeOf(file: Path): Long = if (Files.exists(file)) Files.size(file) else 0L
