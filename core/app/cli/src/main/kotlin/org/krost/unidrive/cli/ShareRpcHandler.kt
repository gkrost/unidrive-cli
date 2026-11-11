package org.krost.unidrive.cli

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.http.UrlRedaction
import org.krost.unidrive.io.ndjsonString

/**
 * The `share.create` verb (docs/dev/specs/unidrive-daemon-design.md §4.13): one share link for one remote path, what
 * `unidrive share` does, served by the daemon's live provider instead of a second engine process (#615). Write class
 * ([org.krost.unidrive.sync.IpcAuth.VERB_CLASSES]): the link is created in the cloud on the caller's say-so.
 *
 * - The request names the remote `path` of one file or folder; everything else about a link (an expiry, a password) is
 *   the engine's default for now and can join the wire additively when a client needs to choose.
 * - The provider's own capability answer is the guard, so the verb registers for every provider: `Capability.Share`
 *   answers the link, without it the reply is `unsupported` with the provider's reason — a daemon of a share-less
 *   provider names itself, not `unknown_verb`.
 * - A provider that throws answers `provider_error` with the redacted message, like `refresh.run`; the daemon keeps
 *   serving.
 */
class ShareRpcHandler(
    private val provider: CloudProvider,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun handle(jsonRequest: String): String {
        val body = jsonRequest.asJsonObjectOrNull()
        val path =
            (body?.get("path") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
                ?: return MISSING_PATH
        val result =
            try {
                withContext(io) { provider.share(path) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = e.message?.let(UrlRedaction::redact) ?: e.javaClass.simpleName
                return """{"ok":false,"error":"provider_error","message":${ndjsonString(reason)}}"""
            }
        return when (result) {
            is CapabilityResult.Success -> """{"ok":true,"url":${ndjsonString(result.value)}}"""
            is CapabilityResult.Unsupported -> """{"ok":false,"error":"unsupported","message":${ndjsonString(result.reason)}}"""
        }
    }

    private companion object {
        val MISSING_PATH = """{"ok":false,"error":"missing_path"}"""
    }
}
