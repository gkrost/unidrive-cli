package org.krost.unidrive.sync

import org.krost.unidrive.io.Os
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#794: UNIDRIVE_IPC_DIR names the socket folder, for a user whose temp folder is cleaned. The file name
 * of the socket, and with it the hydration cache folder named after it, does not depend on the folder that was chosen.
 */
class IpcSocketDirOverrideTest {
    private val windows = Os.isWindows()

    @Test
    fun `an unset or blank variable names no folder`() {
        assertNull(IpcSocketDir.overrideDirectory(null))
        assertNull(IpcSocketDir.overrideDirectory(""))
        assertNull(IpcSocketDir.overrideDirectory("   "))
    }

    @Test
    fun `a relative folder is refused with a message`() {
        val e = assertFailsWith<IllegalStateException> { IpcSocketDir.overrideDirectory("ipc/sockets") }
        assertTrue("UNIDRIVE_IPC_DIR" in e.message!!, e.message)
    }

    @Test
    fun `the socket of a profile lies in the folder the variable names, which is made private`() {
        val tmp = TestScratch.directory("ipc-override-tmp")
        val chosen = TestScratch.directory("ipc-override-dir").resolve("sockets")

        val path = IpcServer.defaultSocketPath("work", tmp, windows, override = chosen)

        assertEquals(chosen.resolve("unidrive-work.sock"), path)
        assertTrue(Files.isDirectory(chosen), "the folder was made")
    }

    @Test
    fun `a long profile name keeps the on-disk name the default folder gives it, whatever folder was chosen`() {
        val tmp = TestScratch.directory("ipc-override-tmp")
        val chosen = TestScratch.directory("ipc-override-dir")
        val long = "p".repeat(70)

        val chosenPath = IpcServer.defaultSocketPath(long, tmp, windows, override = chosen)
        val defaultPath = IpcServer.defaultSocketPath(long, tmp, windows, override = null)

        // The cache folder of the profile is named by canonicalDiskName(profile, default folder); the socket file wears the same
        // name, so choosing another socket folder cannot move the cache.
        assertEquals(defaultPath.fileName, chosenPath.fileName)
        assertEquals(chosen, chosenPath.parent)
    }

    @Test
    fun `a folder that makes the socket path too long for a unix socket is refused`() {
        val tmp = TestScratch.directory("ipc-override-tmp")
        val deep = TestScratch.directory("ipc-override-dir").resolve("a".repeat(60)).resolve("b".repeat(60))

        val e = assertFailsWith<IllegalStateException> { IpcServer.defaultSocketPath("work", tmp, windows, override = deep) }

        assertTrue("UNIDRIVE_IPC_DIR" in e.message!!, e.message)
        assertTrue("107" in e.message!!, e.message)
    }
}
