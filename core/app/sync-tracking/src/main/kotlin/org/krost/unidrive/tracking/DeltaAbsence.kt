package org.krost.unidrive.tracking

import org.krost.unidrive.CloudItem
import org.slf4j.LoggerFactory

/**
 * The rules by which an incremental delta marks tracked paths as remote-absent: a tombstone for a folder,
 * a tombstone known only by its id, and a move. A delta lists none of the affected old paths, so without
 * these marks they would read as unchanged-present.
 */
internal class DeltaAbsence(
    private val trackingSet: TrackingSet,
    private val providerId: String,
) {
    private val log = LoggerFactory.getLogger(DeltaAbsence::class.java)

    /**
     * A tombstone for a folder stands for everything below it, and the delta lists none of those files.
     * A provider may report a deleted folder as a plain deleted item, so the rule applies to any tombstone
     * whose id does not resolve to exactly one tracked file (a file path is never a prefix of another path).
     */
    fun coversSubtree(
        item: CloudItem,
        idPaths: List<String>,
    ): Boolean = item.deleted && idPaths.size != 1 && isUsableFilePath(item.path)

    /**
     * Every tracked file of this provider under [folderPath] is observed as remote-absent. The reconciler and
     * the apply step still decide per file: a locally edited file is preserved, an untracked file is kept.
     */
    fun markSubtreeAbsent(
        folderPath: String,
        out: MutableMap<String, RemoteObservation>,
        trackedPaths: Lazy<Iterable<String>>,
    ) {
        val prefix = "$folderPath/"
        for (tracked in trackedPaths.value) {
            if (!tracked.startsWith(prefix) || out[tracked]?.exists == true) continue
            if (trackingSet.lookup(tracked)?.providerId != providerId) continue
            out[tracked] = RemoteObservation(false, null, null, null, null, null)
        }
    }

    /**
     * A tombstone describes the remote object's identity. Its path is frequently absent or a synthetic parent
     * path, so the id is resolved to the tracking rows first; one id may be held by several rows (a move whose
     * old path was kept).
     */
    fun markTombstoned(
        item: CloudItem,
        idPaths: List<String>,
        out: MutableMap<String, RemoteObservation>,
    ) {
        val targetPaths = idPaths.ifEmpty { listOfNotNull(item.path.takeIf { tombstonePathIsTracked(item, it) }) }
        if (targetPaths.isEmpty()) {
            log.warn("Ignoring pathless remote tombstone with untracked id {}", item.id)
            return
        }
        for (targetPath in targetPaths) {
            val priorAtPath = out[targetPath]
            if (priorAtPath?.exists == true && priorAtPath.remoteFileId != item.id) {
                // A stale tombstone must not mask a newer object occupying the same path.
                log.warn("Ignoring stale tombstone id={} for live path {}", item.id, targetPath)
                continue
            }
            out[targetPath] = RemoteObservation(false, item.id, null, null, null, null)
        }
    }

    /**
     * An id is live at one path only: when it turns up at a new, not yet tracked path the path the walk saw it
     * at before is superseded. Un-observing it reads as unchanged-present on an incremental pass, so it is
     * dropped here and marked absent below if it has a row. A move arrives as the same id under a new path and
     * says nothing about the old paths, which would otherwise be read as unchanged-present, so every other
     * tracked path holding the id is remote-absent.
     */
    fun markMovedAbsent(
        item: CloudItem,
        out: MutableMap<String, RemoteObservation>,
        livePathById: MutableMap<String, String>,
    ) {
        val earlier = livePathById.put(item.id, item.path)
        val newPathKnown = trackingSet.lookup(item.path)?.remoteFileId == item.id
        if (earlier != null && earlier != item.path && !newPathKnown) {
            if (out[earlier]?.remoteFileId == item.id) out.remove(earlier)
        }
        for (oldPath in trackingSet.pathsByRemoteId(providerId, item.id)) {
            if (oldPath != item.path && out[oldPath]?.exists != true) {
                out[oldPath] = RemoteObservation(false, item.id, null, null, null, null)
            }
        }
    }

    private fun tombstonePathIsTracked(
        item: CloudItem,
        candidate: String,
    ): Boolean {
        if (!isUsableFilePath(candidate)) return false
        val row = trackingSet.lookup(candidate) ?: return true
        return row.providerId == providerId && (row.remoteFileId == null || row.remoteFileId == item.id)
    }

    private fun isUsableFilePath(path: String): Boolean =
        path.isNotBlank() && path != "/" && !path.endsWith('/') && !path.split('/').any { it == ".." }
}
