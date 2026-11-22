package org.krost.unidrive.sync

import org.krost.unidrive.auth.CredentialProtectionException
import org.krost.unidrive.auth.CredentialProtector
import org.krost.unidrive.auth.CredentialProtectors
import java.nio.file.Path

/**
 * At-rest protection for the sensitive provider fields of config.toml (gkrost/unidrive-cli#786):
 * `access_key_id`, `secret_access_key`, `password`, `client_id`, `client_secret` — the same set the
 * vault moves ([Vault.SENSITIVE_FIELDS]).
 *
 * On a host with a native protector (Windows DPAPI; the macOS Keychain) these fields are written as the
 * protector's on-disk reference instead of plaintext, exactly like the credential files already are, and
 * decoded again on read. On hosts without one (Linux/Android) every function here is the identity: the
 * current plaintext behavior is unchanged. Legacy plaintext values stay readable everywhere — decode
 * tolerates them — so a config written by an older version works until the first-start migration
 * ([migrateText], wired in by the CLI's daemon/sync start paths) rewrites the values in place.
 *
 * The protector identity is the canonical path of config.toml ([identity], CredentialStore's convention):
 * a reference pasted into another config, or of another profile, does not decode. A reference that
 * cannot be decoded is a [CredentialProtectionException] naming the profile and field — per the
 * protector contract it is never treated as a missing credential, and the read path must not fall back
 * to the vault over it.
 */
object ConfigSecrets {
    /** The config fields protected at rest; the same field names the vault moves. */
    val SENSITIVE_FIELDS: Set<String> = Vault.SENSITIVE_FIELDS

    /**
     * The identity a protector binds a stored value to: the canonical path of [configPath] — the file's
     * real path, so a folder spelled in another case, by its 8.3 short name or through a junction or
     * symlink is one identity. A file that does not exist yet takes its folder's real path, a folder
     * that does not exist the literal path (CredentialStore's convention, so a profile created before
     * its config file existed decodes after).
     */
    internal fun identity(configPath: Path): String =
        runCatching { configPath.toRealPath().toString() }
            .recoverCatching {
                val absolute = configPath.toAbsolutePath().normalize()
                val dir = absolute.parent ?: absolute
                runCatching { dir.toRealPath() }.getOrElse { dir }.resolve(configPath.fileName).toString()
            }
            .getOrElse { configPath.toAbsolutePath().normalize().toString() }

    /**
     * [rp] with its sensitive fields decoded: a value the protector recognizes as its reference becomes
     * the plaintext it protects; a legacy plaintext value passes through unchanged; absent values stay
     * absent. [protector] null (no native protector on this host) returns [rp] as-is. A protected value
     * that cannot be decoded throws [CredentialProtectionException] naming [profileName] and the field —
     * callers must not fall back to other credential sources over it.
     */
    fun decode(
        rp: RawProvider?,
        profileName: String,
        configPath: Path,
        protector: CredentialProtector? = CredentialProtectors.atRestDefaultOrNull(),
    ): RawProvider? {
        if (rp == null) return rp
        val id = identity(configPath)

        fun foreign(field: String): Nothing =
            throw CredentialProtectionException(
                "the $field of profile '$profileName' in config.toml was protected on another computer and " +
                    "cannot be decrypted here; it is not treated as plaintext or as a missing credential — " +
                    "re-enter it on this computer",
            )

        fun one(value: String?, field: String): String? {
            if (value.isNullOrEmpty()) return value
            val knownReference = CredentialProtectors.REFERENCE_PREFIXES.any { value.startsWith(it) }
            if (protector == null) return if (knownReference) foreign(field) else value
            val data =
                try {
                    protector.decode(id, value)
                } catch (e: CredentialProtectionException) {
                    throw CredentialProtectionException(
                        "the $field of profile '$profileName' in config.toml could not be decrypted " +
                            "(${e.message}); it is not treated as a missing credential — fix or re-add it",
                        e,
                    )
                }
            if (data.protected) return data.plaintext
            if (knownReference) foreign(field)
            return value
        }

        return rp.copy(
            access_key_id = one(rp.access_key_id, "access_key_id"),
            secret_access_key = one(rp.secret_access_key, "secret_access_key"),
            password = one(rp.password, "password"),
            client_id = one(rp.client_id, "client_id"),
            client_secret = one(rp.client_secret, "client_secret"),
        )
    }

    /**
     * [text] with every plaintext sensitive field inside a `[providers.<name>]` section replaced by the
     * protector's reference ([identity] of [configPath] as the identity). Lines that do not set one of
     * [SENSITIVE_FIELDS], values already protected at rest, empty values, and everything outside provider
     * sections pass through byte for byte — comments, ordering and unrelated sections included. A
     * protected value that cannot be decoded is left untouched (the read path reports it); a protector
     * failure while protecting propagates: the caller must not write plaintext where protection was
     * expected. [protector] null returns [text] unchanged.
     */
    fun protectText(
        text: String,
        configPath: Path,
        protector: CredentialProtector?,
    ): String = protect(text, configPath, protector)?.text ?: text

