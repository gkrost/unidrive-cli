package org.krost.unidrive.internxt

/** What a by-id meta row (`/files/{uuid}/meta`, `/folders/{uuid}/meta`) says about its item. */
internal enum class MetaRowState { LIVE, TRASHED, GONE }

/**
 * Internxt's server sets `deleted = true` when an item goes to the trash and `removed = true` when it is deleted for
 * good (status `TRASHED` and `DELETED`). The status decides; the flags are read when it says neither (`EXISTS`, the
 * models' default for a row without one), `removed` first because the stronger fact wins.
 */
internal fun metaRowState(
    status: String,
    deleted: Boolean,
    removed: Boolean,
): MetaRowState =
    when {
        status == "DELETED" -> MetaRowState.GONE
        status == "TRASHED" -> MetaRowState.TRASHED
        removed -> MetaRowState.GONE
        deleted -> MetaRowState.TRASHED
        else -> MetaRowState.LIVE
    }
