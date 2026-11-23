package org.krost.unidrive.sync

/** The profile's `bulk_reap_guard` (default off): true holds a bulk remote disappearance for a second full listing; false reaps it at once. */
fun SyncConfig.bulkReapGuard(profileName: String): Boolean = providers[profileName]?.bulkReapGuard ?: false

/** The profile's `top_level_guard` (default off): true keeps the never-hydrated top-level skip of the sync engine; false drops it. */
fun SyncConfig.topLevelGuard(profileName: String): Boolean = providers[profileName]?.topLevelGuard ?: false
