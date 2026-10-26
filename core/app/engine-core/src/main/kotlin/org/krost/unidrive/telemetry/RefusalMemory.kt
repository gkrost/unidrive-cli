package org.krost.unidrive.telemetry

/**
 * The last transfer of each path that ended as a refusal ([TransferHandle.finishRefused]), with the reason it ended for. A path
 * leaves when any other transfer of it ends. Bounded: the paths least recently refused are forgotten first. The memory is the
 * history's own and goes with the process.
 */
internal class RefusalMemory {
    private val refusals =
        object : LinkedHashMap<Pair<TransferDirection, String>, Pair<String, TransferRow>>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<TransferDirection, String>, Pair<String, TransferRow>>) =
                size > MAX_REMEMBERED
        }

    /** The row of the last refusal of [path] when it was for [reason]. */
    fun last(
        direction: TransferDirection,
        path: String,
        reason: String,
    ): TransferRow? = synchronized(refusals) { refusals[direction to path]?.takeIf { it.first == reason }?.second }

    fun note(
        row: TransferRow,
        reason: String,
    ) {
        synchronized(refusals) { refusals[row.direction to row.path] = reason to row }
    }

    fun forget(
        direction: TransferDirection,
        path: String,
    ) {
        synchronized(refusals) { refusals.remove(direction to path) }
    }

    private companion object {
        const val MAX_REMEMBERED = 4096
        const val INITIAL_CAPACITY = 16
        const val LOAD_FACTOR = 0.75f
    }
}
