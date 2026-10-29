package org.krost.unidrive.cli

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * archive#142: auto-spawn of the daemon on the first client connection. The spawner probes before it
 * believes (a kill -9'd daemon leaves a stale socket file), builds `daemon run <profile>` for the
 * running java and jar, detaches, and waits for the socket; every failure degrades to the client's
 * own operator-facing error, never an exception out of [DaemonAutospawn.ensureDaemonRunning].
 */
class DaemonAutospawnTest {
    @Test
    fun `an authenticated daemon that never answers status is not ready`() =
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            val dir = Files.createTempDirectory("autospawn-silent")
            val socket = dir.resolve("daemon.sock")
            val auth = org.krost.unidrive.sync.IpcAuth.issue(dir, "p", "test")
            val server = org.krost.unidrive.sync.IpcServer(socketPath = socket, auth = auth)
            val serverJob = kotlinx.coroutines.Job()
            val serverScope = kotlinx.coroutines.CoroutineScope(coroutineContext + serverJob)
            server.registerHandler("daemon.status") { _, _ ->
                kotlinx.coroutines.delay(60_000)
                """{"ok":true}"""
            }
            try {
                server.start(serverScope)
                assertFalse(DaemonAutospawn.daemonAnswers("p", socket, dir, replyTimeoutMs = 100))
            } finally {
                server.close()
                serverJob.cancel()
                dir.toFile().deleteRecursively()
            }
        }

    @Test
    fun `a daemon that already answers is not spawned again`() {
        val spawnedCommands = mutableListOf<List<String>>()
        val ok =
            DaemonAutospawn.ensureDaemonRunning(
                profileName = "p",
                configDir = Files.createTempDirectory("autospawn-alive"),
                probe = { _, _ -> true },
                locateJar = { error("a live daemon must not need the jar") },
                spawn = { command, _ -> spawnedCommands.add(command) },
            )
        assertTrue(ok)
        assertTrue(spawnedCommands.isEmpty(), "nothing is spawned over a live daemon")
    }

    @Test
    fun `a stopped daemon is spawned as java -jar daemon run profile and waited for`() {
        val tempDir = Files.createTempDirectory("autospawn-stopped")
        var spawnCalls = 0
        var seenCommand: List<String>? = null
        val ok =
            DaemonAutospawn.ensureDaemonRunning(
                profileName = "work_mount",
                configDir = tempDir,
                probe = { name, _ ->
                    // Answers only after the first spawn: the probe is what the spawner waits on.
                    spawnCalls > 0 && name == "work_mount"
                },
                locateJar = { Path.of("/opt/unidrive/unidrive.jar") },
                spawn = { command, logFile ->
                    spawnCalls++
                    seenCommand = command
                    assertEquals(tempDir.resolve("daemon-spawn.log"), logFile, "the child's output goes under the profile's config folder")
                },
            )
        assertTrue(ok, "the spawner reports a serving daemon once the probe turns true")
        assertEquals(1, spawnCalls)
        val command = seenCommand!!
        assertTrue(command[0].endsWith("java") || command[0].endsWith("java.exe"), "the child runs this JVM's java")
        assertEquals(
            Path.of("/opt/unidrive/unidrive.jar").toString(),
            command[command.indexOf("-jar") + 1],
            "the child runs this jar",
        )
        assertEquals(
            listOf("--config-dir", tempDir.toAbsolutePath().parent.toString(), "daemon", "run", "work_mount"),
            command.takeLast(5),
            "the daemon uses the client's configuration root",
        )
        assertTrue(command.contains("-Xmx2g"), "the child carries the heap cap (no UNIDRIVE_XMX in the test environment)")
        assertTrue(command.any { it.startsWith("-Xlog:gc*:file=") }, "the spawned child is always a daemon: the bounded GC log rides")
    }

    @Test
    fun `the spawned daemon carries the launchers' flag set from the single source`() {
        val jarDir = Files.createTempDirectory("autospawn-flags")
        val diag = Files.createTempDirectory("autospawn-flags-diag")
        Files.write(
            jarDir.resolve("jvm-flags.txt"),
            listOf(
                "# comment lines and blanks are skipped",
                "-Dstdout.encoding=UTF-8",
                "",
                "-XX:+ExitOnOutOfMemoryError # a trailing comment too",
            ),
        )
        val command =
            DaemonAutospawn.spawnCommand(
                javaBin = Path.of("java"),
                jar = jarDir.resolve("unidrive.jar"),
                configRoot = Files.createTempDirectory("autospawn-flags-config"),
                profileName = "p",
                env = { name -> if (name == "UNIDRIVE_DIAG_DIR") diag.toString() else null },
                props = { name ->
                    mapOf("user.language" to "de", "user.country" to "LU", "java.io.tmpdir" to "/tmp/x")[name]
                },
            )
        val sep = diag.fileSystem.separator
        val diagPath = diag.toString().removeSuffix(sep) + sep
        assertTrue(command.containsAll(listOf("-Dstdout.encoding=UTF-8", "-XX:+ExitOnOutOfMemoryError")), "the static flags come from jvm-flags.txt beside the jar")
        assertTrue(command.contains("-Duser.language=de"))
        assertTrue(command.contains("-Duser.country=LU"))
        assertTrue(command.contains("-Djdk.net.unixdomain.tmpdir=/tmp/x"))
        assertTrue(command.contains("-XX:ErrorFile=${diagPath}hs_err_pid%p.log"), "fatal-crash logs are pinned into the diagnostics dir")
        assertTrue(command.contains("-XX:HeapDumpPath=${diagPath}heap-p.hprof"), "the dump is the profile's own fixed file: HotSpot does not overwrite it, so an OOM restart loop stacks no fresh ~2 GB hprof per restart")
        assertTrue(command.contains("-Xlog:gc*:file=${diagPath}gc-p.log:time,uptime:filecount=5,filesize=10m"), "the GC log is the profile's own")
        assertTrue(Files.isDirectory(diag), "the diagnostics dir exists before the child starts (the JVM skips dumps into missing dirs)")
    }

    // Two daemons (one per profile) on one machine must not share a GC log: the second JVM cannot
    // rotate a file the first one holds open ("Could not rename log file ... Permission denied").
    @Test
    fun `two profiles give two different GC log paths`() {
        val diag = Files.createTempDirectory("autospawn-gc-diag")
        fun gcFlag(profile: String): String =
            DaemonAutospawn.spawnCommand(
                javaBin = Path.of("java"),
                jar = Files.createTempDirectory("autospawn-gc").resolve("unidrive.jar"),
                configRoot = Files.createTempDirectory("autospawn-gc-config"),
                profileName = profile,
                env = { name -> if (name == "UNIDRIVE_DIAG_DIR") diag.toString() else null },
                props = { null },
            ).single { it.startsWith("-Xlog:gc*:file=") }
        val sep = diag.fileSystem.separator
        val diagPath = diag.toString().removeSuffix(sep) + sep
        assertEquals("-Xlog:gc*:file=${diagPath}gc-a.log:time,uptime:filecount=5,filesize=10m", gcFlag("a"))
        assertEquals("-Xlog:gc*:file=${diagPath}gc-b.log:time,uptime:filecount=5,filesize=10m", gcFlag("b"))
    }

    @Test
    fun `the GC log file name is the profile's, and a name that is not file-safe gives the default one`() {
        assertEquals("gc-work_mount-2.log", DaemonAutospawn.gcLogFileName("work_mount-2"))
        for (bad in listOf("", "a b", "../x", "a:b", "a\\b")) {
            assertEquals("gc-default.log", DaemonAutospawn.gcLogFileName(bad), "'$bad' is never put into the file name")
        }
    }

    // One FIXED dump file per profile: HotSpot does not overwrite an existing dump, so an OOM restart
    // loop cannot stack a fresh ~2 GB hprof on every restart. A name that is not file-safe gives the
    // default one, as with the GC log.
    @Test
    fun `the heap dump file name is the profile's, and a name that is not file-safe gives the default one`() {
        assertEquals("heap-work_mount-2.hprof", heapDumpFileName("work_mount-2"))
        for (bad in listOf("", "a b", "../x", "a:b", "a\\b")) {
            assertEquals("heap-default.hprof", heapDumpFileName(bad), "'$bad' is never put into the file name")
        }
    }

    @Test
    fun `UNIDRIVE_XMX and UNIDRIVE_LOCALE steer the spawned daemon`() {
        val diag = Files.createTempDirectory("autospawn-env-diag")
        val command =
            DaemonAutospawn.spawnCommand(
                javaBin = Path.of("java"),
                jar = Files.createTempDirectory("autospawn-env").resolve("unidrive.jar"),
                configRoot = Files.createTempDirectory("autospawn-env-config"),
                profileName = "p",
                env = { name ->
                    mapOf("UNIDRIVE_XMX" to "4g", "UNIDRIVE_LOCALE" to "de_lu", "UNIDRIVE_DIAG_DIR" to diag.toString())[name]
                },
                props = { name -> if (name == "java.io.tmpdir") "/t" else null },
            )
        assertTrue(command.contains("-Xmx4g"))
        assertTrue(command.contains("-Duser.language=de"))
        assertTrue(command.contains("-Duser.country=LU"), "UNIDRIVE_LOCALE's country part lands upper-cased")
        assertTrue(command.contains("-Djdk.net.unixdomain.tmpdir=/t"))
    }

    @Test
    fun `the spawned daemon passes the coroutine debug agent with a colon`() {
        val jarDir = Files.createTempDirectory("autospawn-agent")
        val diagDir = Files.createTempDirectory("autospawn-agent-diag")
        val agentJar = jarDir.resolve("${CoroutineDebug.AGENT_JAR_PREFIX}test.jar")
        Files.createFile(agentJar)
        val command =
            DaemonAutospawn.spawnCommand(
                javaBin = Path.of("java"),
                jar = jarDir.resolve("unidrive.jar"),
                configRoot = Files.createTempDirectory("autospawn-agent-config"),
                profileName = "p",
                env = { name ->
                    when (name) {
                        CoroutineDebug.ENV_FLAG -> "1"
                        "UNIDRIVE_DIAG_DIR" -> diagDir.toString()
                        else -> null
                    }
                },
                props = { null },
            )

        assertTrue(command.contains("-javaagent:$agentJar"))
        assertFalse(command.any { it.startsWith("-javaagent=") })
    }

    @Test
    fun `a jar without a sibling jvm-flags txt still spawns the heap and diagnostics flags`() {
        val diag = Files.createTempDirectory("autospawn-nostatic-diag")
        val command =
            DaemonAutospawn.spawnCommand(
                javaBin = Path.of("java"),
                jar = Files.createTempDirectory("autospawn-nostatic").resolve("unidrive.jar"),
                configRoot = Files.createTempDirectory("autospawn-nostatic-config"),
                profileName = "p",
                env = { name -> if (name == "UNIDRIVE_DIAG_DIR") diag.toString() else null },
                props = { null },
            )
        assertFalse(command.any { it.startsWith("-Dstdout.encoding") }, "no static flags without the file")
        assertTrue(command.contains("-Xmx2g"))
        assertTrue(command.any { it.startsWith("-XX:ErrorFile=") })
        assertTrue(command.any { it.startsWith("-XX:HeapDumpPath=") })
        assertTrue(command.any { it.startsWith("-Xlog:gc*:file=") })
    }

    @Test
    fun `the default diagnostics dir sits under the OS data root`() {
        val home = Files.createTempDirectory("autospawn-diaghome")
        val nothing: (String) -> String? = { null }
        val homeProps: (String) -> String? = { name -> if (name == "user.home") home.toString() else null }
        assertEquals(
            home.resolve(".local/share/unidrive/diagnostics"),
            DaemonAutospawn.diagDir(nothing, homeProps, windows = false),
            "Linux: under ~/.local/share/unidrive, the daemon's own data root",
        )
        assertEquals(
            home.resolve("unidrive/diagnostics"),
            DaemonAutospawn.diagDir({ name -> if (name == "LOCALAPPDATA") home.toString() else null }, homeProps, windows = true),
            "Windows: under %LOCALAPPDATA%\\unidrive — Local, never the Roaming data root",
        )
        assertEquals(
            home.resolve("AppData/Local/unidrive/diagnostics"),
            DaemonAutospawn.diagDir(nothing, homeProps, windows = true),
            "Windows without LOCALAPPDATA falls back to the profile's own AppData\\Local",
        )
    }

    @Test
    fun `a daemon that never comes up reports failure without throwing`() {
        val ok =
            DaemonAutospawn.ensureDaemonRunning(
                profileName = "p",
                configDir = Files.createTempDirectory("autospawn-never"),
                waitTimeoutMs = 400,
                probe = { _, _ -> false },
                locateJar = { Path.of("/opt/unidrive/unidrive.jar") },
                spawn = { _, _ -> /* a daemon that starts but never binds */ },
            )
        assertFalse(ok, "the timeout is reported as false, to the caller's own error path")
    }

    @Test
    fun `a failed spawn degrades to false`() {
        val ok =
            DaemonAutospawn.ensureDaemonRunning(
                profileName = "p",
                configDir = Files.createTempDirectory("autospawn-fail"),
                probe = { _, _ -> false },
                locateJar = { Path.of("/opt/unidrive/unidrive.jar") },
                spawn = { _, _ -> throw IOException("disk full") },
            )
        assertFalse(ok)
    }

    @Test
    fun `without a jar nothing is spawned and the failure is reported`() {
        val ok =
            DaemonAutospawn.ensureDaemonRunning(
                profileName = "p",
                configDir = Files.createTempDirectory("autospawn-nojar"),
                probe = { _, _ -> false },
                locateJar = { null },
                spawn = { _, _ -> error("nothing can be spawned without a jar") },
            )
        assertFalse(ok)
    }

    @Test
    fun `thisJar names a jar or nothing`() {
        // In the test suite the classes are loose, so both outcomes are legal; the pinned contract is
        // that a non-null answer is always a .jar (the spawner's -jar argument needs exactly that).
        DaemonAutospawn.thisJar()?.let { assertTrue(it.toString().endsWith(".jar")) }
    }

    // An antivirus that scans HTTPS signs with a root only the Windows store trusts, which the JVM's
    // own cacerts does not hold (PKIX path building failed). The daemon a command autospawns takes the Windows store, exactly
    // as the PowerShell launcher does; the Linux and macOS launchers read jvm-flags.txt, where Windows-ROOT does not exist.
    private fun spawn(
        windows: Boolean,
        env: Map<String, String> = emptyMap(),
    ): List<String> {
        val diag = Files.createTempDirectory("autospawn-trust-diag")
        return DaemonAutospawn.spawnCommand(
            javaBin = Path.of("java"),
            jar = Files.createTempDirectory("autospawn-trust-jar").resolve("unidrive.jar"),
            configRoot = Files.createTempDirectory("autospawn-trust-config"),
            profileName = "p",
            env = { name -> (env + ("UNIDRIVE_DIAG_DIR" to diag.toString()))[name] },
            props = { null },
            windows = windows,
        )
    }

    @Test
    fun `on Windows the spawned daemon trusts the Windows certificate store, as one pair before the jar`() {
        val command = spawn(windows = true)
        val pair = listOf("-Djavax.net.ssl.trustStoreType=Windows-ROOT", "-Djavax.net.ssl.trustStore=NONE")
        assertEquals(1, command.count { it == pair[0] }, command.toString())
        assertEquals(1, command.count { it == pair[1] }, command.toString())
        assertTrue(command.indexOf(pair[0]) < command.indexOf("-jar") && command.indexOf(pair[1]) < command.indexOf("-jar"), command.toString())
    }

    @Test
    fun `off Windows the spawned daemon gets no trust store flag`() {
        assertTrue(spawn(windows = false).none { it.contains("javax.net.ssl.trustStore") })
    }

    @Test
    fun `a trust store the user set in the JVM option variables replaces the whole Windows default`() {
        for (variable in listOf("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS")) {
            val command = spawn(windows = true, env = mapOf(variable to "-Xshare:auto -Djavax.net.ssl.trustStore=C:/corp/roots.jks"))
            assertTrue(command.none { it.contains("javax.net.ssl.trustStore") }, "$variable: $command")
        }
        val unrelated = spawn(windows = true, env = mapOf("JAVA_TOOL_OPTIONS" to "-Xshare:auto"))
        assertTrue("-Djavax.net.ssl.trustStoreType=Windows-ROOT" in unrelated, unrelated.toString())
    }
}
