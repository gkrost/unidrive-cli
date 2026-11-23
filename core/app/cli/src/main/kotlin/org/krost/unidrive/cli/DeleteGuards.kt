package org.krost.unidrive.cli

import org.krost.unidrive.sync.bulkReapGuard
import org.krost.unidrive.sync.topLevelGuard

/**
 * The deletion safeguards of one profile's daemon, as its SyncEngine takes them. [DaemonCommand] fills them from the
 * config (general `max_delete_*`, the profile's `bulk_reap_guard` and `top_level_guard`); the guard switches default off (a profile turns one on with `bulk_reap_guard = true` / `top_level_guard = true`).
 */
data class DeleteGuards(
    val maxDeletePercentage: Int = 50,
    val maxDeleteAbsolute: Int = 50,
    val maxDeletePerSubtreePercent: Int = 80,
    val ignoreTopLevelGuard: Boolean = true,
    val bulkReapGuard: Boolean = false,
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
        ignoreTopLevelGuard = resolveIgnoreTopLevelGuard(flag = false, config = config, profileName = profileName),
        bulkReapGuard = config.bulkReapGuard(profileName),
    )

/**
 * Whether the top-level-never-hydrated guard is bypassed: the profile's `top_level_guard` (default off, so bypassed),
 * or `unidrive sync --ignore-top-level-guard` for this one run. The daemon and the standalone sync both resolve it here,
 * so the same mirror profile gets the same delete rule in either.
 */
fun resolveIgnoreTopLevelGuard(
    flag: Boolean,
    config: org.krost.unidrive.sync.SyncConfig,
    profileName: String,
): Boolean = flag || !config.topLevelGuard(profileName)
