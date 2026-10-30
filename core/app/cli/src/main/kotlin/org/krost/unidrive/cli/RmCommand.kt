package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.ProviderException
import org.krost.unidrive.authenticateAndLog
import org.krost.unidrive.engine.RemoteErrors
import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcAuthClient
import org.krost.unidrive.sync.IpcEndpoint
import org.krost.unidrive.sync.IpcLineClient
import org.krost.unidrive.sync.IpcServer
import org.krost.unidrive.sync.ProfileInfo
import org.krost.unidrive.sync.ProfileMode
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

/**
 * `unidrive [-p <profile>] rm [-r] [--yes] [--json] <remotePath>` — delete one remote file, or with `-r` one remote
 * folder with everything in it, through the provider's own delete.
 *
 * Only a MOUNT profile is served. A mirror profile is refused (`mirror_profile`): a remote delete behind the mirror
 * engine's back would be re-uploaded from the sync root by the next sync. With the profile's daemon running the
 * delete goes through the daemon (`hydration.unlink` / `hydration.rmdir` over a full-scope IPC connection), so its
 * state.db and the mount view drop the item at once and the daemon's own guards apply (an upload in flight is
 * `busy`; a recursive delete over a file whose bytes the cloud has not seen is `unsynced_below`). A daemon that holds
 * the profile but does not answer is refused (`daemon_not_answering`); nothing is deleted behind its back. Without a
 * daemon the provider is called directly, but only while the profile has no state.db yet: once the mount has state,
 * a delete that bypasses the daemon would leave its rows and cache copies stale (`daemon_not_running`).
 *
 * What the provider's delete does (the reply's `deleted_as`): Internxt moves the item to the Internxt trash, OneDrive
 * to the OneDrive recycle bin, Dropbox keeps it restorable among the deleted files for the plan's retention
 * (`trash`); localfs deletes it from the disk and S3 deletes the objects (`permanent`; an S3 bucket with versioning
 * keeps the old versions). There is no hard-delete option.
 */
@Command(
    name = "rm",
    description = [
        "Delete a remote file, or with -r a remote folder with everything in it (mount profiles only). " +
            "Internxt, OneDrive and Dropbox keep the item in their trash; localfs and S3 delete it for good. " +
            "--permanent bypasses the provider's trash where the provider can, and always asks first. " +
            "With the daemon running the delete goes through it; a mirror profile is refused.",
    ],
    mixinStandardHelpOptions = true,
)
class RmCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: Main

    @Parameters(index = "0", paramLabel = "<remotePath>", description = ["Remote path to delete, e.g. /folder/file.txt"])
    lateinit var path: String

    @Option(names = ["-r", "--recursive"], description = ["Delete a folder with everything in it"])
    var recursive: Boolean = false

    @Option(names = ["-y", "--yes"], description = ["Do not ask before deleting a folder, or before --permanent (for scripts)"])
    var yes: Boolean = false

    @Option(
        names = ["--permanent"],
        description = ["Delete for good, bypassing the provider's trash (needs a confirmation or --yes)"],
    )
    var permanent: Boolean = false

    @Option(names = ["--json"], description = ["Print the result (or the refusal) as one JSON object on stdout; never asks"])
    var json: Boolean = false

    override fun call(): Int {
        // A JSON caller is a script: stdout carries one object, so there is no prompt to answer.
        val request = RmRequest(path, recursive, permanent, yes, confirm = if (json) null else RmHooks.console())
        if (json) {
            // The last resort of the front-end contract: whatever else goes wrong is still one JSON refusal, with the
            // exception's type only (its message could quote a response).
            @Suppress("TooGenericExceptionCaught")
            return try {
                emitJson(removeRemote(parent, request).json())
                0
            } catch (e: LifecycleError) {
                emitJson(lifecycleErrorJson(e))
                rmExitCode(e)
            } catch (e: Exception) {
                emitJson(lifecycleErrorJson(LifecycleError("internal_error", "unexpected failure: ${e.javaClass.simpleName}")))
                1
            }
        }
        val configPath = parent.configBaseDir().resolve("config.toml")
        if (!Files.exists(configPath)) parent.reportConfigMissingAndExit(configPath)
        return try {
            println(removeRemote(parent, request).consoleLine())
            0
        } catch (e: LifecycleError) {
            System.err.println("rm: ${e.message}")
            rmExitCode(e)
        }
    }
}

