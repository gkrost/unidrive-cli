package org.krost.unidrive.onedrive

import kotlinx.serialization.json.JsonPrimitive
import org.krost.unidrive.AccountName
import org.krost.unidrive.AccountOwner
import org.krost.unidrive.BeginAuthResult
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.CompleteAuthResult
import org.krost.unidrive.CredentialHealth
import org.krost.unidrive.ProviderFactory
import org.krost.unidrive.ProviderMetadata
import org.krost.unidrive.auth.CredentialProtector
import org.krost.unidrive.auth.CredentialProtectors
import org.krost.unidrive.auth.CredentialStore
import org.krost.unidrive.auth.JwtExtractor
import org.krost.unidrive.onedrive.model.Drive
import org.krost.unidrive.onedrive.model.Token
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * [credentialProtector] keeps token.json (and the pending device flow) protected at rest. The default is the host's
 * native store where every host has one (Windows DPAPI, the macOS Keychain); on Linux it is none, the owner-only file
 * (see [CredentialProtectors.atRestDefaultOrNull]). A token file written before is read as it is and rewritten
 * protected the first time it is loaded.
 */
@Suppress("TooManyFunctions") // the interface's bare + properties overload pairs, and the device flow's own guarded steps; none of them hides another
open class OneDriveProviderFactory(
    private val credentialProtector: CredentialProtector? = CredentialProtectors.atRestDefaultOrNull(),
) : ProviderFactory {
    override val id = "onedrive"

    override fun describeConnection(
        properties: Map<String, String?>,
        profileDir: Path,
    ): String {
        val tokenFile = profileDir.resolve("token.json")
        if (!Files.exists(tokenFile)) return "onedrive (no token)"
        return try {
            val token = storedToken(profileDir, credentialProtector) ?: return "onedrive (token unreadable)"
            val parts = mutableListOf<String>()
            // Try to extract email/upn from JWT access token payload
            val email =
                JwtExtractor.extractClaim(token.accessToken, "preferred_username")
                    ?: JwtExtractor.extractClaim(token.accessToken, "upn")
            if (email != null) parts += email
            // Token expiry
            val expiresAt = java.time.Instant.ofEpochMilli(token.expiresAt)
            val now = java.time.Instant.now()
            if (now.isAfter(expiresAt)) {
                parts += "token expired (refresh available)"
            } else {
                val minutes =
                    java.time.Duration
                        .between(now, expiresAt)
                        .toMinutes()
                parts += "token expires in ${minutes}m"
            }
            "onedrive (${parts.joinToString(", ")})"
        } catch (_: Exception) {
            "onedrive (token unreadable)"
        }
    }

    override val metadata =
        ProviderMetadata(
            id = "onedrive",
            displayName = "Microsoft OneDrive",
            description = "Microsoft 365 personal and business cloud storage",
            authType = "OAuth2 (browser)",
            encryption = "Server-side (Microsoft-managed keys)",
            jurisdiction = "USA (with EU Data Boundary option)",
            gdprCompliant = true,
            cloudActExposure = true,
            signupUrl = null,
            tier = "Global",
            userRating = 4.5,
            benchmarkGrade = "A",
            maxConcurrentTransfers = 8,
            // gkrost/unidrive-cli#392: the same 8 as the Graph client's throttle budget (GraphApiService), stated so the
            // size-aware download budget does not depend on the default.
            maxConcurrentSmallDownloads = 8,
            minRequestSpacingMs = 200L,
            // Backwards-compat: existing users have a ~/OneDrive directory.
            // Title-casing the id would give ~/Onedrive, silently orphaning it.
            syncRootDirName = "OneDrive",
        )

    override fun create(
        properties: Map<String, String?>,
        tokenPath: Path,
    ): CloudProvider {
        val authEndpoint = properties["authority_url"] ?: OneDriveConfig.DEFAULT_AUTH_ENDPOINT
        val includeShared = properties["include_shared"]?.toBoolean() ?: false
        // archive#111: the socket.io change channel; absent = on, "false" keeps polling only.
        val remoteNotifications = properties["remote_notifications"]?.toBooleanStrictOrNull() ?: true
        val config =
            OneDriveConfig(
                tokenPath = tokenPath,
                authEndpoint = authEndpoint,
                includeShared = includeShared,
                remoteNotifications = remoteNotifications,
                credentialProtector = credentialProtector,
            )
        return OneDriveProvider(config)
    }

    override fun isAuthenticated(
        properties: Map<String, String?>,
        profileDir: Path,
    ): Boolean = Files.exists(profileDir.resolve("token.json"))

    override fun checkCredentialHealth(
        properties: Map<String, String?>,
        profileDir: Path,
    ): CredentialHealth {
        val tokenFile = profileDir.resolve("token.json")
        if (!Files.exists(tokenFile)) {
            return CredentialHealth.Missing("No token.json")
        }
        return try {
            val token = storedToken(profileDir, credentialProtector) ?: return CredentialHealth.Warning("token.json exists but could not be parsed")
            when {
                token.isExpired && token.refreshToken == null ->
                    // Nothing can renew it: expired, with zero time left (daemon.status reports `expired`).
                    CredentialHealth.ExpiresIn(0, "Access token expired, no refresh token — re-auth required", remainingMs = 0)
                token.isExpired ->
                    CredentialHealth.Ok // expired access token with refresh token is normal; authenticate() will refresh
                token.refreshToken != null ->
                    // A refresh token makes the credential fine: the ~1 h life of an
                    // access token is routine and authenticate() renews it. The 24 h
                    // check below flagged every healthy profile ("[!] ...: 0h").
                    CredentialHealth.Ok
                else -> {
                    val remaining =
                        java.time.Duration.between(
                            java.time.Instant.now(),
                            java.time.Instant.ofEpochMilli(token.expiresAt),
                        )
                    val hoursRemaining = remaining.toHours()
                    if (hoursRemaining < 24) {
                        CredentialHealth.ExpiresIn(hoursRemaining, "${hoursRemaining}h", remainingMs = remaining.toMillis())
                    } else {
                        CredentialHealth.Ok
                    }
                }
            }
        } catch (_: Exception) {
            CredentialHealth.Warning("token.json exists but could not be parsed")
        }
    }

    override fun supportsInteractiveAuth(): Boolean = true

    // The profile's authority decides where the sign-in asks Microsoft for a device code (and where the token is
    // refreshed later): the default is the personal authority, a profile's authority_url moves it to organizations,
    // common or one tenant. The properties-less overloads keep the personal default for callers that have no
    // profile properties (the interface default routes the properties-taking calls to them — they must not).
    override suspend fun beginInteractiveAuth(profileDir: Path): BeginAuthResult = beginInteractiveAuth(profileDir, emptyMap())

    override suspend fun beginInteractiveAuth(
        profileDir: Path,
        properties: Map<String, String?>,
    ): BeginAuthResult {
        val config = beginConfig(profileDir, properties)
        val oauth = newOAuthServiceForBegin(profileDir, config)
        val deviceCode =
            try {
                oauth.getDeviceCode()
            } catch (e: Exception) {
                // No handle issued yet — close the HttpClient here, the
                // registry-cleanup paths cannot reach it.
                oauth.close()
                throw e
            }

        val state =
            OneDriveDeviceFlowState(
                deviceCode = deviceCode.deviceCode,
                expiresAtMillis = System.currentTimeMillis() + deviceCode.expiresIn * 1000L,
                oauthService = oauth,
                profileDir = profileDir,
                intervalSeconds = deviceCode.interval.coerceAtLeast(1L),
            )
        val handle = OneDriveDeviceFlowRegistry.put(state)
        try {
            pendingFlowStore(profileDir).save(
                PendingDeviceFlow(handle, state.deviceCode, state.expiresAtMillis, config.authEndpoint, state.intervalSeconds),
            )
        } catch (e: java.io.IOException) {
            // The flow could not be kept for a later process; do not hand out a handle that cannot be completed.
            OneDriveDeviceFlowRegistry.remove(handle)
            oauth.close()
            throw e
        }

        // The tenant-wide admin-consent URL beside the code, for a work or school authority: a tenant that leaves user
        // consent off refuses the sign-in until its admin granted consent once, so the front-end shows the URL to send
        // to the admin while the user's own wait is still running. Null (and no field) for the personal authority.
        val fields =
            linkedMapOf(
                "verification_uri" to JsonPrimitive(deviceCode.verificationUri),
                "user_code" to JsonPrimitive(deviceCode.userCode),
                "interval_seconds" to JsonPrimitive(deviceCode.interval),
                "expires_in" to JsonPrimitive(deviceCode.expiresIn),
                "message" to JsonPrimitive(deviceCode.message),
            )
        config.adminConsentUrl()?.let { fields["admin_consent_url"] = JsonPrimitive(it) }

        return BeginAuthResult.of(
            continuationHandle = handle,
            // UD-375: typed JsonPrimitive values so the MCP serializer emits
            // numerics as JSON numbers (not strings). Pre-fix the .toString()
            // on `interval` and `expiresIn` made every numeric field arrive
            // as a JSON string on the wire, breaking clients parsing it as
            // a number.
            fields = fields,
            expiresAt = Instant.ofEpochMilli(state.expiresAtMillis),
            retryAfterSeconds = deviceCode.interval,
        )
    }

    override suspend fun completeInteractiveAuth(
        profileDir: Path,
        continuationHandle: String,
        secret: String?,
    ): CompleteAuthResult = completeInteractiveAuth(profileDir, continuationHandle, secret, emptyMap())

    override suspend fun completeInteractiveAuth(
        profileDir: Path,
        continuationHandle: String,
        secret: String?,
        properties: Map<String, String?>,
    ): CompleteAuthResult {
        val state =
            OneDriveDeviceFlowRegistry.get(continuationHandle)
                ?: restorePendingFlow(profileDir, continuationHandle)
                ?: return CompleteAuthResult.Failure(
                    "Unknown or expired continuation_handle. Call auth_begin again.",
                    CompleteAuthResult.Reason.UNKNOWN_HANDLE,
                )

        if (System.currentTimeMillis() > state.expiresAtMillis) {
            finishFlow(profileDir, continuationHandle)
            state.oauthService.close()
            return CompleteAuthResult.Failure(
                "Device code expired. Call auth_begin again.",
                CompleteAuthResult.Reason.EXPIRED,
            )
        }

        val oauth = state.oauthService
        // pollOnceForToken is total: it catches its own network errors and
        // returns DevicePollOutcome.Failed. The only way an exception escapes
        // is via 2xx-with-malformed-body (SerializationException from the
        // TokenResponse decode at OAuthService.kt:193) or an unexpected JVM
        // error. Either way we drain the registry and surface Failure.
        val outcome: OAuthService.DevicePollOutcome =
            try {
                oauth.pollOnceForToken(state.deviceCode, state.intervalSeconds)
            } catch (e: Exception) {
                finishFlow(profileDir, continuationHandle)
                oauth.close()
                // Only the type: a decode error quotes the response it choked on, and that is a token response.
                return CompleteAuthResult.Failure("The token response could not be read (${e.javaClass.simpleName}). Call auth_begin again.")
            }

        return when (outcome) {
            is OAuthService.DevicePollOutcome.Pending -> {
                if (outcome.retryAfterSeconds != state.intervalSeconds) {
                    keepSlowerInterval(profileDir, continuationHandle, state, outcome.retryAfterSeconds)
                }
                CompleteAuthResult.Pending(outcome.retryAfterSeconds)
            }
            is OAuthService.DevicePollOutcome.Success -> {
                // Who signed in is read once, now, with the token just issued and BEFORE it is saved: another user than
                // the one the profile's state belongs to (AccountOwner) is refused and the stored token stays (archive#1241).
                val drive = fetchIdentity(profileDir, outcome.token)
                val refusal = ownerRefusal(AccountOwner.verdict(profileDir, drive?.accountId()))
                if (refusal != null) {
                    finishFlow(profileDir, continuationHandle)
                    oauth.close()
                    return refusal
                }
                try {
                    oauth.saveToken(outcome.token)
                } catch (e: Exception) {
                    finishFlow(profileDir, continuationHandle)
                    oauth.close()
                    return CompleteAuthResult.Failure("Token received but save failed: ${e.javaClass.simpleName}")
                }
                finishFlow(profileDir, continuationHandle)
                oauth.close()
                AccountOwner.record(profileDir, drive?.accountId())
                // The name is kept next to the token; a failed lookup leaves no name (and drops an earlier
                // account's) rather than failing the sign-in.
                val account = drive?.accountName()
                AccountName.write(profileDir, account)
                CompleteAuthResult.Success(account)
            }
            is OAuthService.DevicePollOutcome.Failed -> {
                finishFlow(profileDir, continuationHandle)
                oauth.close()
                CompleteAuthResult.Failure(outcome.message, outcome.reason)
            }
        }
    }

    override suspend fun cancelInteractiveAuth(continuationHandle: String) {
        val state = OneDriveDeviceFlowRegistry.remove(continuationHandle) ?: return
        state.oauthService.close()
        state.profileDir?.let { dir ->
            try {
                val store = pendingFlowStore(dir)
                if (store.load()?.handle == continuationHandle) store.delete()
            } catch (_: java.io.IOException) {
                // The device code expires on its own; a leftover file is rejected as expired.
            }
        }
    }

    private fun pendingFlowStore(profileDir: Path) =
        CredentialStore(profileDir, PENDING_DEVICE_FLOW_FILE, PendingDeviceFlow.serializer(), protector = credentialProtector)

    /**
     * The flow [handle] began in an earlier process (`auth begin` of the CLI), rebuilt from the
     * profile folder with the authority it began at (kept with it, not the profile's current one); null when there is none for that handle.
     */
    private fun restorePendingFlow(
        profileDir: Path,
        handle: String,
    ): OneDriveDeviceFlowState? {
        val pending = pendingFlowStore(profileDir).load() ?: return null
        if (pending.handle != handle) return null
        val config =
            OneDriveConfig(
                tokenPath = profileDir,
                authEndpoint = pending.authEndpoint ?: OneDriveConfig.DEFAULT_AUTH_ENDPOINT,
                credentialProtector = credentialProtector,
            )
        val state =
            OneDriveDeviceFlowState(
                pending.deviceCode,
                pending.expiresAtMillis,
                newOAuthServiceForBegin(profileDir, config),
                profileDir,
                pending.intervalSeconds ?: DEFAULT_DEVICE_POLL_INTERVAL_SECONDS,
            )
        OneDriveDeviceFlowRegistry.putWithHandle(handle, state)
        return state
    }

    /**
     * A `slow_down` raised the flow's interval: it holds for every later poll, in this process and in the next
     * `auth complete` (RFC 8628 section 3.5). A failed save of the pending file costs only the raise in a later process.
     */
    private fun keepSlowerInterval(
        profileDir: Path,
        handle: String,
        state: OneDriveDeviceFlowState,
        intervalSeconds: Long,
    ) {
        OneDriveDeviceFlowRegistry.putWithHandle(handle, state.copy(intervalSeconds = intervalSeconds))
        try {
            val store = pendingFlowStore(profileDir)
            val pending = store.load()
            if (pending?.handle == handle) store.save(pending.copy(intervalSeconds = intervalSeconds))
        } catch (_: java.io.IOException) {
            // The process that keeps polling holds the raise in memory; only a later process would start lower again.
        }
    }

    /** A terminal outcome: forget the flow in memory and on disk. */
    private fun finishFlow(
        profileDir: Path,
        handle: String,
    ) {
        OneDriveDeviceFlowRegistry.remove(handle)
        try {
            pendingFlowStore(profileDir).delete()
        } catch (_: java.io.IOException) {
            // The device code expires on its own; a leftover file is rejected as expired.
        }
    }

    /**
     * The drive (`GET /me/drive`) of [token]: its owner id is the user the profile's state belongs to, its owner name the account name; null
     * when it cannot be read. Never logged.
     */
    internal suspend fun fetchIdentity(
        profileDir: Path,
        token: Token,
    ): Drive? =
        try {
            newGraphApiForIdentity(profileDir, token).use { api -> api.getDrive() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    /** Test seam for the `/me/drive` lookup at sign-in, as [newOAuthServiceForBegin]. */
    internal open fun newGraphApiForIdentity(
        profileDir: Path,
        token: Token,
    ): GraphApiService = GraphApiService(OneDriveConfig(tokenPath = profileDir, credentialProtector = credentialProtector)) { _, _ -> token.accessToken }

    /** UD-014 test seam: subclassed in OneDriveInteractiveAuthContractTest
     *  to inject a Ktor MockEngine-backed HttpClient. Production code
     *  uses the default. Open + internal so the test in the same module
     *  can override; never overridden in production. */
    internal open fun newOAuthServiceForBegin(
        profileDir: Path,
        config: OneDriveConfig,
    ): OAuthService = OAuthService(config)

    /** The config the sign-in runs with: the profile's authority_url, else the personal default, over this factory's protector. */
    private fun beginConfig(
        profileDir: Path,
        properties: Map<String, String?>,
    ): OneDriveConfig =
        OneDriveConfig(
            tokenPath = profileDir,
            authEndpoint = properties["authority_url"]?.trim()?.takeIf { it.isNotEmpty() } ?: OneDriveConfig.DEFAULT_AUTH_ENDPOINT,
            credentialProtector = credentialProtector,
        )
}

// The stored token as the provider reads it, through the same store (and protector) it was written with.
private fun storedToken(
    profileDir: Path,
    protector: CredentialProtector?,
): Token? = CredentialStore(profileDir, "token.json", Token.serializer(), protector = protector).load()
