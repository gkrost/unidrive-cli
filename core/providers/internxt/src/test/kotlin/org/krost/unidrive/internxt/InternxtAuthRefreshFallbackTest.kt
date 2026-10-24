package org.krost.unidrive.internxt

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.TransientNetworkException
import org.krost.unidrive.internxt.model.InternxtCredentials
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * archive#1066: inside the pre-expiry refresh window (24 h, or a quarter of the token's lifetime) a refresh answered with
 * 408/429/5xx failed every call, bridge calls included, although the stored JWT was still valid for hours. A
 * transient refresh failure now leaves the call on the stored credentials, and the next call tries the refresh
 * again. An expired token and a forced (reactive 401) refresh still fail.
 */
class InternxtAuthRefreshFallbackTest {
    /** The refresh route answers from [answers], one per call; the last answer repeats. */
    private class ScriptedAuthService(
        config: InternxtConfig,
        private val answers: List<() -> String>,
    ) : AuthService(config) {
        val calls = AtomicInteger(0)

        override suspend fun fetchRefreshedJwt(currentJwt: String): String = answers[minOf(calls.getAndIncrement(), answers.size - 1)]()
    }

    private val transient = { throw TransientNetworkException("Token refresh failed (HTTP 502): bad gateway") }

    private fun jwtWithExp(expEpochSec: Long): String {
        val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        return "$header.${encoder.encodeToString("""{"exp":$expEpochSec}""".toByteArray())}.fake-signature"
    }

    private fun inTwoHours() = System.currentTimeMillis() / 1000 + 2 * 3600

    // A refreshed token: outside the 24 h window, so it is not refreshed again.
    private val refreshed = jwtWithExp(System.currentTimeMillis() / 1000 + 10L * 24 * 3600)

    private fun withAuth(
        storedJwt: String,
        answers: List<() -> String>,
        block: suspend (ScriptedAuthService) -> Unit,
    ) = runBlocking {
        val tmp: Path = Files.createTempDirectory("internxt-auth-fallback-")
        try {
            val creds = InternxtCredentials(jwt = storedJwt, mnemonic = "test-mnemonic", rootFolderId = "test-root", email = "test@example.invalid")
            Files.writeString(tmp.resolve("credentials.json"), Json.encodeToString(InternxtCredentials.serializer(), creds))
            val auth = ScriptedAuthService(InternxtConfig(credentialProtector = null, tokenPath = tmp), answers)
            auth.initialize()
            block(auth)
        } finally {
            Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `a transient refresh failure leaves a still valid token in use`() {
        val stored = jwtWithExp(inTwoHours())
        withAuth(stored, listOf(transient)) { auth ->
            val creds = auth.getValidCredentials()
            assertEquals(stored, creds.jwt)
            assertEquals(1, auth.calls.get(), "the refresh was tried")
        }
    }

    @Test
    fun `the refresh is tried again on the next call and its result is used`() {
        val stored = jwtWithExp(inTwoHours())
        withAuth(stored, listOf(transient, { refreshed })) { auth ->
            assertEquals(stored, auth.getValidCredentials().jwt)
            assertEquals(refreshed, auth.getValidCredentials().jwt)
            assertEquals(2, auth.calls.get())
        }
    }

    @Test
    fun `an expired token still fails when the refresh fails transiently`() {
        withAuth(jwtWithExp(1L), listOf(transient)) { auth ->
            assertFailsWith<TransientNetworkException> { auth.getValidCredentials() }
        }
    }

    @Test
    fun `a forced refresh still fails when it fails transiently`() {
        withAuth(jwtWithExp(inTwoHours()), listOf(transient)) { auth ->
            assertFailsWith<TransientNetworkException> { auth.getValidCredentials(forceRefresh = true) }
        }
    }

    @Test
    fun `a rejected refresh is still an authentication failure`() {
        withAuth(jwtWithExp(inTwoHours()), listOf({ throw AuthenticationException("Token refresh failed (HTTP 401)") })) { auth ->
            assertFailsWith<AuthenticationException> { auth.getValidCredentials() }
        }
    }

    @Test
    fun `the fallback is logged once per outage`() {
        val logger = LoggerFactory.getLogger(AuthService::class.java) as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try {
            withAuth(jwtWithExp(inTwoHours()), listOf(transient, transient, { refreshed })) { auth ->
                repeat(2) { auth.getValidCredentials() }
                assertEquals(1, appender.list.count { it.level == Level.WARN }, "one WARN for the outage, not one per call")
                assertEquals(refreshed, auth.getValidCredentials().jwt, "the refresh works again")
                assertEquals(refreshed, auth.getValidCredentials().jwt)
                assertEquals(3, auth.calls.get(), "the fresh token is outside the window: no further refresh")
            }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
