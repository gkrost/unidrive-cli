package org.krost.unidrive.hydration

import kotlinx.coroutines.CancellationException

// gkrost/unidrive-cli#746 finding 5: a cancelled caller is not a failed request; a runCatching that caught the
// cancellation hands it on with this.
internal fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}
