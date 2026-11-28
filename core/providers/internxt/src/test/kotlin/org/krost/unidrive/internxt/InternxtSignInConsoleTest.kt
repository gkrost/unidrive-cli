package org.krost.unidrive.internxt

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.AuthRequiredException
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.testing.RecordingSignInConsole
import org.krost.unidrive.testing.TestScratch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * gkrost/unidrive-cli#267: the Internxt sign-in asks for the email and the password through the console its caller hands
 * in, never through System.console(); a console nobody can answer is refused before anything is asked.
 */
class InternxtSignInConsoleTest {
    private val dir = TestScratch.directory("internxt-signin-console")

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun provider() = InternxtProvider(InternxtConfig(credentialProtector = null, tokenPath = dir))

    @Test
    fun the_sign_in_asks_for_the_email_and_the_password_through_the_console() {
        // No password typed: the sign-in stops before it calls Internxt, after both prompts went to the console.
        val console = RecordingSignInConsole(lines = listOf("ada@example.com"))

        val e = assertFailsWith<AuthenticationException> { runBlocking { provider().signIn(console) } }

        assertEquals("No input provided", e.message)
        assertEquals(listOf("line:Internxt email: ", "secret:Password: "), console.events)
    }

    @Test
    fun a_console_nobody_can_answer_is_refused_before_anything_is_asked() {
        val console = RecordingSignInConsole(isInteractive = false)

        val e = assertFailsWith<AuthRequiredException> { runBlocking { provider().signIn(console) } }

        assertEquals(
            "auth_required: Internxt credentials are missing for profile '${dir.fileName}' and there is no interactive " +
                "terminal to sign in on. Sign in again: unidrive -p ${dir.fileName} auth",
            e.message,
        )
        assertEquals(emptyList(), console.events)
    }
}
