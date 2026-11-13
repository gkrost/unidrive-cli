package org.krost.unidrive.auth

import io.ktor.http.Parameters
import io.ktor.http.parseQueryString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.krost.unidrive.AuthenticationException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

// UD-348: shared OAuth loopback callback server for authorization-code
// flows.
//
// Pre-UD-348 OneDrive and HiDrive `TokenManager` each shipped a 35-line
// word-for-word duplicate `waitForCallback` (single-shot loopback
// `ServerSocket`, blocking `accept`, hard-coded HTML success / error
// page) plus a 25-line duplicate `parseAndValidateCallback` with the
// same load-bearing comment about provider-error-vs-state-vs-code regex
// ordering. Lifted here so any future authorization-code provider
// inherits the callback handler without copy-paste.
//
// The two pre-lift copies differed only in:
//   - the success-page H1 text ("Authentication complete!" vs.
//     "HiDrive authentication complete!"), and
//   - the provider-error prefix ("Azure returned error: …" vs.
//     "HiDrive returned error: …").
// Both are now driven by the `providerLabel` parameter.

/** Default OAuth callback timeout (5 minutes) — matches the pre-lift OneDrive + HiDrive constant. */
public val DEFAULT_OAUTH_CALLBACK_TIMEOUT: Duration = 5.minutes

/**
 * Listen on `127.0.0.1:[port]` for the OAuth provider's redirect, parse
 * the authorization code out of the GET request line, and write a small
 * HTML success / error page back to the user's browser.
 *
 * @param port               loopback port the provider's `redirect_uri` points at
 * @param expectedState      `state` parameter we generated at authorize time;
 *                           the callback's `state` MUST match (CSRF guard)
 * @param providerLabel      human-readable provider name. Drives the success-page
 *                           H1 text ("$providerLabel authentication complete!")
 *                           and the provider-error prefix
 *                           ("$providerLabel returned error: …").
 * @param timeout            how long to block on `accept` before throwing
 *                           [AuthenticationException]. Default 5 minutes.
 * @param bindFailureHint    appended to the error when the loopback port cannot be bound,
 *                           so the caller can say how to move the callback or skip the browser.
 * @return the authorization code from the callback URL.
 *
 * @throws AuthenticationException on provider error param, missing /
 *   mismatched `state` (CSRF), missing `code`, the timeout firing, or the
 *   port being unavailable.
 */
public suspend fun awaitOAuthCallback(
    port: Int,
    expectedState: String,
    providerLabel: String,
    timeout: Duration = DEFAULT_OAUTH_CALLBACK_TIMEOUT,
    bindFailureHint: String? = null,
): String = awaitOAuthCallback(port, expectedState, providerLabel, timeout, bindFailureHint, CallbackIo())

// How the listener reads a request and writes a page; a parameter so tests can make a write fail or let a silent
// connection hold its read for longer than the test waits.
internal class CallbackIo(
    val readTimeoutMs: Int = CALLBACK_READ_TIMEOUT_MS,
    val writePage: (Socket, String) -> Unit = { client, page -> client.getOutputStream().write(page.toByteArray()) },
)

// How long one connection may take to send its request line. Each connection is read on its own thread, so a
// connection that sends nothing (a browser's speculative preconnect) delays only itself, never the real redirect.
private const val CALLBACK_READ_TIMEOUT_MS = 10_000

internal suspend fun awaitOAuthCallback(
    port: Int,
    expectedState: String,
    providerLabel: String,
    timeout: Duration,
    bindFailureHint: String?,
    io: CallbackIo,
): String =
    withContext(Dispatchers.IO) {
        val server =
            try {
                ServerSocket(port, 0, InetAddress.getByName("127.0.0.1"))
            } catch (e: java.net.BindException) {
                throw AuthenticationException(
                    "Cannot listen on 127.0.0.1:$port for the $providerLabel sign-in callback " +
                        "(${e.message}); another program is probably using that port." +
                        (bindFailureHint?.let { " $it" } ?: ""),
                    e,
                )
            }
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        try {
            // archive#1054 S1: the timeout is for the whole wait, and a connection that is not the provider's redirect (a port
            // probe, a speculative browser connect, a favicon request) gets a 404 and does not end it.
            val (client, requestLine) = CallbackListener(server, io).use { it.next(deadline) }

            client.use {
                val code =
                    try {
                        parseAndValidateCallback(requestLine, expectedState, providerLabel)
                    } catch (e: AuthenticationException) {
                        // The message can carry text the sender chose (error_description): it is escaped, never reflected as markup.
                        val html =
                            "HTTP/1.1 400 Bad Request\r\nContent-Type: text/html; charset=utf-8\r\n\r\n" +
                                "<html><body><h1>Authentication failed</h1><p>${escapeHtml(e.message ?: "")}</p></body></html>"
                        writeBestEffort(io, client, html)
                        throw e
                    }
                val html =
                    "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n\r\n" +
                        "<html><body><h1>${escapeHtml(providerLabel)} authentication complete!</h1>" +
                        "<p>You can close this window.</p></body></html>"
                // The code is valid and can be redeemed only once: a browser that closed or reset the connection before
                // the page arrived must not cost the sign-in.
                writeBestEffort(io, client, html)
                code
            }
        } catch (_: SocketTimeoutException) {
            throw AuthenticationException(
                "Authentication timed out after ${timeout.inWholeMinutes} minutes. Please try again.",
            )
        } finally {
            server.close()
        }
    }

