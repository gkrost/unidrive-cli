package org.krost.unidrive.cli

import org.krost.unidrive.io.childProcessBuilder
import org.krost.unidrive.io.ndjsonString
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The client side of the unlock helper protocol (docs/dev/specs/unlock-helper-protocol.md): one JSON
 * object each way over the helper's stdin/stdout, one request per run. The engine knows only this
 * wire — never the platform behind it (Windows Hello keyring, macOS Keychain presence, a Linux
 * TPM-backed credential).
 *
 * The helper is started through [childProcessBuilder], so neither passphrase environment variable
 * reaches it (#785). Its path comes from the config as a fixed absolute install path; it is never
 * looked up on PATH.
 */
internal object UnlockHelper {
    /** How long the helper may take (a Windows Hello prompt can sit unanswered for a while). */
    const val TIMEOUT_MS: Long = 60_000L

    /** The soft error codes a helper may answer with: the passphrase chain falls through to the next step. */
    internal val SOFT_CODES = setOf("declined", "unavailable", "locked_out")

    /**
     * What the helper run came back with.
     * [Secret] is the unlocked secret. [Soft] is a helper `declined`/`unavailable`/`locked_out`:
     * the chain falls through to the next step. [Hard] is everything else (a witness mismatch, a
     * broken helper, a timeout, a malformed reply): the chain stops and the refusal names [code].
     */
    internal sealed class Result {
        data class Secret(val chars: CharArray) : Result()

        data class Soft(val code: String) : Result()

        data class Hard(val code: String) : Result()
    }

    /** The request line for [secretKind] (`"vault"` or `"xtra"`) and the helper's keyring file. */
    internal fun requestLine(
        secretKind: String,
        keyring: Path,
    ): String =
        """{"op":"unlock","secret":${ndjsonString(secretKind)},"keyring":${ndjsonString(keyring.toString())}}"""

    /**
     * Runs [helperPath] for [secretKind] and parses its reply. The caller has validated the path
     * (absolute, regular file); a failure to start, a timeout, a malformed reply or a hard error
     * code is [Result.Hard] with the code the refusal names.
     */
    fun run(
        helperPath: Path,
        secretKind: String,
        keyring: Path,
    ): Result {
        val process =
            try {
                childProcessBuilder(listOf(helperPath.toString())).start()
            } catch (_: Exception) {
                return Result.Hard("spawn_failed")
            }
        return try {
            process.outputStream.use { out ->
                out.write((requestLine(secretKind, keyring) + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
            }
            // The reply read must not outlive the timeout: a helper that answers nothing and never
            // closes its stdout would otherwise block for ever before the wait below runs.
            val reply =
                try {
                    java.util.concurrent.CompletableFuture
                        .supplyAsync { process.inputStream.readAllBytes() }
                        .get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                } catch (_: java.util.concurrent.TimeoutException) {
                    process.destroyForcibly()
                    return Result.Hard("timeout")
                } catch (_: Exception) {
                    ByteArray(0)
                }
            if (!process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return Result.Hard("timeout")
            }
            parse(reply)
        } finally {
            process.destroyForcibly()
        }
    }

    /** The reply parsing, separable from the process run for tests. */
    internal fun parse(replyBytes: ByteArray): Result {
        val reply =
            try {
                kotlinx.serialization.json.Json.parseToJsonElement(replyBytes.decodeToString().trim())
            } catch (_: Exception) {
                return Result.Hard("invalid_reply")
            }
        if (reply !is kotlinx.serialization.json.JsonObject) return Result.Hard("invalid_reply")
        val secret = (reply["secret"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        if (secret != null) {
            val bytes =
                try {
                    Base64.getDecoder().decode(secret)
                } catch (_: IllegalArgumentException) {
                    return Result.Hard("invalid_reply")
                }
            // The secret arrives as the UTF-8 bytes of the passphrase: decode to the CharArray the
            // chain passes on, then scrub the byte copy so only one heap copy survives.
            val chars = String(bytes, Charsets.UTF_8).toCharArray()
            bytes.fill(0)
            return Result.Secret(chars)
        }
        val error = (reply["error"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return Result.Hard("invalid_reply")
        return if (error in SOFT_CODES) Result.Soft(error) else Result.Hard(error)
    }

    /** The chain's guard, not a silent skip: a configured helper that is not a regular file is a hard error. */
    internal fun checkConfigured(helperPath: Path): Result.Hard? {
        if (!Files.isRegularFile(helperPath)) return Result.Hard("helper_missing")
        return null
    }
}
