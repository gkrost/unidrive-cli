package org.krost.unidrive.cli

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.krost.unidrive.AccountOwner
import org.krost.unidrive.UnprovenAccountId
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.ProviderException
import org.krost.unidrive.ProviderRegistry
import org.krost.unidrive.TokenRevocation
import org.krost.unidrive.auth.CredentialProtectionException
import org.krost.unidrive.localfs.LocalFsProviderFactory
import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcAuthClient
import org.krost.unidrive.sync.IpcEndpoint
import org.krost.unidrive.sync.IpcServer
import org.krost.unidrive.sync.StateDatabase
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import kotlinx.coroutines.runBlocking

@Command(
    name = "logout",
    description = [
        "Sign out: remove the stored credentials and, where the provider has a revoke call (Dropbox), revoke its token; " +
            "for the other providers only the local credentials go, and the output says so. The sync state is kept unless " +
            "--delete-state is given (the state and the failed-upload record exist only on this machine). " +
            "Works while the profile's daemon runs: the sign-out is handed to the daemon over IPC, which refuses " +
            "while uploads are pending (without credentials they cannot finish) and keeps running unauthenticated.",
    ],
    mixinStandardHelpOptions = true,
)
class LogoutCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: Main

    @Option(names = ["--profile"], description = ["Profile (default: -p, else the default profile)"])
    var profile: String? = null

    // The state-deleting form is opt-in (--delete-state): the destructive part is the one that has to be asked
    // for. --keep-state stays accepted for compatibility; it is what the command does by default now.
    @Option(names = ["--keep-state"], description = ["Keep the sync state (the default; accepted for compatibility)"])
    var keepState: Boolean = false

    @Option(
        names = ["--delete-state"],
        description = ["Delete state.db, scan-staging.db, failures.jsonl and the stored user of the state (account_id); refused while the profile's daemon runs"],
    )
    var deleteState: Boolean = false

    @Option(
        names = ["--force-pending"],
        description = ["Sign out even though uploads are still pending (refusal token uploads_pending; failed uploads alone do not block)"],
    )
    var forcePending: Boolean = false

    @Option(names = ["--json"], description = ["Print the result (or the refusal) as one JSON object on stdout"])
    var json: Boolean = false

    override fun call(): Int {
        // --keep-state is the compatibility form of the default; it wins over --delete-state.
        val delete = deleteState && !keepState
        if (json) {
            // The generic catch is the last resort of the front-end contract: whatever else goes wrong is
            // still one JSON refusal, with the exception's type only (its message could quote a response).
            @Suppress("TooGenericExceptionCaught")
            return try {
                emitJson(logout(parent, profile, delete, forcePending).json)
                0
            } catch (e: LifecycleError) {
                emitJson(lifecycleErrorJson(e))
                1
            } catch (e: CredentialProtectionException) {
                emitJson(
                    lifecycleErrorJson(
                        LifecycleError("credential_protection_unavailable", e.message ?: "unlock the native credential store and retry", e),
                    ),
                )
                1
            } catch (e: Exception) {
                emitJson(lifecycleErrorJson(LifecycleError("internal_error", "unexpected failure: ${e.javaClass.simpleName}")))
                1
            }
        }

        val configPath = parent.configBaseDir().resolve("config.toml")
        if (!Files.exists(configPath)) parent.reportConfigMissingAndExit(configPath)
        return try {
            val target = resolveProfileTarget(parent, profile)
            val result = logout(parent, profile, delete, forcePending)
            println("Logged out of ${profileLabel(target)}.")
            for (f in result.removedFiles) println("  Removed: $f")
            result.revocation?.let { println("  ${it.consoleLine()}") }
            if (!delete) println("  Sync state preserved.")
            if (result.viaDaemon) println("  The daemon keeps running without credentials (unauthenticated).")
            0
        } catch (e: LifecycleError) {
            System.err.println("Error: ${e.message}")
            1
        } catch (e: CredentialProtectionException) {
            System.err.println("Error: ${e.message ?: "the credential marker could not be removed from the OS secret store"}")
            1
        }
    }
}

