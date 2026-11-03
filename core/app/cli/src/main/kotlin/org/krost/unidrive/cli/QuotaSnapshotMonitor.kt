package org.krost.unidrive.cli

import kotlinx.coroutines.launch
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.sync.StateDatabase
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// archive#655: the last quota snapshot served in daemon.status (`quota`); null = nothing fetched yet.
internal data class QuotaSnapshot(
    val usedBytes: Long? = null,
    val totalBytes: Long? = null,
    val remainingBytes: Long? = null,
    val fetchedAtMs: Long? = null,
    val stale: Boolean = true,
    val error: String? = null,
)

/**
 * The daemon's quota snapshot (archive#655): what daemon.status serves as `quota`. The snapshot is
 * seeded from the cache in state.db, refreshed single-flight off the request path (a client-connected
 * status request is the trigger; no client asking means no provider call) and written through the
 * daemon's own database, for `quota --json` and the daemon's next start.
 */
internal class QuotaSnapshotMonitor(
    // archive#655: TTL of the quota snapshot in daemon.status (`quota`), in ms; 0 = the quota field
    // renders from the cache only and never refreshes. `daemon run` passes the profile's
    // quota_refresh_minutes (default 15 min).
    private val quotaRefreshMs: Long,
    private val providerFactory: () -> CloudProvider,
    // The daemon's state database, read at every use: it opens on the daemon's lifecycle and closes on its cleanup.
    private val db: () -> StateDatabase?,
) {
    private val log = LoggerFactory.getLogger(QuotaSnapshotMonitor::class.java)

    @Volatile
    var quotaSnapshot: QuotaSnapshot? = null
        private set

    private val quotaRefreshInFlight = AtomicBoolean(false)

    // The local scanner's manual batch must keep its transaction until it commits or rolls back, and it can stay open
    // for a whole pass: a refresh that finds it open leaves the values here instead of waiting with the provider open.
    // The next refresh, or the shutdown, writes them, unless a newer snapshot has replaced them by then.
    private val unpersistedQuota = AtomicReference<Map<String, String>?>(null)

    /** Seeds the snapshot from the cached quota tuple `quota` persists (same keys, one shared cache). */
    fun seedFromCache(db: StateDatabase) {
        val used = db.getSyncState("quota_used")?.toLongOrNull() ?: return
        val total = db.getSyncState("quota_total")?.toLongOrNull() ?: return
        val remaining = db.getSyncState("quota_remaining")?.toLongOrNull()
        val fetchedAt = db.getSyncState("quota_fetched_at")?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: return
        val age = System.currentTimeMillis() - fetchedAt.toEpochMilli()
        quotaSnapshot = QuotaSnapshot(
            usedBytes = used,
            totalBytes = total,
            remainingBytes = remaining,
            fetchedAtMs = fetchedAt.toEpochMilli(),
            stale = quotaRefreshMs <= 0 || age >= quotaRefreshMs,
            error = null,
        )
    }

    /**
     * Triggers a single-flight quota fetch when a client is asking (status request), the TTL
     * has expired (or nothing was fetched yet), and the facility is on. Never blocks the
     * reply: the fetch runs on serveScope and the NEXT status serves the fresh snapshot.
     * The reply's own `stale` flag is recomputed from the TTL, so a client sees staleness
     * immediately even before the fetch lands.
     */
    fun maybeTriggerQuotaRefresh(
        clientCount: Int,
        scope: kotlinx.coroutines.CoroutineScope,
        // archive#778: no fetch while the daemon is paused; the snapshot is still marked stale once its TTL has expired.
        paused: Boolean = false,
    ) {
        if (quotaRefreshMs <= 0 || clientCount <= 0) return
        val snap = quotaSnapshot
        val expired = snap == null || snap.fetchedAtMs == null ||
            System.currentTimeMillis() - snap.fetchedAtMs >= quotaRefreshMs
        if (snap != null && !expired && !snap.stale) return
        if (snap != null && !snap.stale && expired && !quotaRefreshInFlight.get()) {
            // Mark the served snapshot stale at once: a client must not read a TTL-expired
            // value as fresh while the refetch is in flight.
            quotaSnapshot = snap.copy(stale = true)
        }
        if (paused) return
        if (!quotaRefreshInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                retryQuotaPersist(db())
                val provider = providerFactory()
                try {
                    refreshQuotaSnapshotWith(provider)
                } finally {
                    provider.close()
                }
            } catch (e: Exception) {
                log.warn("quota refresh crashed: {}", e.message)
            } finally {
                quotaRefreshInFlight.set(false)
            }
        }
    }

    /**
     * The fetch behind a quota refresh, on the provider instance the caller hands over. It never signs in (#211):
     * [CloudProvider.checkCredentials] refuses a profile whose credentials are not stored, where
     * [CloudProvider.authenticate] would answer by opening a browser on the daemon host — a status poll must stay a
     * status poll. The values land in the snapshot; a failure keeps the last ones and names itself as the stale
     * reason. A cancelled fetch is not a quota error: cancellation goes on, the in-flight flag is cleared by the
     * caller. Returns the snapshot it left (the fresh one, or the last one flagged stale).
     */
    suspend fun refreshQuotaSnapshotWith(provider: CloudProvider): QuotaSnapshot? {
        try {
            provider.checkCredentials()
            val quota = provider.quota()
            val now = System.currentTimeMillis()
            quotaSnapshot =
                QuotaSnapshot(
                    usedBytes = quota.used,
                    totalBytes = quota.total,
                    remainingBytes = quota.remaining,
                    fetchedAtMs = now,
                    stale = false,
                    error = null,
                )
            persistQuotaSnapshot(db(), quotaSnapshot!!)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep the last values; flag them stale and name the failure.
            quotaSnapshot = (quotaSnapshot ?: QuotaSnapshot()).copy(
                stale = true,
                error = "${e.javaClass.simpleName}: ${e.message ?: "quota fetch failed"}",
            )
            log.warn("quota refresh failed; serving the last snapshot flagged stale: {}", e.message)
        }
        return quotaSnapshot
    }

    // Written through the daemon's own database. A second StateDatabase on the same file ran initialize() (schema
    // writes) and met the write lock of a merge chunk on a connection of its own, with only its busy timeout to wait.
    fun persistQuotaSnapshot(
        db: StateDatabase?,
        snap: QuotaSnapshot,
    ) {
        db ?: return
        val values =
            linkedMapOf(
                "quota_used" to (snap.usedBytes?.toString() ?: return),
                "quota_total" to (snap.totalBytes?.toString() ?: return),
                "quota_fetched_at" to Instant.ofEpochMilli(snap.fetchedAtMs ?: return).toString(),
            )
        snap.remainingBytes?.let { values["quota_remaining"] = it.toString() }
        unpersistedQuota.set(values)
        retryQuotaPersist(db)
    }

    fun retryQuotaPersist(db: StateDatabase?) {
        db ?: return
        val values = unpersistedQuota.get() ?: return
        if (runCatching { db.trySetSyncStatesWhenIdle(values) }.getOrDefault(true)) unpersistedQuota.compareAndSet(values, null)
    }
}
