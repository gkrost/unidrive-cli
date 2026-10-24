package org.krost.unidrive.cli

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.krost.unidrive.sync.ProfileMode
import org.krost.unidrive.sync.RawProvider
import org.krost.unidrive.sync.SyncConfig
import org.krost.unidrive.sync.escapeTomlValue
import org.krost.unidrive.sync.generateProfileToml
import org.krost.unidrive.sync.isValidProfileName
import org.krost.unidrive.sync.isReservedProfileName
import org.krost.unidrive.sync.setDefaultProfile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** What `profile add --type ...` was asked to create. */
internal class NewProfile(
    val type: String,
    val name: String?,
    val mode: String?,
    val syncRoot: String?,
    val label: String?,
    /** `key=value` strings for the provider's non-secret prompts. */
    val options: List<String>,
    val makeDefault: Boolean,
    /** Read the one missing secret from the first line of standard input. */
    val secretStdin: Boolean = false,
)

/**
 * The default sync root the wizard suggests for a mirror profile of [type]: the canonical one,
 * except when that is one of the official Internxt Desktop client's folders, which the owner
 * reserves for that client.
 */
internal fun mirrorSyncRootDefault(type: String): String {
    val computed = SyncConfig.defaultSyncRoot(type)
    return if (isOfficialInternxtClientFolder(computed.fileName.toString())) {
        computed.parent.resolve("InternxtSync").toString()
    } else {
        computed.toString()
    }
}

/**
 * Non-interactive profile creation, the shared body behind `profile add --type ... --json`. No
 * console, no prompt, no daemon. Credentials never come from the argument list: only the provider's
 * non-secret prompts can be given (`--option key=value`); a secret prompt is refused by name and the
 * secret is entered through the provider's own flow (`auth begin`/`auth login`, or the wizard).
 */
internal object ProfileCreator {
    fun create(
        configDir: Path,
        req: NewProfile,
    ): JsonObject {
        val configPath = configDir.resolve("config.toml")
        return ConfigFileTransaction.withLock(configPath) {
        val type = req.type
        val factory =
            LifecycleHooks.factoryFor(type)
                ?: throw LifecycleError(
                    "unknown_type",
                    "'$type' is not a provider type. Known: ${SyncConfig.KNOWN_TYPES.sorted().joinToString(", ")}",
                )
        val name = req.name?.takeIf { it.isNotBlank() } ?: type
        if (isReservedProfileName(name)) {
            throw LifecycleError("reserved_profile_name", "'$name' is a reserved Windows device name")
        }
        if (!isValidProfileName(name)) {
            throw LifecycleError("invalid_profile_name", "'$name' is invalid: use only letters, digits, hyphens and underscores")
        }
        val mode =
            when (req.mode?.trim()?.lowercase()) {
                null, "" -> throw LifecycleError("missing_mode", "--mode mount|mirror is required; there is no default")
                ProfileMode.MOUNT.wireName -> ProfileMode.MOUNT
                ProfileMode.MIRROR.wireName -> ProfileMode.MIRROR
                else -> throw LifecycleError("invalid_mode", "--mode must be 'mount' or 'mirror'")
            }

        val raw =
            try {
                if (Files.exists(configPath)) {
                    SyncConfig.parseRaw(Files.readString(configPath), configPath.toString())
                } else {
                    SyncConfig.parseRaw("[general]\n")
                }
            } catch (_: Exception) {
                // The parser's message can quote the offending config line, which may hold a credential.
                throw LifecycleError("config_invalid", "config.toml could not be parsed")
            }
        if (raw.providers.keys.any { it.equals(name, ignoreCase = true) }) {
            throw LifecycleError("profile_exists", "profile '$name' already exists")
        }

        val label = req.label?.takeIf { it.isNotBlank() }
        val warnings = mutableListOf<String>()
        val syncRoot =
            when (mode) {
                ProfileMode.MOUNT -> {
                    if (req.syncRoot != null) {
                        throw LifecycleError("sync_root_not_allowed", "a mount profile has no sync root; it works on the mount root")
                    }
                    configDir.resolve(name).resolve("sync-root").toString()
                }
                ProfileMode.MIRROR -> {
                    resolveMirrorSyncRoot(req.syncRoot, type, warnings)
                }
            }
        val conflict =
            SyncConfig.detectRootIsolationConflicts(
                raw.copy(providers = raw.providers + (name to RawProvider(type = type, mode = mode.wireName, sync_root = syncRoot))),
            )
        if (conflict != null) {
            val hint = if (req.syncRoot.isNullOrBlank()) " Pass --sync-root <dir> to give this profile its own folder." else ""
            throw LifecycleError("sync_root_conflict", conflict + hint)
        }

        val resolved = credentialsFromOptions(factory.credentialPrompts(), factory.envVarMappings(), req)
        val creds = resolved.written
        warnings += resolved.notes
        // archive#917: an unusable value (a localfs root_path that is a file, a drive root, unreadable...) is
        // refused here with the provider's token, not when the account first starts. Nothing is created.
        factory.preflightAdd(resolved.effective)?.let { throw LifecycleError(it.token, it.message) }

        val profileDir = configDir.resolve(name)
        val createdDir = !Files.exists(profileDir)
        try {
            Files.createDirectories(profileDir)
            StoragePermissions.restrictProfile(profileDir)
        } catch (e: Exception) {
            if (createdDir) profileDir.toFile().deleteRecursively()
            throw LifecycleError("permissions_failed", "the profile folder could not be made owner-only: ${e.javaClass.simpleName}")
        }
        try {
            appendProfile(configPath, generateProfileToml(type, name, syncRoot, creds, mode), label, if (req.makeDefault) name else null)
        } catch (e: IOException) {
            if (createdDir) profileDir.toFile().deleteRecursively()
            throw LifecycleError("write_failed", "config.toml could not be written: ${e.javaClass.simpleName}")
        }

        val defaultProfile =
            try {
                SyncConfig.parseRaw(Files.readString(configPath), configPath.toString()).general.default_profile
            } catch (_: Exception) {
                null
            }
        buildJsonObject {
            put("ok", true)
            put("profile", name)
            put("type", type)
            put("mode", mode.wireName)
            put("label", label)
            put("sync_root", syncRoot)
            put("default_profile", defaultProfile?.takeIf { it.isNotBlank() })
            put("warnings", JsonArray(warnings.map(::JsonPrimitive)))
            put(
                "next",
                when {
                    factory.supportsInteractiveAuth() -> "auth_begin"
                    type == "internxt" -> "auth_login"
                    else -> "none"
                },
            )
        }
        }
    }

