package org.krost.unidrive.cli

import org.krost.unidrive.hydration.HydrationEvent
import org.krost.unidrive.hydration.HydrationImpl
import org.krost.unidrive.hydration.HydrationIpcHandler

// Moved out of [DaemonRuntime] unchanged (gkrost/unidrive-cli#534: the size ratchet); it was DaemonHydrationLink.
/**
 * The one real cycle in the daemon's wiring, made explicit: the [SyncEngine] is built first and the
 * hydration layer over it ([MountEngine.over]), but two of the engine's hooks call into that layer
 * (whether an upload of a path is queued or in flight, and the view.invalidated fan-out). The engine
 * takes them from this link, which is bound once, right after the hydration layer is built.
 *
 * Unbound (before that point, and always on a mirror daemon, which builds no hydration layer): no
 * upload slot is held, and a view invalidation has no subscriber to reach, so it is dropped. No IPC
 * request sees the unbound state: the socket binds after every handler is registered.
 */
internal class DaemonHydrationLink {
    private class Bound(val hydration: HydrationImpl, val ipc: HydrationIpcHandler)

    @Volatile private var bound: Bound? = null

    /** The bound hydration layer (the one the shutdown joins the uploads of), or null. */
    val hydration: HydrationImpl? get() = bound?.hydration

    fun bind(
        hydration: HydrationImpl,
        ipc: HydrationIpcHandler,
    ) {
        check(bound == null) { "the hydration link is bound once" }
        bound = Bound(hydration, ipc)
    }

    fun hasUploadSlot(path: String): Boolean = bound?.hydration?.hasUploadSlot(path) ?: false

    fun dispatchEvent(event: HydrationEvent) {
        bound?.ipc?.dispatchEvent(event)
    }
}
