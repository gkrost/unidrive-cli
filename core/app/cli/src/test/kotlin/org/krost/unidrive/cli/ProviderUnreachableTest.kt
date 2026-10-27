package org.krost.unidrive.cli

import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.ProviderException
import org.krost.unidrive.TransientNetworkException
import java.io.IOException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import java.nio.file.AccessDeniedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The "provider could not be reached" classifier the sign-in steps and `auth check` share (#143). */
class ProviderUnreachableTest {
    private fun wrapped(
        root: Throwable,
        levels: Int,
    ): Throwable = (1..levels).fold(root) { cause, level -> RuntimeException("level $level", cause) }

    @Test
    fun `the network failure is found in the chain and named, the outer exception is kept as the cause`() {
        val root = UnresolvedAddressException()
        val outer = wrapped(root, 3)

        assertSame(root, unreachableCause(outer))
        val refusal = unreachableRefusal(outer)!!
        assertEquals("network_error", refusal.token)
        assertEquals("the provider could not be reached: UnresolvedAddressException", refusal.message)
        assertSame(outer, refusal.cause)
    }

    @Test
    fun `a transient network failure is unreachable, a provider answer is not, whatever lies under it`() {
        assertTrue(unreachableCause(TransientNetworkException("blip")) is TransientNetworkException)
        assertNull(unreachableCause(ProviderException("forbidden", IOException("reset"), httpStatus = 403)))
        assertNull(unreachableCause(wrapped(ProviderException("bad gateway", UnknownHostException("x"), httpStatus = 502), 1)))
        // No status: the provider gave no answer, so what it wraps decides.
        assertTrue(unreachableCause(ProviderException("no answer", UnknownHostException("x"))) is UnknownHostException)
        assertNull(unreachableCause(ProviderException("no answer")))
        assertNull(unreachableCause(AuthenticationException("revoked")))
    }

    @Test
    fun `a local file failure is not the network, neither is an exception without a network cause`() {
        assertNull(unreachableCause(AccessDeniedException("/x/token.json")))
        assertNull(unreachableCause(wrapped(AccessDeniedException("/x/token.json"), 2)))
        assertNull(unreachableCause(kotlin.io.AccessDeniedException(java.io.File("/x/token.json"))))
        assertNull(unreachableCause(IllegalStateException("odd")))
        assertNull(unreachableRefusal(IllegalStateException("odd")))
    }

    @Test
    fun `the chain is followed eight causes deep and no further, and a circular chain ends`() {
        assertTrue(unreachableCause(wrapped(UnknownHostException("x"), 8)) is UnknownHostException)
        assertNull(unreachableCause(wrapped(UnknownHostException("x"), 9)))

        val a = IllegalStateException("a")
        val b = IllegalStateException("b", a)
        a.initCause(b)
        assertNull(unreachableCause(b))
    }
}
