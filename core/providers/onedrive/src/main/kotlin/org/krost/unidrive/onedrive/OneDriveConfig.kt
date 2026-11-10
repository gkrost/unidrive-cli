package org.krost.unidrive.onedrive

import org.krost.unidrive.auth.CredentialProtector
import org.krost.unidrive.io.defaultTokenPath
import java.nio.file.Path

data class OneDriveConfig(
    val applicationId: String = DEFAULT_APP_ID,
    val tokenPath: Path = defaultTokenPath("onedrive"),
    val userAgent: String = DEFAULT_USER_AGENT,
    val oauthCallbackPort: Int = resolveOAuthCallbackPort(System.getenv(OAUTH_PORT_ENV)),
    val redirectUri: String = "http://localhost:$oauthCallbackPort/callback",
    val authEndpoint: String = DEFAULT_AUTH_ENDPOINT,
    val includeShared: Boolean = false,
    val webhookEnabled: Boolean = false,
    val webhookPort: Int = 8081,
    // archive#111: open the socket.io change channel when the engine asks for remote-change hints (profile key remote_notifications).
    val remoteNotifications: Boolean = true,
    // archive#1261: protects token.json at rest, as InternxtConfig.credentialProtector does. Null keeps the owner-only
    // plaintext file the desktop writes today; the Android app passes its Keystore-backed protector.
    val credentialProtector: CredentialProtector? = null,
) {
    companion object {
        const val DEFAULT_APP_ID = "aa961a73-f4ac-41d9-8150-0a18f330ff6c"
        const val DEFAULT_USER_AGENT = "ISV|unidrive|Kotlin/1.0"
        const val DEFAULT_OAUTH_CALLBACK_PORT = 8080
        const val DEFAULT_REDIRECT_URI = "http://localhost:$DEFAULT_OAUTH_CALLBACK_PORT/callback"
        const val DEFAULT_AUTH_ENDPOINT = "https://login.microsoftonline.com/consumers/oauth2/v2.0"

        /** The one Graph scope the provider asks for; [OAuthService.SCOPES] adds the OIDC ones, the admin-consent URL asks for it alone. */
        const val GRAPH_FILES_SCOPE = "https://graph.microsoft.com/Files.ReadWrite.All"

        /** Tenant segments of an authority that have no tenant admin to ask: personal accounts, and the one Microsoft says not to use for admin consent. */
        private val TENANTS_WITHOUT_ADMIN = setOf("consumers", "common")

        /** Environment variable that moves the browser sign-in callback off the default port. */
        const val OAUTH_PORT_ENV = "UNIDRIVE_ONEDRIVE_OAUTH_PORT"

        const val GRAPH_BASE_URL = "https://graph.microsoft.com"
        const val GRAPH_VERSION = "v1.0"

        private val log = org.slf4j.LoggerFactory.getLogger(OneDriveConfig::class.java)

        /**
         * Parse the [OAUTH_PORT_ENV] value: a port in 1..65535. Absent means the default; an unusable
         * value is reported and also falls back to the default, so a typo cannot silently pick a port.
         */
        fun resolveOAuthCallbackPort(raw: String?): Int {
            if (raw == null) return DEFAULT_OAUTH_CALLBACK_PORT
            val port = raw.trim().toIntOrNull()
            if (port == null || port !in 1..65535) {
                log.warn("Ignoring {}='{}' (expected a port in 1..65535); using {}", OAUTH_PORT_ENV, raw, DEFAULT_OAUTH_CALLBACK_PORT)
                return DEFAULT_OAUTH_CALLBACK_PORT
            }
            return port
        }
    }

    /**
     * The tenant-wide admin-consent URL of this [authEndpoint]'s tenant, or null when there is no tenant admin to ask. A
     * work or school tenant that leaves user consent off refuses the sign-in until its admin granted consent for the app
     * once; Microsoft documents the URL as `https://login.microsoftonline.com/{tenant}/v2.0/adminconsent` with required
     * client_id, scope and redirect_uri, and names `organizations` as the tenant to use (never `common`). The redirect is
     * the registered localhost callback: after the approval the browser lands there and answers nothing — the consent is
     * already granted. Null also for anything that is not a `https://login.microsoftonline.com/{tenant}/oauth2/v2.0`
     * authority (the shape `profile set authority_url` keeps, a hand-edited config may not), so a URL is never derived
     * from an endpoint this engine does not talk to.
     */
    fun adminConsentUrl(): String? {
        val tenant = consentTenant() ?: return null
        return io.ktor.http.URLBuilder(
            protocol = io.ktor.http.URLProtocol.HTTPS,
            host = "login.microsoftonline.com",
            pathSegments = listOf(tenant, "v2.0", "adminconsent"),
        ).apply {
            parameters.append("client_id", applicationId)
            parameters.append("scope", GRAPH_FILES_SCOPE)
            parameters.append("redirect_uri", redirectUri)
        }.buildString()
    }

    /** The tenant segment of [authEndpoint] when a tenant admin can be asked there: the authority shape the engine talks to, not one without a tenant admin. */
    private fun consentTenant(): String? {
        val uri =
            try {
                java.net.URI(authEndpoint)
            } catch (_: Exception) {
                return null
            }
        if (uri.scheme?.lowercase() != "https") return null
        if (uri.host?.lowercase() != "login.microsoftonline.com") return null
        if (uri.port != -1 && uri.port != 443) return null
        if (uri.userInfo != null || uri.rawQuery != null || uri.fragment != null) return null
        val segments = uri.rawPath?.trimEnd('/')?.split('/')?.filter(String::isNotEmpty) ?: return null
        if (segments.size != 3 || segments[1] != "oauth2" || segments[2] != "v2.0") return null
        if (segments[0].lowercase() in TENANTS_WITHOUT_ADMIN) return null
        return segments[0]
    }
}
