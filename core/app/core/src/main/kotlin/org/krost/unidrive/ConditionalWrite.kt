package org.krost.unidrive

/**
 * How a provider treats the optimistic-concurrency token (`ifMatchETag`) of one kind of write (#268, #47 item 4).
 * A behaviour descriptor, not a [Capability]: see `docs/adr/capability-contract.md` for the two tiers.
 */
enum class WriteGuard(
    /** The write refuses a token that is not the item's current one. */
    val checksToken: Boolean,
    /** The server checks and writes in one step; no other write can land between them. */
    val atomic: Boolean,
) {
    /** The server checks the token as part of the write (OneDrive's `If-Match`, Dropbox's revision, S3 `If-Match` on AWS). */
    ATOMIC(checksToken = true, atomic = true),

    /** The provider reads the item's current token, then writes: a concurrent write in that window is not caught. */
    EMULATED(checksToken = true, atomic = false),

    /** The token is ignored: the write lands whatever the item became since the caller listed it. */
    NONE(checksToken = false, atomic = false),
}

/** The [WriteGuard] of each write that takes an `ifMatchETag`: [CloudProvider.upload] and [CloudProvider.delete]. */
data class ConditionalWrite(
    val upload: WriteGuard,
    val delete: WriteGuard,
) {
    companion object {
        /** No guard on either write; the default of a provider that declares nothing (fail closed). */
        val NONE = ConditionalWrite(upload = WriteGuard.NONE, delete = WriteGuard.NONE)
    }
}
