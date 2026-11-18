package org.krost.unidrive.hydration

import kotlinx.coroutines.CancellationException

// gkrost/unidrive-cli#746 finding 5: a cancelled caller is not a failed request; a runCatching that caught the
// cancellation hands it on with this.
internal fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}

// archive#1178: a provider without the permanent-delete capability is a typed refusal, not a failed delete. (Moved here from
// HydrationImpl for the size ratchet, gkrost/unidrive-cli#534.)
internal fun permanentRefusalOrNull(e: Throwable): HydrationError? =
    if (e is org.krost.unidrive.UnsupportedCapabilityException && e.capability == org.krost.unidrive.Capability.PermanentDelete) {
        HydrationError.PermanentUnsupported
    } else {
        null
    }
