package org.krost.unidrive.hydration

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * The whole-sweep progress of a released delete hold (gkrost/unidrive-cli#851). When the client releases a hold, it
 * drains the held set by sending the deletes over the write verbs, each marked with the hold's size (`sweep_total`):
 * the count the mount publishes only goes down, so a client that joins late cannot show "remaining of total", an ETA,
 * or whether a bar from an earlier sweep is still the same one. The daemon tracks what the marks tell it and answers
 * from [snapshot]: the size the sweep started with, what is still owed, when the first delete landed, and a sweep id
 * that changes with every new sweep. Counts only, no paths — the held record is counts-only by design (windows#1310),
 * and this status sits where other programs read it.
 *
 * The tracker counts the ops the client counts: one marked `hydration.unlink` or `hydration.rmdir` (recursive or not)
 * is one item. A marked delete that answers an error stays owed; the client retries it, and its later completion
 * decrements. A sweep whose deletes stop landing is cleared after [SWEEP_QUIET_MS] (a big subtree rmdir can occupy the
 * engine for minutes, gkrost/unidrive-cli#634, so the window is generous like the outcome table's TTL) — the fields
 * then read as absent, the same as after the last item landed. Thread-safe: the IPC handlers and the status reply run
 * on different pools.
 */
class DeleteSweepProgress(private val nowMs: () -> Long = System::currentTimeMillis) {
    /**
     * One sweep as the status reports it, present only while the sweep is under way. This is the `delete_sweep`
     * object of `daemon.status`: counts only, no paths, like every shared record of the held deletes. It renders
     * itself, rather than beside the cli status builders, to keep that file's function budget free.
     */
    data class Snapshot(
        val sweepId: String,
        val total: Int,
        val remaining: Int,
        val startedAtMs: Long,
    ) {
        fun toJson(): JsonObject =
            buildJsonObject {
                put("sweep_id", sweepId)
                put("total", total)
                put("remaining", remaining)
                put("started_at_ms", startedAtMs)
            }
    }

    private class Sweep(
        val sweepId: String,
        val total: Int,
        var remaining: Int,
        val startedAtMs: Long,
        var lastActivityAtMs: Long,
    )

    private var active: Sweep? = null

    /**
     * A request marked `sweep_total` arrived. The first mark of a sweep starts it; a repeat of the same total (the
     * client re-declares on the ops it sends later, or after its own restart) keeps the sweep with its counted
     * completions; a different total is a new hold — a new sweep with a new id and a full remaining.
     */
    @Synchronized
    fun declared(total: Int) {
        if (total <= 0) return
        val now = nowMs()
        val current = live(now)
        if (current != null && current.total == total) {
            current.lastActivityAtMs = now
            return
        }
        active = Sweep(UUID.randomUUID().toString(), total, remaining = total, startedAtMs = now, lastActivityAtMs = now)
    }

    /** A marked delete answered ok: one op off the count, and the sweep's quiet window restarts. */
    @Synchronized
    fun completed() {
        val now = nowMs()
        val current = live(now) ?: return
        current.lastActivityAtMs = now
        current.remaining -= 1
        if (current.remaining <= 0) active = null
    }

    /**
     * The sweep to report, or null when none is under way: nothing was declared, the last delete landed, or no marked
     * delete reached the engine for [SWEEP_QUIET_MS].
     */
    @Synchronized
    fun snapshot(): Snapshot? {
        val current = live(nowMs()) ?: return null
        return Snapshot(current.sweepId, current.total, current.remaining, current.startedAtMs)
    }

    /** [active] with the quiet expiry applied; a sweep the deletes stopped reaching is forgotten on the spot. */
    private fun live(now: Long): Sweep? {
        val current = active ?: return null
        if (now - current.lastActivityAtMs > SWEEP_QUIET_MS) {
            active = null
            return null
        }
        return current
    }

    companion object {
        /**
         * How long a sweep survives without a marked delete reaching the engine. The drain keeps landing ops within
         * seconds of each other; a single subtree rmdir of a large folder can occupy the engine for minutes before its
         * completion (gkrost/unidrive-cli#634 measured ~1.3 entries/s), so the window is generous, like the
         * [HydrationIpcHandler.OUTCOME_TTL_MS] the handle outcomes keep.
         */
        const val SWEEP_QUIET_MS: Long = 10 * 60_000L

        /**
         * The most items one sweep's mark can name. A hold of this many items would outlast any drain the client
         * drives; a mark past it, or a number too wide for the request parser's count window, reads as unmarked rather
         * than as a bar that can never drain.
         */
        const val SWEEP_TOTAL_MAX: Int = 10_000_000
    }
}
