package org.krost.unidrive.dropbox

import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.krost.unidrive.testing.RecordingSignInConsole
import org.krost.unidrive.testing.TestScratch
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.ConnectException
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#267: the browser sign-in shows its text and opens the browser through the console its caller hands
 * in, the same lines the CLI printed before, and prints nothing itself.
 */
class DropboxSignInConsoleTest {
    private val dir = TestScratch.directory("dropbox-signin-console")

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    // The user in the browser: Dropbox redirects to the loopback with the code and the state of the authorization url.
    // The listener binds after the browser is opened, so the redirect retries until it is up.
    private fun redirectTo(authorizeUrl: String) {
        val url = Url(authorizeUrl)
        val callback = "${url.parameters["redirect_uri"]}?code=the-code&state=${url.parameters["state"]}"
        Thread {
            val client = HttpClient.newHttpClient()
            repeat(100) {
                try {
                    client.send(HttpRequest.newBuilder(URI.create(callback)).build(), HttpResponse.BodyHandlers.discarding())
                    return@Thread
                } catch (_: ConnectException) {
                    Thread.sleep(50)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    @Test
    fun the_browser_sign_in_shows_its_text_and_opens_the_browser_through_the_console_only() {
        val port = ServerSocket(0).use { it.localPort }
        val fake = FakeDropbox()
        fake.json("oauth2/token", """{"access_token":"${"a".repeat(40)}","expires_in":14400,"refresh_token":"r","account_id":"dbid:a"}""")
        val provider = DropboxProvider(DropboxConfig(appKey = "key", tokenPath = dir, oauthCallbackPort = port), fake.engine)
        val console = RecordingSignInConsole(onOpenBrowser = ::redirectTo)
        val originalOut = System.out
        val out = ByteArrayOutputStream()
        System.setOut(PrintStream(out, true, Charsets.UTF_8))
        try {
            runBlocking { withTimeout(30_000) { provider.signIn(console) } }
        } finally {
            System.setOut(originalOut)
            provider.close()
        }

        val opened = console.events.single { it.startsWith("open:") }.removePrefix("open:")
        assertEquals(
            listOf(
                "show:Opening browser for Dropbox sign-in...",
                "show:Redirect URI (must be registered for this app key exactly): http://localhost:$port/callback",
                "show:URL: $opened",
                "show:Waiting up to 5 minutes for the sign-in...",
                "open:$opened",
            ),
            console.events,
        )
        assertTrue(provider.isAuthenticated)
        // The test logger writes the HTTP request lines to stdout; none of the sign-in's own lines may go there.
        val printed = out.toString(Charsets.UTF_8)
        assertTrue(console.events.none { it.substringAfter(':').isNotEmpty() && it.substringAfter(':') in printed }, printed)
    }
}
