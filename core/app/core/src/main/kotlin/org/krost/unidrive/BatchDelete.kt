package org.krost.unidrive

import kotlinx.coroutines.CancellationException

/**
 * One item of a batched delete by cloud id (gkrost/unidrive-cli#831): [remoteId] names the item, [remotePath] is carried for
 * messages and for the per-item fallback, [isFolder] is the tracked kind, advisory like [TypedDeleteProvider]'s hint.
 */
data class DeleteRequest(
    val remoteId: String,
    val remotePath: String,
    val isFolder: Boolean,
)

/**
 * Optional provider SPI (gkrost/unidrive-cli#831): delete many items by cloud id in as few requests as the provider allows.
 * Each item keeps the semantics of its own [CloudProvider.deleteById] (the same trash, the same refusals, the same
 * already-gone shapes); only the number of round trips changes. The answer has one slot per item of `items`, in order: null
 * when that item was deleted, else the error its own delete would have thrown. Only a cancellation propagates.
 *
 * An optional interface, not a [CloudProvider] member, so that a decorator built `CloudProvider by inner` cannot hand a
 * batch past its own checks by delegation: a provider that does not implement it is asked item by item ([deleteManyOrEach]).
 */
interface BatchDeleteProvider {
    suspend fun deleteMany(items: List<DeleteRequest>): List<Throwable?>
}

/**
 * [BatchDeleteProvider.deleteMany] where the provider has it; otherwise the per-item loop: each item by
 * [TypedDeleteProvider.deleteById] where the provider takes the kind, else by [CloudProvider.deleteById].
 */
suspend fun CloudProvider.deleteManyOrEach(items: List<DeleteRequest>): List<Throwable?> {
    if (items.isEmpty()) return emptyList()
    if (this is BatchDeleteProvider) return deleteMany(items)
    return items.map { item -> deleteOneCatching(item) }
}

/** One item of [deleteManyOrEach]'s loop: null when it was deleted, else its error. A cancellation propagates. */
@Suppress("TooGenericExceptionCaught") // the error is the item's answer, handed back to its caller unchanged
suspend fun CloudProvider.deleteOneCatching(item: DeleteRequest): Throwable? =
    try {
        if (this is TypedDeleteProvider) deleteById(item.remoteId, item.remotePath, item.isFolder) else deleteById(item.remoteId, item.remotePath)
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e
    }
