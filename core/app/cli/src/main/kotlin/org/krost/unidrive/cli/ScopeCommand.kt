package org.krost.unidrive.cli

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.krost.unidrive.engine.ScopeTransition
import org.krost.unidrive.sync.ProfileKeyEdit
import org.krost.unidrive.sync.ProfileMode
import org.krost.unidrive.sync.RawProvider
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncConfig
import org.krost.unidrive.sync.SyncScope
import org.krost.unidrive.sync.editProfileKey
import org.krost.unidrive.sync.escapeTomlValue
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

/**
 * Show or change the standing sync scope of a profile (`sync_path`, archive#395). `set` and `clear` edit config.toml, apply the
 * scope transition to state.db the way the next run would (rows outside the new scope are untracked, a wider scope
 * clears the delta cursor) and replace the scoped-history guard's `effective_scope` with the new scope, so no old root
 * lingers and `clear` needs no `--full-tree`. No local or remote file is touched. They refuse while another unidrive
 * process holds the profile: a running daemon would not see the change.
 */
@Command(
    name = "scope",
    mixinStandardHelpOptions = true,
    description = ["Show or change the sync scope (sync_path) of a profile"],
    subcommands = [ScopeCommand.ShowCommand::class, ScopeCommand.SetCommand::class, ScopeCommand.ClearCommand::class],
)
class ScopeCommand {
    @ParentCommand
    lateinit var parent: Main

    @Command(name = "show", description = ["Show the configured scope, the persisted scope state and what the next run does"])
    class ShowCommand : Callable<Int> {
        @ParentCommand
        lateinit var parent: ScopeCommand

        @Option(names = ["--json"], description = ["Print the result as one JSON object"])
        var json: Boolean = false

        override fun call(): Int =
            reply(json) {
                val target = resolveProfileTarget(parent.parent, null)
                val raw = ScopeConfig.readProfile(parent.parent.configBaseDir().resolve("config.toml"), target.name)
                val scope = ScopeConfig.configuredScope(raw, target.name)
                val state = readState(target.profileDir)
                val result =
                    buildJsonObject {
                        put("ok", true)
                        put("profile", target.name)
                        put("scope", paths(scope))
                        put("effective_scope", state?.let { paths(it.first) } ?: JsonArray(emptyList()))
                        put("tracked_scope", state?.let { paths(it.second) } ?: JsonArray(emptyList()))
                        put("next_run", showNextRun(scope, state))
                    }
                if (!json) {
                    println("Profile '${target.name}': sync scope ${describe(scope)}")
                    state?.first?.takeIf { it.isNotEmpty() && it != scope }?.let {
                        println("Scoped history: ${it.joinToString(", ")}")
                    }
                    println("Next run: ${showNextRun(scope, state)}")
                }
                result
            }
    }

    @Command(name = "set", description = ["Set the sync scope to the given remote paths, apply the transition and print what the next run does"])
    class SetCommand : Callable<Int> {
        @ParentCommand
        lateinit var parent: ScopeCommand

        @Parameters(arity = "1..*", paramLabel = "<path>", description = ["Absolute remote paths, e.g. /Documents"])
        var paths: List<String> = emptyList()

        @Option(names = ["--json"], description = ["Print the result as one JSON object"])
        var json: Boolean = false

        override fun call(): Int = reply(json) { change(parent.parent, paths, json) }
    }

    @Command(name = "clear", description = ["Remove the sync scope: the next run reconciles the whole drive, no --full-tree needed"])
    class ClearCommand : Callable<Int> {
        @ParentCommand
        lateinit var parent: ScopeCommand

        @Option(names = ["--json"], description = ["Print the result as one JSON object"])
        var json: Boolean = false

        override fun call(): Int = reply(json) { change(parent.parent, null, json) }
    }

