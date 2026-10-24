package org.krost.unidrive.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.ViewKind
import org.krost.unidrive.ViewList
import org.krost.unidrive.ViewRef
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/** How far a view can be trusted; the wire word is `state` in `daemon.status` `views`. */
enum class ViewState(
    val wire: String,
) {
    /** The list is the provider's latest answer. */
    OK("ok"),

    /** The last refresh failed; the list before it is served. */
    STALE("stale"),

    /** There is no list: it was never fetched successfully, or the provider answers that it has no such view. */
    UNAVAILABLE("unavailable"),
}

/**
 * What a view answers right now: the provider's references ([refs], in rank order), how far to trust them ([state]),
 * when they were fetched ([asOfMs], null while nothing was), a [generation] that moves exactly when the list changes, and
 * whether the list was cut ([truncated]: the provider has more references than these).
 */
class ViewSnapshot(
    val kind: ViewKind,
    val refs: List<ViewRef>,
    val state: ViewState,
    val asOfMs: Long?,
    val generation: Long,
    val truncated: Boolean = false,
)

/**
 * One page of a view, for a front-end's own list (`view.page`): the provider's references from [offset] on, at most the
 * limit asked for, in rank order. [end] is true when no reference lies after these: the provider answered fewer than were
 * asked for, or the page reaches the most the view may fetch. [state] and [asOfMs] are those of the list the page was cut from.
 */
class ViewPage(
    val kind: ViewKind,
    val offset: Int,
    val refs: List<ViewRef>,
    val end: Boolean,
    val state: ViewState,
    val asOfMs: Long?,
)

/**
 * The provider's references of each view, kept in memory: the only thing a view costs besides the rows it joins.
 *
 * - **Single flight.** At most one request per kind is in flight; every caller that needs one waits for that one.
 * - **TTL.** A list younger than [ttlMs] (counted from the start of the last attempt, failed or not) is answered as it
 *   is. Nothing runs on a timer: while nobody asks, nothing is requested.
 * - **Stale while revalidate.** [get] on a list older than the TTL answers it at once and starts the refresh behind it.
 *   Only a view with no list yet waits for the first fill, at most [waitBoundMs]; past that it answers empty
 *   (`unavailable`) and the fill goes on, reported through [onChanged] when it lands.
 * - **Failure.** A refresh that fails keeps the list before it (`stale`); with none, `unavailable`. A provider that answers
 *   [CapabilityResult.Unsupported] has no such view: the list is empty and `unavailable`.
 * - **Cut.** A list longer than the limit of its kind is cut to it; that cut, or one the provider reports ([ViewList]), makes the
 *   snapshot [ViewSnapshot.truncated] until a list that was not cut lands.
 *
 * - **Pages.** [page] answers a slice of the list for a front-end's own list, which may reach past the limit of the kind, up to
 *   [pageCapOf]. A page within the limit is cut from the list above (the folder's, same TTL, same single flight). A page past it
 *   is cut from a second, longer list per kind, fetched with the size the page needs (rounded up to [PAGE_STEP], so odd sizes
 *   cannot make a request each) and kept under the same rules: one request in flight per kind, at most one request per TTL for
 *   a size no larger than one already asked for, the list older than the TTL answered at once and refreshed behind. The
 *   provider's route has no offset, so the engine asks for the first `offset + limit` and cuts. The longer list never changes
 *   the folder's and sends no [onChanged].
 *
 * The refreshes run in [scope], so a caller that stops waiting (or is cancelled) does not abort the fill. [onChanged] is
 * called, outside any lock, when a refresh changed the list a caller sees.
 */
