package org.krost.unidrive.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** A third-party child process (browser opener, keyring tool) must not inherit the vault passphrase (issue#83 E). */
class ChildProcessTest {
    @Test
    fun `a child process environment carries no vault passphrase`() {
        val builder = childProcessBuilder(listOf("true"))

        assertFalse(VAULT_PASSPHRASE_ENV in builder.environment())
    }

    @Test
    fun `an ordinary environment survives the strip`() {
        val builder =
            childProcessBuilder(listOf("true")).apply {
                environment()["PATH"] = "/usr/bin"
            }

        assertFalse(VAULT_PASSPHRASE_ENV in builder.environment())
        assertEquals("/usr/bin", builder.environment()["PATH"])
    }
}
