package org.krost.unidrive

import org.krost.unidrive.http.RequestStatusSnapshot

/**
 * The provider's own account of its HTTP requests, for daemon.status's `requests` object (gkrost/unidrive-cli#74):
 * the calls in flight per endpoint class with the age of the oldest, and the free slots of each throttle budget.
 * A provider whose HTTP layer cannot account for its calls does not implement this, and the daemon leaves the field
 * out; the daemon feature-detects it on the provider it built, like every optional surface.
 */
interface RequestStatusReporting {
    /** A reading taken now; it never blocks and never identifies a call. */
    fun requestStatus(): RequestStatusSnapshot
}
