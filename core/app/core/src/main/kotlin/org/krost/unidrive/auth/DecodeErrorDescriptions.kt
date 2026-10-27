package org.krost.unidrive.auth

/**
 * What may be said in a log about a failed decode of a credential or token payload (issue#83 E). kotlinx's decode
 * exceptions quote the JSON text they choked on in their message, and for these payloads that text is the secret
 * itself; only the exception's class and, for a missing field, the absent field names are safe to log.
 */
public fun describeDecodeError(e: kotlinx.serialization.SerializationException): String =
    (e as? kotlinx.serialization.MissingFieldException)
        ?.missingFields
        ?.takeIf { it.isNotEmpty() }
        ?.joinToString(prefix = "missing fields ", separator = ", ")
        ?: e.javaClass.simpleName