    /**
     * The first-start migration (issue#786): [text]'s plaintext sensitive fields protected in place.
     * Null when there is nothing to do ([protector] null, or no plaintext sensitive field found — the
     * second start of a migrated config is a no-op). As the backstop (issue#83 C's "vault encrypt can
     * leave secrets behind"), the rewritten text is re-parsed and every rewritten field must decode back
     * to its original value, or [IllegalStateException] refuses the result naming profile and field,
     * never the value. A protector failure while protecting propagates.
     */
    fun migrateText(
        text: String,
        configPath: Path,
        protector: CredentialProtector?,
    ): MigratedSecrets? {
        val backend = protector ?: return null
        val result = protect(text, configPath, backend) ?: return null
        val parsed = SyncConfig.parseRaw(result.text)
        for ((profileName, field, original) in result.rewritten) {
            val profile =
                requireNotNull(parsed.providers[profileName]) {
                    "migration would drop the section of profile '$profileName'; nothing was rewritten"
                }
            val now = fieldOf(profile, field)
            val back =
                try {
                    now?.let { backend.decode(identity(configPath), it) }
                } catch (_: CredentialProtectionException) {
                    null
                }
            require(back != null && back.protected && back.plaintext == original) {
                "migration would not round-trip $field of profile '$profileName'; nothing was rewritten"
            }
        }
        return MigratedSecrets(result.rewritten.size, result.text)
    }

    /** A config rewritten by [migrateText]: how many fields were protected, and the new text. */
    data class MigratedSecrets(
        val count: Int,
        val text: String,
    )

    // ── line-oriented rewriting ──────────────────────────────────────────────

    private data class Protected(
        val text: String,
        /** The (profile, field, original plaintext) of every rewritten value, for migrateText's backstop. */
        val rewritten: List<Triple<String, String, String>>,
    )

    /**
     * The header shape is the tolerant one ktoml accepts (the filterVaultedLines contract): inner and trailing
     * whitespace in the header, a trailing comment. A sensitive key may be bare or quoted (either quoting), and
     * the value a one-line basic or literal string; the spelling after the `=` decides which, and a spelling
     * neither matches (a multiline string) is not rewritten — the completeness check then refuses it.
     */
    private val headerPattern = Regex("""^\s*\[([^\]]+)]\s*(?:#.*)?$""")

    /** One setter line per sensitive field: the key spelling (captured) and everything after the `=`. */
    private val sensitiveSetter =
        SENSITIVE_FIELDS.joinToString("|") { field -> """"${Regex.escape(field)}"|'${Regex.escape(field)}'|${Regex.escape(field)}""" }
            .let { Regex("""^\s*((?:$it))\s*=\s*(.*)$""") }

    private val basicStringValue = Regex("""^"((?:[^"\\]|\\.)*)"\s*(?:#.*)?$""")

    private val literalStringValue = Regex("""^'([^']*)'\s*(?:#.*)?$""")

    private fun protect(
        text: String,
        configPath: Path,
        protector: CredentialProtector?,
    ): Protected? {
        if (protector == null) return null
        val id = identity(configPath)
        // The parsed config is the source of truth for what a secret IS (issue#786): what the parser reads — any
        // key quoting, literal strings, the full escape grammar, even spellings spread over shapes the rewrite
        // below does not carry — is what must end up protected, and the baseline it must round-trip to. A text
        // the parser rejects falls back to the line shapes alone, best effort as before.
        val parsedFields = runCatching { parsedSensitiveFields(SyncConfig.parseRaw(text)) }.getOrNull()
        var section: String? = null
        val rewritten = mutableListOf<Triple<String, String, String>>()
        val out = text.split("\n").map { line ->
            headerPattern.matchEntire(line)?.groupValues?.get(1)?.trim()?.let { section = it }
            val profileName = section?.takeIf { it.startsWith("providers.") }?.substringAfter("providers.")?.substringBefore('.')
            if (profileName == null) return@map line
            val replaced = rewriteLine(line, profileName, id, protector, parsedFields?.get(profileName), rewritten)
            replaced ?: line
        }
        // The completeness check runs even when nothing was rewritten: a config whose only plaintext secret
        // sits in a spelling the rewrite cannot carry must refuse, not pass as a no-op with nothing to do.
        val candidate = if (rewritten.isEmpty()) text else out.joinToString("\n")
        if (parsedFields != null) requireCompleteProtection(candidate, parsedFields, id, protector)
        if (rewritten.isEmpty()) return null
        return Protected(candidate, rewritten)
    }

