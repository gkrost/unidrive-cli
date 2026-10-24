package org.krost.unidrive.cli

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.krost.unidrive.sync.EnumerateResult
import org.slf4j.LoggerFactory
import kotlin.random.Random

/**
 * The daemon's remote-change discovery (mount-view-refresh-design.md §5, #463). When
 * [intervalMs] > 0 the daemon launches ONE periodic coroutine on its serve scope that fires the
 * enumerate path (engine.enumerateRemoteIntoState(reset=false)) every interval — the same
 * operation `sync.enumerate` runs — serialised by the shared in-flight guard so a tick never
 * overlaps a manual refresh/enumerate or another tick. `daemon run` polls by default (profile key
 * `daemon_poll_seconds`, 60 s; `--poll-interval` overrides, 0 = off): the notification channel is
 * not reliable, and a mount profile has no other way to learn what changed in the cloud.
 *
 * - The first poll runs [firstPollDelayMs] after start (default: at once), so a daemon that comes
 *   back from a stop or an outage catches up right away instead of one interval later.
 * - A poll is skipped while [isBusy] says a refresh is running (the legacy reconcile does not hold
 *   the enumerate guard).
 * - On a provider failure / 429 it extends the next interval (back-off) rather than hammering, the
 *   back-off ESCALATES with consecutive failures (each failure multiplies the previous interval,
 *   capped at [maxBackoffMs], or at the interval when that is longer) — a flat first step repeated forever re-ran a doomed cycle every
 *   ~7 min for hours on the live account (#517 R3) — and it reports through [onNextAttempt] when it
 *   will try again (the jittered sleep it really takes) until the next run starts.
 * - A pass that succeeds but leaves the recovery latch set (`EnumerateResult.recoveryPending`: the scan was incomplete, so
 *   the next pass lists the whole drive again) backs off the same way, so a drive that cannot be listed completely is not
 *   listed again one interval after each attempt (#926). Unlike a failure it is no `failed` state: no `next_attempt_at_ms`.
 * - [providerReachable] ends a back-off early: when a transfer through the daemon succeeded, the
 *   provider answers again, and the next poll runs one plain interval after the failed one instead
 *   of up to [maxBackoffMs] later. Outside a back-off the signal changes nothing.
 *
 * - While the daemon is paused (#778) no tick enumerates: a tick waits for the resume ([awaitResumed]) and runs
 *   at once when the pause ends.
 * - A provider's remote-change hint ([wake], #111) ends a plain sleep early: the enumerate runs after a 2 s quiet
 *   window instead of up to one interval later. The poll itself stays (the push channels are best effort).
 *
 * Cancelled cleanly when the serve scope is cancelled at shutdown.
 */