private fun writeBestEffort(
    io: CallbackIo,
    client: Socket,
    page: String,
) {
    try {
        io.writePage(client, page)
    } catch (_: IOException) {
        // the page is a courtesy to the browser; the caller still gets the code or the failure
    }
}

// Accepts connections on [server] and reads each on its own virtual thread. A connection whose request line can be the
// provider's redirect is handed to [next]; anything else is answered 404 and closed. Closing the listener closes every
// redirect that arrived but was not taken; the accepting thread ends when the caller closes [server].
private class CallbackListener(
    private val server: ServerSocket,
    private val io: CallbackIo,
) : AutoCloseable {
    private val arrived = LinkedBlockingQueue<Pair<Socket, String>>()
    private val lock = Any()
    private var closed = false

    init {
        Thread.ofVirtual().name("oauth-callback-accept").start {
            while (true) {
                val client =
                    try {
                        server.accept()
                    } catch (_: IOException) {
                        break // the server socket was closed: the wait is over
                    }
                Thread.ofVirtual().name("oauth-callback-read").start { read(client) }
            }
        }
    }

    // The next redirect; throws SocketTimeoutException once [deadlineNanos] has passed.
    fun next(deadlineNanos: Long): Pair<Socket, String> {
        val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L
        if (remainingMs <= 0) throw SocketTimeoutException()
        return arrived.poll(remainingMs, TimeUnit.MILLISECONDS) ?: throw SocketTimeoutException()
    }

    private fun read(client: Socket) {
        val requestLine =
            try {
                client.soTimeout = io.readTimeoutMs
                client.getInputStream().bufferedReader().readLine() ?: ""
            } catch (_: IOException) {
                "" // a connection that sent nothing in time, or that broke, is not the redirect
            }
        // A broken percent escape in a redirect-looking query is handed on too: the parse turns it into the failure page.
        val isCallback =
            try {
                looksLikeCallback(requestLine)
            } catch (_: AuthenticationException) {
                true
            }
        if (!isCallback) {
            runCatching { client.use { io.writePage(it, "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n") } }
            return
        }
        val taken =
            synchronized(lock) {
                if (!closed) arrived.add(client to requestLine)
                !closed
            }
        if (!taken) runCatching { client.close() }
    }

    override fun close() {
        val left =
            synchronized(lock) {
                closed = true
                buildList { arrived.drainTo(this) }
            }
        for ((client, _) in left) runCatching { client.close() }
    }
}

// A request line that can be the provider's redirect: a GET whose query names code, state or error.
private fun looksLikeCallback(requestLine: String): Boolean =
    requestLine.startsWith("GET ") &&
        callbackQuery(requestLine).let { it.contains("code") || it.contains("state") || it.contains("error") }

// The decoded query of a request line `METHOD target HTTP/x`: what follows the first `?` of the target, up to a `#`.
// Empty when the line has no target or no query. Throws AuthenticationException on a broken percent escape.
private fun callbackQuery(requestLine: String): Parameters {
    val target = requestLine.split(' ').getOrNull(1) ?: return Parameters.Empty
    val query = target.substringBefore('#').substringAfter('?', "")
    return try {
        parseQueryString(query)
    } catch (e: Exception) {
        throw AuthenticationException("Malformed OAuth callback query", e)
    }
}

private fun escapeHtml(text: String): String =
    buildString(text.length + 16) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

/**
 * Parse an HTTP callback request line and validate the OAuth state parameter.
 *
 * Order matters and is load-bearing:
 *   1. provider error (surfaces `access_denied` etc. as themselves, not as CSRF)
 *   2. state parameter (rejects CSRF before we ever look at the code)
 *   3. authorization code (happy path only after CSRF is cleared)
 *
 * @param requestLine    raw HTTP request line, e.g. `GET /?code=…&state=… HTTP/1.1`
 * @param expectedState  the `state` value we generated at authorize time
 * @param providerLabel  human-readable provider name; used as the prefix
 *                       in the provider-error message
 * @throws AuthenticationException on provider error, missing/mismatched state, or missing code.
 */
public fun parseAndValidateCallback(
    requestLine: String,
    expectedState: String,
    providerLabel: String,
): String {
    val query = callbackQuery(requestLine)

    fun param(name: String): String? = query[name]?.takeIf { it.isNotEmpty() }

    val error = param("error")
    if (error != null) {
        throw AuthenticationException("$providerLabel returned error: $error ${param("error_description") ?: ""}")
    }

    val receivedState =
        param("state")
            ?: throw AuthenticationException("Missing OAuth state parameter — possible CSRF attack")
    if (receivedState != expectedState) {
        throw AuthenticationException("OAuth state mismatch — possible CSRF attack")
    }

    return param("code")
        ?: throw AuthenticationException("No authorization code in callback: $requestLine")
}
