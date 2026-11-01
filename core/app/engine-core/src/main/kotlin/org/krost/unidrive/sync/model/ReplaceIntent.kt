package org.krost.unidrive.sync.model

/**
 * gkrost/unidrive-cli#311: a mount replace-rename recorded before its old destination is parked
 * ([org.krost.unidrive.sync.StateDatabase.recordReplaceIntent]): the file at [destPath] (cloud id [destRemoteId], null when its row
 * had none) moves aside to [parkingPath], the file at [sourcePath] ([sourceRemoteId]) takes [destPath], and the parked file is then
 * deleted. A daemon stopped between those steps finishes the replace or undoes it from this record at its next start.
 */
data class ReplaceIntent(
    val sourcePath: String,
    val destPath: String,
    val parkingPath: String,
    val sourceRemoteId: String?,
    val destRemoteId: String?,
)
