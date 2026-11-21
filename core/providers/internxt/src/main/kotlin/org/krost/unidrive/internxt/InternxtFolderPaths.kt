package org.krost.unidrive.internxt

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import org.krost.unidrive.CloudItem
import org.krost.unidrive.internxt.InternxtProvider.Companion.PER_FOLDER_REQUESTS_IN_FLIGHT
import org.krost.unidrive.internxt.InternxtProvider.Companion.sanitizeName
import org.krost.unidrive.internxt.InternxtProvider.Companion.toResumedFolder
import org.krost.unidrive.internxt.model.InternxtFile
import org.krost.unidrive.internxt.model.InternxtFolder
import java.util.concurrent.atomic.AtomicInteger

// The folder-path resolution of a listing or delta pass: a folder uuid to its absolute path through the folders the pass
// knows, the ancestors it has to fetch or can take from state.db, and the folders that stop a path. Moved out of
// [InternxtProvider]'s companion unchanged (gkrost/unidrive-cli#534: the size ratchet).

// Resolves the absolute path of a folder uuid by walking ancestors
// through [folderMap]. Returns the empty string for [rootUuid]
// (parentPath sentinel) and `null` when any non-root ancestor is
// absent from [folderMap].
//
// A null return signals that the caller cannot safely construct a
// full path for an item under this uuid — typically because the
// /folders delta page returned a changed leaf without its
// unchanged ancestors. Callers must drop the item and signal
// `complete = false` upstream rather than silently rooting it at
// `/` (the prior silent-empty fallback produced ~84k duplicate
// `remote_id` rows in user state.db). See
// docs/audits/internxt-phantom-investigation.md.
internal fun buildFolderPath(
    uuid: String,
    folderMap: Map<String, InternxtFolder>,
    rootUuid: String,
): String? {
    if (uuid == rootUuid) return ""
    val folder = folderMap[uuid] ?: return null
    val parentPath: String =
        when (val parentUuid = folder.parentUuid) {
            null -> ""
            else -> buildFolderPath(parentUuid, folderMap, rootUuid) ?: return null
        }
    val name = sanitizeName(folder.plainName ?: folder.name ?: "")
    return "$parentPath/$name"
}

// Self-healing variant of [buildFolderPath]: when an ancestor uuid is
// absent from [folderMap] (typical when the /folders delta page returned
// a changed leaf without its unchanged ancestors), fetch that folder's
// metadata via [fetchFolder] and walk up, inserting each fetched
// ancestor into [folderMap] before resolving the path. Without this an
// unchanged remote keeps reproducing the same ancestor-uuid drop on
// every pass — the gather never reaches `complete=true`, so the engine
// suppresses deletes indefinitely.
//
// Memoisation, bounded to one fetch per missing uuid per pass:
//  - a successful fetch is written into [folderMap], so any later item
//    under the same ancestor finds it already present (no re-fetch);
//  - an unfetchable uuid (404 / gone / unavailable → [fetchFolder] returns
//    null) is recorded in [unfetchable], so a known-gone ancestor is
//    never re-requested.
// Both maps are shared across the whole delta() pass; at ~196k-file
// scale this caps the re-fetch at the number of distinct missing
// ancestors, not the number of orphaned leaves.
//
// Before any request, a folder is looked up in [ancestors]: the trashed folders the listing saw and did
// not return as items. A row found there is spliced into [folderMap] like a fetched one, so it is read once.
//
// Returns null exactly as [buildFolderPath] does when an ancestor
// cannot be resolved — the caller drops the item and counts it: below a
// folder that is gone for good it is unreachable ([goneAncestorOf]),
// otherwise `complete=false` stands for the pass.
internal suspend fun buildFolderPathFetching(
    uuid: String,
    folderMap: MutableMap<String, InternxtFolder>,
    rootUuid: String,
    unfetchable: MutableSet<String>,
    fetchFolder: suspend (String) -> InternxtFolder?,
    ancestors: Map<String, InternxtFolder> = emptyMap(),
): String? {
    if (uuid == rootUuid) return ""
    if (uuid in unfetchable) return null
    val folder =
        knownFolder(uuid, folderMap, ancestors) ?: run {
            val fetched = fetchFolder(uuid)
            if (fetched == null) {
                unfetchable.add(uuid)
                return null
            }
            folderMap[uuid] = fetched
            fetched
        }
    val parentPath: String =
        when (val parentUuid = folder.parentUuid) {
            null -> ""
            else ->
                buildFolderPathFetching(parentUuid, folderMap, rootUuid, unfetchable, fetchFolder, ancestors)
                    ?: return null
        }
    val name = sanitizeName(folder.plainName ?: folder.name ?: "")
    return "$parentPath/$name"
}

