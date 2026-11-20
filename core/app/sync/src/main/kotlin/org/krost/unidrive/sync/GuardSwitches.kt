package org.krost.unidrive.sync

/** The profile's `bulk_reap_guard` (default on): false reaps a bulk remote disappearance at once. */
fun SyncConfig.bulkReapGuard(profileName: String): Boolean = providers[profileName]?.bulkReapGuard ?: true

/** The profile's `top_level_guard` (default on): false drops the never-hydrated top-level skip of the sync engine. */
fun SyncConfig.topLevelGuard(profileName: String): Boolean = providers[profileName]?.topLevelGuard ?: true
