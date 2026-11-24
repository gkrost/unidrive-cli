package org.krost.unidrive.cli

import org.krost.unidrive.io.childProcessBuilder
import org.krost.unidrive.io.ndjsonString
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

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
            readReply(process)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            Result.Hard("interrupted")
        } catch (_: java.io.IOException) {
            Result.Hard("invalid_reply")
        } finally {
            process.destroyForcibly()
        }
    }

    private const val MAX_REPLY_BYTES = 64 * 1024

    // One daemon reader thread with bounded, preallocated reads (no available(): unreliable on Windows pipes), joined with
    // the timeout. The thread scrubs the buffer in its own finally; a timeout or an oversized reply kills the helper.
    private fun readReply(process: Process): Result {
        val buffer = ByteArray(MAX_REPLY_BYTES + 1)
        var count = 0
        var failed = false
        val reader =
            Thread {
                try {
                    process.inputStream.use { stream ->
                        while (count < buffer.size) {
                            val read = stream.read(buffer, count, buffer.size - count)
                            if (read < 0) break
                            count += read
                        }
                    }
                } catch (_: java.io.IOException) {
                    failed = true
                }
            }.apply { isDaemon = true }
        reader.start()
        reader.join(TIMEOUT_MS)
        if (reader.isAlive) {
            process.destroyForcibly()
            reader.join(1_000)
            if (!reader.isAlive) buffer.fill(0)
            return Result.Hard("timeout")
        }
        return try {
            if (failed || count > MAX_REPLY_BYTES) Result.Hard("invalid_reply") else parse(buffer.copyOf(count))
        } finally {
            buffer.fill(0)
        }
    }

    /** The reply parsing, separable from the process run for tests. */
    internal fun parse(replyBytes: ByteArray): Result {
        val reply =
            try {
                if (replyBytes.size > MAX_REPLY_BYTES) return Result.Hard("invalid_reply")
                kotlinx.serialization.json.Json.parseToJsonElement(replyBytes.decodeToString().trim())
            } catch (_: Exception) {
                return Result.Hard("invalid_reply")
            } finally {
                replyBytes.fill(0)
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
            // The JSON parser retains immutable encoded text; wipe the mutable plaintext buffers.
            return try {
                val decoded = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes))
                try {
                    Result.Secret(CharArray(decoded.remaining()).also { decoded.get(it) })
                } finally {
                    if (decoded.hasArray()) decoded.array().fill('\u0000')
                }
            } catch (_: java.nio.charset.CharacterCodingException) {
                Result.Hard("invalid_reply")
            } finally {
                bytes.fill(0)
            }
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
