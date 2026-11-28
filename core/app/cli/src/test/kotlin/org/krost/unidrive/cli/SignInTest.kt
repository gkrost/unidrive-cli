package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.AuthRequiredException
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.NotSignedInException
import org.krost.unidrive.SignInConsole
import org.krost.unidrive.sync.FakeCloudProvider
import org.krost.unidrive.testing.RecordingSignInConsole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * gkrost/unidrive-cli#267: a CLI command signs in through the terminal when the provider's non-interactive authenticate()
 * finds no usable sign-in, as authenticate() itself did before; any other refusal is not answered with a sign-in.
 */
class SignInTest {
    private class Provider(
        private val refusal: Exception?,
    ) : CloudProvider by FakeCloudProvider() {
        val signIns = mutableListOf<SignInConsole>()

        override suspend fun authenticate() {
            refusal?.let { throw it }
        }

        override suspend fun signIn(console: SignInConsole) {
            signIns += console
            console.show("signed in")
        }
    }

    @Test
    fun a_command_without_a_stored_sign_in_signs_in_through_the_console() {
        val provider = Provider(NotSignedInException("nothing stored"))
        val console = RecordingSignInConsole()

        runBlocking { provider.authenticateOrSignIn(console) }

        assertEquals(1, provider.signIns.size)
        assertSame(console, provider.signIns.single())
        assertEquals(listOf("show:signed in"), console.events)
    }

    @Test
    fun an_expired_internxt_session_signs_in_through_the_console() {
        val provider = Provider(AuthRequiredException("auth_required: expired"))

        runBlocking { provider.authenticateOrSignIn(RecordingSignInConsole()) }

        assertEquals(1, provider.signIns.size)
    }

    @Test
    fun a_stored_sign_in_never_reaches_the_console() {
        val provider = Provider(refusal = null)

        runBlocking { provider.authenticateOrSignIn(RecordingSignInConsole()) }

        assertEquals(emptyList(), provider.signIns)
    }

    @Test
    fun a_refused_sign_in_is_not_answered_with_a_new_one() {
        val provider = Provider(AuthenticationException("revoked"))

        assertFailsWith<AuthenticationException> { runBlocking { provider.authenticateOrSignIn(RecordingSignInConsole()) } }

        assertEquals(emptyList(), provider.signIns)
    }
}
