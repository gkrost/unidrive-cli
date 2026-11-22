package org.krost.unidrive.cli

import org.krost.unidrive.engine.RemoteOperationGuard
import org.krost.unidrive.sync.ProfileMode
import org.krost.unidrive.sync.Reconciler
import org.krost.unidrive.sync.SyncConfig
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.ignore.CacheIgnoreSource
import org.krost.unidrive.sync.ignore.IgnoreSettings
import org.krost.unidrive.sync.ignore.LocalRuleFiles
import org.krost.unidrive.sync.ignore.UploadIgnore
import java.nio.file.Path

/**
 * The exclusion guard the daemon applies, built for a one-shot command (`get`, `ls`): the profile's exclude patterns
 * and, for a mount profile, its ignore rules, read from the profile's hydration cache. [inCloud] says whether state.db
 * holds a path as a cloud item, so a rule file only the cloud has holds its folder back, as it does in the daemon. [lost]
 * says whether it holds a path as a never-uploaded file whose bytes are here (gkrost/unidrive-cli#775): such a rule file
 * missing from the cache is read from the copy the daemon kept of it, or holds its folder back, as in the daemon.
 */
internal fun cliExclusionGuard(
    config: SyncConfig,
    profileName: String,
    mode: ProfileMode?,
    lost: (path: String) -> Boolean,
    inCloud: (path: String) -> Boolean,
): RemoteOperationGuard =
    exclusionGuard(
        patterns = config.effectiveExcludePatterns(profileName),
        ignoreSettings = config.ignoreSettings(profileName).takeIf { mode == ProfileMode.MOUNT },
        cacheDir = SyncEngine.hydrationCacheRoot(SyncEngine.defaultHydrationCacheRoot(), profileName),
        inCloud = inCloud,
        lost = lost,
        ignoreRulesDir = SyncEngine.ignoreRuleFilesRoot(SyncEngine.defaultHydrationCacheRoot(), profileName),
    )

internal fun exclusionGuard(
    patterns: List<String>,
    ignoreSettings: IgnoreSettings?,
    cacheDir: Path,
    inCloud: (path: String) -> Boolean,
    lost: (path: String) -> Boolean = { false },
    // The profile's folder of downloaded rule files ([SyncEngine.ignoreRuleFilesRoot]): the daemon's kept copies are read from there, never written.
    ignoreRulesDir: Path? = null,
    // The profile's standing sync scope (`sync_path`); empty is the whole drive. A one-shot `get` or `ls` leaves it out, as before.
    standingScope: List<String> = emptyList(),
): RemoteOperationGuard {
    val ignore =
        ignoreSettings?.let { settings ->
            val kept = ignoreRulesDir?.let { LocalRuleFiles.under(it, settings.maxRuleFileBytes, writable = false) }
            UploadIgnore(CacheIgnoreSource(cacheDir, lost = lost, kept = kept, inCloud = inCloud), settings)
        }
    return RemoteOperationGuard(
        standingScope = standingScope,
        syncPaths = emptyList(),
        // With the ignore rules on, the built-in defaults are theirs (overridable); same split as the engine's.
        excludePatterns = if (ignore == null) patterns else patterns.filterNot { it in SyncConfig.DEFAULT_EXCLUDE_PATTERNS },
        maxConcurrentTransfers = 1,
        matchesGlob = { path, pattern -> Reconciler.matchesGlob(path, pattern) },
        ignore = ignore,
    )
}
