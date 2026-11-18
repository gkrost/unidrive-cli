package org.krost.unidrive.hydration

import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider

/**
 * archive#1217: an item an upload created after its row was gone: the [item] as the provider answered, the [remotePath] it
 * landed at, and the cache copy's (mtime, size) as they were sent ([sent], null when they could not be read).
 */
data class LandedWithoutRow(
    val item: CloudItem,
    val remotePath: String,
    val sent: Pair<Long, Long>?,
)

/**
 * gkrost/unidrive-cli#746 finding 2: [landed] as the cloud holds it at [remotePath] now, when it is still the same item with
 * the same bytes, else [landed]. A rename's move that landed after the upload gave the item a newer write token than the
 * upload's answer carries; the row takes the newest one, or its next edit is refused as a conflict with its own version.
 */
internal suspend fun CloudProvider.currentIfSameBytes(
    landed: CloudItem,
    remotePath: String,
): CloudItem {
    val now = runCatching { getMetadata(remotePath) }.onFailure { it.rethrowIfCancellation() }.getOrNull() ?: return landed
    return now.takeIf { it.id == landed.id && it.size == landed.size && it.hash == landed.hash } ?: landed
}
