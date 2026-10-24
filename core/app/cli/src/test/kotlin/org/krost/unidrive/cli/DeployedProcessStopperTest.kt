package org.krost.unidrive.cli

import org.krost.unidrive.sync.ProcessFacts
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * archive#1194: deploy stops the engine processes that hold a profile by their `.lock.pid` sidecar, gracefully for a
 * daemon, forcefully for anything else — because `ProcessHandle`'s command line reads empty for them on Windows
 * and the old kill step matched nothing.
 */
class DeployedProcessStopperTest {
    private lateinit var configDir: Path
    private val shutdownAsked = mutableListOf<String>()
    private val destroyed = mutableListOf<Long>()
    private val alivePids = mutableSetOf<Long>()
    private val daemonPids = mutableMapOf<String, Long>()

    @BeforeTest
    fun setUp() {
        configDir = Files.createTempDirectory("unidrive-deploy-stopper")
    }

    @AfterTest
    fun tearDown() {
        configDir.toFile().deleteRecursively()
    }

    private fun profile(
        name: String,
        pid: Long?,
        mode: String? = "daemon",
    ): Path {
        val dir = Files.createDirectories(configDir.resolve(name))
        if (pid != null) daemonPids[name] = pid
        if (pid != null) Files.writeString(dir.resolve(".lock.pid"), if (mode == null) "$pid" else "$pid $mode")
        return dir
    }

    private fun stopper(): DeployedProcessStopper =
        DeployedProcessStopper(
            profileDirs = { Files.list(configDir).use { it.filter { f -> Files.isDirectory(f) }.toList() } },
            holderAlive = { pid, _ -> pid in alivePids },
            requestShutdown = { endpoint ->
                shutdownAsked += endpoint.profileName
                alivePids -= daemonPids.getValue(endpoint.profileName) // a graceful daemon exits on its own
                true
            },
            // A pid leaves when it exited on its own (graceful) or was destroyed.
            awaitExit = { pid, _ -> pid !in alivePids || pid in destroyed },
            destroyForcibly = { pid ->
                destroyed += pid
            },
        )

    @Test
    fun `a live daemon is asked to stop over IPC and is not destroyed when it exits`() {
        profile("ix", 111)
        alivePids += 111

        val lines = stopper().stopAll()

        assertEquals(listOf("ix"), shutdownAsked)
        assertTrue(destroyed.isEmpty(), "a graceful stop must not destroy")
        assertEquals(1, lines.size)
        assertTrue("stopped the daemon of 'ix' (pid 111) over IPC" in lines.single(), lines.single())
    }

    @Test
    fun `waiting for a live holder times out as a false result`() {
        assertEquals(false, awaitDeployedProcessExit(ProcessHandle.current(), 1, java.util.concurrent.CompletableFuture()))
    }

    @Test
    fun `a daemon that outlives the graceful deadline is destroyed`() {
        profile("ix", 222)
        alivePids += 222
        // awaitExit stays false for 222 even after destroy: the process clings.

        val lines = DeployedProcessStopper(
            profileDirs = { listOf(configDir.resolve("ix")) },
            holderAlive = { pid, _ -> pid in alivePids },
            requestShutdown = { true },
            awaitExit = { _, _ -> false },
            destroyForcibly = { destroyed += it },
        ).stopAll()

        assertEquals(listOf(222L), destroyed, "the fallback destroy runs")
        assertTrue(lines.single().contains("could NOT stop the daemon of 'ix'"), lines.single())
    }

    @Test
    fun `a sync holder has no IPC listener and is destroyed at once`() {
        profile("work", 333, mode = "sync")
        alivePids += 333

        val lines = stopper().stopAll()

        assertTrue(shutdownAsked.isEmpty(), "no shutdown verb for a sync")
        assertEquals(listOf(333L), destroyed)
        assertTrue(lines.single().contains("terminated the sync of 'work'"), lines.single())
    }

    @Test
    fun `a stale lock pid and a profile without one are left alone`() {
        profile("gone", 444)
        profile("never-ran", null)

        val lines = stopper().stopAll()

        assertTrue(shutdownAsked.isEmpty() && destroyed.isEmpty())
        assertTrue(lines.isEmpty(), "nothing to report: $lines")
    }

    @Test
    fun `a stale pid reused by an unrelated process is never destroyed`() {
        profile("ix", 555)
        // The real liveness rule with a fake process table: pid 555 now runs notepad, not a unidrive/java process.
        val lookup = { pid: Long -> if (pid == 555L) ProcessFacts(startInstant = null, command = "C:\\Windows\\notepad.exe") else null }

        val lines = DeployedProcessStopper(
            profileDirs = { listOf(configDir.resolve("ix")) },
            holderAlive = { pid, pidFile -> isLockHolderAlive(pid, pidFile, lookup) },
            requestShutdown = { shutdownAsked += "ix"; true },
            awaitExit = { _, _ -> true },
            destroyForcibly = { destroyed += it },
        ).stopAll()

        assertTrue(shutdownAsked.isEmpty() && destroyed.isEmpty(), "an unrelated process must be left alone")
        assertTrue(lines.isEmpty(), "$lines")
    }

    @Test
    fun `an unreadable lock pid is reported, not acted on`() {
        val dir = Files.createDirectories(configDir.resolve("broken"))
        Files.writeString(dir.resolve(".lock.pid"), "not a pid")

        val lines = stopper().stopAll()

        assertTrue(shutdownAsked.isEmpty() && destroyed.isEmpty())
        assertTrue(lines.single().contains("unreadable .lock.pid"), lines.single())
    }
}