    /** The stored sync root of a mirror profile: absolute, not a file; a missing parent is only a warning. */
    private fun resolveMirrorSyncRoot(
        given: String?,
        type: String,
        warnings: MutableList<String>,
    ): String {
        val typed = given?.takeIf { it.isNotBlank() } ?: mirrorSyncRootDefault(type)
        val path =
            try {
                Path.of(typed)
            } catch (_: java.nio.file.InvalidPathException) {
                throw LifecycleError("invalid_sync_root", "the sync root is not a valid path")
            }
        val home = typed.startsWith("~")
        // A relative root would mean a different folder for whatever runs the daemon later.
        val absolute = if (home) path else path.toAbsolutePath().normalize()
        val root = if (home) typed else absolute.toString()
        if (Files.isRegularFile(absolute)) {
            throw LifecycleError("sync_root_is_file", "the sync root '$root' is an existing file; give a folder")
        }
        val parent = absolute.parent
        if (!home && parent != null && !Files.exists(parent)) {
            warnings += "the parent of the sync root '$root' does not exist; check the path (a drive or folder may be missing)"
        }
        val leaf = absolute.fileName?.toString()
        if (leaf != null && isOfficialInternxtClientFolder(leaf)) {
            warnings += "'$root' is the official Internxt Desktop client's folder; the two clients will fight over it"
        }
        return root
    }

    /** The `--option key=value` list as a map; a malformed, repeated, unknown or secret key is refused by name. */
    private fun parseOptions(
        prompts: List<org.krost.unidrive.PromptSpec>,
        options: List<String>,
    ): Map<String, String> {
        val given = linkedMapOf<String, String>()
        for (opt in options) {
            val eq = opt.indexOf('=')
            if (eq <= 0) throw LifecycleError("invalid_option", "--option takes key=value")
            val key = opt.substring(0, eq)
            if (key in given) throw LifecycleError("duplicate_option", "option '$key' was given twice")
            given[key] = opt.substring(eq + 1)
        }
        val byKey = prompts.associateBy { it.key }
        for (key in given.keys) {
            val prompt = byKey[key] ?: throw LifecycleError("unknown_option", "'$key' is not a setting of this provider type")
            if (prompt.isMasked) {
                throw LifecycleError("secret_not_allowed_in_argv", "'$key' is a secret and cannot be passed on the command line")
            }
        }
        return given
    }

    private fun missingRefusal(
        prompts: List<org.krost.unidrive.PromptSpec>,
        envVarOf: Map<String, String>,
        missingPlain: List<String>,
        missingSecrets: List<String>,
    ): LifecycleError {
        val parts = mutableListOf<String>()
        if (missingPlain.isNotEmpty()) {
            val vars = missingPlain.mapNotNull { envVarOf[it] }
            parts += "required: " + missingPlain.joinToString(", ") { "--option $it=<value>" } +
                if (vars.isEmpty()) "" else " (or set ${vars.joinToString(", ")})"
        }
        if (missingSecrets.isNotEmpty()) {
            parts += "secret: " +
                missingSecrets.joinToString(", ") { key ->
                    "'$key' via ${envVarOf[key]?.let { "$it or " } ?: ""}--secret-stdin"
                }
        }
        val optional = prompts.filter { !it.required && !it.isMasked }.map { it.key }
        if (optional.isNotEmpty()) parts += "optional: ${optional.joinToString(", ")}"
        val token = if (missingPlain.isEmpty()) "secret_prompt_unsupported" else "missing_option"
        return LifecycleError(token, "this provider type needs more settings (${parts.joinToString("; ")})")
    }

