package org.krost.unidrive.sync.model

/**
 * archive#1040 M4: a mount rename recorded before its cloud move ([org.krost.unidrive.sync.StateDatabase.recordRenameIntent]):
 * the item [remoteId] at [oldPath] is to end, with its row and its cache copy, at [newPath]. [ghost]: the row had no id (it was
 * never uploaded, but its upload had landed), and takes the moved item's.
 */
data class RenameIntent(
    val oldPath: String,
    val newPath: String,
    val remoteId: String?,
    val ghost: Boolean,
)