/** Test seams of `rm`; production uses the defaults and never reassigns them. */
internal object RmHooks {
    /** The IPC socket of a profile. */
    var socketPath: (String) -> Path = { IpcServer.defaultSocketPath(it) }

    /** A yes/no question on an interactive terminal, or null when there is none (piped, redirected, a service). */
    var console: () -> ((String) -> Boolean)? = { systemConsoleConfirm() }

    /** How long a daemon may take to answer a delete (a recursive delete of a large folder is a provider round trip). */
    var daemonReplyTimeoutMs: Long = 120_000
}

/** What the operator asked for. [confirm] is null when nobody can be asked. */
internal class RmRequest(
    val rawPath: String,
    val recursive: Boolean,
    val permanent: Boolean = false,
    val yes: Boolean,
    val confirm: ((String) -> Boolean)?,
)

/** A delete that happened: [route] is `daemon` or `provider`, [deletedAs] what the provider's delete does. */
internal class RmResult(
    val path: String,
    val isFolder: Boolean,
    val route: String,
    val deletedAs: String,
) {
    fun json(): JsonObject =
        buildJsonObject {
            put("ok", true)
            put("path", path)
            put("kind", if (isFolder) "folder" else "file")
            put("route", route)
            put("deleted_as", deletedAs)
        }

    fun consoleLine(): String {
        val what = if (isFolder) "the folder $path with everything in it" else path
        val where =
            when (deletedAs) {
                DELETED_AS_TRASH -> "moved to the provider's trash"
                DELETED_AS_PERMANENT -> "deleted for good"
                else -> "deleted as the provider deletes"
            }
        return "Deleted $what ($where)."
    }
}

internal const val DELETED_AS_TRASH = "trash"
internal const val DELETED_AS_PERMANENT = "permanent"

/** What each provider's `delete` does with the item; documented in the command help. */
internal fun deleteSemantics(providerType: String): String =
    when (providerType) {
        "internxt", "onedrive", "dropbox" -> DELETED_AS_TRASH
        "localfs", "s3" -> DELETED_AS_PERMANENT
        else -> "provider_defined"
    }

/** A hosting-contract refusal exits 78 like every mode refusal ([Main.MODE_REFUSAL_EXIT]); every other refusal 1. */
private fun rmExitCode(e: LifecycleError): Int = if (e.token == "mirror_profile" || e.token == "mode_missing") 78 else 1

/**
 * Deletes [RmRequest.rawPath] of the `-p` (else default) profile. Never exits the JVM: every refusal is a
 * [LifecycleError] the caller renders, and nothing is deleted when one is thrown.
 */
internal fun removeRemote(
    main: Main,
    request: RmRequest,
): RmResult {
    val path = LsCommand.normalizeLsPath(request.rawPath)
    if (path == "/") throw LifecycleError("root_refused", "refusing to delete the root of the drive")
    val target = resolveProfileTarget(main, null)
    requireMountProfile(main, target)
    // archive#1178: a permanent delete is permanent whatever the provider's trash default is.
    val deletedAs = if (request.permanent) DELETED_AS_PERMANENT else deleteSemantics(target.type)
    val socket = RmHooks.socketPath(target.name)
    return when (ProfileRemover.holderOf(target.profileDir, socket)) {
        ProfileRemover.Holder.OTHER ->
            throw LifecycleError("profile_in_use", "profile '${target.name}' is in use by another unidrive process; nothing was deleted")
        ProfileRemover.Holder.DAEMON ->
            removeViaDaemon(IpcEndpoint(socket, target.profileDir, target.name), path, request, deletedAs)
        ProfileRemover.Holder.NONE ->
            withProfileLock(target) { removeViaProvider(main, target, path, request, deletedAs) }
    }
}

