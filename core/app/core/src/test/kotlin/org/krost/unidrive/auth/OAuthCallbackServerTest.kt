package org.krost.unidrive.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.krost.unidrive.AuthenticationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * UD-348: tests ported from the per-provider `TokenManagerTest` copies
 * (OneDrive + HiDrive). Pre-lift the same six tests existed twice; now
 * they cover the shared parser once.
 */
class OAuthCallbackServerTest {
    private val provider = "Azure"

    @Test
    fun `parseAndValidateCallback returns code when state matches`() {
        val requestLine = "GET /?code=abc123&state=expected-state HTTP/1.1"
        val code = parseAndValidateCallback(requestLine, expectedState = "expected-state", providerLabel = provider)
        assertEquals("abc123", code)
    }

    @Test
    fun `parseAndValidateCallback rejects missing state as CSRF`() {
        val requestLine = "GET /?code=abc123 HTTP/1.1"
        val ex =
            assertFailsWith<AuthenticationException> {
                parseAndValidateCallback(requestLine, expectedState = "expected-state", providerLabel = provider)
            }
        // Error message must identify the CSRF failure mode, not the code path.
        assert(ex.message?.contains("state", ignoreCase = true) == true) {
            "Expected state-related error message, got: ${ex.message}"
        }
    }

    @Test
    fun `parseAndValidateCallback rejects mismatched state as CSRF`() {
        val requestLine = "GET /?code=abc123&state=attacker-state HTTP/1.1"
        val ex =
            assertFailsWith<AuthenticationException> {
                parseAndValidateCallback(requestLine, expectedState = "expected-state", providerLabel = provider)
            }
        assert(ex.message?.contains("state", ignoreCase = true) == true) {
            "Expected state-related error message, got: ${ex.message}"
        }
    }

    @Test
    fun `parseAndValidateCallback still rejects missing code`() {
        val requestLine = "GET /?state=expected-state HTTP/1.1"
        assertFailsWith<AuthenticationException> {
            parseAndValidateCallback(requestLine, expectedState = "expected-state", providerLabel = provider)
        }
    }

    @Test
    fun `parseAndValidateCallback still surfaces provider error param`() {
        val requestLine = "GET /?error=access_denied&error_description=User%20cancelled HTTP/1.1"
        val ex =
            assertFailsWith<AuthenticationException> {
                parseAndValidateCallback(requestLine, expectedState = "expected-state", providerLabel = provider)
            }
        assert(ex.message?.contains("access_denied") == true) {
            "Expected provider error to surface, got: ${ex.message}"
        }
    }

    @Test
    fun `parseAndValidateCallback order-independent state and code`() {
        // Real callbacks may arrive with params in any order; validator must not depend on it.
        val requestLine = "GET /?state=expected-state&code=abc123 HTTP/1.1"
        val code = parseAndValidateCallback(requestLine, expectedState = "expected-state", providerLabel = provider)
        assertEquals("abc123", code)
    }

