package org.krost.unidrive.onedrive

import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * cli#223 item 3: the authorize URL is built with ktor's `URLBuilder`, so a redirect URI or a state that holds `&`,
 * `?`, `#`, `+` or a space is one parameter value and does not split into others.
 */
class OAuthServiceAuthorizationUrlTest {
    private fun urlFor(
        redirectUri: String,
        state: String,
    ) = Url(OAuthService(OneDriveConfig(redirectUri = redirectUri)).getAuthorizationUrl(state).first)

    @Test
    fun `a redirect uri with an ampersand and a question mark is one parameter value`() {
        val redirect = "http://localhost:8080/cb?a=1&b=2#frag"
        val url = urlFor(redirect, "s")

        assertEquals(redirect, url.parameters["redirect_uri"])
        assertNull(url.parameters["b"], "the ampersand in the redirect uri must not open a parameter of its own")
        assertNull(url.parameters["a"])
        assertEquals("s", url.parameters["state"])
    }

    @Test
    fun `a state with reserved characters round-trips`() {
        val state = "a b&c=d?e+f/g#h%i"
        val url = urlFor("http://localhost:53682/callback", state)

        assertEquals(state, url.parameters["state"])
        assertNull(url.parameters["c"])
        assertNull(url.parameters["e"])
    }

    @Test
    fun `the default authorize url keeps its parameters and its order`() {
        val (text, verifier) = OAuthService(OneDriveConfig()).getAuthorizationUrl("st")
        val url = Url(text)

        assertEquals(
            listOf(
                "client_id", "response_type", "redirect_uri", "scope", "state", "code_challenge", "code_challenge_method", "prompt",
            ),
            url.parameters.names().toList(),
        )
        assertEquals("code", url.parameters["response_type"])
        assertEquals(OAuthService.SCOPES, url.parameters["scope"])
        assertEquals("S256", url.parameters["code_challenge_method"])
        assertEquals("select_account", url.parameters["prompt"])
        assertEquals(org.krost.unidrive.auth.Pkce.generateChallenge(verifier), url.parameters["code_challenge"])
        assertEquals("/consumers/oauth2/v2.0/authorize", url.encodedPath)
    }
}
