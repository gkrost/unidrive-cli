package org.krost.unidrive.engine

import kotlinx.coroutines.delay
import org.krost.unidrive.sync.SyncScope
import java.nio.file.Path
import java.security.MessageDigest
import java.util.TreeSet

/**
 * gkrost/unidrive-cli#860: the live change of a mount daemon's tracked scope behind `scope.set` (owner decision 2026-11-21: in
 * process, no restart). [RemoteOperationGuard.trackScope] is the one owner of the scope: the gather, the enumeration, the
 * mount's `outside_scope` refusals and the live listing all ask it, so the swap replaces it there and runs the
 * [ScopeTransition] the next gather would run, under the enumeration's single-flight flag (no pass is merging while the set
 * changes).
 *
 * A narrower scope never deletes in the cloud (owner decision 2026-11-21): the engine stops tracking the rows of what
 * leaves and drops the clean cache copies of their files. A change that would drop a file only this computer holds (one
 * [RemoteEnumeration.ReapGuards.holdsUnsyncedEdit] names: never uploaded, an edit not uploaded, an upload in flight) is
 * refused whole and changes nothing (owner decision 2026-11-24, windows#1277 Q10).
 */
class ScopeSwap(
    private val wiring: MountWiring,
    // How long [apply] waits for a running enumeration to end before it answers [Outcome.Busy].
    private val maxWaitMs: Long = MAX_WAIT_MS,
    // The hold of the mount's row-adding verbs (the daemon registers them through it); see [apply].
    private val writeGate: ScopeWriteGate = ScopeWriteGate(),
) {
    /** What a change of the scope does to this computer; nothing in the cloud changes. */
    data class Effect(
        /** The top of each subtree that leaves the scope (folders or files), the first [LEAVING_CAP] by path. */
        val leaving: List<String>,
        /** How many more tops leave than [leaving] names. */
        val leavingMore: Int,
        /** The new roots outside the old scope: listed at the next enumeration. */
        val entering: List<String>,
        val folders: Int,
        /** Cloud-only files: their placeholders go. */
        val placeholders: Int,
        /** Files with a clean copy on this computer (offline): the copy goes, the cloud keeps the file. */
        val offlineCopies: Int,
        /** The first [UNSYNCED_PATHS_CAP] files only this computer holds, by path; any of them refuses the change. */
        val unsynced: List<String>,
        val unsyncedCount: Int,
        /** True when the next enumeration lists the drive again (the scope widened). */
        val reEnumerate: Boolean,
    )

    sealed interface Outcome {
        data class Applied(
            val previous: List<String>,
            val scope: List<String>,
            val effect: Effect,
        ) : Outcome

        /** A file only this computer holds would leave; nothing changed. */
        data class Refused(
            val effect: Effect,
        ) : Outcome

        /** An enumeration held the flag longer than the wait; nothing changed. */
        data object Busy : Outcome
    }

    /** The tracked scope now; empty = the whole drive. */
    fun current(): List<String> = wiring.guard.trackScope

    /** What changing to [scope] would do; reads state.db only. */
    fun plan(scope: List<String>): Effect = survey(current(), SyncScope.normalize(scope)).effect

    /**
     * Changes the scope to [scope] (normalised): [persist] writes the whole new set (config.toml) before state.db is
     * reshaped, so a crash after it leaves the next start applying the same transition. A [persist] that throws leaves the
     * scope as it was and rethrows.
     */
    suspend fun apply(
        scope: List<String>,
        persist: (List<String>) -> Unit,
    ): Outcome {
        val target = SyncScope.normalize(scope)
        val flag = wiring.enumeration.enumerateInFlight
        var waitedMs = 0L
        while (!flag.compareAndSet(false, true)) {
            if (waitedMs >= maxWaitMs) return Outcome.Busy
            delay(WAIT_STEP_MS)
            waitedMs += WAIT_STEP_MS
        }
        try {
            // Owner decision 2026-11-24: the swap, the survey and the transition run with the mount's row-adding verbs held
            // ([ScopeWriteGate]), so a write accepted under the old set has added its row before the survey, and one that comes
            // during the hold meets the new set. No provider call is made under the hold.
            val (outcome, survey) = writeGate.exclusive(WRITE_DRAIN_MS) { swapHeld(target, persist) } ?: return Outcome.Busy
            // The cache files go after the hold: file deletes, the row flip is the truth (as the enumeration's reap).
            survey?.let { evictCacheCopies(it.evictions, wiring.enumeration.reapGuards) }
            return outcome
        } finally {
            flag.set(false)
        }
    }

    // Under the enumeration's flag and the write hold. The survey is returned for the cache evictions of an applied change.
    private fun swapHeld(
        target: List<String>,
        persist: (List<String>) -> Unit,
    ): Pair<Outcome, Survey?> {
        val guard = wiring.guard
        val previous = guard.trackScope
        // Swapped first, so nothing that may still run outside the hold (a replay, an upload landing) can widen the set of
        // rows outside the new scope behind the survey with a write the old scope would have let through.
        guard.swapTrackScope(target)
        var kept = false
        val survey =
            try {
                survey(previous, target).also {
                    if (it.effect.unsyncedCount > 0) return Outcome.Refused(it.effect) to null
                    persist(target)
                    kept = true
                }
            } finally {
                // A refusal, or a survey or persist that threw: the scope stays as it was.
                if (!kept) guard.swapTrackScope(previous)
            }
        wiring.gather.applyScopeTransition()
        // The mirror's scoped-history key: a mount profile has it only where it was a mirror once (as `unidrive scope set`).
        if (!wiring.db.getSyncState(EFFECTIVE_SCOPE_KEY).isNullOrEmpty()) wiring.db.setSyncState(EFFECTIVE_SCOPE_KEY, target.joinToString("\t"))
        return Outcome.Applied(previous, target, survey.effect) to survey
    }

    private class Survey(
        val effect: Effect,
        val evictions: List<Triple<String, Path, Long?>>,
    )

    private fun survey(
        previous: List<String>,
        target: List<String>,
    ): Survey {
        val ancestors = SyncScope.ancestors(target)
        val guards = wiring.enumeration.reapGuards
        val leaving = TreeSet<String>()
        val unsynced = TreeSet<String>()
        val evictions = mutableListOf<Triple<String, Path, Long?>>()
        var tops = 0
        var folders = 0
        var placeholders = 0
        var offline = 0
        var unsyncedCount = 0
        wiring.db.scanAliveRows { row ->
            val entry = row.entry()
            val key = entry.remotePath ?: entry.path
            if (SyncScope.contains(key, target) || key in ancestors) return@scanAliveRows
            val parent = key.substringBeforeLast('/', "")
            if (parent.isEmpty() || parent in ancestors) {
                tops++
                leaving += key
                if (leaving.size > LEAVING_CAP) leaving.pollLast()
            }
            when {
                guards.holdsUnsyncedEdit(entry.path, entry) -> {
                    unsyncedCount++
                    unsynced += entry.path
                    if (unsynced.size > UNSYNCED_PATHS_CAP) unsynced.pollLast()
                }
                entry.isFolder -> folders++
                entry.isHydrated -> {
                    offline++
                    cacheEvictionSnapshot(entry.path, guards, wiring.log)?.let { evictions += it }
                }
                else -> placeholders++
            }
        }
        val effect =
            Effect(
                leaving = leaving.toList(),
                leavingMore = tops - leaving.size,
                entering = if (previous.isEmpty()) emptyList() else target.filter { !SyncScope.contains(it, previous) },
                folders = folders,
                placeholders = placeholders,
                offlineCopies = offline,
                unsynced = unsynced.toList(),
                unsyncedCount = unsyncedCount,
                reEnumerate = previous.isNotEmpty() && (target.isEmpty() || target.any { !SyncScope.contains(it, previous) }),
            )
        return Survey(effect, evictions)
    }

    companion object {
        const val LEAVING_CAP = 50
        const val UNSYNCED_PATHS_CAP = 10
        const val MAX_WAIT_MS = 30_000L

        /** How long [apply] waits for the running row-adding writes to end before it answers busy; writes go on meanwhile. */
        const val WRITE_DRAIN_MS = 10_000L
        private const val WAIT_STEP_MS = 50L
        private const val EFFECTIVE_SCOPE_KEY = "effective_scope"

        /**
         * The version of a scope: an opaque string derived from the normalised set, so the same set has the same version in
         * every process (a change by `unidrive scope set` while the daemon was stopped moves it too). `scope.set` takes it
         * back for optimistic concurrency.
         */
        fun versionOf(scope: List<String>): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(("unidrive-scope-v1\n" + SyncScope.normalize(scope).joinToString("\n")).toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(VERSION_HEX_CHARS)
        }

        private const val VERSION_HEX_CHARS = 16
    }
}
