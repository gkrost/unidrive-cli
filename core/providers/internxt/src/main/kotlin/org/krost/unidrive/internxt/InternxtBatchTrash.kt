package org.krost.unidrive.internxt

import kotlinx.coroutines.CancellationException
import org.krost.unidrive.DeleteRequest

/** The server cap of one `POST /storage/trash/add` (`MoveItemsToTrashDto`, `@ArrayMaxSize(50)`). */
internal const val TRASH_BATCH_MAX = 50

/**
 * The batched delete of [InternxtProvider.deleteMany] (gkrost/unidrive-cli#831): the items a listing already named go to the
 * trash up to [batchSize] per call; every other item, and every item of a call the server refused, takes the single
 * delete ([single]), so each item is answered exactly as its own `deleteById` would answer it.
 *
 * - [listedKind] says, for an item a listing named, its kind on the wire (`"file"` / `"folder"`) after the refusals that
 *   the listing settles (backup device, a tree outside the drive) passed; a refusal throws and is that item's answer. Null
 *   sends the item to [single], which asks the meta first, as before.
 * - A call that answers 400 names no item: the server refuses the whole set for one bad uuid (an item already in the
 *   trash, or gone). Each item of that call then takes [single], whose own 400 handling tells the shapes apart, so one bad
 *   uuid costs that call's items one request each, and the others are still deleted.
 * - Any other failure of a call (a 5xx after the retries, an auth failure, a network error) is the answer of every item
 *   of that call: none of them is known to be trashed, as a single delete that failed so.
 * - An id that comes twice is batched once; the repeats take [single] after the batches (the item is trashed by then,
 *   and the single delete answers the already-gone shape).
 * - [forget] runs for each item a call trashed (the listed-parents cache drops it, as the single fast path does).
 */
@Suppress("TooGenericExceptionCaught", "LongParameterList") // an item's error is its answer; the collaborators are the seams
internal suspend fun trashInBatches(
    items: List<DeleteRequest>,
    listedKind: suspend (DeleteRequest) -> String?,
    trash: suspend (List<Pair<String, String>>) -> Unit,
    forget: (String) -> Unit,
    single: suspend (DeleteRequest) -> Unit,
    batchSize: Int = TRASH_BATCH_MAX,
): List<Throwable?> {
    val answers = arrayOfNulls<Throwable>(items.size)

    suspend fun attempt(block: suspend () -> Unit): Throwable? =
        try {
            block()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }
    val batched = ArrayList<Pair<Int, String>>()
    val singles = ArrayList<Int>()
    val seen = HashSet<String>()
    items.forEachIndexed { i, item ->
        var kind: String? = null
        val refused = attempt { kind = listedKind(item) }
        when {
            refused != null -> answers[i] = refused
            kind != null && seen.add(item.remoteId) -> batched += i to kind!!
            else -> singles += i
        }
    }
    for (call in batched.chunked(batchSize)) {
        val failed = attempt { trash(call.map { (i, type) -> items[i].remoteId to type }) }
        when {
            failed == null -> call.forEach { (i, _) -> forget(items[i].remoteId) }
            failed is InternxtApiException && failed.statusCode == 400 -> singles += call.map { it.first }
            else -> call.forEach { (i, _) -> answers[i] = failed }
        }
    }
    for (i in singles.sorted()) answers[i] = attempt { single(items[i]) }
    return answers.toList()
}
