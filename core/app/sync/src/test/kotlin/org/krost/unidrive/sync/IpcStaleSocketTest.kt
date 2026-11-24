package org.krost.unidrive.sync

import org.junit.Assume.assumeTrue
import org.krost.unidrive.testing.TestScratch
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** gkrost/unidrive-cli#926 S5: a socket file is removed as stale only when nothing changed between the probe and the delete. */
class IpcStaleSocketTest {
    private val dir: Path = TestScratch.directory("ipc-stale")

    // A socket file whose listener is gone: what a hard-killed daemon leaves.
    private fun staleSocket(name: String): Path {
        val path = dir.resolve(name)
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { it.bind(UnixDomainSocketAddress.of(path)) }
        assertTrue(Files.exists(path), "precondition: stale socket file left")
        return path
    }

    @Test
    fun a_stale_socket_is_removed() {
        val path = staleSocket("s.sock")
        assertEquals(IpcStaleSocket.Outcome.REMOVED, IpcStaleSocket.removeIfStale(path))
        assertTrue(Files.notExists(path))
    }

    @Test
    fun a_socket_bound_again_after_the_probe_is_not_deleted() {
        assumeTrue("unix file identity only", FileSystems.getDefault().supportedFileAttributeViews().contains("unix"))
        val path = staleSocket("s.sock")
        val successor = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        try {
            // A new daemon removes the stale file and binds its own between the failed connect and the delete.
            IpcStaleSocket.removeIfStale(path) {
                Files.delete(path)
                successor.bind(UnixDomainSocketAddress.of(path))
            }

            assertTrue(Files.exists(path), "the successor's socket file is still there")
            SocketChannel.open(UnixDomainSocketAddress.of(path)).close() // and reachable
        } finally {
            successor.close()
        }
    }

    @Test
    fun a_socket_is_not_deleted_on_a_connect_error_other_than_refused() {
        assumeTrue("POSIX permissions only", FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
        assumeTrue("root ignores the mode", System.getProperty("user.name") != "root")
        val path = staleSocket("s.sock")
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("---------")) // connect: EACCES, not ECONNREFUSED

        IpcServer.removeStaleSocketFiles(path)

        assertTrue(Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS), "a socket it cannot classify is left alone")
    }
}
