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
) {
    /** #452: a rename that never asked the cloud (the item was never uploaded): its row and its cache copy are the only state. */
    val local: Boolean get() = remoteId == null && !ghost

    /** Whether [path] is one end of the rename, or a folder above or below one. */
    fun names(path: String): Boolean = related(path, oldPath) || related(path, newPath)
}

private fun related(
    a: String,
    b: String,
): Boolean = a == b || a == "/" || b == "/" || a.startsWith("$b/") || b.startsWith("$a/")
