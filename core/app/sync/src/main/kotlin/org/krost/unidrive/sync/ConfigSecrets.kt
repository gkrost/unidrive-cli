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
        if (rp == null || protector == null) return rp
        val id = identity(configPath)

        fun one(value: String?, field: String): String? {
            if (value.isNullOrEmpty()) return value
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
            return if (data.protected) data.plaintext else value
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
     * The header and key shapes are the tolerant ones ktoml accepts (the filterVaultedLines contract):
     * inner and trailing whitespace in the header, a trailing comment, spacing around the `=`. The value
     * is a one-line basic string with TOML escapes intact.
     */
    private val headerPattern = Regex("""^\s*\[([^\]]+)]\s*(?:#.*)?$""")

    private val fieldPatterns: Map<String, Regex> =
        SENSITIVE_FIELDS.associateWith { field ->
            // One capture group: the raw body between the quotes (TOML escapes intact).
            Regex("""^\s*${Regex.escape(field)}\s*=\s*"((?:[^"\\]|\\.)*)"\s*(?:#.*)?$""")
        }

    private fun protect(
        text: String,
        configPath: Path,
        protector: CredentialProtector?,
    ): Protected? {
        if (protector == null) return null
        val id = identity(configPath)
        var section: String? = null
        val rewritten = mutableListOf<Triple<String, String, String>>()
        val out = text.split("\n").map { line ->
            headerPattern.matchEntire(line)?.groupValues?.get(1)?.trim()?.let { section = it }
            val profileName = section?.takeIf { it.startsWith("providers.") }?.substringAfter("providers.")?.substringBefore('.')
            if (profileName == null) return@map line
            val replaced = rewriteLine(line, profileName, id, protector, rewritten)
            replaced ?: line
        }
        if (rewritten.isEmpty()) return null
        return Protected(out.joinToString("\n"), rewritten)
    }

    /** The line with the first plaintext sensitive field replaced, appended to [rewritten]; null to keep [line]. */
    private fun rewriteLine(
        line: String,
        profileName: String,
        id: String,
        protector: CredentialProtector,
        rewritten: MutableList<Triple<String, String, String>>,
    ): String? {
        val lead = line.substringBefore(line.trimStart())
        val trimmed = line.trimStart()
        for ((field, pattern) in fieldPatterns) {
            val m = pattern.matchEntire(trimmed) ?: continue
            val raw = m.groupValues[1]
            if (raw.isEmpty()) return null
            val plain = unescapeTomlValue(raw)
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
        return null
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
                    'u' -> {
                        val hex = raw.substring(i + 2, (i + 6).coerceAtMost(raw.length))
                        val code = hex.toIntOrNull(16)
                        if (hex.length == 4 && code != null) {
                            append(code.toChar())
                            i += 6
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
