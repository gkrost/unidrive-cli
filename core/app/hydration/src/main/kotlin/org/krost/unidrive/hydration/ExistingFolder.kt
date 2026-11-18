package org.krost.unidrive.hydration

import org.krost.unidrive.CloudItem

/** gkrost/unidrive-cli#452: what [MountEngine.existingFolderAt] proved the cloud holds at a create refused with 409. */
sealed interface ExistingFolder {
    /** The folder is there, and its row now names it. */
    data class Adopted(val item: CloudItem) : ExistingFolder

    /** A file holds the name. */
    data object HeldByFile : ExistingFolder

    /** Nothing provable: the caller's refusal stands. */
    data object Unknown : ExistingFolder
}
