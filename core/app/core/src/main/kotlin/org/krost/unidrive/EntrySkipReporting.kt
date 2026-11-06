package org.krost.unidrive

/**
 * The provider's own count of the listing entries it deliberately leaves out (gkrost/unidrive-cli#405):
 * OneDrive's Personal Vault stub and its package entries (a OneNote notebook), which are never synced or
 * downloaded. The daemon surfaces the counts in `daemon.status`'s `provider_health.skipped` so an operator
 * sees "the drive carries a vault this engine does not sync" without reading the log. A provider with no
 * such entries does not implement this, and the daemon leaves the field out; the daemon feature-detects it
 * on the provider it built, like every optional surface.
 */
interface EntrySkipReporting {
    /** A reading taken now; it never blocks. The counts are of this daemon run: a restart starts at 0. */
    fun skippedEntries(): SkippedEntries
}

/** How many listing entries the provider skipped, per kind. */
data class SkippedEntries(
    val vault: Long,
    val packageEntries: Long,
)