    internal companion object {
        private fun reply(
            json: Boolean,
            block: () -> JsonObject,
        ): Int =
            try {
                val result = block()
                if (json) emitJson(result)
                0
            } catch (e: LifecycleError) {
                if (json) emitJson(lifecycleErrorJson(e)) else System.err.println("Error: ${e.message}")
                1
            }

        private fun paths(list: List<String>) = JsonArray(list.map(::JsonPrimitive))

        private fun describe(scope: List<String>) = if (scope.isEmpty()) "whole drive" else scope.joinToString(", ")

        // The persisted effective_scope and tracked_scope, or null when the profile has no state.db yet.
        private fun readState(profileDir: Path): Pair<List<String>, List<String>>? {
            val path = profileDir.resolve("state.db")
            if (!Files.exists(path)) return null
            val db =
                try {
                    StateDatabase(path, readOnly = true).also { it.initialize() }
                } catch (e: java.sql.SQLException) {
                    throw LifecycleError("state_unreadable", "the state of the profile could not be read: ${e.message}", e)
                }
            try {
                val effective = db.getSyncState(EFFECTIVE_SCOPE_KEY).orEmpty().split("\t").filter { it.isNotEmpty() }
                return effective to ScopeTransition.trackedScope(db)
            } finally {
                db.close()
            }
        }

        private fun showNextRun(
            scope: List<String>,
            state: Pair<List<String>, List<String>>?,
        ): String =
            when {
                state == null -> "the first run syncs ${describe(scope)}."
                scope.isEmpty() && state.first.isNotEmpty() ->
                    "a bare bidirectional sync is refused: the profile has scoped history (${state.first.joinToString(", ")}). " +
                        "Run 'unidrive scope clear' to reconcile the whole drive."
                state.second != scope -> "the run first moves the tracked set from ${describe(state.second)} to ${describe(scope)}, then syncs ${describe(scope)}."
                else -> "syncs ${describe(scope)}."
            }
        private fun change(
            main: Main,
            requested: List<String>?,
            json: Boolean,
        ): JsonObject {
            val target = resolveProfileTarget(main, null)
            val configPath = main.configBaseDir().resolve("config.toml")
            val scope = ScopeConfig.requestedScope(requested, target.name)
            return withProfileLock(target) {
                val (previous, mode) = ScopeConfig.writeScope(configPath, target.name, scope)
                // The config is written first: should the state step fail, the guard's old scope is still in place and
                // the next run applies the transition itself.
                val change = applyState(target.profileDir, scope, mode)
                val nextRun = changeNextRun(scope, change)
                if (!json) {
                    val verb = if (scope.isEmpty()) "cleared (whole drive)" else "set to ${describe(scope)}"
                    println("Profile '${target.name}': sync scope $verb (was ${describe(previous)}).")
                    change?.takeIf { it.untracked > 0 }?.let {
                        println("Stopped tracking ${it.untracked} row(s) outside the new scope; their local files and cloud items are left in place.")
                    }
                    println("Next run: $nextRun")
                }
                buildJsonObject {
                    put("ok", true)
                    put("profile", target.name)
                    put("scope", paths(scope))
                    put("previous", paths(previous))
                    put("untracked", change?.untracked ?: 0)
                    put("re_enumerate", change?.widened ?: false)
                    put("next_run", nextRun)
                }
            }
        }

        // Applies the transition and replaces the guard's history; null when the profile has no state.db yet (the first
        // run starts from the new scope anyway, and no state.db is created here).
        private fun applyState(
            profileDir: Path,
            scope: List<String>,
            mode: ProfileMode?,
        ): ScopeTransition.Result? {
            val path = profileDir.resolve("state.db")
            if (!Files.exists(path)) return null
            try {
                val db = StateDatabase(path).also { it.initialize() }
                try {
                    val result = ScopeTransition.apply(db, scope) ?: ScopeTransition.Result(0, false)
                    // effective_scope is the mirror guard's; a mount profile only gets it replaced where it already has one.
                    if (mode == ProfileMode.MIRROR || !db.getSyncState(EFFECTIVE_SCOPE_KEY).isNullOrEmpty()) {
                        db.setSyncState(EFFECTIVE_SCOPE_KEY, scope.joinToString("\t"))
                    }
                    return result
                } finally {
                    db.close()
                }
            } catch (e: java.sql.SQLException) {
                throw LifecycleError(
                    "state_unwritable",
                    "config.toml was updated, but the state of the profile could not be changed: ${e.message}; the next run applies the scope itself",
                    e,
                )
            }
        }

        private fun changeNextRun(
            scope: List<String>,
            change: ScopeTransition.Result?,
        ): String {
            val what =
                if (scope.isEmpty()) {
                    "reconciles the whole drive; no --full-tree is needed."
                } else {
                    "syncs ${describe(scope)} only."
                }
            return if (change?.widened == true) "re-enumerates the drive to find the newly in-scope items, then $what" else what
        }

        private const val EFFECTIVE_SCOPE_KEY = "effective_scope"
    }
}

