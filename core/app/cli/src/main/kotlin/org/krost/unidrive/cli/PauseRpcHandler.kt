package org.krost.unidrive.cli

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.krost.unidrive.hydration.HydrationEvent
import org.krost.unidrive.hydration.PauseGate

/**
 * IPC verb handlers for `daemon.pause` and `daemon.resume` (docs/dev/specs/unidrive-daemon-design.md §4.7).
 * Both need the full scope (`IpcAuth.VERB_CLASSES`: admin); the state is [gate], held in memory only.
 *
 * - `daemon.pause {reason, auto, until_ms}`: `reason` is a short machine word the client words itself (`metered`,
 *   `power_saver`, `user`): 1 to [REASON_MAX_LENGTH] characters of `a-z`, `0-9` and `_`. A missing reason answers
 *   `missing_reason`, any other that is not such a word `invalid_reason`; `auto` (default false: a person
 *   paused) must be a JSON boolean or the answer is `invalid_auto`. `until_ms` (optional) is the epoch ms at which
 *   the daemon resumes by itself: it must be a JSON integer (`invalid_until`) after the daemon's clock now
 *   (`until_in_past`). A refusal changes nothing. The reply is `{"ok":true,"paused":{reason,since_ms,auto[,until_ms]}}`.
 *   Pausing while paused keeps the first `since_ms` and takes the new reason, `auto` and `until_ms`: a request names the
 *   whole pause, so one without `until_ms` makes a timed pause untimed, and one with a new `until_ms` replaces the timer.
 * - `daemon.resume`: `{"ok":true}`, also when the daemon was running (a no-op). It cancels the timer.
 *
 * The timer lives in [scope] and is as volatile as the pause: a restarted daemon runs. It checks the wall clock
 * ([PauseGate.clockMs]) at least every [CLOCK_RECHECK_MS], so a clock that was set or a machine that slept through
 * the instant resumes the daemon within that interval of waking, not at a monotonic deadline.
 *
 * A pause that begins applies the in-flight rule at once ([PauseGate.abortSlowTransfers]), and every change a
 * client can observe (a pause begun, a reason, `auto` or end instant changed, a pause ended by `daemon.resume` or by
 * the timer) goes to [publish] as an event. Pausing with the state the daemon already has, and resuming a running
 * daemon, publish nothing.
 */
