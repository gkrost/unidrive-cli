package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.krost.unidrive.sync.IpcEndpoint
import org.krost.unidrive.sync.IpcServer
import java.io.PrintStream
import java.nio.file.Path

/**
 * gkrost/unidrive-cli#706: what `status --pending` and plain `quota` take from a running daemon's `daemon.status` reply
 * instead of working it out again (a state.db scan, a second provider sign-in). Without a daemon, or when its reply lacks
 * the figures, the callers keep their local path.
 */

/** The `daemon.status` reply of the daemon holding [profileName]'s lock; null when none runs, it does not answer, or the reply is not a line. */
internal fun runningDaemonStatusReply(
    profileName: String,
    configDir: Path,
    holder: ProfileLockHolder = profileLockHolder(configDir),
    request: (IpcEndpoint) -> String? = { requestDaemonStatus(it) },
): String? =
    if (holder != ProfileLockHolder.DAEMON) {
        null
    } else {
        try {
            request(IpcEndpoint(IpcServer.defaultSocketPath(profileName), configDir, profileName))
        } catch (_: Exception) {
            null
        }
    }

private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toLongOrNull()

private fun objectOf(
    reply: String,
    member: String,
): JsonObject? = runCatching { (Json.parseToJsonElement(reply) as? JsonObject)?.get(member) as? JsonObject }.getOrNull()

/**
 * Prints the pending-transfer report from a `daemon.status` [reply]: `uploads.pending` and `uploads.cloud_only`. Returns false
 * (prints nothing) when the reply has no `uploads` (a mirror daemon, or a queue not yet read) or an engine that predates
 * `cloud_only`: the caller then counts from state.db.
 */
internal fun printDaemonPending(
    profileName: String,
    reply: String,
    out: PrintStream = System.out,
): Boolean {
    val uploads = objectOf(reply, "uploads") ?: return false
    val pending = uploads.long("pending") ?: return false
    val cloudOnly = uploads.long("cloud_only") ?: return false
    if (pending == 0L) {
        out.println("No pending transfers for profile '$profileName'. ($cloudOnly file(s) cloud-only; nothing for `apply` to do.)")
        return true
    }
    out.println("Pending transfers for ${AnsiHelper.bold(profileName)}:")
    out.println("  Upload:   $pending file(s)")
    if (cloudOnly > 0) out.println("  Cloud-only (not downloaded): $cloudOnly file(s)")
    out.println("Run `unidrive -p $profileName apply` to drain.")
    return true
}

/** Prints the `quota` block from a `daemon.status` [reply]; false when the reply carries no usable account quota (the caller asks the provider). */
internal fun printDaemonQuota(
    reply: String,
    out: PrintStream = System.out,
): Boolean {
    val quota = objectOf(reply, "quota") ?: return false
    val used = quota.long("used_bytes") ?: return false
    val total = quota.long("total_bytes") ?: return false
    val name = (Json.parseToJsonElement(reply) as JsonObject)["provider_name"].let { (it as? JsonPrimitive)?.content }
    out.println("Storage Quota (${name ?: "daemon"}):")
    out.println("  Used:      ${CliProgressReporter.formatSize(used)}")
    out.println("  Total:     ${CliProgressReporter.formatSize(total)}")
    out.println("  Remaining: ${CliProgressReporter.formatSize((total - used).coerceAtLeast(0))}")
    return true
}
