package org.krost.unidrive.hydration

import org.slf4j.Logger

// gkrost/unidrive-cli#635: every not_empty answer of rmdir says why in the log; the wire token stays not_empty.
internal fun refusedNotEmpty(
    log: Logger,
    path: String,
    recursive: Boolean,
    reason: String,
    child: String? = null,
): RmdirResult {
    if (child == null) {
        log.info("rmdir of {} refused: not_empty reason={} recursive={}", path, reason, recursive)
    } else {
        log.info("rmdir of {} refused: not_empty reason={} recursive={} child={}", path, reason, recursive, child)
    }
    return RmdirResult.NotEmpty
}