class PauseRpcHandler(
    private val gate: PauseGate,
    private val scope: CoroutineScope,
    private val publish: (HydrationEvent) -> Unit,
) {
    // Guarded by synchronized(gate), like the state it belongs to.
    private var timer: Job? = null

    fun pause(jsonRequest: String): String {
        val body = jsonRequest.asJsonObjectOrNull()
        val reasonElement = body?.get("reason") ?: return MISSING_REASON
        val reason = (reasonElement as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (reason == null || !REASON.matches(reason)) return INVALID_REASON
        val auto =
            when (val element = body["auto"]) {
                null -> false
                else -> element.strictBooleanOrNull() ?: return INVALID_AUTO
            }
        val untilMs =
            when (val element = body["until_ms"]) {
                null -> null
                else -> element.strictLongOrNull() ?: return INVALID_UNTIL
            }
        if (untilMs != null && untilMs <= gate.clockMs()) return UNTIL_IN_PAST
        // State, abort and event in one step: two requests at once cannot swap their events.
        synchronized(gate) {
            val change = gate.pause(reason, auto, untilMs)
            val state = checkNotNull(gate.current) { "paused just now" }
            if (change == PauseGate.Change.STARTED) gate.abortSlowTransfers()
            if (change != PauseGate.Change.UNCHANGED) {
                armTimer(untilMs)
                publish(HydrationEvent.Paused(state.reason, state.sinceMs, state.auto, state.untilMs))
            }
            return buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("paused", state.toJson())
            }.toString()
        }
    }

    fun resume(): String {
        synchronized(gate) {
            cancelTimer()
            if (gate.resume()) publish(HydrationEvent.Resumed())
        }
        return """{"ok":true}"""
    }

    private fun cancelTimer() {
        timer?.cancel()
        timer = null
    }

    /**
     * The programmatic pause behind `vault.lock` (issue#796): the same gate, in-flight rule and
     * event as `daemon.pause`, with the fixed reason [VAULT_LOCKED_REASON] (`auto`, untimed). A
     * no-op when the daemon is already paused with exactly that state (no event, no change). The
     * timer of a pause this replaces is cancelled with the transition — a timed pause's deadline
     * must not resume the untimed vault lock (issue#796 review).
     */
    fun pauseForVaultLock() {
        synchronized(gate) {
            val change = gate.pause(VAULT_LOCKED_REASON, auto = true, untilMs = null)
            cancelTimer()
            if (change == PauseGate.Change.UNCHANGED) return
            gate.abortSlowTransfers()
            val state = checkNotNull(gate.current) { "paused just now" }
            publish(HydrationEvent.Paused(state.reason, state.sinceMs, state.auto, state.untilMs))
        }
    }

    /** The resume behind `vault.unlock` after a `vault.lock`: true when a pause of this daemon actually ended. */
    fun resumeForVaultUnlock(): Boolean =
        synchronized(gate) {
            cancelTimer()
            gate.resume().also { if (it) publish(HydrationEvent.Resumed()) }
        }

    /**
     * #1073: the programmatic pause behind a full remote drive — the same gate, in-flight rule and event as
     * `daemon.pause`, with the fixed reason [PauseGate.QUOTA_HOLD_REASON] (`auto`, untimed). The hold fires ONCE per
     * crossing: a repeat while held is the same pause (no event, no change). It does not displace a person's pause
     * (`auto=false`) or the vault lock — the queue is already stopped there, and a hold whose headless release
     * resumed a pause it did not begin would un-pause the person or serve a locked vault. A timed pause it replaces
     * has its timer cancelled with the transition, like the vault lock's. Returns whether the hold is in force
     * afterwards (true also when it already stood).
     */
    fun pauseForQuota(): Boolean =
        synchronized(gate) {
            val current = gate.current
            when {
                current?.reason == PauseGate.QUOTA_HOLD_REASON -> return@synchronized true
                current != null && (!current.auto || current.reason == VAULT_LOCKED_REASON) -> return@synchronized false
            }
            val change = gate.pause(PauseGate.QUOTA_HOLD_REASON, auto = true, untilMs = null)
            if (change == PauseGate.Change.UNCHANGED) return@synchronized true
            cancelTimer()
            gate.abortSlowTransfers()
            val state = checkNotNull(gate.current) { "paused just now" }
            publish(HydrationEvent.Paused(state.reason, state.sinceMs, state.auto, state.untilMs))
            true
        }

    /**
     * The headless release behind #1073: ends exactly the quota hold — a pause of any other reason stands. True when
     * a quota hold actually ended (the `resumed` event went out), false when nothing was held or the pause is another's.
     */
    fun resumeForQuotaRelease(): Boolean =
        synchronized(gate) {
            if (gate.current?.reason != PauseGate.QUOTA_HOLD_REASON) return@synchronized false
            gate.resume().also { if (it) publish(HydrationEvent.Resumed()) }
        }

    // Caller holds synchronized(gate). Replaces any earlier timer; null [untilMs] leaves the pause untimed.
    private fun armTimer(untilMs: Long?) {
        cancelTimer()
        if (untilMs == null) return
        timer =
            scope.launch {
                val self = coroutineContext.job
                while (true) {
                    val remaining = untilMs - gate.clockMs()
                    if (remaining <= 0) break
                    delay(minOf(remaining, CLOCK_RECHECK_MS))
                }
                synchronized(gate) {
                    // A resume or a newer pause that raced this wake-up has already replaced the timer.
                    if (timer !== self) return@launch
                    timer = null
                    if (gate.resume()) publish(HydrationEvent.Resumed(HydrationEvent.Resumed.TIMEOUT))
                }
            }
    }

    companion object {
        const val REASON_MAX_LENGTH = 32

        /** The reason the programmatic pause of `vault.lock` pauses with (issue#796). */
        const val VAULT_LOCKED_REASON = "vault_locked"

        /** The longest the timer sleeps before it reads the wall clock again. */
        const val CLOCK_RECHECK_MS = 30_000L

        private val REASON = Regex("[a-z0-9_]{1,$REASON_MAX_LENGTH}")

        private const val MISSING_REASON = """{"ok":false,"error":"missing_reason"}"""
        private const val INVALID_REASON = """{"ok":false,"error":"invalid_reason"}"""
        private const val INVALID_AUTO = """{"ok":false,"error":"invalid_auto"}"""
        private const val INVALID_UNTIL = """{"ok":false,"error":"invalid_until"}"""
        private const val UNTIL_IN_PAST = """{"ok":false,"error":"until_in_past"}"""
    }
}