private fun requireMountProfile(
    main: Main,
    target: ProfileTarget,
) {
    modeRefusal(target.name, readProfile(main, target).mode)?.let { throw it }
}

private fun readProfile(
    main: Main,
    target: ProfileTarget,
): ProfileInfo {
    val profile =
        try {
            main.resolveProfile(target.name)
        } catch (e: IllegalArgumentException) {
            // The message can quote the offending config value: the reply names the token only.
            throw LifecycleError("config_invalid", "the profile '${target.name}' could not be read from config.toml", e)
        }
    return profile ?: throw LifecycleError("profile_not_found", "profile '${target.name}' is not configured")
}

/** Why a profile of [mode] is not served by rm, or null for a mount profile. */
private fun modeRefusal(
    profileName: String,
    mode: ProfileMode?,
): LifecycleError? =
    when (mode) {
        null -> LifecycleError("mode_missing", ProfileMode.modelessMessage(profileName, "rm"))
        ProfileMode.MIRROR ->
            LifecycleError(
                "mirror_profile",
                "profile '$profileName' is a mirror profile; rm serves mount profiles only. Nothing was deleted: " +
                    "the next sync would upload the item again from the sync root. Delete the local copy and run sync instead.",
            )
        ProfileMode.MOUNT -> null
    }

/** A folder is deleted only with `-r`, and only after a yes on the console or `--yes`; `--permanent` asks for either kind. */
private fun requireFolderConsent(
    path: String,
    request: RmRequest,
) {
    folderRefusal(path, request)?.let { throw it }
}

private fun folderRefusal(
    path: String,
    request: RmRequest,
): LifecycleError? {
    val confirm = request.confirm
    return when {
        !request.recursive -> LifecycleError("path_is_folder", "'$path' is a folder; rm -r deletes a folder with everything in it")
        // archive#1178: the permanent consent below asks; one question, never two.
        request.permanent -> null
        request.yes -> null
        confirm == null ->
            LifecycleError("confirmation_required", "deleting the folder '$path' needs a confirmation: run rm on a console, or pass --yes")
        !confirm("Delete the folder '$path' with everything in it?") -> LifecycleError("cancelled", "nothing was deleted")
        else -> null
    }
}

// archive#1178: --permanent refuses without a yes, whatever the kind is; one question, never two.
private fun requirePermanentConsent(
    path: String,
    request: RmRequest,
    isFolder: Boolean?,
) {
    if (!request.permanent) return
    if (request.yes) return
    val confirm =
        request.confirm ?: throw LifecycleError(
            "confirmation_required",
            "deleting '$path' for good needs a confirmation: run rm on a console, or pass --yes",
        )
    val what =
        when (isFolder) {
            true -> "the folder '$path' with everything in it"
            false -> "'$path'"
            // The kind is not known yet (the daemon route asks before its type probe); one question covers both.
            null -> "'$path' (a folder with everything in it, if it is one)"
        }
    if (!confirm("Delete $what FOR GOOD, bypassing the provider's trash?")) throw LifecycleError("cancelled", "nothing was deleted")
}

// ── the daemon route ─────────────────────────────────────────────────────────

/**
 * The daemon deletes: `hydration.unlink` first (a folder answers `path_is_folder` before anything is touched), then,
 * for a folder with `-r` and consent, `hydration.rmdir` with `"recursive":true`.
 */
private fun removeViaDaemon(
    endpoint: IpcEndpoint,
    path: String,
    request: RmRequest,
    deletedAs: String,
): RmResult {
    // archive#1178: a permanent delete asks before the unlink type-probe — a file would be gone at once otherwise.
    if (request.permanent) requirePermanentConsent(path, request, null)
    val unlink = askDaemonToDelete(endpoint, "hydration.unlink", path, recursive = false, permanent = request.permanent)
    if (unlink == null) return RmResult(path, isFolder = false, route = "daemon", deletedAs = deletedAs)
    if (unlink != "path_is_folder") throw daemonRefusal(unlink, path, recursive = false)
    requireFolderConsent(path, request) // -r is required for a permanent delete too; its consent was asked above
    val rmdir = askDaemonToDelete(endpoint, "hydration.rmdir", path, recursive = true, permanent = request.permanent)
    if (rmdir == null) return RmResult(path, isFolder = true, route = "daemon", deletedAs = deletedAs)
    throw daemonRefusal(rmdir, path, recursive = true)
}

