package org.krost.unidrive.hydration

import org.krost.unidrive.CloudItem
import org.krost.unidrive.HashAlgorithm
import org.krost.unidrive.engine.Transfers
import org.krost.unidrive.sync.model.SyncEntry

/**
 * gkrost/unidrive-cli#1018 item 16: a hydration of the version this machine uploaded must bring the bytes that were sent
 * ([Transfers.uploadDigestRefusal]; [algorithm] is the provider's). [item] is what the download asked for by the row's id; an
 * item read by its live id instead ([MountEngine.Fetched.liveItem]) is another item and gets no check. Called once the size is
 * confirmed and before the swap, so a refusal leaves the cache copy and the row as they are, and the same version is refused
 * from memory until the row names another.
 */
internal fun MountEngine.refuseUnlessUploadedBytes(
    path: String,
    entry: SyncEntry,
    item: CloudItem,
    fetched: MountEngine.Fetched,
    algorithm: HashAlgorithm?,
) {
    if (fetched.liveItem != null) return
    val readKey = Transfers.readVersionKey(item, fetched.receipt)
    val refusal = Transfers.uploadDigestRefusal(path, entry.uploadDigest, readKey, fetched.receipt, algorithm) ?: return
    throw remembered(path, entry, refusal, untilVersionChanges = true)
}
