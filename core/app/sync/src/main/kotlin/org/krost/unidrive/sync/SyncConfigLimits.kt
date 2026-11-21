package org.krost.unidrive.sync

/**
 * issue#72: the per-profile daemon-limit keys (`max_concurrent_transfers`, `upload_queue_depth`,
 * `cache_access_grace_ms`) — their typed accessors, ranges and defaults — beside [SyncConfig], whose file
 * they would otherwise grow past its sizeRatchet baseline. The raw keys and the parse-time validation call
 * sites stay in SyncConfig.kt (RawProvider, its toSyncConfig); the accessors read [SyncConfig.providers]
 * as extensions, the way the report queries read StateDatabase (FileTotals.kt, in engine-core).
 */

/** The ceiling of `max_concurrent_transfers` as typed; the engine then clamps to the provider's audited cap. */
const val MAX_CONCURRENT_TRANSFERS: Int = 16

/**
 * The mount uploads' waiting-queue bound a profile without `upload_queue_depth` gets — the value
 * HydrationImpl's own DEFAULT_UPLOAD_QUEUE_DEPTH default runs at (:app:hydration does not depend on this
 * module, so the two constants are kept in step by hand). A profile key may narrow or widen it, 1..[MAX_UPLOAD_QUEUE_DEPTH].
 */
const val DEFAULT_UPLOAD_QUEUE_DEPTH: Int = 256

/** The ceiling of `upload_queue_depth`. */
const val MAX_UPLOAD_QUEUE_DEPTH: Int = 4096

/**
 * The cache access grace a profile without `cache_access_grace_ms` gets, in ms — the value
 * HydrationImpl's own CACHE_ACCESS_GRACE_MS default runs at (constants kept in step by hand, as for
 * [DEFAULT_UPLOAD_QUEUE_DEPTH]). 0..[MAX_CACHE_ACCESS_GRACE_MS].
 */
const val DEFAULT_CACHE_ACCESS_GRACE_MS: Long = 60_000L

/** The ceiling of `cache_access_grace_ms`. */
const val MAX_CACHE_ACCESS_GRACE_MS: Long = 3_600_000L

/**
 * issue#72: this profile's configured transfer budget (key `max_concurrent_transfers`), or null for the
 * provider's audited cap ([org.krost.unidrive.ProviderMetadata.maxConcurrentTransfers], fallback 4). The
 * engine clamps the value to the audited cap; `unidrive sync` does not read it.
 */
fun SyncConfig.maxConcurrentTransfersOverride(profileName: String): Int? =
    providers[profileName]?.maxConcurrentTransfersOverride

/**
 * issue#72: how many submitted-but-not-yet-running mount uploads this profile's daemon holds before
 * open_write back-pressures (HydrationImpl.uploadQueueDepth); [DEFAULT_UPLOAD_QUEUE_DEPTH] when unset.
 */
fun SyncConfig.uploadQueueDepth(profileName: String): Int =
    providers[profileName]?.uploadQueueDepth ?: DEFAULT_UPLOAD_QUEUE_DEPTH

/**
 * issue#72: how long a hydration-cache file of this profile stays un-evictable after its last use, in ms
 * (HydrationImpl.cacheAccessGraceMs); [DEFAULT_CACHE_ACCESS_GRACE_MS] when unset.
 */
fun SyncConfig.cacheAccessGraceMs(profileName: String): Long =
    providers[profileName]?.cacheAccessGraceMs ?: DEFAULT_CACHE_ACCESS_GRACE_MS

/** The ceiling of `tombstone_retention_days` (ten years). The default itself is [DEFAULT_TOMBSTONE_RETENTION_DAYS] in :app:engine-core. */
const val MAX_TOMBSTONE_RETENTION_DAYS: Int = 3650

/**
 * gkrost/unidrive-cli#863: how many days this profile's state.db keeps a `DELETED` tombstone row before the daemon's start
 * purges it (key `tombstone_retention_days`); [DEFAULT_TOMBSTONE_RETENTION_DAYS] (90) when unset, 0 = keep every one.
 */
fun SyncConfig.tombstoneRetentionDays(profileName: String): Int =
    providers[profileName]?.tombstoneRetentionDays ?: DEFAULT_TOMBSTONE_RETENTION_DAYS

/** issue#72: an out-of-range daemon-limit key is refused naming the section, key and range (the CLI exits 78). */
internal fun validateDaemonLimit(
    profile: String,
    key: String,
    value: Long?,
    min: Long,
    max: Long,
) {
    if (value != null && value !in min..max) {
        throw ConfigValueException("[providers.$profile] $key = $value: must be a whole number from $min to $max.")
    }
}
