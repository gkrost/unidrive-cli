package org.krost.unidrive.cli

import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcAuthClient
import org.krost.unidrive.sync.IpcEndpoint
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A real `daemon run` process, started from the shipped jar on a scratch localfs profile, must answer
 * `daemon.status` and then stop through `daemon stop` without being terminated: the process exits on its
 * own, and its lock, socket and JVM are gone afterwards. The in-process tests (DaemonStopTest) cannot see
 * a daemon whose accept loop or shutdown hook keeps the JVM alive; this one can, on Linux and on the
 * Windows CI job alike. Every path the child touches (config, logs, caches, temp, IPC socket) is in the
 * scratch folder.
 */
class DaemonRoundTripTest {
    // A per-run name: on Linux the socket goes to /run/user/<uid> when that exists, outside the scratch folder.
    // Short names throughout: an AF_UNIX path is limited to about 100 characters.
    private val profile = "rt${ProcessHandle.current().pid()}"

    @Test
    fun `daemon run answers daemon_status and exits on daemon stop`() {
        val scratch = Files.createTempDirectory(shortTempBase(), "udrt")
        val configDir = Files.createDirectories(scratch.resolve("config"))
        val profileDir = configDir.resolve(profile)
        val daemonLog = scratch.resolve("daemon.out").toFile()
        var daemon: Process? = null
        try {
            Files.writeString(
                configDir.resolve("config.toml"),
                """
                [general]
                default_profile = "$profile"

                [providers.$profile]
                type = "localfs"
                mode = "mount"
                sync_root = "${toml(Files.createDirectories(scratch.resolve("mount-root")))}"
                root_path = "${toml(Files.createDirectories(scratch.resolve("remote")))}"
                """.trimIndent() + "\n",
            )

            daemon = cli(scratch, configDir, "daemon", "run", "--poll-interval", "0")
                .redirectErrorStream(true)
                .redirectOutput(daemonLog)
                .start()

            val socketPath = awaitReady(daemon, daemonLog)
            val status = awaitStatus(IpcEndpoint(socketPath, profileDir, profile), daemon, daemonLog)
            assertTrue(status.contains("\"ok\":true"), "daemon.status: $status")
            val descendants = daemon.descendants().toList()

            val stop = cli(scratch, configDir, "daemon", "stop").redirectErrorStream(true).start()
            assertTrue(stop.waitFor(STOP_BOUND_S, TimeUnit.SECONDS), "daemon stop must return within ${STOP_BOUND_S}s")
            val stopOutput = stop.inputStream.bufferedReader().readText()
            assertEquals(0, stop.exitValue(), "daemon stop: $stopOutput")
            assertTrue(
                stopOutput.contains("daemon for profile '$profile' stopped"),
                "the daemon must stop on daemon.shutdown, not be terminated: $stopOutput\n--- daemon ---\n${daemonLog.readText()}",
            )

            assertTrue(daemon.waitFor(STOP_BOUND_S, TimeUnit.SECONDS), "the daemon process must exit: ${daemonLog.readText()}")
            assertEquals(0, daemon.exitValue(), "a clean stop exits 0: ${daemonLog.readText()}")
            assertTrue(descendants.none { it.isAlive }, "no child process of the daemon outlives it")
            assertFalse(Files.exists(socketPath), "the socket is removed")
            assertFalse(Files.exists(profileDir.resolve(".lock.pid")), "the lock-pid file is removed")
            FileChannel.open(profileDir.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                assertNotNull(channel.tryLock(), "the profile lock is released")?.release()
            }
        } finally {
            daemon?.let { process ->
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
                process.waitFor(10, TimeUnit.SECONDS)
            }
            runCatching { scratch.toFile().deleteRecursively() }
        }
    }

    /** The daemon prints `daemon ready, pid <pid>, socket <path>` once the socket is bound. */
    private fun awaitReady(daemon: Process, log: File): Path {
        val deadline = System.currentTimeMillis() + READY_BOUND_MS
        while (System.currentTimeMillis() < deadline) {
            if (!daemon.isAlive) fail("daemon run exited ${daemon.exitValue()} before it was ready: ${log.readText()}")
            val ready = log.readLines().firstOrNull { it.startsWith("daemon ready, pid ") }
            if (ready != null) return Path.of(ready.substringAfter(", socket ").trim())
            Thread.sleep(100)
        }
        fail("daemon run was not ready within ${READY_BOUND_MS}ms: ${log.readText()}")
    }

    private fun awaitStatus(endpoint: IpcEndpoint, daemon: Process, log: File): String {
        val deadline = System.currentTimeMillis() + READY_BOUND_MS
        var last: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            if (!daemon.isAlive) fail("daemon run exited ${daemon.exitValue()}: ${log.readText()}")
            try {
                return requestStatus(endpoint)
            } catch (e: java.io.IOException) {
                last = e
                Thread.sleep(100)
            }
        }
        fail("daemon.status got no reply within ${READY_BOUND_MS}ms ($last): ${log.readText()}")
    }

    // A read-scope connection, as `daemon status` opens it.
    private fun requestStatus(endpoint: IpcEndpoint): String =
        IpcAuthClient.connect(endpoint, IpcAuth.Scope.READ).use { channel ->
            channel.write(ByteBuffer.wrap(("""{"verb":"daemon.status"}""" + "\n").toByteArray()))
            val reply = StringBuilder()
            val buf = ByteBuffer.allocate(8192)
            while (!reply.contains('\n')) {
                buf.clear()
                if (channel.read(buf) <= 0) break
                reply.append(String(buf.array(), 0, buf.position()))
            }
            reply.toString().substringBefore('\n')
        }

    private fun cli(scratch: Path, configDir: Path, vararg args: String): ProcessBuilder {
        val home = Files.createDirectories(scratch.resolve("home"))
        val tmp = Files.createDirectories(scratch.resolve("t"))
        val builder = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "--enable-native-access=ALL-UNNAMED",
            "-Djava.io.tmpdir=$tmp",
            "-Duser.home=$home",
            "-jar", System.getProperty("unidrive.runtime.test.jar"),
            *args,
        )
        val env = builder.environment()
        env.keys.removeIf { it.startsWith("UNIDRIVE_") || it.startsWith("XDG_") }
        env["UNIDRIVE_CONFIG_DIR"] = configDir.toString()
        env["HOME"] = home.toString()
        env["LOCALAPPDATA"] = Files.createDirectories(scratch.resolve("localappdata")).toString()
        env["APPDATA"] = Files.createDirectories(scratch.resolve("appdata")).toString()
        env["XDG_CACHE_HOME"] = Files.createDirectories(scratch.resolve("cache")).toString()
        env["XDG_DATA_HOME"] = Files.createDirectories(scratch.resolve("data")).toString()
        env["TEMP"] = tmp.toString()
        env["TMP"] = tmp.toString()
        env["TMPDIR"] = tmp.toString()
        return builder
    }

    // The test JVM's java.io.tmpdir is build/tmp/test, deep enough on CI to push the socket path past the limit.
    private fun shortTempBase(): Path =
        if (File.separatorChar == '\\') {
            Path.of(System.getenv("TEMP") ?: System.getProperty("java.io.tmpdir"))
        } else {
            Path.of("/tmp")
        }

    private fun toml(p: Path) = p.toAbsolutePath().toString().replace('\\', '/')

    private companion object {
        const val READY_BOUND_MS = 60_000L
        const val STOP_BOUND_S = 30L
    }
}
