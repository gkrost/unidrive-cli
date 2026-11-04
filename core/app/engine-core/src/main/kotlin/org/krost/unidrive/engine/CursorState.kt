package org.krost.unidrive.engine

import org.krost.unidrive.sync.StateDatabase
import java.time.Instant

/**
 * A boolean `sync_state` value as it is stored: the text `"true"`/`"false"`, or absent ([raw] null).
 *
 * The call sites parse it in two ways today, and they differ for a value that is neither exact spelling: [strict]
 * (`toBooleanStrictOrNull`, so `"TRUE"` is null) and [ignoringCase] (`equals("true", ignoreCase = true)`, so `"TRUE"` is
 * true and any other stored text is false). Each caller picks one and states its own default for an absent key, because
 * absence means true at some sites and false at others (gkrost/unidrive-cli#269): no default lives here.
 */
@JvmInline
value class StoredFlag(
    val raw: String?,
) {
    /** `"true"` → true, `"false"` → false; absent or any other text → null. `== "true"` on the raw text is `strict == true`. */
    val strict: Boolean? get() = raw?.toBooleanStrictOrNull()

    /** Absent → null; `"true"` in any case → true; any other stored text → false. */
    val ignoringCase: Boolean? get() = raw?.equals("true", ignoreCase = true)
}

/** The cursor and recovery keys as `doctor` and `status` read them: no writes. */
interface CursorStateView {
    /** `delta_cursor`: the cursor the next gather continues from; empty or absent means a full listing. */
    val deltaCursor: String?

    /** `pending_cursor`: the cursor the last gather reached, promoted to [deltaCursor] by [CursorState.promote]. */
    val pendingCursor: String?

    /** `pending_cursor_complete`: whether the last gather read every page. */
    val pendingComplete: StoredFlag

    /** `pending_cursor_skipped_paths`: what the last gather could not read, newline-joined; empty after a complete one. */
    val skippedPaths: String?

    /** `last_gather_full`: whether the last gather was a full listing. */
    val lastGatherFull: StoredFlag

    /** `last_full_scan`: when a gather last ended complete, as `Instant.toString()`. */
    val lastFullScan: String?
}

/**
 * The one owner of the `sync_state` keys that carry the delete logic's safety invariants (gkrost/unidrive-cli#269): the
 * delta cursor, the pending cursor and its completeness, the skipped paths, the full-listing verdict and the last full
 * scan. A thin layer over [StateDatabase.getSyncState]/[StateDatabase.setSyncState]: it goes through them (never raw SQL),
 * so the scan-checkpoint routing there still applies, and it stores the same text as before, so an existing state.db
 * reads the same. It adds no batch and no default: the callers keep their order of writes and their own reading of an
 * absent key.
 *
 * The recovery latch ([RemoteGather.INCOMPLETE_ENUMERATION_KEY]) stays with [RemoteGather.settleRecoveryLatch].
 */
class CursorState(
    private val db: StateDatabase,
) : CursorStateView {
    override val deltaCursor: String? get() = db.getSyncState(DELTA_CURSOR)
    override val pendingCursor: String? get() = db.getSyncState(PENDING_CURSOR)
    override val pendingComplete: StoredFlag get() = StoredFlag(db.getSyncState(PENDING_CURSOR_COMPLETE))
    override val skippedPaths: String? get() = db.getSyncState(RemoteGather.PENDING_SKIPPED_PATHS_KEY)
    override val lastGatherFull: StoredFlag get() = StoredFlag(db.getSyncState(LAST_GATHER_FULL))
    override val lastFullScan: String? get() = db.getSyncState(LAST_FULL_SCAN)

    /** Writes `delta_cursor`; an empty [cursor] clears it (the next gather lists from zero). */
    fun setDeltaCursor(cursor: String) = db.setSyncState(DELTA_CURSOR, cursor)

    fun setPendingCursor(cursor: String) = db.setSyncState(PENDING_CURSOR, cursor)

    /** Writes `pending_cursor_complete` as `"true"`/`"false"`. */
    fun setPendingComplete(complete: Boolean) = db.setSyncState(PENDING_CURSOR_COMPLETE, if (complete) "true" else "false")

    fun setSkippedPaths(paths: String) = db.setSyncState(RemoteGather.PENDING_SKIPPED_PATHS_KEY, paths)

    /** Writes `last_gather_full` as `"true"`/`"false"`. */
    fun setLastGatherFull(full: Boolean) = db.setSyncState(LAST_GATHER_FULL, full.toString())

    /** Stamps `last_full_scan` with the current instant. */
    fun stampFullScan() = db.setSyncState(LAST_FULL_SCAN, Instant.now().toString())

    /**
     * Moves the pending cursor to the delta cursor, unconditionally (archive#523), and stamps `last_full_scan` when the
     * gather was complete. Absent completeness counts as complete here, and only the exact text `"true"` does otherwise:
     * `"TRUE"` or any other text does not stamp. No pending cursor: nothing changes.
     */
    fun promote() {
        val pendingCursor = db.getSyncState(PENDING_CURSOR) ?: return
        db.setSyncState(DELTA_CURSOR, pendingCursor)
        val complete = db.getSyncState(PENDING_CURSOR_COMPLETE) ?: "true"
        if (complete == "true") {
            stampFullScan()
        }
    }

    private companion object {
        const val DELTA_CURSOR = "delta_cursor"
        const val PENDING_CURSOR = "pending_cursor"
        const val PENDING_CURSOR_COMPLETE = "pending_cursor_complete"
        const val LAST_GATHER_FULL = "last_gather_full"
        const val LAST_FULL_SCAN = "last_full_scan"
    }
}
