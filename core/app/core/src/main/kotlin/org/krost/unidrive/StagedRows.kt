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
 * Whether it is a snapshot is [load]'s doing: the engine's [load] reads only the rows the staging held when the pass began,
 * so a row the pass stages afterwards never joins it. A [load] that reads the live staging gives a live view.
 */
fun stagedRowsOf(
    chunk: Int = STAGED_ROWS_CHUNK,
    load: (afterId: String?, limit: Int) -> List<CloudItem>,
): Iterable<CloudItem> {
    require(chunk > 0) { "chunk must be > 0, got $chunk" }
    return Iterable {
        iterator {
            var after: String? = null
            while (true) {
                val rows = load(after, chunk)
                yieldAll(rows)
                if (rows.size < chunk) break
                val next = rows.last().id
                check(after == null || compareUtf8(next, after) > 0) { "load must advance past '$after', got '$next'" }
                after = next
            }
        }
    }
}

// The staging orders ids bytewise (SQLite BINARY over UTF-8), which String.compareTo (UTF-16 units) does not for supplementary characters.
private fun compareUtf8(
    a: String,
    b: String,
): Int {
    val x = a.encodeToByteArray()
    val y = b.encodeToByteArray()
    for (i in 0 until minOf(x.size, y.size)) {
        val d = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
        if (d != 0) return d
    }
    return x.size - y.size
}
