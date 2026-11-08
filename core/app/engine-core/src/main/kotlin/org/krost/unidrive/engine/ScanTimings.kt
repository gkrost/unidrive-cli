package org.krost.unidrive.engine

import org.krost.unidrive.sync.StateDatabase

/**
 * The one owner of the scan-timing keys in `sync_state` (gkrost/unidrive-cli#269): `last_scan_secs_remote`,
 * `last_scan_count_remote`, `last_scan_secs_local` and `last_scan_count_local` — the previous run's wall-clock
 * seconds and final item count per phase, the input for the next run's ETA hint. No deletion safety rides on them
 * — a lost timing only costs one run's throughput hint — but the key spelling and the parse live here so the
 * engine reads back the numbers it wrote. A thin layer over
 * [StateDatabase.getSyncState]/[StateDatabase.setSyncState]: it goes through them (never raw SQL), and it stores
 * the same text as before, so an existing state.db reads the same.
 */
class ScanTimings(
    private val db: StateDatabase,
) {
    /** `last_scan_secs_remote`, or null when absent or not a number. */
    val remoteSecs: Long? get() = db.getSyncState(REMOTE_SECS)?.toLongOrNull()

    /** `last_scan_count_remote`, or null when absent or not a number. */
    val remoteCount: Int? get() = db.getSyncState(REMOTE_COUNT)?.toIntOrNull()

    /** `last_scan_secs_local`, or null when absent or not a number. */
    val localSecs: Long? get() = db.getSyncState(LOCAL_SECS)?.toLongOrNull()

    /** `last_scan_count_local`, or null when absent or not a number. */
    val localCount: Int? get() = db.getSyncState(LOCAL_COUNT)?.toIntOrNull()

    /** Writes `last_scan_secs_local` and `last_scan_count_local`, seconds first. */
    fun recordLocal(secs: Long, count: Int) {
        db.setSyncState(LOCAL_SECS, secs.toString())
        db.setSyncState(LOCAL_COUNT, count.toString())
    }

    /** Writes `last_scan_secs_remote` and `last_scan_count_remote`, seconds first. */
    fun recordRemote(secs: Long, count: Int) {
        db.setSyncState(REMOTE_SECS, secs.toString())
        db.setSyncState(REMOTE_COUNT, count.toString())
    }

    private companion object {
        const val REMOTE_SECS = "last_scan_secs_remote"
        const val REMOTE_COUNT = "last_scan_count_remote"
        const val LOCAL_SECS = "last_scan_secs_local"
        const val LOCAL_COUNT = "last_scan_count_local"
    }
}