class ViewCache(
    private val scope: CoroutineScope,
    private val fetch: suspend (kind: ViewKind, limit: Int) -> CapabilityResult<List<ViewRef>>,
    private val limitOf: (ViewKind) -> Int,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val waitBoundMs: Long = DEFAULT_WAIT_BOUND_MS,
    private val onChanged: (ViewKind) -> Unit = {},
    private val log: Logger = LoggerFactory.getLogger(ViewCache::class.java),
    private val pageCapOf: (ViewKind) -> Int = limitOf,
) {
    private class Slot {
        var refs: List<ViewRef>? = null
        var fetchedAtMs: Long = 0
        var attemptedAtMs: Long = NEVER
        var generation: Long = 0
        var state: ViewState = ViewState.UNAVAILABLE
        var flight: Job? = null
        var failing: Boolean = false
        var truncated: Boolean = false

        // The longer list of [page] only: how many references the held list was asked for, and the last attempt.
        var size: Int = 0
        var attemptedSize: Int = 0
    }

    private val slots = ConcurrentHashMap<ViewKind, Slot>()

    // The longer list per kind that pages past the folder's limit are cut from.
    private val deepSlots = ConcurrentHashMap<ViewKind, Slot>()

    private fun slot(kind: ViewKind): Slot = slots.computeIfAbsent(kind) { Slot() }

    /**
     * The references of view [kind] from [offset] on, at most [limit] of them (both checked by the caller: offset >= 0, limit
     * >= 1). Within the folder's limit the folder's list answers; past it the longer list does (see the class doc). Nothing
     * lies past [pageCapOf]: a page that starts there is empty and ends the list, and asks the provider nothing.
     */
    suspend fun page(
        kind: ViewKind,
        offset: Int,
        limit: Int,
    ): ViewPage {
        require(offset >= 0 && limit >= 1) { "a page starts at 0 or later and holds at least one reference" }
        val primary = limitOf(kind)
        val cap = maxOf(pageCapOf(kind), primary)
        if (offset >= cap) return peek(kind).let { ViewPage(kind, offset, emptyList(), end = true, state = it.state, asOfMs = it.asOfMs) }
        val want = minOf(cap.toLong(), offset.toLong() + limit).toInt()
        val (refs, askedFor, snapshot) =
            if (want <= primary) {
                get(kind).let { Triple(it.refs, primary, it) }
            } else {
                deep(kind, minOf(cap, (want + PAGE_STEP - 1) / PAGE_STEP * PAGE_STEP))
            }
        // More may follow when the provider filled what it was asked for and a longer list may still be asked for.
        val more = refs.size >= askedFor && want < cap
        val end = !more && offset.toLong() + limit >= refs.size
        return ViewPage(kind, offset, refs.drop(offset).take(limit), end, snapshot.state, snapshot.asOfMs)
    }

    // The longer list of [kind], at least [size] references long where the provider has them: (refs, the size they were asked
    // for, the snapshot). Waits (at most the bound) only when the list held is shorter than [size].
    private suspend fun deep(
        kind: ViewKind,
        size: Int,
    ): Triple<List<ViewRef>, Int, ViewSnapshot> {
        val slot = deepSlots.computeIfAbsent(kind) { Slot() }
        // Twice at most: a flight for a shorter list is waited for, and then one for this size may start.
        repeat(2) {
            val wait = deepStep(kind, slot, size) ?: return deepAnswer(kind, slot, size)
            withTimeoutOrNull(waitBoundMs) { wait.join() } ?: return deepAnswer(kind, slot, size)
        }
        return deepAnswer(kind, slot, size)
    }

    // Starts the fetch the longer list needs, if any, and returns the flight to wait for (null: answer what is held).
    private fun deepStep(
        kind: ViewKind,
        slot: Slot,
        size: Int,
    ): Job? {
        var start: Job? = null
        val wait: Job?
        synchronized(slot) {
            val running = slot.flight?.takeIf { !it.isCompleted }
            val fresh = slot.attemptedAtMs != NEVER && nowMs() - slot.attemptedAtMs in 0 until ttlMs
            if (running == null && (!fresh || slot.attemptedSize < size)) {
                val ask = maxOf(size, if (fresh) slot.attemptedSize else slot.size)
                slot.attemptedAtMs = nowMs()
                slot.attemptedSize = ask
                start = scope.launch(start = CoroutineStart.LAZY) { refreshDeep(kind, slot, ask) }
                slot.flight = start
            }
            val covers = slot.refs != null && slot.size >= size
            wait = if (covers) null else running ?: start
        }
        start?.start()
        return wait
    }

    private fun deepAnswer(
        kind: ViewKind,
        slot: Slot,
        size: Int,
    ): Triple<List<ViewRef>, Int, ViewSnapshot> =
        synchronized(slot) { Triple(slot.refs ?: emptyList(), if (slot.refs != null) slot.size else size, snapshotOf(kind, slot)) }

    @Suppress("TooGenericExceptionCaught") // whatever the provider throws is a failed fetch, never a dead cache
    private suspend fun refreshDeep(
        kind: ViewKind,
        slot: Slot,
        size: Int,
    ) {
        try {
            val answer =
                try {
                    fetch(kind, size)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed(kind, slot, e)
                    null
                }
            if (answer != null) {
                synchronized(slot) {
                    applied(slot, answer, size)
                    slot.size = size
                }
            }
        } finally {
            synchronized(slot) { slot.flight = null }
        }
    }

    /**
     * The view's list. Answers at once when there is one; starts a refresh when it is older than the TTL; waits for
     * the first fill (at most [waitBoundMs]) when there is none.
     */
    suspend fun get(kind: ViewKind): ViewSnapshot {
        val slot = slot(kind)
        var start: Job? = null
        var fill: Job? = null
        synchronized(slot) {
            val running = slot.flight?.takeIf { !it.isCompleted }
            // An attempt that lies in the future (the clock was set back) is as good as old: never wait for the clock to catch up.
            if (running == null && (slot.attemptedAtMs == NEVER || nowMs() - slot.attemptedAtMs !in 0 until ttlMs)) {
                start = launchRefresh(kind, slot)
                slot.flight = start
            }
            if (slot.refs == null) fill = running ?: start
        }
        // Started only now, with the flight already recorded: a refresh that finishes at once finds it and clears it.
        start?.start()
        fill?.let { withTimeoutOrNull(waitBoundMs) { it.join() } }
        return synchronized(slot) { snapshotOf(kind, slot) }
    }

    /** What the view holds now, without asking the provider and without starting anything. */
    fun peek(kind: ViewKind): ViewSnapshot = slot(kind).let { s -> synchronized(s) { snapshotOf(kind, s) } }

    private fun snapshotOf(
        kind: ViewKind,
        slot: Slot,
    ): ViewSnapshot =
        ViewSnapshot(
            kind = kind,
            refs = slot.refs ?: emptyList(),
            state = slot.state,
            asOfMs = if (slot.refs != null) slot.fetchedAtMs else null,
            generation = slot.generation,
            truncated = slot.refs != null && slot.truncated,
        )

    private fun launchRefresh(
        kind: ViewKind,
        slot: Slot,
    ): Job {
        slot.attemptedAtMs = nowMs()
        return scope.launch(start = CoroutineStart.LAZY) { refresh(kind, slot) }
    }

    @Suppress("TooGenericExceptionCaught") // whatever the provider throws is a failed refresh, never a dead cache
    private suspend fun refresh(
        kind: ViewKind,
        slot: Slot,
    ) {
        var changed = false
        val limit = limitOf(kind)
        try {
            val answer: CapabilityResult<List<ViewRef>>? =
                try {
                    fetch(kind, limit)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed(kind, slot, e)
                    null
                }
            if (answer != null) changed = synchronized(slot) { applied(slot, answer, limit) }
        } finally {
            synchronized(slot) { slot.flight = null }
        }
        if (changed) onChanged(kind)
    }

    private fun failed(
        kind: ViewKind,
        slot: Slot,
        cause: Exception,
    ) {
        val firstOfStreak =
            synchronized(slot) {
                slot.state = if (slot.refs != null) ViewState.STALE else ViewState.UNAVAILABLE
                !slot.failing.also { slot.failing = true }
            }
        if (firstOfStreak) {
            log.warn("The {} view could not be refreshed: {}", ViewNamespaces.wireName(kind), cause.message ?: cause.javaClass.simpleName)
        } else {
            log.debug("The {} view could not be refreshed: {}", ViewNamespaces.wireName(kind), cause.message)
        }
    }

    // Called with the slot locked. True when the list a caller sees is another one now. A list longer than [limit] is cut to it
    // here, and a cut, here or by the provider ([ViewList]), is remembered for the status.
    private fun applied(
        slot: Slot,
        answer: CapabilityResult<List<ViewRef>>,
        limit: Int,
    ): Boolean {
        val before = slot.refs ?: emptyList()
        slot.failing = false
        val after =
            when (answer) {
                is CapabilityResult.Success -> {
                    slot.state = ViewState.OK
                    slot.fetchedAtMs = nowMs()
                    val refs = answer.value
                    slot.truncated = (refs as? ViewList)?.truncated == true || refs.size > limit
                    refs.take(limit)
                }
                is CapabilityResult.Unsupported -> {
                    slot.state = ViewState.UNAVAILABLE
                    slot.truncated = false
                    emptyList()
                }
            }
        slot.refs = if (answer is CapabilityResult.Success) after else null
        val changed = before != after
        if (changed) slot.generation++
        return changed
    }

    companion object {
        /** How long a list is answered as it is before the next question starts a refresh. */
        const val DEFAULT_TTL_MS: Long = 60_000L

        /** The longest a caller waits for the first fill of a view. */
        const val DEFAULT_WAIT_BOUND_MS: Long = 5_000L

        /** The step a longer list's size is rounded up to: the page a front-end asks for. */
        const val PAGE_STEP: Int = 50

        private const val NEVER: Long = Long.MIN_VALUE
    }
}
