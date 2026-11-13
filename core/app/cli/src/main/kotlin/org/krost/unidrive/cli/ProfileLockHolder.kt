package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.nio.file.Path

/** Who holds a profile's lock, as far as `status` cares (gkrost/unidrive-cli#455). */
internal enum class ProfileLockHolder { NONE, DAEMON, OTHER }

/** The live holder of the lock of the profile at [configDir]; a stale sidecar (its writer gone) is no holder. */
internal fun profileLockHolder(
    configDir: Path,
    isAlive: (Long, Path) -> Boolean = { pid, pidFile -> isLockHolderAlive(pid, pidFile) },
): ProfileLockHolder {
    val pidFile = configDir.resolve(".lock.pid")
    val read =
        try {
            readLockPid(pidFile)
        } catch (_: IOException) {
            // Present but unreadable (access denied, a sharing violation): something may hold the profile. Only absence is NONE.
            return ProfileLockHolder.OTHER
        }
    return when (read) {
        is LockPidReadResult.Absent -> ProfileLockHolder.NONE
        // Unreadable: something may hold the profile, so nothing here signs in beside it.
        is LockPidReadResult.Malformed -> ProfileLockHolder.OTHER
        is LockPidReadResult.Present ->
            when {
                !isAlive(read.contents.pid, pidFile) -> ProfileLockHolder.NONE
                read.contents.modeToken == "daemon" -> ProfileLockHolder.DAEMON
                else -> ProfileLockHolder.OTHER
            }
    }
}

/**
 * gkrost/unidrive-cli#455: where `status` takes the account's used bytes from. A process that holds the profile owns its
 * credentials: a second provider here would refresh the token beside it, and both would write the token file (the race
 * archive#1240 removed from `auth.check`). A daemon is asked for the quota it keeps; any other holder (a sync watcher) gets
 * no remote probe, and the caller falls back to the enumerated sum. Only with no holder does `status` sign in itself.
 */
internal fun statusQuotaUsed(
    holder: ProfileLockHolder,
    fromDaemon: () -> Long?,
    fromProvider: () -> Long?,
): Long? =
    when (holder) {
        ProfileLockHolder.DAEMON -> fromDaemon()
        ProfileLockHolder.OTHER -> null
        ProfileLockHolder.NONE -> fromProvider()
    }

/** The `quota.used_bytes` of a `daemon.status` reply; null when the reply carries none or is not JSON. */
internal fun quotaUsedOfStatusReply(reply: String): Long? =
    runCatching {
        val root = Json.parseToJsonElement(reply) as? JsonObject ?: return null
        val used = (root["quota"] as? JsonObject)?.get("used_bytes") as? JsonPrimitive ?: return null
        if (used.isString) null else used.content.toLongOrNull()
    }.getOrNull()