/** The display name a sign-out names: the provider's, with the profile in brackets when it is not the type. */
internal fun profileLabel(target: ProfileTarget): String {
    val displayName = ProviderRegistry.getMetadata(target.type)?.displayName ?: target.type
    return if (target.name != target.type) "$displayName (${target.name})" else displayName
}

/**
 * What a sign-out did at the provider (archive#1052). [revoked] is the `revoked` of the reply: true only after the provider
 * confirmed the revocation, false when it was asked and did not, null when nothing was asked of it. [token] is the
 * machine-readable reason, the `revocation` of the reply.
 */
internal enum class Revocation(
    val revoked: Boolean?,
    val token: String,
) {
    /** The provider confirmed that the token no longer works. */
    REVOKED(true, "revoked"),

    /** The provider could not be reached or refused the revocation; the token may still work. */
    FAILED(false, "failed"),

    /** The provider has no call that revokes a token; only the local credentials went. */
    UNSUPPORTED(null, "unsupported"),

    /** There was no valid credential to revoke (the profile was already signed out). */
    NOT_AUTHENTICATED(null, "not_signed_in"),
    ;

    /** The reply's `revoked` as JSON. */
    fun revokedJson(): JsonElement = revoked?.let { JsonPrimitive(it) } ?: JsonNull

    /** The console line that tells the user what happened to the provider's side of the token. */
    fun consoleLine(): String =
        when (this) {
            REVOKED -> "The provider confirmed that the token was revoked."
            FAILED -> "The provider could not be asked to revoke the token; it may still work. Revoke it in the provider's account settings."
            UNSUPPORTED -> "Local credentials removed; the provider session was not revoked (the provider has no revoke call)."
            NOT_AUTHENTICATED -> "There was no valid sign-in to revoke."
        }
}

/**
 * Asks [provider] to revoke its token at the provider and maps the outcome. A failure (the provider could not be
 * reached, the revocation was refused) is [Revocation.FAILED]; the sign-out goes on locally either way.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun revokeToken(provider: CloudProvider): Revocation =
    try {
        when (provider.revokeToken()) {
            TokenRevocation.REVOKED -> Revocation.REVOKED
            TokenRevocation.UNSUPPORTED -> Revocation.UNSUPPORTED
        }
    } catch (_: AuthenticationException) {
        Revocation.NOT_AUTHENTICATED
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        Revocation.FAILED
    }

/**
 * Signs [provider] out: the revocation first (it needs the token), then the local forgetting. A
 * [CredentialProtectionException] passes through — the marker file must survive when its secret-store item could
 * not be removed; any other failure of the local forgetting is left to the caller, who deletes the credential files itself.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun signOutProvider(provider: CloudProvider): Revocation {
    val revocation = revokeToken(provider)
    try {
        provider.logout()
    } catch (e: CredentialProtectionException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // the caller's file deletion is the local sign-out; this one only released what the provider held
    }
    return revocation
}

internal fun deleteCredentialFiles(profileDir: Path): List<String> =
    StoragePermissions.CREDENTIAL_FILES.mapNotNull { name ->
        val file = profileDir.resolve(name)
        if (Files.deleteIfExists(file)) file.toString() else null
    }

internal fun deleteStateFiles(profileDir: Path): List<String> =
    STATE_FILES.mapNotNull { name ->
        val file = profileDir.resolve(name)
        // A throw stops the list. The staging database is before state.db, so a failure there
        // (the file still on disk) does not continue on to state.db.
        if (Files.deleteIfExists(file)) file.toString() else null
    }

// Staging names first, then state.db. A checkpoint left after state.db is gone is resumed, because getActiveScan
// has no last_full_scan left to invalidate it. The root-seen marker and the database's other files are state too.
// The stored user (AccountOwner) says whose drive the state describes: it is deleted with the state, never with the
// credentials, so a sign-in after a plain sign-out is still compared with it.
internal val STATE_FILES =
    StateDatabase.stagingFileNames() +
        listOf("state.db", "failures.jsonl", LocalFsProviderFactory.ROOT_SEEN_MARKER) +
        StateDatabase.sidecarNames() +
        AccountOwner.FILE_NAME +
        UnprovenAccountId.FILE_NAME

@Suppress("TooGenericExceptionCaught")
internal fun logoutAndDeleteLocalFiles(
    profileDir: Path,
    keepState: Boolean,
    logout: suspend () -> Unit,
): List<String> {
    try {
        runBlocking { logout() }
    } catch (e: CredentialProtectionException) {
        throw e
    } catch (_: Exception) {
        // the credential files below are the local sign-out
    }
    val deleted = deleteCredentialFiles(profileDir).toMutableList()
    if (!keepState) deleted += deleteStateFiles(profileDir)
    return deleted
}

/** What a sign-out did: [json] is the `--json` reply, the rest is the console rendering. */
internal class LogoutResult(
    val json: JsonObject,
    /** The deleted files, for the console listing; empty when the daemon did the deletion. */
    val removedFiles: List<String>,
    /** True when a running daemon performed the sign-out over IPC and now serves the profile unauthenticated. */
    val viaDaemon: Boolean,
    /** What happened at the provider; null when a daemon too old to say answered. */
    val revocation: Revocation? = null,
)

