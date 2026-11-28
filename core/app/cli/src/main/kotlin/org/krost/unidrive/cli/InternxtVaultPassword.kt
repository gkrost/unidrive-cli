package org.krost.unidrive.cli

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.krost.unidrive.authenticateAndLog
import org.krost.unidrive.sync.ProfileInfo
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/*
 * gkrost/unidrive-cli#861 (Windows Hello P5, engine side): the vault keeps an Internxt profile's sign-in, opt-in, so the
 * engine can sign an expired session in again by itself (owner decision 2026-11-21: no verb reads the password back).
 * The entry is the reserved section `.internxt-password:<profile>` (`.` starts no profile name) with the fields `email`
 * and `password`; clearing it removes the section whole. A two-factor account is never signed in that way: the engine
 * reports `totp_required` instead, and the code stays the user's to type.
 */

internal const val INTERNXT_PASSWORD_SECTION_PREFIX = ".internxt-password:"

private const val EMAIL_FIELD = "email"
private const val PASSWORD_FIELD = "password"

/**
 * The `daemon.status` capability that says this engine keeps an Internxt profile's sign-in in the vault
 * (`vault.internxt_password.set` / `.clear`) and signs an expired session in again with it; listed on an Internxt daemon
 * whose vault.enc exists.
 */
internal const val VAULT_INTERNXT_PASSWORD_CAPABILITY = "vault-internxt-password:v1"

/** The reserved vault section of [profile]'s Internxt sign-in. */
internal fun internxtPasswordSection(profile: String): String = INTERNXT_PASSWORD_SECTION_PREFIX + profile

/** An Internxt sign-in read from the vault. Never printed: [toString] names no value. */
internal class VaultedInternxtSignIn(
    val email: String,
    val password: String,
) {
    override fun toString(): String = "VaultedInternxtSignIn(<redacted>)"
}

/** [profile]'s vaulted sign-in, or null when [data] holds none (the default: the option is opt-in). */
internal fun vaultInternxtSignIn(
    data: Map<String, Map<String, String>>,
    profile: String,
): VaultedInternxtSignIn? {
    val section = data[internxtPasswordSection(profile)] ?: return null
    val email = section[EMAIL_FIELD]?.takeIf { it.isNotBlank() } ?: return null
    val password = section[PASSWORD_FIELD]?.takeIf { it.isNotEmpty() } ?: return null
    return VaultedInternxtSignIn(email, password)
}

/** [data] with [profile]'s sign-in set; the other entries are unchanged. An empty e-mail or password is refused. */
internal fun withVaultInternxtPassword(
    data: Map<String, Map<String, String>>,
    profile: String,
    email: String,
    password: String,
): Map<String, Map<String, String>> {
    require(email.isNotBlank()) { "the e-mail must not be empty" }
    require(password.isNotEmpty()) { "the password must not be empty" }
    return data + (internxtPasswordSection(profile) to mapOf(EMAIL_FIELD to email.trim(), PASSWORD_FIELD to password))
}

/** [data] without [profile]'s sign-in: the section goes whole. */
internal fun withoutVaultInternxtPassword(
    data: Map<String, Map<String, String>>,
    profile: String,
): Map<String, Map<String, String>> = data - internxtPasswordSection(profile)

/** [profile]'s sign-in in this process's in-memory vault data; null while the vault is locked or holds none. */
internal fun Main.vaultedInternxtSignIn(profile: String): VaultedInternxtSignIn? = vaultDataCache?.let { vaultInternxtSignIn(it, profile) }

/**
 * The engine's own sign-in with the vaulted password. [attempt] sends one `auth.login` request to `login` — the daemon's
 * own `auth.login` handler, so the step is the one a client's sign-in runs (the library sign-in, its initialize before the
 * save, the reload into the running provider) — and reads the reply's token:
 * - `tfa_required`: the account has two-factor sign-in; nothing was sent but the e-mail, [totpRequired] is set, and the
 *   `auth` object of `daemon.status` says `totp_required`;
 * - `bad_credentials` (and the account refusals): the stored password no longer signs in.
 * Neither is tried again with the same stored sign-in; another password (`vault internxt-password-set`) is tried. Any
 * other failure (no network) is tried again at the next check. Nothing here logs or returns the password.
 */