    /** The non-empty sensitive fields of every provider, as the parser read them: profile → (field → value). */
    private fun parsedSensitiveFields(config: RawSyncConfig): Map<String, Map<String, String>> =
        config.providers.entries.associate { (name, rp) ->
            val fields =
                rp?.let { provider ->
                    SENSITIVE_FIELDS.mapNotNull { field ->
                        fieldOf(provider, field)?.takeIf { it.isNotEmpty() }?.let { field to it }
                    }
                }.orEmpty().toMap()
            name to fields
        }

    /**
     * Nothing passes as protected while a parsed secret is not (issue#786; migrateText's round-trip backstop,
     * made complete over every field the parser read): the candidate is re-parsed, and each sensitive field the
     * original held must either decode, as this protector's own reference, to the value the parser read — or be
     * exactly what it was, when it was already a value this protector cannot decode (left for the read path to
     * report). A spelling the rewrite cannot carry (a multiline string) refuses here, naming profile and field,
     * never the value.
     */
    private fun requireCompleteProtection(
        candidateText: String,
        parsedFields: Map<String, Map<String, String>>,
        id: String,
        protector: CredentialProtector,
    ) {
        val candidate = SyncConfig.parseRaw(candidateText)
        for ((profileName, fields) in parsedFields) {
            for ((field, original) in fields) {
                val now = candidate.providers[profileName]?.let { fieldOf(it, field) }
                val existing = runCatching { protector.decode(id, original) }.getOrNull()
                val decodes = runCatching { protector.decode(id, now ?: "") }.getOrNull()
                val ok =
                    when {
                        existing == null -> now == original // not this protector's to touch; it must be untouched
                        existing.protected -> decodes?.protected == true && decodes.plaintext == existing.plaintext
                        else -> decodes?.protected == true && decodes.plaintext == original
                    }
                require(ok) {
                    "the $field of profile '$profileName' is a plaintext spelling this protection cannot rewrite; " +
                        "refusing to leave it unprotected (nothing was written)"
                }
            }
        }
    }

    /** The line with the first plaintext sensitive field replaced, appended to [rewritten]; null to keep [line]. */
    private fun rewriteLine(
        line: String,
        profileName: String,
        id: String,
        protector: CredentialProtector,
        parsed: Map<String, String>?,
        rewritten: MutableList<Triple<String, String, String>>,
    ): String? {
        val lead = line.substringBefore(line.trimStart())
        val trimmed = line.trimStart()
        val setter = sensitiveSetter.matchEntire(trimmed) ?: return null
        val basic = basicStringValue.matchEntire(setter.groupValues[2])
        val literal = if (basic == null) literalStringValue.matchEntire(setter.groupValues[2]) else null
        if (basic == null && literal == null) return null
        val field = setter.groupValues[1].trim('"', '\'')
        // The parser's value is the truth; the spelling is only where the bytes are. Without a parse (a text the
        // parser rejected) the spelling is the best available: a literal body is raw, a basic body carries escapes.
        val plain =
            parsed?.get(field)
                ?: (literal?.groupValues?.get(1) ?: unescapeTomlValue(basic!!.groupValues[1]))
        if (plain.isEmpty()) return null
        val data =
            try {
                protector.decode(id, plain)
            } catch (_: CredentialProtectionException) {
                // Already protected, but undecodable on this host/identity: leave the value for the
                // read path to report; rewriting it would throw away the only copy.
                return null
            }
        if (data.protected) return null
        val reference = protector.protect(id, plain)
        rewritten += Triple(profileName, field, plain)
        return "$lead$field = \"${escapeTomlValue(reference)}\""
    }

    private fun fieldOf(
        profile: RawProvider,
        field: String,
    ): String? =
        when (field) {
            "access_key_id" -> profile.access_key_id
            "secret_access_key" -> profile.secret_access_key
            "password" -> profile.password
            "client_id" -> profile.client_id
            "client_secret" -> profile.client_secret
            else -> null
        }

    /** The inverse of [escapeTomlValue] for the escapes it writes (plus the two TOML shorthand ones). */
    private fun unescapeTomlValue(raw: String): String {
        if (!raw.contains('\\')) return raw
        return buildString(raw.length) {
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c != '\\' || i + 1 >= raw.length) {
                    append(c)
                    i++
                    continue
                }
                when (val esc = raw[i + 1]) {
                    '\\' -> append('\\')
                    '"' -> append('"')
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    'b' -> append('\b')
                    'f' -> append('\u000C')
                    'u', 'U' -> {
                        val width = if (esc == 'u') 4 else 8
                        val hex = raw.substring(i + 2, (i + 2 + width).coerceAtMost(raw.length))
                        val code = hex.toIntOrNull(16)
                        if (hex.length == width && code != null && code <= 0x10FFFF) {
                            appendCodePoint(code) // an eight-digit \U spans the supplementary planes: a surrogate pair
                            i += 2 + width
                            continue
                        }
                        append(c).append(esc)
                    }
                    else -> append(c).append(esc)
                }
                i += 2
            }
        }
    }
}