/** Test seams of the sign-out; production uses the defaults and never reassigns them. */
internal object LogoutHooks {
    /** The IPC socket of a profile (also where the sign-in steps find a running daemon, [withProfileLockOrDaemon]). */
    var socketPath: (String) -> Path = { IpcServer.defaultSocketPath(it) }

    /** Sends `auth.logout` to the daemon on the endpoint; its parsed reply, or null when it could not be asked. */
    var requestLogout: (IpcEndpoint, Boolean) -> JsonObject? = { endpoint, force -> sendAuthLogout(endpoint, force) }
}

/**
 * Signs [profileOption] (else `-p`, else the default profile) out.
 *
 * A profile held by another unidrive process is refused (`profile_in_use`). A running daemon performs the
 * sign-out itself over IPC (`auth.logout`, §4.10 of the daemon design): it refuses while uploads are pending
 * and keeps its sync state, so `--delete-state` is refused against it. A stopped profile is signed out here,
 * under the profile lock, with the same refusals (`uploads_pending`, `state_unreadable`) as `profile remove`.
 * Never exits the JVM: every refusal is a [LifecycleError] the caller renders.
 */
internal fun logout(
    main: Main,
    profileOption: String?,
    deleteState: Boolean,
    forcePending: Boolean,
): LogoutResult {
    val target = resolveProfileTarget(main, profileOption)
    val socket = LogoutHooks.socketPath(target.name)
    when (ProfileRemover.holderOf(target.profileDir, socket)) {
        ProfileRemover.Holder.OTHER ->
            throw LifecycleError("profile_in_use", "profile '${target.name}' is in use by another unidrive process; stop it first")

        // The daemon hands out the sign-out; null means it stopped while it was asked and the
        // profile is free now — the local form below takes over.
        ProfileRemover.Holder.DAEMON -> askRunningDaemon(target, socket, deleteState, forcePending)?.let { return it }

        ProfileRemover.Holder.NONE -> {}
    }
    return withProfileLock(target) { logoutStoppedProfile(main, target, deleteState, forcePending) }
}

/**
 * Hands the sign-out to the running daemon on [socket]: `--delete-state` is refused against it (a
 * running daemon's state cannot be deleted), its reply decides the outcome, and a daemon that does
 * not answer but still holds the profile is a `daemon_running` refusal. Null when the daemon went
 * away between the holder check and its answer.
 */
