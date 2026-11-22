package org.krost.unidrive.cli

import org.krost.unidrive.engine.CachePaths
import org.krost.unidrive.sync.PendingUploads
import org.krost.unidrive.sync.ProfileMode
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncConfig
import org.krost.unidrive.sync.SyncEngine
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * The pending-upload rule of a stopped profile, for the `profile remove` / `logout` gate: the same definition the
 * running daemon counts `uploads.pending` by ([PendingUploads]), built from the profile's config because no daemon
 * is there to ask. A profile whose config cannot be read keeps nothing local and judges no file gone, so the gate
 * counts every row as it did before (the conservative side).
 *
 * Mount profile: the exclude patterns and the ignore rules keep rows local, and a row whose hydration-cache copy is gone
 * is gone (judged only while the cache folder exists). Mirror profile: the exclude patterns keep rows local; its bytes
 * live in the sync root, which the next sync scan settles, so no row is judged gone here.
 */
internal object PendingGate {
    private val log = LoggerFactory.getLogger(PendingGate::class.java)

    /** The rows of [stateDb] sorted by the profile's rule; null when the state.db cannot be read (locked, corrupt, unexpected schema). */
    fun split(
        stateDb: Path,
        configDir: Path,
        name: String,
    ): PendingUploads.Split? {
        val db = StateDatabase(stateDb, readOnly = true)
        return try {
            db.initialize()
            val rows = db.pendingUploadRows()
            val rules = rulesOf(configDir, name, db)
            PendingUploads.split(rows, rules.keepsLocal, rules.hasLocalCopy)
        } catch (_: java.sql.SQLException) {
            null
        } catch (_: IllegalStateException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } finally {
            db.close()
        }
    }

    /**
     * The keep-local rule [split] sorts by, for `status --pending`. The caller keeps [db] open for indexed
     * lookups of candidate rule files while scanning its pages.
     */
    fun keepsLocal(
        configDir: Path,
        name: String,
        db: StateDatabase,
    ): (String) -> Boolean = rulesOf(configDir, name, db).keepsLocal

    /**
     * Drops the `local:` rows [gone] from [stateDb] (never a row with a real remote id: [StateDatabase.deleteLocalPendingRow]
     * re-checks), logged once. Best effort: a state.db that cannot be written changes nothing, the rows are only not
     * counted.
     */
    fun dropGone(
        stateDb: Path,
        gone: List<StateDatabase.PendingUploadRow>,
        name: String,
    ) {
        if (gone.isEmpty()) return
        val dropped =
            try {
                val db = StateDatabase(stateDb)
                try {
                    db.initialize()
                    gone.filter { db.deleteLocalPendingRow(it.path) }
                } finally {
                    db.close()
                }
            } catch (_: java.sql.SQLException) {
                return
            } catch (_: IllegalStateException) {
                return
            }
        if (dropped.isEmpty()) return
        log.info(
            "profile '{}': dropped {} pending upload row(s) whose file is gone, e.g. {}",
            name,
            dropped.size,
            dropped.take(PendingUploads.DETAIL_PATHS_CAP).joinToString { CachePaths.forLog(it.path) },
        )
    }

    /**
     * The keep-local rule of a mirror profile's config: the exclude [patterns] and the standing sync scope (`sync_path`; empty is the
     * whole drive). The one rule behind a mirror profile's pending rows, for the stopped profile's gate ([rulesOf]) and for a running
     * mirror daemon's `auth.logout` refusal (`DaemonRuntime`), so a row cannot refuse in one and pass in the other (review of #89, P2).
     */
    internal fun keepsLocalRule(
        patterns: List<String>,
        standingScope: List<String>,
    ): (String) -> Boolean =
        exclusionGuard(patterns = patterns, ignoreSettings = null, cacheDir = Path.of(""), inCloud = { false }, standingScope = standingScope)::keepsLocal

    private class Rules(
        val keepsLocal: (String) -> Boolean,
        val hasLocalCopy: ((String) -> Boolean)?,
    )

    private fun rulesOf(
        configDir: Path,
        name: String,
        db: StateDatabase,
    ): Rules {
        val none = Rules(keepsLocal = { false }, hasLocalCopy = null)
        return try {
            val configPath = configDir.resolve("config.toml")
            val raw = SyncConfig.parseRaw(Files.readString(configPath), configPath.toString())
            val mode = ProfileMode.fromConfig(raw.providers[name]?.mode) ?: return none
            val config = SyncConfig.load(configPath, name)
            val cacheDir = SyncEngine.hydrationCacheRoot(ProfileRemoveHooks.cacheRoot(), name)
            if (mode != ProfileMode.MOUNT) return Rules(keepsLocalRule(config.effectiveExcludePatterns(name), config.syncPaths), null)
            // A rule file only the cloud has is not known here: its folder is not held back, so its rows still count.
            // The standing scope is the daemon's too: a row outside `sync_path` is kept local there, so it does not block here either.
            // gkrost/unidrive-cli#775: a local-only rule file (one of the pending rows) missing from the cache is read from the copy
            // the daemon kept, or holds its folder back, as in the daemon.
            val guard =
                exclusionGuard(
                    patterns = config.effectiveExcludePatterns(name),
                    ignoreSettings = config.ignoreSettings(name),
                    cacheDir = cacheDir,
                    inCloud = { false },
                    lost = { path -> db.getEntry(path)?.let { !it.isFolder && it.isPendingUpload } == true },
                    db = db,
                    ignoreRulesDir = SyncEngine.ignoreRuleFilesRoot(ProfileRemoveHooks.cacheRoot(), name),
                    standingScope = config.syncPaths,
                )
            // As the daemon's start-up drop: a rule file the rules keep here is not gone with its cache copy (its row keeps them in force).
            val hasCopy =
                PendingUploads.localCopyCheck(cacheDir, config.syncRoot)?.let { check ->
                    { path: String -> check(path) || (guard.isIgnoreRuleFile(path) && guard.keepsLocal(path)) }
                }
            Rules(keepsLocal = guard::keepsLocal, hasLocalCopy = hasCopy)
        } catch (_: Exception) {
            // The config could not be read or parsed (its message can quote a credential, so nothing of it is kept).
            none
        }
    }
}
