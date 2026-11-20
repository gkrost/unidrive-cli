package org.krost.unidrive.cli

/**
 * `status --json` prints one profile's row. Combined with a mode that renders prose or a table the flag
 * used to be dropped silently, so a script parsing stdout got a box table and exit 0. Refuse instead.
 */
internal fun statusJsonConflict(
    json: Boolean,
    all: Boolean,
    checkAuth: Boolean,
    audit: Boolean,
    pending: Boolean,
): String? {
    if (!json) return null
    val mode =
        when {
            checkAuth -> "--check-auth"
            audit -> "--audit"
            pending -> "--pending"
            all -> "--all"
            else -> return null
        }
    return "Error: --json prints the status of one profile and cannot be combined with $mode."
}
