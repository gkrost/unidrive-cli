package org.krost.unidrive.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A third-party child process (browser opener, keyring tool) must not inherit this process's passphrases
 * (issue#83 E for the vault's, #785 for the X-tra's). A ProcessBuilder inherits the process environment, which a
 * test cannot seed, so the strip itself is driven through [stripPassphrases]; the builder test below pins that
 * [childProcessBuilder] applied it.
 */
class ChildProcessTest {
    @Test
    fun `the strip removes both passphrases and keeps the rest`() {
        val env =
            mutableMapOf(
                VAULT_PASSPHRASE_ENV to "secret",
                XTRA_PASSPHRASE_ENV to "secret",
                "PATH" to "/usr/bin",
            )

        env.stripPassphrases()

        assertFalse(VAULT_PASSPHRASE_ENV in env)
        assertFalse(XTRA_PASSPHRASE_ENV in env)
        assertEquals("/usr/bin", env["PATH"])
    }

    @Test
    fun `a child process environment carries no passphrase`() {
        val builder = childProcessBuilder(listOf("true"))

        assertFalse(VAULT_PASSPHRASE_ENV in builder.environment())
        assertFalse(XTRA_PASSPHRASE_ENV in builder.environment())
    }

    @Test
    fun `an ordinary environment survives the strip`() {
        val builder =
            childProcessBuilder(listOf("true")).apply {
                environment()["PATH"] = "/usr/bin"
            }

        assertFalse(VAULT_PASSPHRASE_ENV in builder.environment())
        assertFalse(XTRA_PASSPHRASE_ENV in builder.environment())
        assertEquals("/usr/bin", builder.environment()["PATH"])
    }
}
