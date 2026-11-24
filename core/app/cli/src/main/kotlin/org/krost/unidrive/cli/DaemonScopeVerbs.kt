package org.krost.unidrive.cli

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.engine.MountWiring
import org.krost.unidrive.engine.ScopeSwap
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
    fixedScope: List<String>,
    persist: ((List<String>) -> Unit)?,
    cloud: CloudProvider,
    isVirtual: (String) -> Boolean,
    publish: (HydrationEvent) -> Unit,
    serveScope: CoroutineScope,
    enumerate: (suspend () -> Unit)?,
): ScopeRpcHandler {
    val control = if (wiring != null && persist != null) ScopeSwap(wiring).asControl() else null
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
