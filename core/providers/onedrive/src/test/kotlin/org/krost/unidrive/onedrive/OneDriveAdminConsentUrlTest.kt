package org.krost.unidrive.onedrive

import java.net.URI
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The tenant-wide admin-consent URL the sign-in hands out beside the device code (gkrost/unidrive-cli#617): a work or
 * school tenant that leaves user consent off refuses the sign-in until its admin granted consent once, so the URL is
 * the one thing a front-end can show the admin. Microsoft documents the endpoint as
 * `https://login.microsoftonline.com/{tenant}/v2.0/adminconsent` with required client_id, scope and redirect_uri
 * ([admin consent protocols](https://learn.microsoft.com/en-us/entra/identity-platform/v2-admin-consent)).
 */
class OneDriveAdminConsentUrlTest {
    private fun config(authEndpoint: String) = OneDriveConfig(authEndpoint = authEndpoint)

    /** The query of [url] as plain (decoded) name to value. */
    private fun queryOf(url: String): Map<String, String> =
        URI(url).rawQuery
            ?.split("&")
            ?.associate {
                val (name, value) = it.split("=", limit = 2)
                name to URLDecoder.decode(value, Charsets.UTF_8)
            }.orEmpty()

    @Test
    fun `the personal authority has no admin consent url - a personal account has no tenant admin`() {
        assertNull(config("https://login.microsoftonline.com/consumers/oauth2/v2.0").adminConsentUrl())
        // The default the profile carries when it says nothing is the personal one.
        assertNull(OneDriveConfig().adminConsentUrl())
    }

    @Test
    fun `common has none - Microsoft says not to use it for admin consent`() {
        assertNull(config("https://login.microsoftonline.com/common/oauth2/v2.0").adminConsentUrl())
    }

    @Test
    fun `organizations builds the documented url with client_id, scope and the registered redirect`() {
        val config = config("https://login.microsoftonline.com/organizations/oauth2/v2.0")
        val url = config.adminConsentUrl()
        val uri = URI(url!!)
        assertEquals("https", uri.scheme)
        assertEquals("login.microsoftonline.com", uri.host)
        assertEquals("/organizations/v2.0/adminconsent", uri.rawPath)
        val query = queryOf(url)
        assertEquals(OneDriveConfig.DEFAULT_APP_ID, query["client_id"])
        assertEquals(OneDriveConfig.GRAPH_FILES_SCOPE, query["scope"])
        assertEquals(config.redirectUri, query["redirect_uri"])
    }

    @Test
    fun `one tenant stands where organizations stands, as typed`() {
        val tenant = "11111111-2222-3333-4444-555555555555"
        val uri = URI(config("https://login.microsoftonline.com/$tenant/oauth2/v2.0").adminConsentUrl()!!)
        assertEquals("/$tenant/v2.0/adminconsent", uri.rawPath)
    }

    @Test
    fun `anything that is not the authority shape the profile key keeps has no url`() {
        // A hand-edited config.toml may hold any address; a consent URL is derived only from the
        // endpoint the engine actually talks to, never from a look-alike on another host or path.
        for (endpoint in
            listOf(
                "http://login.microsoftonline.com/organizations/oauth2/v2.0",
                "https://evil.example.com/organizations/oauth2/v2.0",
                "https://login.microsoftonline.com/organizations",
                "https://login.microsoftonline.com/organizations/oauth2/v2.0/extra",
                "https://login.microsoftonline.com/organizations/oauth2/v2.0?x=1",
                "not an address",
                "",
            )) {
            assertNull(config(endpoint).adminConsentUrl(), endpoint)
        }
    }
}
