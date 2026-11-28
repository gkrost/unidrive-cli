package org.krost.unidrive.cli

import kotlinx.coroutines.launch
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.engine.QuotaState
import org.krost.unidrive.sync.StateDatabase
import org.slf4j.LoggerFactory
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
    // The daemon's own provider instance (null before it is built and after it is closed): the refresh uses it and never
    // closes it. A second instance would hold a token state of its own, refresh the same token and write the same file,
    // and its throttling would reach neither the budget nor the request metrics.
    private val provider: () -> CloudProvider?,
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
        val cached = QuotaState(db)
        val used = cached.used ?: return
        val total = cached.total ?: return
        val remaining = cached.remaining
        val fetchedAt = cached.fetchedAt ?: return
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
     *
     * #1073: an account-level quota hold (`quotaHold`) fetches without a client and against the
     * paused rule — the hold releases headless, on the poll cadence, so the snapshot must keep
     * moving exactly when the daemon is held.
     */
    fun maybeTriggerQuotaRefresh(
        clientCount: Int,
        scope: kotlinx.coroutines.CoroutineScope,
        // archive#778: no fetch while the daemon is paused; the snapshot is still marked stale once its TTL has expired.
        paused: Boolean = false,
        // #1073: the quota hold is active; the fetch runs headless and pause-immune.
        quotaHold: Boolean = false,
    ) {
        if (quotaRefreshMs <= 0) return
        if (clientCount <= 0 && !quotaHold) return
        val snap = quotaSnapshot
        val expired = snap == null || snap.fetchedAtMs == null ||
            System.currentTimeMillis() - snap.fetchedAtMs >= quotaRefreshMs
        if (snap != null && !expired && !snap.stale) return
        if (snap != null && !snap.stale && expired && !quotaRefreshInFlight.get()) {
            // Mark the served snapshot stale at once: a client must not read a TTL-expired
            // value as fresh while the refetch is in flight.
            quotaSnapshot = snap.copy(stale = true)
        }
        if (paused && !quotaHold) return
        if (!quotaRefreshInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                retryQuotaPersist(db())
                provider()?.let { refreshQuotaSnapshotWith(it) }
            } catch (e: Exception) {
                log.warn("quota refresh crashed: {}", e.message)
            } finally {
                quotaRefreshInFlight.set(false)
            }
        }
    }

    /**
     * #1073: the hold's own refresh — a fetch now, past the TTL, single-flight with the client-triggered
     * ones (a status poll may be refreshing the same moment). Without a provider it returns the snapshot
     * as it stands, so a caller releases only on a fresh fetch, never on a missing one.
     */
    suspend fun refreshForQuotaHold(provider: CloudProvider?): QuotaSnapshot? {
        provider ?: return quotaSnapshot
        if (!quotaRefreshInFlight.compareAndSet(false, true)) return quotaSnapshot
        try {
            retryQuotaPersist(db())
            return refreshQuotaSnapshotWith(provider)
        } finally {
            quotaRefreshInFlight.set(false)
        }
    }

    /**
     * #1073: a typed remote-space refusal is ground truth — the provider refused a write for want of room,
     * while the usage endpoint may lag or under-count (a full drive was seen reporting 84 %). Marks the
     * cached snapshot full (used = total, remaining = 0) as of now and writes it through the daemon's
     * database like a fetched snapshot, so `quota --json` and the next start read it too. Without a known
     * total there is nothing to reconcile (a provider without a quota concept relies on the refusal alone).
     * Returns whether the snapshot was reconciled.
     */
    fun markFullFromRefusal(nowMs: Long = System.currentTimeMillis()): Boolean {
        val total = quotaSnapshot?.totalBytes ?: return false
        val snapshot =
            QuotaSnapshot(
                usedBytes = total,
                totalBytes = total,
                remainingBytes = 0,
                fetchedAtMs = nowMs,
                stale = false,
                error = null,
            )
        quotaSnapshot = snapshot
        persistQuotaSnapshot(db(), snapshot)
        return true
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
            val snapshot =
                QuotaSnapshot(
                    usedBytes = quota.used,
                    totalBytes = quota.total,
                    remainingBytes = quota.remaining,
                    fetchedAtMs = now,
                    stale = false,
                    error = null,
                )
            quotaSnapshot = snapshot
            persistQuotaSnapshot(db(), snapshot)
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
            QuotaState.cacheValues(
                usedBytes = snap.usedBytes,
                totalBytes = snap.totalBytes,
                remainingBytes = snap.remainingBytes,
                fetchedAtMs = snap.fetchedAtMs,
            ) ?: return
        unpersistedQuota.set(values)
        retryQuotaPersist(db)
    }

    fun retryQuotaPersist(db: StateDatabase?) {
        db ?: return
        val values = unpersistedQuota.get() ?: return
        if (runCatching { db.trySetSyncStatesWhenIdle(values) }.getOrDefault(true)) unpersistedQuota.compareAndSet(values, null)
    }
}
