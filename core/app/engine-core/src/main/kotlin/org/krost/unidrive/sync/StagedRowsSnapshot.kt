package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.stagedRowsOf

/**
 * The rows [scanId] holds now, as the provider's resumed rows (see [org.krost.unidrive.ScanContext.resumedItems]): read a
 * chunk at a time, and bounded by the staging's high-water mark taken here, so a row the pass stages afterwards is never one
 * of them, however late they are read.
 */
fun StateDatabase.stagedRowsSnapshot(scanId: String): Iterable<CloudItem> {
    val mark = scanStaging.highWater()
    return stagedRowsOf { after, limit -> scanStaging.loadPage(scanId, after, limit, upToRowid = mark) }
}