private fun askRunningDaemon(
    target: ProfileTarget,
    socket: Path,
    deleteState: Boolean,
    forcePending: Boolean,
): LogoutResult? {
    if (deleteState) {
        throw LifecycleError(
            "daemon_running",
            "the sync state of the running daemon of profile '${target.name}' cannot be deleted; " +
                "stop it first (`unidrive -p ${target.name} daemon stop`), then run logout --delete-state",
        )
    }
    val reply = LogoutHooks.requestLogout(IpcEndpoint(socket, target.profileDir, target.name), forcePending)
    if (reply != null) return signedOutByDaemon(target, reply)
    if (ProfileRemover.holderOf(target.profileDir, socket) != ProfileRemover.Holder.NONE) {
        throw LifecycleError(
            "daemon_running",
            "the daemon of profile '${target.name}' did not answer the sign-out; " +
                "stop it first (`unidrive -p ${target.name} daemon stop`)",
        )
    }
    return null
}

/** The daemon's answer to `auth.logout`: a success reply becomes the command's, a refusal passes through. */
private fun signedOutByDaemon(
    target: ProfileTarget,
    reply: JsonObject,
): LogoutResult {
    if ((reply["ok"] as? JsonPrimitive)?.content == "true") {
        val token = (reply["revocation"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val json =
            buildJsonObject {
                put("ok", true)
                put("profile", target.name)
                put("credentials_removed", reply.optBoolean("credentials_removed") ?: false)
                put("revoked", reply.optBoolean("revoked")?.let { JsonPrimitive(it) } ?: JsonNull)
                // An older daemon has no `revocation` (and its `revoked` only meant "logout() returned").
                if (token != null) put("revocation", token)
                put("state_kept", true)
                put("daemon_unauthenticated", true)
            }
        return LogoutResult(json, emptyList(), viaDaemon = true, revocation = Revocation.entries.firstOrNull { it.token == token })
    }
    throw daemonRefusal(target, reply)
}

/** The daemon's refusal of `auth.logout`, worded for the command; unknown tokens pass through unchanged. */
private fun daemonRefusal(
    target: ProfileTarget,
    reply: JsonObject,
): LifecycleError =
    when (val token = (reply["error"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
        "uploads_pending" -> {
            val pending = (reply["pending"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
            val failed = (reply["failed"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
            LifecycleError(
                "uploads_pending",
                "profile '${target.name}' still has uploads that never reached the cloud ($pending pending, $failed of them failed); " +
                    "let them finish, or run again with --force-pending",
                details =
                    buildJsonObject {
                        put("pending", pending)
                        put("failed", failed)
                        (reply["paths"] as? JsonArray)?.let { put("paths", it) }
                    },
            )
        }
        "credential_protection_unavailable" ->
            LifecycleError(
                token,
                "the credential marker of profile '${target.name}' could not be removed from the OS secret store; unlock it and retry",
            )
        "unknown_verb" ->
            LifecycleError(
                "daemon_running",
                "the running daemon of profile '${target.name}' is older than this command and cannot sign out over IPC; " +
                    "stop it first (`unidrive -p ${target.name} daemon stop`)",
            )
        else ->
            LifecycleError(
                token ?: "daemon_logout_failed",
                "the daemon of profile '${target.name}' refused the sign-out${token?.let { " ($it)" } ?: ""}",
            )
    }

/** The sign-out of a stopped profile, under the profile lock. The destructive form deletes the sync state. */
private fun logoutStoppedProfile(
    main: Main,
    target: ProfileTarget,
    delete: Boolean,
    forcePending: Boolean,
): LogoutResult {
    if (!forcePending) ProfileRemover.refuseIfUploadsPending(target.profileDir, target.name)
    val revoked: Revocation =
        when (val provider = providerToSignOut(main, target)) {
            null -> Revocation.UNSUPPORTED
            else ->
                try {
                    runBlocking { signOutProvider(provider) }
                } finally {
                    runCatching { provider.close() }
                }
        }
    val removedCredentials = deleteCredentialFiles(target.profileDir)
    val removed = removedCredentials.toMutableList()
    var stateKept = true
    if (delete) {
        removed += deleteStateFiles(target.profileDir)
        stateKept = false
    }
    val json =
        buildJsonObject {
            put("ok", true)
            put("profile", target.name)
            put("credentials_removed", removedCredentials.isNotEmpty())
            put("revoked", revoked.revokedJson())
            put("revocation", revoked.token)
            put("state_kept", stateKept)
            put("daemon_unauthenticated", false)
        }
    return LogoutResult(json, removed, viaDaemon = false, revocation = revoked)
}

/**
 * The provider whose token the sign-out revokes, or null when there is none to revoke: a provider without
 * an account (localfs) that refuses to be built, as localfs does for a profile whose root is gone. Its
 * logout does nothing, so the sign-out goes on without it; any other provider's refusal passes through.
 */
private fun providerToSignOut(
    main: Main,
    target: ProfileTarget,
): CloudProvider? =
    try {
        main.createProviderForName(target.name)
    } catch (e: ProviderException) {
        if (ProviderRegistry.getMetadata(target.type)?.authType != "None") throw e
        null
    }

/**
 * Sends `auth.logout` to the daemon listening on [endpoint] and reads its one reply line. Null when the
 * socket is absent or unreachable, the IPC handshake fails (an admin verb needs a full-scope connection)
 * or no reply arrives in time; never throws. A missing reply means "cannot be asked", never a refusal.
 */
internal fun sendAuthLogout(
    endpoint: IpcEndpoint,
    force: Boolean,
    // Long enough for the daemon to finish its token revocation (a network round trip) before answering;
    // giving up early would read as an unanswered daemon although the sign-out went through.
    timeoutMs: Long = 30_000,
): JsonObject? =
    sendDaemonRequest(
        endpoint,
        if (force) """{"verb":"auth.logout","force":true}""" else """{"verb":"auth.logout"}""",
        timeoutMs,
    )

/**
 * Sends one [request] line to the daemon listening on [endpoint] over a full-scope (owner-only) connection and reads
 * its one reply line. Null when the socket is absent or unreachable, the IPC handshake fails or no reply arrives in
 * time; never throws. The request may carry a secret (a sign-in step): it is written to the socket only, never printed
 * or logged.
 */
internal fun sendDaemonRequest(
    endpoint: IpcEndpoint,
    request: String,
    timeoutMs: Long,
): JsonObject? {
    if (!Files.exists(endpoint.socketPath)) return null
    return try {
        IpcAuthClient.connect(endpoint, IpcAuth.Scope.FULL).use { channel ->
            channel.configureBlocking(false)
            val deadline = System.currentTimeMillis() + timeoutMs
            if (!writeRequest(channel, ByteBuffer.wrap((request + "\n").toByteArray(Charsets.UTF_8)), deadline)) return null
            readReplyLine(channel, deadline)
        }
    } catch (_: Exception) {
        null
    }
}

/** The whole [request] written by [deadline], or false when the daemon stopped taking it. */
internal fun writeRequest(
    channel: SocketChannel,
    request: ByteBuffer,
    deadline: Long,
): Boolean {
    while (request.hasRemaining()) {
        channel.write(request)
        if (request.hasRemaining()) {
            if (System.currentTimeMillis() >= deadline) return false
            Thread.sleep(10)
        }
    }
    return true
}

/** One JSON reply line read by [deadline], or null; what arrives past the deadline is nothing. */
internal fun readReplyLine(
    channel: SocketChannel,
    deadline: Long,
): JsonObject? {
    // Bytes, decoded once the line is whole: a reply can carry non-ASCII text (an account name), and a read can end
    // inside one of its UTF-8 sequences.
    val collected = java.io.ByteArrayOutputStream()
    val buf = ByteBuffer.allocate(4096)
    while (System.currentTimeMillis() < deadline) {
        val n = channel.read(buf)
        if (n < 0) break
        if (n > 0) {
            buf.flip()
            collected.write(buf.array(), 0, buf.limit())
            buf.clear()
            val bytes = collected.toByteArray()
            val newline = bytes.indexOf('\n'.code.toByte())
            if (newline >= 0) {
                val line = String(bytes, 0, newline, Charsets.UTF_8)
                return runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
            }
        } else {
            Thread.sleep(10)
        }
    }
    return null
}

/** A JSON boolean member, or null when it is absent or not a boolean literal. */
private fun JsonObject.optBoolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
