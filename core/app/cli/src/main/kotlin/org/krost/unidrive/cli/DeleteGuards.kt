package org.krost.unidrive.cli

import org.krost.unidrive.sync.bulkReapGuard
import org.krost.unidrive.sync.topLevelGuard

/**
 * The deletion safeguards of one profile's daemon, as its SyncEngine takes them. [DaemonCommand] fills them from the
 * config (general `max_delete_*`, the profile's `bulk_reap_guard` and `top_level_guard`); the defaults are the engine's.
 */
data class DeleteGuards(
    val maxDeletePercentage: Int = 50,
    val maxDeleteAbsolute: Int = 50,
    val maxDeletePerSubtreePercent: Int = 80,
    val ignoreTopLevelGuard: Boolean = false,
    val bulkReapGuard: Boolean = true,
)

/** The profile's delete guards as the config resolves them: the general `max_delete_*` and the profile's guard keys. */
fun deleteGuardsFor(
    config: org.krost.unidrive.sync.SyncConfig,
    profileName: String,
): DeleteGuards =
    DeleteGuards(
        maxDeletePercentage = config.maxDeletePercentage,
        maxDeleteAbsolute = config.maxDeleteAbsolute,
        maxDeletePerSubtreePercent = config.maxDeletePerSubtreePercent,
        ignoreTopLevelGuard = !config.topLevelGuard(profileName),
        bulkReapGuard = config.bulkReapGuard(profileName),
    )
