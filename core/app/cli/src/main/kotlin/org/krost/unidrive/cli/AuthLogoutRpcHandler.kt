package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.auth.CredentialProtectionException
import org.krost.unidrive.sync.PendingUploads
import org.krost.unidrive.sync.StateDatabase
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * The `auth.logout` IPC verb (docs/dev/specs/unidrive-daemon-design.md §4.10): a front-end signs an
 * account out while its daemon is running. The daemon — the profile-lock holder — revokes the
 * provider's token where the provider has a revoke call, deletes the credential files and goes on running
 * without credentials; the sync state is never touched (a front-end's sign-out must not lose it, and the
 * daemon holds state.db open). The reply says what happened; the CLI's `logout --json` wraps it.
 *
 * - Refused with `uploads_pending` (with `pending` / `failed` counts and the first few `paths`) while the state.db still holds
 *   uploads on their way — without credentials they cannot finish; `profile remove` counts them by the
 *   same rule. `"force":true` signs out anyway.
 * - Refused with `credential_protection_unavailable` when a credential marker's OS secret-store item
 *   could not be removed; nothing is deleted, the sign-out can be retried.
 * - `revoked` is true only when the provider confirmed the revocation, false when it could not be reached
 *   or refused it (the local cleanup goes on either way), null when nothing was asked of the provider.
 *   `revocation` says why: `revoked`, `failed`, `unsupported` (the provider has no revoke call: only the
 *   local credentials went; archive#1052) or `not_signed_in` (no valid credential to revoke).
 *
 * Admin class: a `read` connection gets `forbidden` ([IpcAuth] default).
 */
internal class AuthLogoutRpcHandler(
    private val profileDir: Path,
    private val db: StateDatabase,
    private val providerFactory: () -> CloudProvider,
    // The daemon's own provider instance forgets the credentials it holds in memory (CloudProvider.forgetCredentials):
    // it stops reporting itself authenticated and no later request uses the old token.
    private val forgetCredentials: suspend () -> Unit,
    // The uploads that can still happen: the daemon counts `uploads.pending` by the same rows, so the two agree (#86).
    // Without a hydration layer (a mirror daemon) every pending row counts.
    private val pendingRows: () -> PendingUploads.Split = { PendingUploads.split(db.pendingUploadRows(), { false }, null) },
) {
    private val log = LoggerFactory.getLogger(AuthLogoutRpcHandler::class.java)

    suspend fun handle(jsonRequest: String): String {
        val body = runCatching { Json.parseToJsonElement(jsonRequest) as? JsonObject }.getOrNull()
        val force =
            when (val element = body?.get("force")) {
                null -> false
                else -> (element as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull() ?: return INVALID_FORCE
            }
        if (!force) {
            val split = pendingRows()
            val pending = split.waiting.size
            val failed = split.waiting.count { it.lastErrorAt != null }
            if (pending - failed > 0) {
                return buildJsonObject {
                    put("ok", false)
                    put("error", "uploads_pending")
                    put("pending", pending)
                    put("failed", failed)
                    put("paths", JsonArray(split.detailPaths().map { JsonPrimitive(it) }))
                }.toString()
            }
        }
        // Any failure of the revocation attempt (the provider unreachable, the revocation refused) means
        // "not confirmed": the sign-out still goes on with the local cleanup, and the reply says false.
        @Suppress("TooGenericExceptionCaught")
        val revocation =
            try {
                val provider = providerFactory()
                try {
                    signOutProvider(provider)
                } finally {
                    provider.close()
                }
            } catch (e: CredentialProtectionException) {
                log.warn("auth.logout: the credential marker of '{}' could not be removed from the secret store: {}", profileDir, e.message)
                return CREDENTIAL_PROTECTION
            } catch (e: Exception) {
                log.warn("auth.logout: revoking the token of '{}' failed: {}", profileDir, e.message)
                Revocation.FAILED
            }
        // Before the files go: a refresh the daemon's instance still had under way stores nothing from now on, and one
        // that stored its token before this is deleted with the rest below.
        forgetCredentials()
        var credentialsRemoved = false
        for (name in StoragePermissions.CREDENTIAL_FILES) {
            if (Files.deleteIfExists(profileDir.resolve(name))) credentialsRemoved = true
        }
        return """{"ok":true,"revoked":${revocation.revokedJson()},"revocation":"${revocation.token}","credentials_removed":$credentialsRemoved}"""
    }

    companion object {
        const val VERB: String = "auth.logout"

        private const val INVALID_FORCE = """{"ok":false,"error":"invalid_force"}"""
        private const val CREDENTIAL_PROTECTION = """{"ok":false,"error":"credential_protection_unavailable"}"""
    }
}
