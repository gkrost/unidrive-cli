package org.krost.unidrive.cli

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.krost.unidrive.sync.DownloadStallTimeout

/**
 * The `download_stall_timeout` object of `daemon.status` (gkrost/unidrive-cli#508): how long a cold download of the mount may
 * write no bytes before it is abandoned and started again, in seconds, and where the value came from: `default` (the
 * profile has no key) or `config` (the profile key `download_stall_timeout`). Additive, read-only, always present.
 */
internal fun downloadStallTimeoutJson(timeout: DownloadStallTimeout): String =
    buildJsonObject {
        put("seconds", timeout.seconds)
        put("source", timeout.source)
    }.toString()
