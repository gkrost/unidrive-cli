package org.krost.unidrive.cli

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.engine.MountWiring
import org.krost.unidrive.engine.ScopeSwap
import org.krost.unidrive.engine.ScopeWriteGate
import org.krost.unidrive.hydration.HydrationEvent
import org.krost.unidrive.sync.IpcServer
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("org.krost.unidrive.cli.DaemonScopeVerbs")

/**
 * gkrost/unidrive-cli#860: builds and registers `scope.get` and `scope.set` on [server], beside [DaemonRuntime]'s lifecycle
 * (which they would grow past its sizeRatchet baseline). A mount daemon that can persist the set ([persist] not null) swaps
 * its scope live through [ScopeSwap] over [wiring]; any other daemon answers `scope.get` with [fixedScope] and `scope.set`
 * with `wrong_mode`. A change goes to both event streams like a pause; a wider scope starts one guarded enumeration.
 */
@Suppress("LongParameterList") // each parameter is one seam of the daemon the verbs read; a holder object would only rename them
internal fun registerScopeVerbs(
    server: IpcServer,
    wiring: MountWiring?,
    writeGate: ScopeWriteGate,
    fixedScope: List<String>,
    persist: ((List<String>) -> Unit)?,
    cloud: CloudProvider,
    isVirtual: (String) -> Boolean,
    publish: (HydrationEvent) -> Unit,
    serveScope: CoroutineScope,
    enumerate: (suspend () -> Unit)?,
): ScopeRpcHandler {
    val control = if (wiring != null && persist != null) ScopeSwap(wiring, writeGate = writeGate).asControl() else null
    val handler =
        ScopeRpcHandler(
            control = control,
            fixedScope = fixedScope,
            persist = persist ?: {},
            listRoot = { cloud.listChildren("/") },
            isVirtual = isVirtual,
            publish = publish,
            afterWiden = {
                if (enumerate != null) {
                    serveScope.launch {
                        runCatching { enumerate() }.onFailure { e ->
                            if (e is CancellationException) throw e
                            log.warn("enumerate after a wider scope failed", e)
                        }
                    }
                }
            },
        )
    server.registerHandler(ScopeRpcHandler.GET) { _, json -> handler.get(json) }
    server.registerHandler(ScopeRpcHandler.SET) { _, json -> handler.set(json) }
    return handler
}

/**
 * The hydration verbs that add a row or move one to another path: they run in [ScopeWriteGate.write], so a scope change never
 * checks for local-only files while one of them is half done (owner decision 2026-11-24). The read verbs, the transfers and the
 * deletes do not add rows and are not held.
 */
internal val ROW_WRITING_VERBS: Set<String> =
    setOf(
        "hydration.create", "hydration.mkdir", "hydration.open_write", "hydration.open_write_begin", "hydration.close_handle",
        "hydration.rename", "hydration.refuse_name",
    )

/** Runs the hydration verb [verb] through the scope change's hold when it adds or moves rows ([ROW_WRITING_VERBS]). */
internal suspend fun ScopeWriteGate.around(
    verb: String,
    block: suspend () -> String,
): String = if (verb in ROW_WRITING_VERBS) write(block) else block()