/** The config.toml side of `scope set` / `scope clear`. */
private object ScopeConfig {
    fun readProfile(
        configPath: Path,
        name: String,
    ): RawProvider {
        // The parser's message can quote the offending config line, which may hold a credential: kept as the cause only.
        val raw =
            runCatching { SyncConfig.parseRaw(Files.readString(configPath), configPath.toString()) }
                .getOrElse { throw LifecycleError("config_invalid", "config.toml could not be parsed", it) }
        return raw.providers[name] ?: throw LifecycleError("profile_not_found", "profile '$name' is not configured")
    }

    fun configuredScope(
        raw: RawProvider,
        name: String,
    ): List<String> =
        try {
            SyncScope.fromConfig(raw.sync_path, name)
        } catch (e: IllegalArgumentException) {
            throw LifecycleError("config_invalid", e.message ?: "sync_path is invalid", e)
        }

    // The requested roots, validated as config.toml spells them and normalised (NFC, nested roots dropped).
    fun requestedScope(
        requested: List<String>?,
        name: String,
    ): List<String> {
        if (requested == null) return emptyList()
        val normalized =
            try {
                SyncScope.fromConfig(requested, name)
            } catch (e: IllegalArgumentException) {
                throw LifecycleError("invalid_value", e.message ?: "invalid sync path", e)
            }
        if (normalized.isEmpty()) throw LifecycleError("invalid_value", "'/' is the whole drive; use 'unidrive scope clear'")
        return normalized
    }

    // [text] with sync_path set to [scope] (removed when empty), checked to read back as written.
    fun editedConfig(
        text: String,
        configPath: Path,
        name: String,
        scope: List<String>,
    ): String {
        val line = if (scope.isEmpty()) null else "sync_path = " + scope.joinToString(", ", "[", "]") { "\"${escapeTomlValue(it)}\"" }
        val edit = editProfileKey(text, name, "sync_path", line)
        val edited =
            (edit as? ProfileKeyEdit.Edited)?.text
                ?: throw if (edit == ProfileKeyEdit.UnsupportedLayout) {
                    LifecycleError("unsupported_layout", "sync_path spans several lines in config.toml; edit it by hand")
                } else {
                    LifecycleError("profile_not_found", "profile '$name' is not configured")
                }
        val readBack =
            runCatching { SyncConfig.parseRaw(edited, configPath.toString()).providers[name]?.let { SyncScope.fromConfig(it.sync_path, name) } }
                .getOrNull()
        if (readBack != scope) throw LifecycleError("invalid_value", "the new sync_path does not produce a valid config")
        return edited
    }

    // Writes the new sync_path; returns the previous scope and the profile's mode.
    fun writeScope(
        configPath: Path,
        name: String,
        scope: List<String>,
    ): Pair<List<String>, ProfileMode?> =
        ConfigFileTransaction.withLock(configPath) {
            val text = Files.readString(configPath)
            val raw = readProfile(configPath, name)
            val previous = runCatching { SyncScope.fromConfig(raw.sync_path, name) }.getOrElse { raw.sync_path.orEmpty() }
            val edited = editedConfig(text, configPath, name, scope)
            try {
                ConfigFileTransaction.writeAtomically(configPath, edited)
            } catch (e: IOException) {
                throw LifecycleError("write_failed", "config.toml could not be written: ${e.javaClass.simpleName}", e)
            }
            previous to runCatching { ProfileMode.fromConfig(raw.mode) }.getOrNull()
        }

}
