package org.krost.unidrive.cli

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.krost.unidrive.CloudItem
import org.krost.unidrive.engine.ScopeSwap
import org.krost.unidrive.hydration.HydrationEvent
import org.krost.unidrive.sync.SyncScope
import org.slf4j.LoggerFactory

/** gkrost/unidrive-cli#860: what [ScopeRpcHandler] changes the scope through; [ScopeSwap] on a mount daemon. */
internal interface ScopeControl {
    fun current(): List<String>

    fun plan(scope: List<String>): ScopeSwap.Effect

    suspend fun apply(
        scope: List<String>,
        persist: (List<String>) -> Unit,
    ): ScopeSwap.Outcome
}

internal fun ScopeSwap.asControl(): ScopeControl =
    object : ScopeControl {
        override fun current() = this@asControl.current()

        override fun plan(scope: List<String>) = this@asControl.plan(scope)

        override suspend fun apply(
            scope: List<String>,
            persist: (List<String>) -> Unit,
        ) = this@asControl.apply(scope, persist)
    }

/**
 * gkrost/unidrive-cli#860: `scope.get` (read) and `scope.set` (admin), docs/dev/specs/ipc-write-protocol.md section 3.10.
 *
 * - `scope.get {list_excluded?}`: `{"ok":true,"included":[...],"version":"..."}`; with `list_excluded:true` also `excluded`, the
 *   cloud root's folders outside the scope (one listing of the root, kept [EXCLUDED_TTL_MS]; the read-only namespaces left out).
 * - `scope.set {included, version, dry_run?}`: replaces the whole set when `version` is the current one. The reply names the
 *   new set and version and the `effect` (what leaves this computer); `dry_run:true` answers the same without changing anything.
 *   A file only this computer holds in what would leave refuses the change (`unsynced_in_removed_scope`, with the effect).
 *
 * [control] is null on a mirror daemon (its planner's scope is fixed for the run): `scope.set` answers `wrong_mode` and
 * `scope.get` answers [fixedScope]. A change that applied goes to [publish] as [HydrationEvent.ScopeChanged], and a wider scope
 * starts an enumeration through [afterWiden].
 */