    @Test
    fun `awaitOAuthCallback reports a busy port as an authentication error naming the port`() {
        // A port another service already listens on: the bind failure must not surface as a raw
        // BindException after the browser has opened.
        java.net.ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1")).use { busy ->
            val ex =
                assertFailsWith<AuthenticationException> {
                    kotlinx.coroutines.runBlocking {
                        awaitOAuthCallback(port = busy.localPort, expectedState = "s", providerLabel = provider)
                    }
                }
            assert("127.0.0.1:${busy.localPort}" in ex.message.orEmpty()) {
                "Expected the busy port in the message, got: ${ex.message}"
            }
            assert(provider in ex.message.orEmpty()) { "Expected the provider label, got: ${ex.message}" }
        }
    }

    @Test
    fun `parseAndValidateCallback uses providerLabel as prefix in error message`() {
        val requestLine = "GET /?error=access_denied&error_description=User%20cancelled HTTP/1.1"
        val azureEx =
            assertFailsWith<AuthenticationException> {
                parseAndValidateCallback(requestLine, expectedState = "expected-state", providerLabel = "Azure")
            }
        assert(azureEx.message?.startsWith("Azure returned error") == true) {
            "Expected Azure-prefixed error, got: ${azureEx.message}"
        }
        val hidriveEx =
            assertFailsWith<AuthenticationException> {
                parseAndValidateCallback(requestLine, expectedState = "expected-state", providerLabel = "HiDrive")
            }
        assert(hidriveEx.message?.startsWith("HiDrive returned error") == true) {
            "Expected HiDrive-prefixed error, got: ${hidriveEx.message}"
        }
    }

    // -- archive#1054 S1: the loopback listener over a real socket ---------------------------------------------------

    private fun freePort(): Int =
        java.net.ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }

    /** Connects once the listener is up (the wait binds on its own thread). */
    private fun connect(port: Int): java.net.Socket {
        var last: Exception? = null
        repeat(100) {
            try {
                return java.net.Socket("127.0.0.1", port).also { it.soTimeout = 10_000 }
            } catch (e: java.net.ConnectException) {
                last = e
                Thread.sleep(50)
            }
        }
        throw AssertionError("the listener never came up on port $port", last)
    }

    private fun java.net.Socket.send(text: String) {
        getOutputStream().write(text.toByteArray())
        getOutputStream().flush()
    }

    private fun java.net.Socket.readAll(): String = getInputStream().readBytes().decodeToString()

    @Test
    fun `an empty first connection does not end the wait for the real redirect`() {
        val port = freePort()
        kotlinx.coroutines.runBlocking {
            val waiting =
                async(Dispatchers.IO) {
                    runCatching { awaitOAuthCallback(port, "expected-state", provider, timeout = 30.seconds) }
                }
            // a port probe: connects, sends nothing, closes
            connect(port).close()
            // a speculative browser request that is not the redirect
            connect(port).use { stray ->
                stray.send("GET /favicon.ico HTTP/1.1\r\n\r\n")
                assert(stray.readAll().startsWith("HTTP/1.1 404")) { "a stray request is answered 404" }
            }
            connect(port).use { real ->
                real.send("GET /?code=abc123&state=expected-state HTTP/1.1\r\n\r\n")
                assert(real.readAll().startsWith("HTTP/1.1 200"))
            }

            assertEquals("abc123", waiting.await().getOrThrow())
        }
    }

    @Test
    fun `text in error_description is not reflected as markup in the failure page`() {
        val port = freePort()
        kotlinx.coroutines.runBlocking {
            val waiting =
                async(Dispatchers.IO) {
                    runCatching { awaitOAuthCallback(port, "expected-state", provider, timeout = 30.seconds) }
                }
            val page =
                connect(port).use { client ->
                    client.send("GET /?error=access_denied&error_description=%3Cscript%3Ealert(1)%3C/script%3E%22%27 HTTP/1.1\r\n\r\n")
                    client.readAll()
                }

            assert(page.startsWith("HTTP/1.1 400")) { page }
            assert("<script>" !in page) { "the description was reflected as markup: $page" }
            assert("&lt;script&gt;" in page) { "the description is shown, escaped: $page" }
            assertFailsWith<AuthenticationException> { waiting.await().getOrThrow() }
        }
    }

    // cli#223 item 4: the query is parsed as a query, not searched with regexes over the whole request line.
    @Test
    fun `an error= inside another parameter's value is not a provider error`() {
        val requestLine = "GET /?state=expected-state&code=abc123&note=x?error=boom HTTP/1.1"
        assertEquals("abc123", parseAndValidateCallback(requestLine, expectedState = "expected-state", providerLabel = provider))
    }

    @Test
    fun `a state= in the path is not the state parameter`() {
        val requestLine = "GET /cb&state=attacker?state=expected-state&code=abc123 HTTP/1.1"
        assertEquals("abc123", parseAndValidateCallback(requestLine, expectedState = "expected-state", providerLabel = provider))
    }

    @Test
    fun `the first of two equal parameters is the one that counts, as before`() {
        val requestLine = "GET /?state=expected-state&state=attacker&code=first&code=second HTTP/1.1"
        assertEquals("first", parseAndValidateCallback(requestLine, expectedState = "expected-state", providerLabel = provider))
    }

    @Test
    fun `the provider error is URL-decoded like its description`() {
        val requestLine = "GET /?error=access%5Fdenied&error_description=User+said%20no%2C+twice HTTP/1.1"
        val ex =
            assertFailsWith<AuthenticationException> {
                parseAndValidateCallback(requestLine, expectedState = "expected-state", providerLabel = provider)
            }
        assertEquals("$provider returned error: access_denied User said no, twice", ex.message)
    }

    @Test
    fun `code and state are URL-decoded, plus is a space and an encoded plus is a plus`() {
        val requestLine = "GET /?state=a%20b+c%2Bd&code=M.C5%2B%3D%3Dx+y HTTP/1.1"
        assertEquals("M.C5+==x y", parseAndValidateCallback(requestLine, expectedState = "a b c+d", providerLabel = provider))
    }

    @Test
    fun `empty values count as missing`() {
        assertFailsWith<AuthenticationException> {
            parseAndValidateCallback("GET /?state=expected-state&code= HTTP/1.1", expectedState = "expected-state", providerLabel = provider)
        }
        assertFailsWith<AuthenticationException> {
            parseAndValidateCallback("GET /?state=&code=abc HTTP/1.1", expectedState = "expected-state", providerLabel = provider)
        }
    }

    @Test
    fun `a request line without a query, or a broken percent escape, is an authentication error`() {
        for (requestLine in listOf("GET / HTTP/1.1", "GET", "", "GET /?state=expected-state&code=%zz HTTP/1.1")) {
            assertFailsWith<AuthenticationException>(requestLine) {
                parseAndValidateCallback(requestLine, expectedState = "expected-state", providerLabel = provider)
            }
        }
    }
}
