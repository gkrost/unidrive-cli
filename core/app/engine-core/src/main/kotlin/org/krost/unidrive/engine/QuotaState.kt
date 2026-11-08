package org.krost.unidrive.engine

import org.krost.unidrive.sync.StateDatabase
import java.time.Instant

/**
 * The one owner of the cached quota tuple in `sync_state` (gkrost/unidrive-cli#269): `quota_used`, `quota_total`,
 * `quota_remaining` and `quota_fetched_at`. No deletion safety rides on them — a lost cache only costs one
 * re-fetch — but the key spelling and the parse live here so `quota`, `doctor` and the daemon read the same
 * numbers. A thin layer over [StateDatabase.getSyncState]/[StateDatabase.setSyncState]: it goes through them (never
 * raw SQL), and it stores the same text as before, so an existing state.db reads the same.
 */
class QuotaState(
    private val db: StateDatabase,
) {
    /** `quota_used` as bytes, or null when absent or not a number. */
    val used: Long? get() = db.getSyncState(USED)?.toLongOrNull()

    /** `quota_total` as bytes, or null when absent or not a number. */
    val total: Long? get() = db.getSyncState(TOTAL)?.toLongOrNull()

    /** `quota_remaining` as bytes, or null when absent or not a number. */
    val remaining: Long? get() = db.getSyncState(REMAINING)?.toLongOrNull()

    /** `quota_fetched_at` as stored. Doctor distinguishes an absent stamp (never cached) from an unparsable one. */
    val fetchedAtText: String? get() = db.getSyncState(FETCHED_AT)

    /** [fetchedAtText] parsed as an ISO-8601 instant; null when absent or unparsable. */
    val fetchedAt: Instant? get() = fetchedAtText?.let(::parseInstantOrNull)

    /** Writes the tuple as the strings the string-keyed writes stored, in the same order. */
    fun cache(
        used: Long,
        total: Long,
        remaining: Long,
        fetchedAt: Instant,
    ) {
        db.setSyncState(USED, used.toString())
        db.setSyncState(TOTAL, total.toString())
        db.setSyncState(REMAINING, remaining.toString())
        db.setSyncState(FETCHED_AT, fetchedAt.toString())
    }

    companion object {
        private const val USED = "quota_used"
        private const val TOTAL = "quota_total"
        private const val REMAINING = "quota_remaining"
        private const val FETCHED_AT = "quota_fetched_at"

        /**
         * The tuple for [StateDatabase.trySetSyncStatesWhenIdle], the daemon's idle-batch write, in its persist order
         * (used, total, fetched_at, then remaining when known). Null when used, total or fetchedAtMs is absent: a
         * partial snapshot persists nothing, exactly like the writer it replaces.
         */
        fun cacheValues(
            usedBytes: Long?,
            totalBytes: Long?,
            remainingBytes: Long?,
            fetchedAtMs: Long?,
        ): Map<String, String>? {
            val values =
                linkedMapOf(
                    USED to (usedBytes?.toString() ?: return null),
                    TOTAL to (totalBytes?.toString() ?: return null),
                    FETCHED_AT to (fetchedAtMs?.let(Instant::ofEpochMilli)?.toString() ?: return null),
                )
            remainingBytes?.let { values[REMAINING] = it.toString() }
            return values
        }

        private fun parseInstantOrNull(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()
    }
}
