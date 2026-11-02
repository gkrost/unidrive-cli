package org.krost.unidrive

import org.slf4j.LoggerFactory

/**
 * UD-752: shared post-authenticate debug log. The engine, the CLI and the MCP wrapper call it, so every
 * provider gets one post-auth line without emitting it itself.
 *
 * Production callers that previously did `provider.authenticate()` should
 * call this instead. Tests and integration tests can keep calling
 * `authenticate()` directly — the log is purely diagnostic.
 */
private val authLog = LoggerFactory.getLogger("org.krost.unidrive.Auth")

suspend fun CloudProvider.authenticateAndLog() {
    authenticate()
    if (isAuthenticated) {
        authLog.debug("Auth: {} authenticated", id)
    } else {
        authLog.warn("Auth: {} not authenticated after initialize", id)
    }
}