// The folder that is gone for good and that stopped the path of an item whose parent is [parentUuid]: walking up from
// the parent, the first folder the pass could not resolve ([unfetchable]) decides. It is returned when it is one of
// [gone] (the server answered 404 / 410 for it); null when it was unavailable for another reason, which may be
// over by the next pass, or when nothing on the way is unresolved. An item dropped below a gone folder is
// unreachable; any other drop keeps the pass incomplete.
internal fun goneAncestorOf(
    parentUuid: String?,
    folderMap: Map<String, InternxtFolder>,
    ancestors: Map<String, InternxtFolder>,
    rootUuid: String,
    unfetchable: Set<String>,
    gone: Set<String>,
): String? {
    var uuid = parentUuid
    // A parent chain that comes back to itself is damage; it is not walked twice.
    val seen = HashSet<String>()
    while (uuid != null && uuid != rootUuid && seen.add(uuid)) {
        if (uuid in unfetchable) return uuid.takeIf { it in gone }
        val folder = folderMap[uuid] ?: ancestors[uuid] ?: return null
        uuid = folder.parentUuid
    }
    return null
}

// The top of a tree of its own ([tops]) that an item whose parent is [parentUuid] hangs below, walking up through
// [folderMap]; null when the walk reaches the drive root or a folder it does not know.
internal fun treeTopOf(
    parentUuid: String?,
    folderMap: Map<String, InternxtFolder>,
    rootUuid: String,
    tops: Set<String>,
): String? {
    var uuid = parentUuid
    val seen = HashSet<String>()
    while (uuid != null && uuid != rootUuid && seen.add(uuid)) {
        if (uuid in tops) return uuid
        uuid = folderMap[uuid]?.parentUuid
    }
    return null
}

// The folder a path runs through, from what the listing already holds: the folders it returned, then the
// ancestor-only rows (spliced into [folderMap] on the way). Null when only a request can tell.
private fun knownFolder(
    uuid: String,
    folderMap: MutableMap<String, InternxtFolder>,
    ancestors: Map<String, InternxtFolder>,
): InternxtFolder? = folderMap[uuid] ?: ancestors[uuid]?.also { folderMap[uuid] = it }

// The folders above the delivered items that state.db tracks, as rows for the pass to use in place of a request.
// [files] and [folders] are the items the pass delivered, each with its parent folder; [folderMap] and [ancestors]
// hold what the pass already knows for certain, and they win over a row. A row is taken only along an edge that
// state.db itself confirms: the item is tracked under that very parent. An item the engine has not seen, or has
// seen under another folder (it was moved), says nothing about the row of its new parent, and that folder is
// asked for, as is a delivered folder's parent that differs from the tracked one. The same goes for each step up:
// from a folder the pass delivered it is made only where the tracked parent is the delivered one. A folder that
// is asked for stays out of the result even when another edge confirmed it, so what the remote says of it
// (moved, gone) is never overruled by a row. A row that is not a folder, or that the engine cannot vouch for
// (ScanContext.trackedRow answers null), ends the climb: that folder is asked for. Trashed folders are also
// asked for: a purged folder can leave re-delivered children behind with no new child to challenge its row.
internal fun trackedAncestorRows(
    files: Collection<InternxtFile>,
    folders: Collection<InternxtFolder>,
    folderMap: Map<String, InternxtFolder>,
    ancestors: Map<String, InternxtFolder>,
    rootUuid: String,
    tracked: (String) -> CloudItem?,
): Map<String, InternxtFolder> {
    val rows = HashMap<String, CloudItem?>()

    fun row(id: String): CloudItem? = if (id in rows) rows[id] else tracked(id).also { rows[id] = it }
    val found = LinkedHashMap<String, InternxtFolder>()
    val ask = HashSet<String>()
    val climbed = HashSet<String>()

    fun climb(
        item: String,
        parent: String?,
    ) {
        var below = item
        var uuid = parent
        while (uuid != null && uuid != rootUuid) {
            val certain = folderMap[uuid] ?: ancestors[uuid]
            val confirmed = certain != null || row(below)?.parentId == uuid
            if (!confirmed) ask.add(uuid)
            val folder =
                certain
                    ?: found[uuid].takeIf { confirmed }
                    ?: row(uuid)?.takeIf { confirmed && it.isFolder && !it.trashed }?.toResumedFolder()?.also { found[uuid] = it }
            if (folder == null || !climbed.add(uuid)) return
            below = uuid
            uuid = folder.parentUuid
        }
    }
    files.forEach { climb(it.uuid, it.folderUuid) }
    folders.forEach { climb(it.uuid, it.parentUuid) }
    found.keys.removeAll(ask)
    return found
}