// Every tuning knob and test seam is its own parameter: the virtual-time tests set them one by one.
@Suppress("LongParameterList")
class EnumeratePoller(
    private val handler: EnumerateRpcHandler,
    private val intervalMs: Long,
    private val scope: CoroutineScope,
    // ±10% jitter on each sleep (thundering-herd avoidance across profiles).
    // Injectable for deterministic virtual-time tests.
    private val jitter: (Long) -> Long = { base ->
        val span = (base / 10).coerceAtLeast(1)
        base - span + Random.nextLong(2 * span + 1)
    },
    private val backoffMultiplier: Long = DEFAULT_BACKOFF_MULTIPLIER,
    private val maxBackoffMs: Long = DEFAULT_MAX_BACKOFF_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onNextAttempt: (epochMs: Long?) -> Unit = {},
    // Consecutive enumerate failures already on record when the daemon starts
    // (SyncEngine.ENUMERATE_FAILURE_STREAK_KEY in sync_state): the first sleep
    // is escalated as if those failures had just happened, so a restart into a
    // known-bad remote doesn't re-run the doomed cycle a fresh process would
    // otherwise pay for immediately. The escalated first sleep is reported
    // through [onNextAttempt] like any post-failure one.
    private val consecutiveFailuresAtStart: Int = 0,
    // #463: delay of the first poll after start when no failure streak is on record; 0 = at once.
    // A value > 0 is jittered like every later sleep.
    private val firstPollDelayMs: Long = 0,
    // #463: true while something else works the state database the way a poll would (a refresh);
    // the tick is then skipped like one that met the enumerate guard.
    private val isBusy: () -> Boolean = { false },
    // #778: returns at once while the daemon runs and suspends while it is paused. A tick whose sleep ends in a
    // pause waits here and runs the moment the pause ends, so the poll catches up instead of losing an interval.
    private val awaitResumed: suspend () -> Unit = {},
    // #111: the quiet window after a remote-change hint ([wake]); the mirror's SyncEngine.registerRemoteWakeListener uses 2 s too.
    private val hintQuietMs: Long = DEFAULT_HINT_QUIET_MS,
) {
    private val log = LoggerFactory.getLogger(EnumeratePoller::class.java)

    // A back-off is never shorter than the plain interval: with daemon_poll_seconds = 3600 a cap of 10 minutes
    // made the poll after a failure come sooner than the poll after a success (#1084).
    private val backoffCapMs = maxOf(maxBackoffMs, intervalMs)

    // Conflated: any number of signals during one sleep count as one.
    private val reachable = Channel<Unit>(Channel.CONFLATED)

    // Conflated too: hints that arrive during one sleep or one run count as one.
    private val hints = Channel<Unit>(Channel.CONFLATED)

    /**
     * #463: something proved that the provider answers (a download or upload through the daemon
     * succeeded). Cuts a back-off short; no effect outside one. Never starts more than one poll per
     * plain interval.
     */
    fun providerReachable() {
        reachable.trySend(Unit)
    }

    /**
     * #111: the provider's push channel says something changed in the cloud ([org.krost.unidrive.CloudProvider.onRemoteChangeHint]).
     * Ends the current plain sleep: the enumerate runs once no further hint came for [hintQuietMs] (a burst is one run), and
     * never later than the scheduled poll. It goes through the same pause gate and in-flight guard as every poll; a run the
     * guard or a refresh skips is tried again after the next quiet window. A hint during a failure back-off (or the back-off
     * after an incomplete scan) does not cut it short. Hints during a run give one run after it. Callable from any thread.
     */
    fun wake() {
        hints.trySend(Unit)
    }

    fun start() {
        if (intervalMs <= 0) return
        log.info("auto-poll enabled: enumerate every ${intervalMs}ms (±10% jitter)")
        scope.launch {
            var nextMs = intervalMs
            repeat(consecutiveFailuresAtStart.coerceAtMost(8)) {
                nextMs = (nextMs * backoffMultiplier).coerceAtMost(backoffCapMs)
            }
            if (nextMs != intervalMs) {
                log.warn("auto-poll: starting at escalated backoff ${nextMs}ms ($consecutiveFailuresAtStart prior failure(s))")
            }
            // The seeded schedule is reported like a post-failure one: a status
            // client sees when the first try of a restarted daemon actually is.
            var afterFailure = consecutiveFailuresAtStart > 0
            // Without a failure streak the first poll is the catch-up after start (#463).
            var firstSleepMs: Long? = if (afterFailure) null else firstPollDelayMs.coerceAtLeast(0)
            // #111: only a plain sleep (after a success, a skipped poll, or before the first poll) ends early on a hint.
            var hintsMayWake = !afterFailure
            while (true) {
                try {
                    val sleepMs = firstSleepMs?.let { if (it > 0) jitter(it) else 0L } ?: jitter(nextMs)
                    firstSleepMs = null
                    var hinted = false
                    if (afterFailure) {
                        onNextAttempt(clock() + sleepMs)
                        sleepInBackoff(sleepMs)
                    } else if (sleepMs > 0) {
                        if (hintsMayWake) hinted = sleepUntilTickOrHint(sleepMs) else delay(sleepMs)
                    }
                    onNextAttempt(null)
                    awaitResumed()
                    // The hints that came before this run are covered by it; the ones during it give one run after it.
                    hints.tryReceive()
                    val result = runOnce(hinted)
                    // The profile was busy (a manual enumerate, a refresh): the hint is not lost, the run is tried again.
                    if (hinted && result == null) hints.trySend(Unit)
                    afterFailure = result != null && !result.ok
                    hintsMayWake = result == null || (result.ok && !result.recoveryPending)
                    nextMs = nextSleepAfter(result, nextMs)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("auto-poll: tick error; backing off", e)
                    afterFailure = true
                    hintsMayWake = false
                    nextMs = (nextMs * backoffMultiplier).coerceAtMost(backoffCapMs)
                }
            }
        }
    }

    private suspend fun runOnce(hinted: Boolean): EnumerateResult? {
        if (isBusy()) return null
        if (hinted && !handler.isInFlight()) log.info("remote change hint: enumerate")
        return handler.runGuarded(reset = false)
    }

    private fun nextSleepAfter(
        result: EnumerateResult?,
        nextMs: Long,
    ): Long =
        when {
            result == null -> intervalMs // busy: another enumerate or a refresh held the profile, skip
            // #926: the pass worked but ended incomplete, and the next one lists the whole drive again
            // (EnumerateResult.recoveryPending). A listing takes minutes on a large drive: it must not start
            // again one plain interval after the last one ended, so it backs off like a failure. A pass that
            // ends the recovery (complete, or the same dropped set twice) returns to the plain interval.
            result.ok && result.recoveryPending -> {
                val backed = (nextMs * backoffMultiplier).coerceAtMost(backoffCapMs)
                log.warn("auto-poll: the last scan was incomplete and the next one lists the whole drive again; backing off to ${backed}ms")
                backed
            }
            result.ok -> intervalMs
            else -> {
                val backed = (nextMs * backoffMultiplier).coerceAtMost(backoffCapMs)
                log.warn("auto-poll: enumerate failed (${result.error}); backing off to ${backed}ms")
                backed
            }
        }

    // #111: a plain sleep that a hint ends early. After the first hint it waits until [hintQuietMs] pass without another
    // one; the whole wait, quiet window included, never runs past the scheduled poll. True when a hint ended the sleep.
    private suspend fun sleepUntilTickOrHint(sleepMs: Long): Boolean {
        var hinted = false
        withTimeoutOrNull(sleepMs) {
            hints.receive()
            hinted = true
            while (withTimeoutOrNull(hintQuietMs) { hints.receive() } != null) {
                // another hint inside the quiet window: the window starts again
            }
        }
        return hinted
    }

    // A back-off sleep that a [providerReachable] signal shortens to one plain interval after its
    // start (the failed attempt). Signals that arrived before this sleep began are dropped: they
    // predate the failure they would excuse.
    private suspend fun sleepInBackoff(sleepMs: Long) {
        reachable.tryReceive()
        val startedAt = clock()
        val signalled = withTimeoutOrNull(sleepMs) { reachable.receive() } != null
        if (!signalled) return
        val remainingMs = startedAt + intervalMs - clock()
        log.info("auto-poll: the provider answers again; next poll in ${remainingMs.coerceAtLeast(0)}ms instead of the back-off")
        if (remainingMs > 0) {
            onNextAttempt(clock() + remainingMs)
            delay(remainingMs)
        }
    }

    companion object {
        const val DEFAULT_BACKOFF_MULTIPLIER: Long = 4
        const val DEFAULT_MAX_BACKOFF_MS: Long = 600_000
        const val DEFAULT_HINT_QUIET_MS: Long = 2_000

        private val DURATION_REGEX = Regex("^(\\d+)(ms|s|m|h)?$")

        /**
         * #463: the poll interval `daemon run` uses: `--poll-interval` when given ([cliValue]),
         * otherwise the profile's `daemon_poll_seconds` ([configSeconds], default 60). 0 = off.
         */
        fun effectiveIntervalMs(
            cliValue: String?,
            configSeconds: Int,
        ): Long = cliValue?.let { parseIntervalMs(it) } ?: (configSeconds.coerceAtLeast(0) * 1_000L)

        /**
         * Parse a `--poll-interval` value to milliseconds. Accepts a bare number
         * (seconds), or a number suffixed `ms`/`s`/`m`/`h` (e.g. `60s`, `5m`).
         * `0` / `0s` means OFF. Throws IllegalArgumentException on a malformed value.
         */
        fun parseIntervalMs(raw: String): Long {
            val m = DURATION_REGEX.matchEntire(raw.trim())
                ?: throw IllegalArgumentException("invalid --poll-interval '$raw' (use e.g. 0, 60s, 5m)")
            val n = m.groupValues[1].toLong()
            return when (m.groupValues[2]) {
                "ms" -> n
                "", "s" -> n * 1_000
                "m" -> n * 60_000
                "h" -> n * 3_600_000
                else -> throw IllegalArgumentException("invalid --poll-interval unit in '$raw'")
            }
        }
    }
}
