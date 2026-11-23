package org.krost.unidrive.hydration

import org.krost.unidrive.hydration.HydrationImpl.BlockedUploads
import org.krost.unidrive.hydration.HydrationImpl.UploadHealth
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.fileTotals

/**
 * One read of the rows and slots; [oldestSinceMs] is the stamp [UploadHealth.oldestPendingAgeMs] is measured from. (Moved here
 * from HydrationImpl for the size ratchet, gkrost/unidrive-cli#534.)
 */
@Suppress("LongParameterList") // one value per count of the status reply; a record for them would only move the list
internal class UploadSnapshot(
    val pending: Int,
    val inFlight: Int,
    val failed: Int,
    val oldestSinceMs: Long?,
    val takenAtMs: Long,
    val blocked: BlockedUploads,
    val retrying: Int,
    val nextTryAtMs: Long?,
    val deferred: Int,
    val conflicts: Int,
    val cloudOnly: Int = 0,
) {
    fun health(nowMs: Long) =
        UploadHealth(
            pending, inFlight, failed, oldestSinceMs?.let { (nowMs - it).coerceAtLeast(0) }, blocked, takenAtMs,
            retrying, nextTryAtMs, deferred = deferred, conflicts = conflicts, cloudOnly = cloudOnly,
        )
}

/** gkrost/unidrive-cli#706: the alive file rows whose bytes are not local, from one aggregate query (taken with the upload snapshot, never per status request). */
internal fun StateDatabase.cloudOnlyFiles(): Int = fileTotals().notHydrated
