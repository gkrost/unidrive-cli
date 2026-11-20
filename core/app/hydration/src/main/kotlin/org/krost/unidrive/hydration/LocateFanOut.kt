package org.krost.unidrive.hydration

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * gkrost/unidrive-cli#746 finding 7: how many by-id asks one `hydration.locate` keeps in flight. One after another, the
 * [HydrationIpcHandler.LOCATE_MAX_IDS] ids one request may carry took that many provider round trips end to end.
 */
internal const val LOCATE_PARALLELISM = 8

/**
 * [transform] of every element with at most [parallelism] running at once, the results in this list's order. The first
 * failure cancels the transforms still running and is thrown (all or nothing, as the sequential map was).
 */
internal suspend fun <T, R> List<T>.mapBounded(
    parallelism: Int,
    transform: suspend (T) -> R,
): List<R> {
    val permits = Semaphore(parallelism)
    return coroutineScope { map { async { permits.withPermit { transform(it) } } }.awaitAll() }
}
