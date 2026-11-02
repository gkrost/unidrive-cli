package org.krost.unidrive.auth

/**
 * What a `toString()` shows in place of a secret (a token, a key, a mnemonic): whether there is one, never its text. A
 * data class that holds a secret overrides its generated `toString()` with this, so a log line, an exception message
 * or a test failure that prints the whole object carries no secret.
 */
fun maskSecret(secret: String?): String =
    when {
        secret == null -> "null"
        secret.isEmpty() -> "<empty>"
        else -> "<redacted>"
    }
