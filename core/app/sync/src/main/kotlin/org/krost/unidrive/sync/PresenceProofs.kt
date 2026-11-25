package org.krost.unidrive.sync

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.krost.unidrive.io.OwnerOnly
import org.krost.unidrive.io.OwnerOnlyAtomicWrite
import org.krost.unidrive.io.grantProblem
import org.krost.unidrive.io.string
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec

/**
 * `[general] require_presence` (gkrost/unidrive-cli#798): which IPC verbs need a presence proof before their handler
 * runs. The default is [OFF].
 */
enum class PresencePolicy(
    val wire: String,
) {
    OFF("off"),

    /** The destructive table, [IpcAuth.PRESENCE_DESTRUCTIVE_VERBS]. */
    DESTRUCTIVE("destructive"),

    /** Every ADMIN-class verb of [IpcAuth.VERB_CLASSES] except the presence verbs themselves (and the destructive table). */
    ADMIN("admin"),
    ;

    fun guards(verb: String): Boolean =
        when (this) {
            OFF -> false
            DESTRUCTIVE -> verb in IpcAuth.PRESENCE_DESTRUCTIVE_VERBS
            ADMIN ->
                verb in IpcAuth.PRESENCE_DESTRUCTIVE_VERBS ||
                    (IpcAuth.VERB_CLASSES[verb] == IpcAuth.VerbClass.ADMIN && verb !in PresenceProofs.VERBS)
        }

    companion object {
        /** The value of `require_presence`; unset is [OFF]. A value outside the three is refused, never read as off. */
        fun parse(value: String?): PresencePolicy =
            if (value == null) {
                OFF
            } else {
                entries.firstOrNull { it.wire == value }
                    ?: throw ConfigValueException("[general] require_presence must be \"off\", \"destructive\" or \"admin\", got \"$value\"")
            }
    }
}

/**
 * Presence proofs for guarded IPC verbs (gkrost/unidrive-cli#798, docs/dev/specs/ipc-authentication.md section 4a). The
 * daemon only verifies: a client (the tray, the unlock helper) signs with its platform credential, and the daemon checks
 * `SHA256withRSA` (PKCS#1 v1.5) against the **anchor**, the RSA public key enrolled for this profile.
 *
 * - `presence.challenge {target, params_hash}` issues a single-use nonce that expires after [NONCE_TTL_MS], bound to the
 *   verb it is for and to the hash of that request's params ([paramsHash]).
 * - A guarded verb ([PresencePolicy.guards]) carries `presence:{nonce, signature}`; [gate] runs before its handler and
 *   refuses it, fail-closed, with `presence_required`, `presence_invalid` or `presence_expired`.
 * - `presence.enroll` stores the anchor ([ANCHOR_FILE], owner-only, beside the IPC tokens). The request proves possession
 *   of the new key; the first enrollment also needs the vault passphrase (a co-factor), and a rotation needs a proof under
 *   the current anchor — the passphrase alone never replaces one.
 * - `user-presence:v1` is advertised ([withCapability]) once an anchor is enrolled, and nothing more: whether a proof is
 *   needed is the `presence` member of `daemon.status` ([statusJson]).
 *
 * Honest limit: this proves that a human approved a prompt, not which program asked; and a same-user process that can
 * write the profile folder can replace the anchor file or the config switch, so it is no sandbox.
 */