/** Null when the daemon deleted; else its refusal token. Throws when the daemon cannot be asked or does not answer. */
private fun askDaemonToDelete(
    endpoint: IpcEndpoint,
    verb: String,
    path: String,
    recursive: Boolean,
    permanent: Boolean = false,
): String? {
    val request =
        buildJsonObject {
            put("verb", verb)
            put("path", path)
            if (recursive) put("recursive", true)
            if (permanent) put("permanent", true)
        }.toString()
    return when (val answer = askDaemon(endpoint, request, RmHooks.daemonReplyTimeoutMs)) {
        DaemonAnswer.NotReached ->
            throw LifecycleError(
                "daemon_not_answering",
                "the profile's daemon holds the profile but does not answer; nothing was deleted. " +
                    "Check it with `unidrive -p ${endpoint.profileName} daemon status`.",
            )
        DaemonAnswer.NoReply ->
            throw LifecycleError(
                "no_reply",
                "the daemon took the delete of '$path' but did not answer in time; it may still happen. Check with `unidrive ls`.",
            )
        is DaemonAnswer.Reply ->
            if (answer.json.isTrue("ok")) null else (answer.json["error"] as? JsonPrimitive)?.content ?: "unknown"
    }
}

private fun JsonObject.isTrue(key: String): Boolean = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content == "true"

/** The rm refusal for a daemon's error token; the daemon's tokens are documented in HydrationIpcHandler. */
private fun daemonRefusal(
    token: String,
    path: String,
    recursive: Boolean,
): LifecycleError =
    when (token) {
        "unknown_path" -> LifecycleError("not_found", "no such remote path: $path")
        "not_empty" ->
            if (recursive) {
                LifecycleError(
                    "unsynced_below",
                    "a file below '$path' holds changes the cloud has not received yet; nothing was deleted. " +
                        "Let the upload finish, or delete that file on its own first.",
                )
            } else {
                LifecycleError("folder_not_empty", "'$path' is not empty; rm -r deletes a folder with everything in it")
            }
        "busy" -> LifecycleError("busy", "an upload of '$path' (or of a file below it) is in flight; retry when it has finished")
        "read_only" -> LifecycleError("read_only", "'$path' is read-only on this drive")
        "outside_scope" -> LifecycleError("outside_scope", "'$path' is outside the profile's sync_path")
        "permanent_unsupported" ->
            LifecycleError(
                "permanent_delete_unsupported",
                "the provider of this profile cannot delete for good (no permanent-delete capability); leave --permanent off",
            )
        "path_is_file" -> LifecycleError("path_is_file", "'$path' became a file while it was deleted; run rm again")
        else -> LifecycleError("delete_failed", "the daemon could not delete '$path': $token")
    }

/** What a request to the daemon came to. */
internal sealed interface DaemonAnswer {
    /** The daemon could not be reached or authenticated, or the request was not written: nothing was asked. */
    data object NotReached : DaemonAnswer

    /** The request was written but no reply came in time: the daemon may still act on it. */
    data object NoReply : DaemonAnswer

    data class Reply(
        val json: JsonObject,
    ) : DaemonAnswer
}

/** Sends one [request] line over a full-scope connection (the hydration write verbs need it) and reads one reply line. */
internal fun askDaemon(
    endpoint: IpcEndpoint,
    request: String,
    timeoutMs: Long,
): DaemonAnswer {
    if (!Files.exists(endpoint.socketPath)) return DaemonAnswer.NotReached
    val channel =
        try {
            IpcAuthClient.connect(endpoint, IpcAuth.Scope.FULL)
        } catch (_: IOException) {
            return DaemonAnswer.NotReached
        }
    return channel.use {
        it.configureBlocking(false)
        val deadline = System.currentTimeMillis() + timeoutMs
        val lines = IpcLineClient(it)
        // A request that is not written in full has no newline: the daemon never dispatches it.
        val written =
            try {
                lines.writeLine(request, deadline)
            } catch (_: IOException) {
                false
            }
        if (!written) return@use DaemonAnswer.NotReached
        val reply =
            try {
                readReplyObject(lines, deadline)
            } catch (_: IOException) {
                null
            }
        reply?.let { json -> DaemonAnswer.Reply(json) } ?: DaemonAnswer.NoReply
    }
}

