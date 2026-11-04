package org.krost.unidrive.cli

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

/**
 * The `auth` object of `daemon.status` (unidrive-cli#387): whether the profile can start without an interactive sign-in, why not,
 * and the profile folder the engine looked in. It is the credential health reading ([CredentialHealthMonitor.State]) read as a
 * sign-in question, not a second check: `needs_sign_in` for a credential that is missing or no longer works, `ok` for one that is
 * present and usable, `unknown` while the engine does not know (no check has finished, or it found something it cannot classify).
 *
 * [reason] is `no_credentials` or `expired` when [state] is `needs_sign_in`, null otherwise. A client that cannot reach the daemon
 * reads the same answer from `profile list --json` (`auth_reason`, `profile_dir`).
 */
internal data class AuthStatus(
    val state: String,
    val reason: String?,
    val profileDir: String,
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            put("state", state)
            put("reason", reason)
            put("profile_dir", profileDir)
        }
}

/** The [AuthStatus] of a credential reading, with the folder the check looked in. */
internal fun authStatusOf(
    state: CredentialHealthMonitor.State,
    profileDir: Path,
): AuthStatus {
    val dir = profileDir.toString()
    return when (state) {
        CredentialHealthMonitor.State.OK, CredentialHealthMonitor.State.EXPIRING -> AuthStatus("ok", null, dir)
        CredentialHealthMonitor.State.EXPIRED -> AuthStatus("needs_sign_in", "expired", dir)
        CredentialHealthMonitor.State.MISSING -> AuthStatus("needs_sign_in", "no_credentials", dir)
        CredentialHealthMonitor.State.UNKNOWN -> AuthStatus("unknown", null, dir)
    }
}
