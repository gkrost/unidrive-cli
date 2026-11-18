package org.krost.unidrive.hydration

import org.krost.unidrive.NameRefusedException
import org.krost.unidrive.sync.StateDatabase

/**
 * gkrost/unidrive-cli#29 follow-up: the refusal of a read or hydrate of an item the provider's strict-name mode blocks
 * (`name_incompatible: <rule>`), or null. Such an item is not materialised, and the block stays: the download's row write
 * cleared it before, until the next poll marked it again. A block of another kind (a too-similar sibling) names a local
 * file whose upload the drive refused, which stays readable.
 */
internal fun StateDatabase.strictNameRefusal(path: String): HydrationError? =
    nameBlock(path)
        ?.takeIf { it.reason.startsWith(NameRefusedException.NAME_INCOMPATIBLE) }
        ?.let { HydrationError.NameRefused(it.reason, null) }