internal class ScopeRpcHandler(
    private val control: ScopeControl?,
    private val fixedScope: List<String> = emptyList(),
    private val persist: (List<String>) -> Unit,
    private val listRoot: suspend () -> List<CloudItem>,
    private val isVirtual: (String) -> Boolean = { false },
    private val publish: (HydrationEvent) -> Unit = {},
    private val afterWiden: () -> Unit = {},
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(ScopeRpcHandler::class.java)

    // One scope.set at a time: the version check and the swap are one step.
    private val oneAtATime = Mutex()
    private val listing = Mutex()
    private var rootFolders: Pair<Long, List<String>>? = null

    // The guard may tentatively narrow during a swap. Both IPC read paths use the last accepted scope.
    @Volatile
    private var committedScope = control?.current() ?: fixedScope

    /** Whether `scope.set` changes the scope: `daemon.status` lists `scope.set` only then. */
    val isSettable: Boolean get() = control != null

    /** The scope the daemon tracks now (`daemon.status` `sync_paths`). */
    fun included(): List<String> = committedScope

    suspend fun get(json: String): String {
        val body = json.asJsonObjectOrNull() ?: JsonObject(emptyMap())
        val listExcluded =
            when (val element = body["list_excluded"]) {
                null -> false
                else -> element.strictBooleanOrNull() ?: return error("invalid_list_excluded")
            }
        val scope = included()
        return buildJsonObject {
            put("ok", true)
            put("included", strings(scope))
            put("version", ScopeSwap.versionOf(scope))
            if (listExcluded) {
                val folders = runCatching { rootFolders() }.getOrElse { e ->
                    log.warn("scope.get: the cloud root could not be listed: {}", e.javaClass.simpleName)
                    return error("listing_failed")
                }
                val ancestors = SyncScope.ancestors(scope)
                put("excluded", strings(folders.filter { it !in ancestors && !SyncScope.contains(it, scope) }))
            }
        }.toString()
    }

    suspend fun set(json: String): String {
        val body = json.asJsonObjectOrNull() ?: JsonObject(emptyMap())
        val versionElement = body["version"] ?: return error("missing_version")
        val requestedVersion = (versionElement as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return error("invalid_version")
        val includedElement = body["included"] ?: return error("missing_included")
        val requested = requestedScope(includedElement) ?: return error("invalid_included")
        val dryRun =
            when (val element = body["dry_run"]) {
                null -> false
                else -> element.strictBooleanOrNull() ?: return error("invalid_dry_run")
            }
        val swap = control ?: return error("wrong_mode")
        return oneAtATime.withLock {
            val current = included()
            val currentVersion = ScopeSwap.versionOf(current)
            if (requestedVersion != currentVersion) {
                return@withLock buildJsonObject {
                    put("ok", false)
                    put("error", "version_conflict")
                    put("included", strings(current))
                    put("version", currentVersion)
                }.toString()
            }
            if (dryRun) {
                val effect = swap.plan(requested)
                return@withLock if (effect.unsyncedCount > 0) refused(effect) else applied(false, requested, currentVersion, effect)
            }
            val outcome =
                try {
                    swap.apply(requested, persist)
                } catch (e: LifecycleError) {
                    // config.toml could not take the new set (write_failed, unsupported_layout, ...); nothing changed.
                    return@withLock error(e.token)
                } catch (e: java.io.IOException) {
                    log.warn("scope.set: the new scope could not be written: {}", e.javaClass.simpleName)
                    return@withLock error("write_failed")
                }
            when (outcome) {
                ScopeSwap.Outcome.Busy -> error("busy")
                is ScopeSwap.Outcome.Refused -> refused(outcome.effect)
                is ScopeSwap.Outcome.Applied -> {
                    committedScope = outcome.scope
                    val version = ScopeSwap.versionOf(outcome.scope)
                    publish(HydrationEvent.ScopeChanged(outcome.scope, version))
                    if (outcome.effect.reEnumerate) afterWiden()
                    applied(true, outcome.scope, currentVersion, outcome.effect)
                }
            }
        }
    }

    // The requested roots, checked as config.toml spells them (absolute, '/', no '..') and normalised; null when invalid.
    private fun requestedScope(element: JsonElement): List<String>? {
        val array = element as? JsonArray ?: return null
        val raw = array.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
        return runCatching { SyncScope.fromConfig(raw, "scope.set") }.getOrNull()
    }

    private suspend fun rootFolders(): List<String> =
        listing.withLock {
            rootFolders?.takeIf { clockMs() - it.first < EXCLUDED_TTL_MS }?.second
                ?: listRoot()
                    .filter { it.isFolder }
                    .mapNotNull { SyncScope.normalizePath(it.path) }
                    .filter { !isVirtual(it) }
                    .distinct()
                    .sorted()
                    .also { rootFolders = clockMs() to it }
        }

    private fun applied(
        applied: Boolean,
        scope: List<String>,
        previousVersion: String,
        effect: ScopeSwap.Effect,
    ): String =
        buildJsonObject {
            put("ok", true)
            put("applied", applied)
            put("included", strings(scope))
            put("version", ScopeSwap.versionOf(scope))
            put("previous_version", previousVersion)
            put("effect", effect.toJson())
        }.toString()

    private fun refused(effect: ScopeSwap.Effect): String =
        buildJsonObject {
            put("ok", false)
            put("error", "unsynced_in_removed_scope")
            put("effect", effect.toJson())
        }.toString()

    private fun ScopeSwap.Effect.toJson(): JsonObject =
        buildJsonObject {
            put("leaving", strings(leaving))
            put("leaving_more", leavingMore)
            put("entering", strings(entering))
            put(
                "remove_locally",
                buildJsonObject {
                    put("folders", folders)
                    put("placeholders", placeholders)
                    put("offline_copies", offlineCopies)
                },
            )
            put(
                "unsynced",
                buildJsonObject {
                    put("count", unsyncedCount)
                    put("paths", strings(unsynced))
                },
            )
            put("re_enumerate", reEnumerate)
        }

    private fun strings(list: List<String>) = JsonArray(list.map(::JsonPrimitive))

    private fun error(token: String) = """{"ok":false,"error":"$token"}"""

    companion object {
        const val GET = "scope.get"
        const val SET = "scope.set"
        const val EXCLUDED_TTL_MS = 10_000L
    }
}
