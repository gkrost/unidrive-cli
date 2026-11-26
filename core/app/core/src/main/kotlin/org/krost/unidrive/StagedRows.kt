package org.krost.unidrive

/** How many staged rows [stagedRowsOf] reads back at a time: the most [CloudItem]s a pass over the staging holds at once. */
const val STAGED_ROWS_CHUNK: Int = 1000

/**
 * The staged rows of a scan as an [Iterable] read from the staging in chunks of at most [chunk] rows, in id order
 * ([load] is [ScanContext.stagedPage]: the rows whose id sorts after `afterId`, at most `limit`). Nothing is read until
 * it is iterated, a chunk is read when the one before it is used up, and each iteration reads the staging anew,
 * so it can be iterated more than once and never holds more than a chunk. Each chunk is one finished query: a
 * consumer that stops early, throws or is cancelled leaves nothing open.
 *
 * It is a view of the staging, not a snapshot: consume it before [ScanContext.persistPage] stores the next page, or the
 * rows of that page (whose ids sort anywhere) can join it.
 */
fun stagedRowsOf(
    chunk: Int = STAGED_ROWS_CHUNK,
    load: (afterId: String?, limit: Int) -> List<CloudItem>,
): Iterable<CloudItem> =
    Iterable {
        iterator {
            var after: String? = null
            while (true) {
                val rows = load(after, chunk)
                yieldAll(rows)
                if (rows.size < chunk) break
                after = rows.last().id
            }
        }
    }
