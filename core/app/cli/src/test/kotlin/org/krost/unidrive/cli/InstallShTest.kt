package org.krost.unidrive.cli

import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * dist/install.sh runs against a throwaway HOME with a fake `systemctl` first on PATH, so the real user
 * manager is never touched. The fake reports two active unidrive units and logs every call.
 */
class InstallShTest {
    private lateinit var dir: Path
    private lateinit var home: Path
    private lateinit var jar: Path
    private lateinit var systemctlLog: Path

    @BeforeTest
    fun setUp() {
        assumeTrue("install.sh: Linux only", System.getProperty("os.name").lowercase().contains("linux"))
        dir = Files.createTempDirectory("install-sh")
        home = Files.createDirectories(dir.resolve("home"))
        jar = dir.resolve("unidrive-1.jar")
        Files.writeString(jar, "new jar")
        systemctlLog = dir.resolve("systemctl.log")
        val bin = Files.createDirectories(dir.resolve("bin"))
        val fake = bin.resolve("systemctl")
        // `start` launches a long-running JVM whose command line holds -jar <lib>/unidrive-1.jar, the way a
        // unit-started daemon does; its pid is recorded for teardown (detached from the pipes, or install()'s read of the output waits for it to exit; a plain sleep would not match the
        // NOTE's /proc scan, and a compound command keeps sh from exec-optimizing the cmdline away).
        Files.writeString(
            fake,
            "#!/bin/sh\necho \"\$*\" >> '$systemctlLog'\n" +
                "case \"\$*\" in *list-units*) echo 'unidrive.service loaded active running x'; " +
                "echo 'unidrive-mount.service loaded active running y';; " +
                "*start*) setsid sh -c 'sleep 300; :' \"-jar $home/.local/lib/unidrive/unidrive-1.jar\" >/dev/null 2>&1 </dev/null & " +
                "echo \$! >> '${dir.resolve("fake-pids")}';; esac\n",
        )
        fake.toFile().setExecutable(true)
    }

    @AfterTest
    fun tearDown() {
        if (::dir.isInitialized) {
            val fakePids = dir.resolve("fake-pids")
            if (Files.exists(fakePids)) {
                val pids = Files.readString(fakePids).split('\n').filter { it.isNotBlank() }
                if (pids.isNotEmpty()) {
                    Runtime.getRuntime()
                        .exec(arrayOf("sh", "-c", "kill -TERM -- ${pids.joinToString(" ") { "-$it" }} 2>/dev/null"))
                        .waitFor()
                }
            }
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Runs only install.sh's Java-check block, with a fake [version]-reporting `java` first on PATH (and as
     * UNIDRIVE_JAVA when [asUnidriveJava]): the probe's answer must follow the launcher's floor, not its warning text.
     * The test is skipped on a host that keeps a real Java 25+ under /usr/lib/jvm, where a 21-only scenario cannot be
     * constructed because the probe's glob would find it.
     */
    private fun runJavaProbe(version: String, asUnidriveJava: Boolean): String {
        java.io.File("/usr/lib/jvm").listFiles()?.forEach { jvm ->
            val hostJava = jvm.resolve("bin/java").toPath()
            if (Files.isExecutable(hostJava)) {
                val p = ProcessBuilder(hostJava.toString(), "-version").redirectErrorStream(true).start()
                val out = p.inputStream.readAllBytes().decodeToString()
                p.waitFor()
                val major = Regex("""version "(\d+)""").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                assumeTrue("the host has Java $major under /usr/lib/jvm; a 21-only installation cannot be constructed here", major < 25)
            }
        }
        val lines = Files.readAllLines(installScript())
        val start = lines.indexOfFirst { it.startsWith("java_ok=0") }
        val end = lines.subList(maxOf(start, 0), lines.size).indexOfFirst { it == "fi" }
        assertTrue(start >= 0 && end > start, "the Java-check block was found in install.sh")
        val probe = dir.resolve("probe.sh")
        Files.writeString(
            probe,
            lines.subList(start, start + end + 1).joinToString("\n") + "\n" + """echo "java_ok=${'$'}{java_ok}"""" + "\n",
        )
        val fake = dir.resolve("bin").resolve("java")
        Files.writeString(fake, "#!/bin/sh\necho 'openjdk version \"$version\" 2025-01-01' >&2\n")
        fake.toFile().setExecutable(true)
        val pb = ProcessBuilder("bash", probe.toString()).redirectErrorStream(true)
        pb.environment()["PATH"] = "${dir.resolve("bin")}:/usr/bin:/bin"
        if (asUnidriveJava) pb.environment()["UNIDRIVE_JAVA"] = fake.toString() else pb.environment().remove("UNIDRIVE_JAVA")
        val p = pb.start()
        val out = p.inputStream.readAllBytes().decodeToString()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "probe timed out")
        assertEquals(0, p.exitValue(), out)
        return out
    }

