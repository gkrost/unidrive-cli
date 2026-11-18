package org.krost.unidrive.cli

import org.krost.unidrive.io.Os
import org.krost.unidrive.sync.ConflictLog
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The profile's data folder, beside its config (the conflict backups live here): `%LOCALAPPDATA%\unidrive\<profile>` on
 * Windows, `$HOME/.local/share/unidrive/<profile>` elsewhere; a missing variable falls back to user.home. `sync`,
 * `conflicts`, the tracking engine and the daemon all use it, so they agree on where a backup is.
 */
internal fun profileDataDir(
    profileName: String,
    windows: Boolean = Os.isWindows(),
    env: (String) -> String? = System::getenv,
    userHome: String = System.getProperty("user.home"),
): Path =
    if (windows) {
        Paths.get(env("LOCALAPPDATA") ?: userHome, "unidrive", profileName)
    } else {
        Paths.get(env("HOME") ?: userHome, ".local", "share", "unidrive", profileName)
    }

/**
 * #768: the daemon's conflict log, where `unidrive sync` writes it and `unidrive conflicts` reads it: the journal in the
 * profile's config folder [configDir], the loser backups in the profile's data folder [dataDir] ([profileDataDir]).
 */
internal fun daemonConflictLog(
    profileName: String,
    configDir: Path,
    dataDir: Path = profileDataDir(profileName),
): ConflictLog =
    ConflictLog(
        logFile = configDir.resolve("conflicts.jsonl"),
        backupDir = dataDir.resolve("conflict-backups"),
    )