internal class InternxtVaultRelogin(
    private val signIn: () -> VaultedInternxtSignIn?,
) {
    enum class Outcome { SIGNED_IN, NO_PASSWORD, TOTP_REQUIRED, REJECTED, FAILED, SKIPPED }

    private val log = LoggerFactory.getLogger(InternxtVaultRelogin::class.java)

    /** True while the last attempt found that the account needs a two-factor code. */
    @Volatile var totpRequired: Boolean = false
        private set

    // A digest of the stored sign-in the last attempt refused for good (two-factor, wrong password); never the password.
    @Volatile private var refused: String? = null

    suspend fun attempt(login: suspend (String) -> String): Outcome {
        val stored = signIn() ?: return Outcome.NO_PASSWORD
        val digest = digestOf(stored)
        if (digest == refused) return Outcome.SKIPPED
        val request =
            buildJsonObject {
                put("verb", AuthLoginRpcHandler.LOGIN)
                put("email", stored.email)
                put("password", stored.password)
            }
        val reply = runCatching { Json.parseToJsonElement(login(request.toString())) as? JsonObject }.getOrNull()
        val outcome =
            if ((reply?.get("ok") as? JsonPrimitive)?.content == "true") {
                Outcome.SIGNED_IN
            } else {
                when ((reply?.get("error") as? JsonPrimitive)?.content) {
                    "tfa_required" -> Outcome.TOTP_REQUIRED
                    "bad_credentials", ACCOUNT_CHANGED, ACCOUNT_UNVERIFIED -> Outcome.REJECTED
                    else -> Outcome.FAILED
                }
            }
        totpRequired = outcome == Outcome.TOTP_REQUIRED
        refused = if (outcome == Outcome.TOTP_REQUIRED || outcome == Outcome.REJECTED) digest else null
        log.info("vault sign-in of the expired Internxt session: {}", outcome.name.lowercase())
        return outcome
    }

    /** Checks every [intervalMs] whether the session needs a sign-in ([needsSignIn]) and then [attempt]s one. */
    suspend fun watch(
        intervalMs: Long,
        needsSignIn: () -> Boolean,
        login: suspend (String) -> String,
    ) {
        while (true) {
            delay(intervalMs)
            if (needsSignIn()) attempt(login)
        }
    }

    private fun digestOf(stored: VaultedInternxtSignIn): String =
        MessageDigest.getInstance("SHA-256")
            .digest((stored.email + "\u0000" + stored.password).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/**
 * The daemon's authentication of [provider] at start: an expired session (never a signed-out one, read by [healthCheck])
 * is signed in again with the vaulted password through the `auth.login` step before the start gives up, and a two-factor
 * account says `totp_required` in the refusal. Without [relogin] (no Internxt profile) or [target] it is the plain one.
 */
internal suspend fun authenticateOrRelogin(
    provider: org.krost.unidrive.CloudProvider,
    relogin: InternxtVaultRelogin?,
    target: ProfileTarget?,
    healthCheck: (() -> org.krost.unidrive.CredentialHealth)?,
) {
    try {
        provider.authenticateAndLog()
    } catch (e: org.krost.unidrive.AuthRequiredException) {
        val expired = healthCheck?.let { CredentialHealthMonitor.toReading(it(), 0L).state } == CredentialHealthMonitor.State.EXPIRED
        val outcome = if (relogin != null && target != null && expired) relogin.attempt(AuthLoginRpcHandler(target, onSignedIn = {})::login) else null
        if (outcome != InternxtVaultRelogin.Outcome.SIGNED_IN) {
            throw if (outcome == InternxtVaultRelogin.Outcome.TOTP_REQUIRED) {
                org.krost.unidrive.AuthRequiredException("${e.message} (totp_required: the vaulted password needs a two-factor code)", e)
            } else {
                e
            }
        }
        provider.authenticateAndLog()
    }
}

/** The running daemon's session needs a sign-in: the reading `daemon.status` reports as `expired` (a signed-out profile does not). */
internal fun sessionExpired(
    credentialHealth: CredentialHealthMonitor,
    mount: org.krost.unidrive.hydration.MountEngine,
    signedInAtMs: Long?,
): Boolean =
    credentialHealth.reading().withRejection(rejectionAfterSignIn(mount.authRejectedAtMs, signedInAtMs), mount.lastProviderContactAtMs).state ==
        CredentialHealthMonitor.State.EXPIRED

/** `daemon run`'s vault seam: an Internxt profile keeps its sign-in in the vault (the verbs) and signs an expired session in again. */
internal fun Main.wireDaemonVault(
    runtime: DaemonRuntime,
    profile: ProfileInfo,
    start: DaemonVaultStart,
) {
    val internxt = profile.type == "internxt"
    runtime.vault = daemonVault(start, internxtProfile = profile.name.takeIf { internxt })
    if (internxt) runtime.vaultRelogin = InternxtVaultRelogin { vaultedInternxtSignIn(profile.name) }
}

/**
 * The daemon start's sign-in before it refuses an Internxt profile whose session expired (archive#865's refusal): null
 * when the profile is no Internxt profile or the vault holds no sign-in for it (the refusal stands as before), otherwise
 * the outcome of the engine's own sign-in — the same `auth.login` step, with no running provider to reload yet.
 */
internal fun Main.reloginBeforeStartRefusal(profile: ProfileInfo): InternxtVaultRelogin.Outcome? {
    if (profile.type != "internxt" || vaultedInternxtSignIn(profile.name) == null) return null
    val target = ProfileTarget(profile.name, profile.type, configBaseDir().resolve(profile.name))
    val handler = AuthLoginRpcHandler(target, onSignedIn = {})
    return kotlinx.coroutines.runBlocking(org.krost.unidrive.http.Priority.Foreground) { InternxtVaultRelogin { vaultedInternxtSignIn(profile.name) }.attempt(handler::login) }
}
