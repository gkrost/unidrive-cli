package org.krost.unidrive.hydration

import org.krost.unidrive.NameRefusedException

/**
 * cli#950: a rename the drive refuses by the new name (it compares the name as equal to a sibling's) is the blocked-row refusal
 * with its sibling (`similar_name: <sibling>`), nothing moved. The refusal's cause is the drive's 409, which must not turn it
 * back into `new_path_exists`. Null for any other failure.
 */
internal fun nameRefusal(e: Throwable): RenameResult? =
    (e as? NameRefusedException)?.let { RenameResult.Failed(HydrationError.NameRefused(it.reason, it.sibling)) }
