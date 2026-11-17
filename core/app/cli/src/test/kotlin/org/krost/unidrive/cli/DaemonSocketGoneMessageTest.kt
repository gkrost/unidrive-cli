package org.krost.unidrive.cli

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/** gkrost/unidrive-cli#794: `daemon status` of a running daemon whose socket file is gone says so, and what to do. */
class DaemonSocketGoneMessageTest {
    private val message = daemonSocketGoneMessage(4242, Path.of("/run/user/1000/unidrive-work.sock"))

    @Test
    fun `it names the running daemon and the missing socket`() {
        assertTrue("pid 4242" in message, message)
        assertTrue("unidrive-work.sock" in message, message)
        assertTrue("is running" in message, message)
    }

    @Test
    fun `it says the daemon binds the file again, and what to do when it does not`() {
        assertTrue("within 5 s" in message, message)
        assertTrue("restart the daemon" in message, message)
    }
}
