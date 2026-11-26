package org.krost.unidrive.http

import java.nio.file.Path

private const val RANGE_NOT_SATISFIABLE = 416

/** True, after dropping the kept partial of [destination], when a request that continued from [from] was answered 416. */
fun discardIfRangeRefused(
    status: Int,
    from: Long,
    destination: Path,
): Boolean {
    if (from <= 0L || status != RANGE_NOT_SATISFIABLE) return false
    ResumableStaging.discardFor(destination)
    return true
}