// ── the provider route ───────────────────────────────────────────────────────

/**
 * No daemon: the provider deletes, under the profile lock (no daemon or sync can start meanwhile). Only for a mount
 * profile without state: the daemon's rows, cache copies and pending uploads are its own to update.
 */
private fun removeViaProvider(
    main: Main,
    target: ProfileTarget,
    path: String,
    request: RmRequest,
    deletedAs: String,
): RmResult {
    requireNoMountState(target)
    val provider = main.createProviderForName(target.name)
    return try {
        runBlocking { deleteWithProvider(provider, path, request) }
            .let { isFolder -> RmResult(path, isFolder, route = "provider", deletedAs = deletedAs) }
    } catch (e: AuthenticationException) {
        throw LifecycleError("auth_required", "profile '${target.name}' is not signed in; run `unidrive -p ${target.name} auth`", e)
    } catch (e: ProviderException) {
        throw LifecycleError("delete_failed", "the provider could not delete '$path': ${e.message}", e)
    } finally {
        provider.close()
    }
}

/** The provider route bypasses the daemon: only a mount profile that has no state yet has nothing to keep true. */
private fun requireNoMountState(target: ProfileTarget) {
    if (Files.exists(target.profileDir.resolve("state.db"))) {
        throw LifecycleError(
            "daemon_not_running",
            "the mount profile '${target.name}' has state and its daemon is not running; nothing was deleted. " +
                "Start it (`unidrive -p ${target.name} daemon run`, or open the mount) and run rm again, so the mount drops the item too.",
        )
    }
}

/** Deletes [path] with [provider]; true when it was a folder. */
private suspend fun deleteWithProvider(
    provider: CloudProvider,
    path: String,
    request: RmRequest,
): Boolean {
    provider.authenticateAndLog()
    if (request.permanent && provider.capabilities().none { it is org.krost.unidrive.Capability.PermanentDelete }) {
        throw LifecycleError(
            "permanent_delete_unsupported",
            "the provider of this profile cannot delete for good (no permanent-delete capability); leave --permanent off",
        )
    }
    val item = metadataOrNull(provider, path) ?: throw LifecycleError("not_found", "no such remote path: $path")
    if (item.isFolder) requireFolderConsent(path, request)
    requirePermanentConsent(path, request, item.isFolder)
    if (request.permanent) {
        provider.deletePermanently(path)
    } else {
        provider.delete(path)
    }
    return item.isFolder
}

/** The item at [path], or null when the provider proves it absent; any other failure propagates. */
@Suppress("TooGenericExceptionCaught") // classified by RemoteErrors.isNotFound, anything else is rethrown unchanged
private suspend fun metadataOrNull(
    provider: CloudProvider,
    path: String,
): CloudItem? =
    try {
        provider.getMetadata(path)
    } catch (e: Exception) {
        if (RemoteErrors.isNotFound(e)) null else throw e
    }

/**
 * The system console as a yes/no question, only when it is a real terminal. JDK 22+ hands out a [java.io.Console] even
 * when the streams are redirected, so [java.io.Console.isTerminal] decides there (called reflectively: the compile
 * toolchain is JDK 21); up to JDK 21 a console exists only on a terminal.
 */
internal fun systemConsoleConfirm(): ((String) -> Boolean)? {
    val console = System.console() ?: return null
    val isTerminalMethod = java.io.Console::class.java.methods.firstOrNull { it.name == "isTerminal" && it.parameterCount == 0 }
    val isTerminal = isTerminalMethod?.invoke(console) as? Boolean ?: true
    if (!isTerminal) return null
    return { question -> console.readLine("%s [y/N] ", question)?.trim()?.lowercase() in setOf("y", "yes") }
}