class PresenceProofs(
    private val anchorFile: Path,
    private val profileName: String,
    val policy: PresencePolicy,
    // The first enrollment's co-factor: true when the passphrase opens vault.enc. Null = no vault (it is refused).
    private val passphraseMatches: ((CharArray) -> Boolean)?,
    private val nonces: () -> ByteArray = { IpcAuth.randomBytes(NONCE_BYTES) },
    // A monotonic clock: a step of the wall clock neither extends nor cuts the 60 s of a nonce.
    private val nowNanos: () -> Long = System::nanoTime,
    // The wrong-passphrase delay of a first enrollment, as vault.unlock's.
    private val failureDelayMs: Long = FAILURE_DELAY_MS,
    private val grantProblem: (Path) -> String? = { OwnerOnly.grantProblem(it) },
) {
    private class Issued(
        val target: String,
        val paramsHash: String,
        val expiresAtNanos: Long,
    )

    private val issued = LinkedHashMap<String, Issued>()

    // One enrollment at a time: the "first or rotation" decision and the anchor write are one step.
    private val enrollment = Mutex()

    @Volatile private var anchor: PublicKey? = loadAnchor()

    val enrolled: Boolean get() = anchor != null

    /** [capabilitiesJson] (a JSON array of strings) with `user-presence:v1` appended once an anchor exists. */
    fun withCapability(capabilitiesJson: String): String {
        if (!enrolled) return capabilitiesJson
        val open = capabilitiesJson.trimEnd().removeSuffix("]")
        return if (open.trim() == "[") "[\"$CAPABILITY\"]" else "$open,\"$CAPABILITY\"]"
    }

    /** The `presence` member of `daemon.status`: the policy of this run and whether an anchor is enrolled. */
    fun statusJson(): String = """{"policy":"${policy.wire}","enrolled":$enrolled}"""

    /** `presence.challenge`. */
    fun challenge(jsonRequest: String): String {
        val body = parseObject(jsonRequest)
        val target = body?.string("target")?.takeIf { TARGET.matches(it) } ?: return INVALID_TARGET
        val paramsHash = body.string("params_hash")?.takeIf { IpcAuth.decode(it)?.size == HASH_BYTES } ?: return INVALID_PARAMS_HASH
        val nonce = IpcAuth.encode(nonces())
        synchronized(issued) {
            val now = nowNanos()
            issued.values.removeIf { it.expiresAtNanos - now <= 0 }
            while (issued.size >= MAX_OUTSTANDING) issued.remove(issued.keys.first())
            issued[nonce] = Issued(target, paramsHash, now + NONCE_TTL_MS * NANOS_PER_MS)
        }
        return """{"ok":true,"nonce":"$nonce","expires_in_ms":$NONCE_TTL_MS}"""
    }

    /**
     * The gate of a guarded verb, after the scope check: null lets the request on to its handler, otherwise the refusal to
     * write instead. [verb] is the one the server dispatches on, [line] the whole request.
     */
    fun gate(
        verb: String?,
        line: String,
    ): String? {
        if (verb == null || !policy.guards(verb)) return null
        val body = parseObject(line)
        val proof = body?.get("presence") as? JsonObject
        val nonce = proof?.string("nonce")
        val signature = proof?.string("signature")
        val key = anchor
        if (key == null || nonce == null || signature == null) return refusal(PRESENCE_REQUIRED, verb, enrolled = key != null)
        val token = checkProof(key, nonce, signature, verb, paramsHash(body, GUARDED_UNSIGNED)) ?: return null
        log.warn("IPC: {} refused ({})", verb, token)
        return refusal(token, verb)
    }

    /** `presence.enroll`. */
    suspend fun enroll(jsonRequest: String): String {
        // Under policy off nothing asks for a proof: the verb answers before any co-factor work.
        if (policy == PresencePolicy.OFF) return PRESENCE_DISABLED
        val body = parseObject(jsonRequest) ?: return INVALID_PUBLIC_KEY
        val encoded = body.string("public_key")?.let { IpcAuth.decode(it) } ?: return INVALID_PUBLIC_KEY
        val newKey = rsaKey(encoded) ?: return INVALID_PUBLIC_KEY
        val nonce = body.string("nonce")
        val signature = body.string("signature")
        if (nonce == null || signature == null) return MISSING_PROOF
        val passphrase = body.string("passphrase")?.toCharArray()
        try {
            return enrollment.withLock { enrollLocked(body, encoded, newKey, nonce, signature, passphrase) }
        } finally {
            passphrase?.fill('\u0000')
        }
    }

    private suspend fun enrollLocked(
        body: JsonObject,
        encoded: ByteArray,
        newKey: PublicKey,
        nonce: String,
        signature: String,
        passphrase: CharArray?,
    ): String {
        val paramsHash = paramsHash(body, ENROLL_UNSIGNED)
        // The nonce is spent first: every attempt, a passphrase guess included, needs a new challenge.
        val entry = take(nonce)
        val possession = checkEntry(entry, ENROLL, paramsHash) ?: verifyOrInvalid(newKey, message(profileName, ENROLL, paramsHash, nonce), signature)
        if (possession != null) return refusal(possession, ENROLL)
        val current = anchor
        if (current == null) {
            if (passphrase == null) return PASSPHRASE_REQUIRED
            val matches = passphraseMatches ?: return VAULT_ABSENT
            if (!matches(passphrase)) {
                delay(failureDelayMs)
                return BAD_PASSPHRASE
            }
        } else {
            val anchorSignature = body.string("anchor_signature") ?: return refusal(PRESENCE_REQUIRED, ENROLL, enrolled = true)
            verifyOrInvalid(current, message(profileName, ENROLL, paramsHash, nonce), anchorSignature)?.let { return refusal(it, ENROLL) }
        }
        try {
            writeAnchor(encoded)
        } catch (e: IOException) {
            log.warn("presence: the anchor cannot be written: {}", e.message)
            return ANCHOR_WRITE_FAILED
        }
        anchor = newKey
        log.info("presence: anchor {} for profile '{}'", if (current == null) "enrolled" else "rotated", profileName)
        return """{"ok":true,"rotated":${current != null},"anchor":"${fingerprint(encoded)}"}"""
    }

    // The refusal token for a proof that does not hold, or null when it holds.
    private fun checkProof(
        key: PublicKey,
        nonce: String,
        signature: String,
        verb: String,
        paramsHash: String,
    ): String? = checkEntry(take(nonce), verb, paramsHash) ?: verifyOrInvalid(key, message(profileName, verb, paramsHash, nonce), signature)

    private fun checkEntry(
        entry: Issued?,
        verb: String,
        paramsHash: String,
    ): String? =
        when {
            entry == null -> PRESENCE_INVALID
            entry.expiresAtNanos - nowNanos() <= 0 -> PRESENCE_EXPIRED
            entry.target != verb || entry.paramsHash != paramsHash -> PRESENCE_INVALID
            else -> null
        }

    private fun take(nonce: String): Issued? = synchronized(issued) { issued.remove(nonce) }

    private fun verifyOrInvalid(
        key: PublicKey,
        message: ByteArray,
        signature: String,
    ): String? {
        val bytes = IpcAuth.decode(signature) ?: return PRESENCE_INVALID
        val valid =
            try {
                Signature.getInstance(ALGORITHM).run {
                    initVerify(key)
                    update(message)
                    verify(bytes)
                }
            } catch (_: GeneralSecurityException) {
                false
            }
        return if (valid) null else PRESENCE_INVALID
    }

    private fun loadAnchor(): PublicKey? {
        if (!Files.exists(anchorFile)) return null
        val key = runCatching { IpcAuth.decode(Files.readString(anchorFile, Charsets.US_ASCII).trim())?.let(::rsaKey) }.getOrNull()
        if (key == null) log.warn("presence: {} does not hold an RSA public key; no anchor is enrolled", anchorFile)
        return key
    }

    private fun writeAnchor(encoded: ByteArray) {
        OwnerOnlyAtomicWrite.replace(
            anchorFile,
            (IpcAuth.encode(encoded) + "\n").toByteArray(Charsets.US_ASCII),
            verify = { temp -> grantProblem(temp)?.let { throw IOException("the new anchor file is not owner-only, $it") } },
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(PresenceProofs::class.java)

        const val ENROLL: String = "presence.enroll"
        const val CHALLENGE: String = "presence.challenge"
        val VERBS: Set<String> = setOf(ENROLL, CHALLENGE)
        const val CAPABILITY: String = "user-presence:v1"
        const val ANCHOR_FILE: String = "presence.anchor"
        const val NONCE_BYTES: Int = 32
        const val NONCE_TTL_MS: Long = 60_000
        const val MAX_OUTSTANDING: Int = 32
        const val FAILURE_DELAY_MS: Long = 250
        const val MIN_RSA_BITS: Int = 2048
        private const val HASH_BYTES = 32
        private const val NANOS_PER_MS = 1_000_000L
        private const val ALGORITHM = "SHA256withRSA"
        private val TARGET = Regex("^[a-z][a-z0-9_.]{0,63}$")

        // The members a proof does not bind: the verb (bound by itself) and the proof.
        private val GUARDED_UNSIGNED = setOf("verb", "presence")
        private val ENROLL_UNSIGNED = setOf("verb", "nonce", "signature", "anchor_signature", "passphrase")

        private const val PRESENCE_REQUIRED = "presence_required"
        private const val PRESENCE_INVALID = "presence_invalid"
        private const val PRESENCE_EXPIRED = "presence_expired"
        private const val PRESENCE_DISABLED = """{"ok":false,"error":"presence_disabled"}"""
        private const val INVALID_TARGET = """{"ok":false,"error":"invalid_target"}"""
        private const val INVALID_PARAMS_HASH = """{"ok":false,"error":"invalid_params_hash"}"""
        private const val INVALID_PUBLIC_KEY = """{"ok":false,"error":"invalid_public_key"}"""
        private const val MISSING_PROOF = """{"ok":false,"error":"missing_proof"}"""
        private const val PASSPHRASE_REQUIRED = """{"ok":false,"error":"passphrase_required"}"""
        private const val VAULT_ABSENT = """{"ok":false,"error":"vault_absent"}"""
        private const val BAD_PASSPHRASE = """{"ok":false,"error":"vault_bad_passphrase"}"""
        private const val ANCHOR_WRITE_FAILED = """{"ok":false,"error":"anchor_write_failed"}"""

        private fun refusal(
            token: String,
            verb: String,
            enrolled: Boolean? = null,
        ): String = """{"ok":false,"error":"$token","verb":${JsonPrimitive(verb)}${if (enrolled == null) "" else ""","enrolled":$enrolled"""}}"""

        /** The bytes a client signs: `unidrive-presence-v1|<utf8-len>:<profile>|<verb>|<params_hash>|<nonce>` in UTF-8. */
        fun message(
            profile: String,
            verb: String,
            paramsHash: String,
            nonce: String,
        ): ByteArray = "unidrive-presence-v1|${profile.toByteArray(Charsets.UTF_8).size}:$profile|$verb|$paramsHash|$nonce".toByteArray(Charsets.UTF_8)

        /** base64url (unpadded) of SHA-256 over the UTF-8 of [canonical] of [params]. */
        fun paramsHash(params: JsonObject): String = IpcAuth.encode(MessageDigest.getInstance("SHA-256").digest(canonical(params).toByteArray(Charsets.UTF_8)))

        private fun paramsHash(
            request: JsonObject,
            unsigned: Set<String>,
        ): String = paramsHash(JsonObject(request.filterKeys { it !in unsigned }))

        /**
         * The canonical text of [element]: no whitespace, object members sorted by name (UTF-16 code units, as RFC 8785),
         * strings with JSON's short escapes, numbers as their literal (a client writes integers without fraction or exponent).
         */
        fun canonical(element: JsonElement): String =
            when (element) {
                is JsonObject -> element.entries.sortedBy { it.key }.joinToString(",", "{", "}") { (k, v) -> "${JsonPrimitive(k)}:${canonical(v)}" }
                is JsonArray -> element.joinToString(",", "[", "]") { canonical(it) }
                JsonNull -> "null"
                is JsonPrimitive -> element.toString()
            }

        private fun fingerprint(encoded: ByteArray): String = IpcAuth.encode(MessageDigest.getInstance("SHA-256").digest(encoded))

        private fun rsaKey(encoded: ByteArray): PublicKey? =
            runCatching { KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(encoded)) }
                .getOrNull()
                ?.takeIf { (it as? RSAPublicKey)?.modulus?.bitLength()?.let { bits -> bits >= MIN_RSA_BITS } == true }

        private fun parseObject(line: String): JsonObject? = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
    }
}