/** How [resolveAncestors] found the folders that the listing did not return: [fromRows] of the ancestor-only rows, [fetched] by request, [unfetchable] not at all. */
internal class AncestorResolution(
    val fromRows: Int,
    val fetched: Int,
    val unfetchable: Int,
) {
    val needed: Int get() = fromRows + fetched + unfetchable
}

// Finds, before any path is built, every folder that a path of the gathered items runs through and that the
// listing did not return, and puts it into [folderMap]. [parents] are the parent folders of the items. A chain
// is climbed from each of them up to the root or to a folder already visited, so each folder is looked at once.
// A folder that the ancestor-only rows [ancestors] hold is read from there. The others are asked for through
// [fetchFolder], with at most [PER_FOLDER_REQUESTS_IN_FLIGHT] requests in flight: they used to be asked for one after the other,
// each when the first item below it was met, which on a large drive was 949 requests in a row.
//
// A folder that is fetched may hang below another that is missing: the next round asks for those, and the
// total reported to [onProgress] (done, total) grows by them. An unfetchable folder is recorded in
// [unfetchable], and the items below it are dropped by the path building as before.
internal suspend fun resolveAncestors(
    parents: Sequence<String?>,
    folderMap: MutableMap<String, InternxtFolder>,
    ancestors: Map<String, InternxtFolder>,
    rootUuid: String,
    unfetchable: MutableSet<String>,
    fetchFolder: suspend (String) -> InternxtFolder?,
    onProgress: ((done: Int, total: Int) -> Unit)? = null,
): AncestorResolution {
    val visited = HashSet<String>()
    val missing = LinkedHashSet<String>()
    var fromRows = 0

    fun climb(start: String?) {
        var uuid = start
        while (uuid != null && uuid != rootUuid && visited.add(uuid)) {
            val fromAncestors = uuid !in folderMap && uuid in ancestors
            val folder = knownFolder(uuid, folderMap, ancestors)
            if (folder == null) {
                if (uuid !in unfetchable) missing.add(uuid)
                return
            }
            if (fromAncestors) fromRows++
            uuid = folder.parentUuid
        }
    }
    parents.forEach(::climb)

    val permits = kotlinx.coroutines.sync.Semaphore(PER_FOLDER_REQUESTS_IN_FLIGHT)
    val done = AtomicInteger(0)
    val total = AtomicInteger(0)
    var fetched = 0
    var gone = 0
    while (missing.isNotEmpty()) {
        val round = missing.toList()
        missing.clear()
        total.addAndGet(round.size)
        onProgress?.invoke(done.get(), total.get())
        val answers =
            coroutineScope {
                round
                    .map { uuid ->
                        async {
                            val folder = permits.withPermit { fetchFolder(uuid) }
                            onProgress?.invoke(done.incrementAndGet(), total.get())
                            uuid to folder
                        }
                    }.awaitAll()
            }
        for ((uuid, folder) in answers) {
            if (folder == null) {
                unfetchable.add(uuid)
                gone++
            } else {
                folderMap[uuid] = folder
                fetched++
                climb(folder.parentUuid)
            }
        }
    }
    return AncestorResolution(fromRows = fromRows, fetched = fetched, unfetchable = gone)
}
