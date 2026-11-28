package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncAction

/**
 * gkrost/unidrive-cli#267: a deletion safeguard that tripped, as data. A one-shot apply throws it as
 * [SafeguardTrippedException]; a dry run or a daemon-mode pass hands it to [ProgressReporter.onSafeguard]. A client keys
 * on [kind] and the numbers, never on the English of [SafeguardText].
 */
data class SafeguardTripped(
    val kind: Kind,
    /** The deletes planned: all of them, or those under [subtree] for [Kind.DELETE_SUBTREE]. */
    val planned: Int,
    /** What [planned] is measured against: every tracked entry, or those under [subtree]. Null for [Kind.DELETE_ABSOLUTE]. */
    val total: Int?,
    /** The configured cap: a percentage, or a count for [Kind.DELETE_ABSOLUTE]. */
    val threshold: Int,
    /** The top-level folder (`/Documents`) for [Kind.DELETE_SUBTREE], else null. */
    val subtree: String?,
    val syncRoot: String,
) {
    /** `planned * 100 / total`, rounded down as the cap compares it; null for [Kind.DELETE_ABSOLUTE]. */
    val percent: Int? get() = total?.let { planned * 100 / it }

    /** [token] is the stable wire name: the config key of the cap that tripped. */
    enum class Kind(
        val token: String,
    ) {
        DELETE_PERCENTAGE("max_delete_percentage"),
        DELETE_ABSOLUTE("max_delete_absolute"),
        DELETE_SUBTREE("max_delete_per_subtree_percent"),
    }

    companion object {
        /** The stable token an IPC event carries for any tripped safeguard. */
        const val TOKEN = "safeguard_tripped"
    }
}

/**
 * The one-shot apply's abort. Still an [IllegalStateException] so the callers that classify engine guards that way (the
 * CLI's operator-facing rendering, `refresh.run`'s `engine_guard`) are unchanged; its message is the English the CLI prints.
 */
class SafeguardTrippedException(
    val trip: SafeguardTripped,
) : IllegalStateException(SafeguardText.message(trip))

/**
 * The English text of a [SafeguardTripped], byte for byte what the CLI printed before #267 (pinned by
 * DeleteSafeguardTextTest). Presentation, kept beside the reporters that show text; the engine never builds it.
 */
object SafeguardText {
    fun message(trip: SafeguardTripped): String =
        when (trip.kind) {
            SafeguardTripped.Kind.DELETE_PERCENTAGE ->
                "Deletion safeguard: ${trip.planned} of ${trip.total} files (${trip.percent}%) would be deleted, " +
                    "exceeding max_delete_percentage=${trip.threshold}%. "
            SafeguardTripped.Kind.DELETE_ABSOLUTE ->
                "UD-265 Deletion safeguard: ${trip.planned} deletes planned, " +
                    "exceeding max_delete_absolute=${trip.threshold}. "
            SafeguardTripped.Kind.DELETE_SUBTREE ->
                "UD-265 Deletion safeguard: ${trip.planned} of ${trip.total} entries " +
                    "under top-level '${trip.subtree}' (${trip.percent}%) would be deleted, exceeding " +
                    "max_delete_per_subtree_percent=${trip.threshold}%. "
        } + "sync_root='${trip.syncRoot}'. Use --force-delete to override."

    /** A dry-run or daemon-mode trip's warning; [deferred] (daemon mode) adds that the deletes wait for the next cycle. */
    fun warning(
        trip: SafeguardTripped,
        deferred: Boolean,
    ): String = if (deferred) "${message(trip)} Deferring deletes this cycle (daemon mode)." else message(trip)
}

/**
 * The three deletion-safeguard axes (UD-298, UD-265), moved out of SyncEngine for #267. 0 disables an axis. The trips are
 * yielded lazily in the order the engine always checked them, so a one-shot apply that throws on the first trip runs no
 * further subtree counts.
 */
internal class DeleteSafeguards(
    private val maxDeletePercentage: Int,
    private val maxDeleteAbsolute: Int,
    private val maxDeletePerSubtreePercent: Int,
) {
    fun trips(
        actions: List<SyncAction>,
        db: StateDatabase,
        syncRoot: String,
    ): Sequence<SafeguardTripped> =
        sequence {
            val deleteActions = actions.filter { it is SyncAction.DeleteRemote || it is SyncAction.DeleteLocal }
            val deleteCount = deleteActions.size
            val totalEntries = db.getEntryCount()

            // UD-298 legacy whole-inventory percentage axis.
            if (maxDeletePercentage in 1..99 && totalEntries > 0 && deleteCount > 10) {
                if (deleteCount * 100 / totalEntries > maxDeletePercentage) {
                    yield(SafeguardTripped(SafeguardTripped.Kind.DELETE_PERCENTAGE, deleteCount, totalEntries, maxDeletePercentage, null, syncRoot))
                }
            }

            // UD-265 axis 1: absolute count cap.
            if (maxDeleteAbsolute > 0 && deleteCount > maxDeleteAbsolute) {
                yield(SafeguardTripped(SafeguardTripped.Kind.DELETE_ABSOLUTE, deleteCount, null, maxDeleteAbsolute, null, syncRoot))
            }

            // UD-265 axis 2: per-top-level-subtree percentage cap. Group delete actions by their top-level segment, count
            // the tracked entries under that top-level in state.db, and compare. Skip subtrees with fewer than 5 tracked
            // entries — small folders produce noisy 100% trips on legitimate cleanups.
            if (maxDeletePerSubtreePercent in 1..99 && deleteCount > 0) {
                for ((top, group) in deleteActions.groupBy { topLevelOf(it.path) }) {
                    if (top == null) continue
                    val tracked = db.countEntriesUnderTopLevel(top)
                    if (tracked < 5 || group.size * 100 / tracked <= maxDeletePerSubtreePercent) continue
                    yield(SafeguardTripped(SafeguardTripped.Kind.DELETE_SUBTREE, group.size, tracked, maxDeletePerSubtreePercent, top, syncRoot))
                }
            }
        }
}

// UD-264: extract the top-level cloud-path segment from an absolute path.
// `/Documents/CyberLink/Foo` -> `/Documents`. Root-only paths (`/`, ``) or
// single-segment paths under root (`/Foo`) return their own value as the
// top-level. Returns null for paths that don't start with `/`, which never
// happens in production but keeps callers honest.
internal fun topLevelOf(path: String): String? {
    if (!path.startsWith("/")) return null
    val trimmed = path.trimStart('/')
    if (trimmed.isEmpty()) return null
    val firstSlash = trimmed.indexOf('/')
    return "/" + if (firstSlash < 0) trimmed else trimmed.substring(0, firstSlash)
}