    // gkrost/unidrive-cli#498: the warning says Java 25+, so the probe must require it — a Java 21-24 runtime is not
    // enough for the launcher, and the install may restart services into a daemon that then refuses to start.
    @Test
    fun the_java_probe_warns_when_only_java_21_is_found() {
        val out = runJavaProbe(version = "21.0.9", asUnidriveJava = false)
        assertTrue(out.contains("WARNING: no Java 25+ runtime found"), "the missing-runtime warning printed: $out")
        assertTrue(out.contains("java_ok=0"), "the probe did not accept Java 21: $out")
    }

    @Test
    fun the_java_probe_accepts_java_25_without_a_warning() {
        val out = runJavaProbe(version = "25", asUnidriveJava = true)
        assertTrue(!out.contains("WARNING"), "no warning printed for Java 25: $out")
        assertTrue(out.contains("java_ok=1"), "the probe accepted Java 25: $out")
    }

    private fun installScript(): Path {
        var d: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (d != null) {
            val candidate = d.resolve("dist/install.sh")
            if (Files.exists(candidate)) return candidate
            d = d.parent
        }
        error("dist/install.sh not found above ${System.getProperty("user.dir")}")
    }

    private fun install(): String {
        val pb = ProcessBuilder("bash", installScript().toString(), jar.toString()).redirectErrorStream(true)
        pb.environment()["HOME"] = home.toString()
        pb.environment()["PATH"] = "${dir.resolve("bin")}:/usr/bin:/bin"
        val p = pb.start()
        val out = p.inputStream.readAllBytes().decodeToString()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "install.sh timed out")
        assertEquals(0, p.exitValue(), out)
        return out
    }

    private fun inode(p: Path): Any = Files.getAttribute(p, "unix:ino")

    @Test
    fun reinstall_replaces_the_jar_with_a_fresh_inode_and_leaves_a_held_old_file_intact() {
        install()
        val installed = home.resolve(".local/lib/unidrive/unidrive-1.jar")
        val before = inode(installed)
        Files.newInputStream(installed).use { held ->
            Files.writeString(jar, "newer jar")
            install()
            assertNotEquals(before, inode(installed), "the jar must be a fresh inode, not overwritten in place")
            assertEquals("newer jar", Files.readString(installed))
            assertEquals("new jar", held.readAllBytes().decodeToString(), "a holder of the old jar keeps reading it intact")
        }
    }

    @Test
    fun active_unidrive_units_are_stopped_before_the_copy_and_started_again_after() {
        install()
        val calls = Files.readAllLines(systemctlLog)
        val stop = calls.indexOf("--user stop unidrive.service unidrive-mount.service")
        val start = calls.indexOf("--user start unidrive.service unidrive-mount.service")
        assertTrue(stop >= 0, "stop call missing: $calls")
        assertTrue(start > stop, "start must follow stop: $calls")
    }

    // archive#745: the NOTE is for JVMs started BY HAND that still run the previous jar. A JVM the install itself
    // starts afterwards (the unit daemons) must not be named: the scan is captured while the units are
    // stopped, before the fresh units come up.
    @Test
    fun the_still_running_note_names_only_the_hand_started_previous_jar() {
        val installedJar = home.resolve(".local/lib/unidrive/unidrive-1.jar")
        val hand = ProcessBuilder("setsid", "sh", "-c", "sleep 300; :", "-jar", installedJar.toString()).start()
        try {
            val out = install()
            val note = out.lineSequence().firstOrNull { it.startsWith("NOTE: still running the previous jar") }
            assertTrue(note != null, "the NOTE is printed: $out")
            val named = note!!.substringAfter("(pid ").substringBefore(")").split(' ').map { it.trim() }
            assertEquals(listOf(hand.pid().toString()), named, "only the hand-started JVM is named: $note (out: $out)")
        } finally {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "kill -TERM -- -${hand.pid()} 2>/dev/null")).waitFor()
        }
    }
}