    /** The values of the prompts: [written] go to config.toml, [effective] is what the provider will see. */
    private class ResolvedCredentials(
        val written: Map<String, String>,
        val effective: Map<String, String>,
        val notes: List<String>,
    )

    /**
     * The prompt values. A secret is never taken from the argument list: it comes from the environment
     * variable the provider maps to its key (not written; the account reads it when it starts) or, with
     * `--secret-stdin`, from standard input (written, as the wizard does). A supplied secret option and an
     * option that is no prompt of the type are refused by key; every missing required key is reported in
     * one message. The refusals name keys, never values.
     */
    private fun credentialsFromOptions(
        prompts: List<org.krost.unidrive.PromptSpec>,
        envMappings: Map<String, String>,
        req: NewProfile,
    ): ResolvedCredentials {
        val given = parseOptions(prompts, req.options)
        val envVarOf = envMappings.entries.associate { (envVar, key) -> key to envVar }

        fun fromEnv(key: String): String? = envVarOf[key]?.let { LifecycleHooks.env(it) }?.takeIf { it.isNotBlank() }

        val written = linkedMapOf<String, String>()
        val effective = linkedMapOf<String, String>()
        val notes = mutableListOf<String>()
        val missingPlain = mutableListOf<String>()
        val missingSecrets = mutableListOf<String>()
        for (prompt in prompts) {
            val env = fromEnv(prompt.key)
            val typed = if (prompt.isMasked) null else given[prompt.key]?.takeIf { it.isNotBlank() }
            val default = prompt.default?.takeIf { it.isNotBlank() && !prompt.isMasked }
            when {
                typed != null -> {
                    written[prompt.key] = typed
                    effective[prompt.key] = typed
                }
                env != null -> {
                    effective[prompt.key] = env
                    notes += "'${prompt.key}' is read from ${envVarOf.getValue(prompt.key)} when the account starts; it is not written to config.toml"
                }
                default != null -> {
                    written[prompt.key] = default
                    effective[prompt.key] = default
                }
                !prompt.required -> Unit
                prompt.isMasked -> missingSecrets += prompt.key
                else -> missingPlain += prompt.key
            }
        }
        if (missingSecrets.isNotEmpty() && req.secretStdin && missingPlain.isEmpty()) {
            val line = secretFromStdin(missingSecrets)
            written[missingSecrets.single()] = line
            effective[missingSecrets.single()] = line
            missingSecrets.clear()
        }
        if (missingPlain.isNotEmpty() || missingSecrets.isNotEmpty()) {
            throw missingRefusal(prompts, envVarOf, missingPlain, missingSecrets)
        }
        return ResolvedCredentials(written, effective, notes)
    }

    /** The one secret on the first line of standard input; refused when several are missing or the line is empty. */
    private fun secretFromStdin(missingSecrets: List<String>): String {
        if (missingSecrets.size > 1) {
            throw LifecycleError(
                "secret_stdin_ambiguous",
                "standard input holds one secret but ${missingSecrets.size} are missing: ${missingSecrets.joinToString(", ")}",
            )
        }
        val line = generateSequence { readLine() }.firstOrNull()?.removeSuffix("\r") ?: ""
        if (line.isEmpty()) throw LifecycleError("empty_secret", "--secret-stdin was given but standard input held no secret")
        return line
    }

    private fun appendProfile(
        configPath: Path,
        toml: String,
        label: String?,
        makeDefault: String?,
    ) {
        val existing = if (Files.exists(configPath)) Files.readString(configPath) else "[general]\n\n"
        // setDefaultProfile rewrites the [general] section; a config that exists without one is a
        // supported shape, so anchor it before appending rather than letting the rewrite throw.
        val anchored =
            if (makeDefault != null && existing.lineSequence().none { it.trim() == "[general]" }) "[general]\n\n$existing" else existing
        val section =
            if (label != null) toml.trimEnd('\n') + "\nlabel = \"${escapeTomlValue(label)}\"\n" else toml
        var text = anchored + section
        if (makeDefault != null) text = setDefaultProfile(text, makeDefault)
        // One atomic replace: a kill between an append and a default-profile rewrite must not leave a
        // truncated config.toml (every profile gone) or a profile without the default that was asked for.
        Files.createDirectories(configPath.parent)
        ConfigFileTransaction.writeAtomically(configPath, text)
    }
}
