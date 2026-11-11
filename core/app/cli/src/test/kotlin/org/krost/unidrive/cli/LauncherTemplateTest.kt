package org.krost.unidrive.cli

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * The PowerShell launcher template builds the JVM argument list in PowerShell, where `,` binds tighter than `+`:
 * `@('-A=' + $x + 'f', '-B=' + $y)` is ONE argument that carries a space and a trailing backslash. pwsh 7 quotes
 * that, Windows PowerShell 5.1 (the shell the mount scripts start the daemon launcher with) does not, and java
 * prints its usage text instead of starting. This renders the template, lets the real `powershell.exe` build the
 * list and checks that every JVM flag is an argument of its own.
 */
class LauncherTemplateTest {
    private fun templateFile(): Path {
        var dir: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve("dist/launcher/unidrive.ps1.tmpl")
            if (Files.exists(candidate)) return candidate
            dir = dir.parent
        }
        error("dist/launcher/unidrive.ps1.tmpl not found above ${System.getProperty("user.dir")}")
    }

    private companion object {
        /** The template's last statement, which decides how the JVM start receives standard input. */
        const val JAVA_LINE = "if ([Console]::IsInputRedirected) { \$input | & java @javaArgs @passArgs } else { & java @javaArgs @passArgs }"
    }

    /** Renders the template, lets the real powershell.exe build the JVM argument list for [passArgs] and returns it. */
    private fun launcherArgs(
        vararg passArgs: String,
        env: Map<String, String> = emptyMap(),
        jar: String = "unidrive.jar",
        debugAgent: String = "",
    ): List<String> {
        val dir = Files.createTempDirectory("launcher-tmpl")
        try {
            val flags = templateFile().parent.resolve("jvm-flags.txt").toFile().readLines()
                .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            val rendered = templateFile().toFile().readText().replace("\r\n", "\n")
                .replace("@STATIC_FLAGS_PS@", "\$javaArgs += @(" + flags.joinToString(", ") { "'$it'" } + ")")
                .replace("@JAR@", jar)
                .replace("@COROUTINES_DEBUG_AGENT@", debugAgent)
                // print the argument list instead of starting java (the stdin branch is not under test here)
                .replace(JAVA_LINE, "\$javaArgs | ForEach-Object { 'ARG:' + \$_ }")
            val script = dir.resolve("launcher.ps1")
            Files.writeString(script, rendered)
            val process = ProcessBuilder(
                listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString()) + passArgs,
            ).redirectErrorStream(true)
                .also {
                    it.environment().remove("JAVA_TOOL_OPTIONS")
                    it.environment().remove("JDK_JAVA_OPTIONS")
                    it.environment()["UNIDRIVE_DIAG_DIR"] = dir.resolve("diag").toString()
                    it.environment().putAll(env)
                }
                .start()
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "powershell.exe did not finish")
            return output.lines().filter { it.startsWith("ARG:") }.map { it.removePrefix("ARG:") }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Renders the template with the JVM start stubbed to echo what reached it, keeping the stdin branch, and runs
     * it: [stdinLines] are fed through a redirected file, `null` inherits a pipe that is closed at once. The tests
     * read the output only after the bounded [Process.waitFor], so the hang this guards against fails the 60 s
     * bound instead of blocking forever on a pipe nobody closes.
     */
    private fun runLauncherStub(stdinLines: List<String>?): Pair<Path, Process> {
        val dir = Files.createTempDirectory("launcher-tmpl")
        val argEcho = "\$javaArgs | ForEach-Object { 'ARG:' + \$_ }"
        val rendered = templateFile().toFile().readText().replace("\r\n", "\n")
            .replace("@STATIC_FLAGS_PS@", "\$javaArgs += @()")
            .replace("@JAR@", "unidrive.jar")
            .replace("@COROUTINES_DEBUG_AGENT@", "")
            // echo the standard input and the argument list instead of starting java, in both branches
            .replace(
                JAVA_LINE,
                "if ([Console]::IsInputRedirected) { \$input | ForEach-Object { 'STDIN:' + \$_ }; $argEcho } else { $argEcho }",
            )
        val script = dir.resolve("launcher.ps1")
        Files.writeString(script, rendered)
        val process = ProcessBuilder(
            listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString()),
        ).redirectErrorStream(true)
            .also { it.environment()["UNIDRIVE_DIAG_DIR"] = dir.resolve("diag").toString() }
        if (stdinLines == null) {
            process.redirectInput(ProcessBuilder.Redirect.PIPE)
        } else {
            val stdin = dir.resolve("stdin.txt")
            Files.write(stdin, stdinLines)
            process.redirectInput(stdin.toFile())
        }
        val running = process.start()
        if (stdinLines == null) running.outputStream.close()
        return dir to running
    }

    /**
     * A fake JDK `<root>/<name>` whose `release` says [version] (no `release` file when null, like a PATH shim);
     * returns its bin/java.cmd, which prints which JDK it is (followed by the arguments it got when [echoArgs]),
     * or [versionBanner] on stderr when given (the `java -version` first line of a shim that has no `release` file).
     */
    private fun fakeJdk(
        root: Path,
        name: String,
        version: String?,
        versionBanner: String? = null,
        echoArgs: Boolean = false,
    ): Path {
        val home = root.resolve(name)
        Files.createDirectories(home.resolve("bin"))
        if (version != null) Files.writeString(home.resolve("release"), "IMPLEMENTOR=\"fake\"\nJAVA_VERSION=\"$version\"\n")
        val java = home.resolve("bin/java.cmd")
        Files.writeString(
            java,
            when {
                versionBanner != null -> "@echo $versionBanner 1>&2"
                echoArgs -> "@echo JAVA:$name %*"
                else -> "@echo JAVA:$name"
            },
        )
        return java
    }

    /**
     * Renders the template the way the deploys render it, keeps the real JVM start, and runs it with [env] and
     * [passArgs]: the runtime resolution block decides which java starts, so a fake JDK whose java.cmd prints its
     * name pins the choice (the resolution harness of LauncherShTemplateTest, ported to PowerShell 5.1, which finds
     * java.cmd on PATH through PATHEXT). Standard input is an immediately closed pipe, as in [runLauncherStub].
     */
    private fun runResolution(
        env: Map<String, String>,
        vararg passArgs: String,
        beforeRun: (scriptDir: Path) -> Unit = {},
        jar: (scriptDir: Path) -> String = { "unidrive.jar" },
    ): Pair<Int, String> {
        val dir = Files.createTempDirectory("launcher-tmpl")
        try {
            val flags = templateFile().parent.resolve("jvm-flags.txt").toFile().readLines()
                .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            val rendered = templateFile().toFile().readText().replace("\r\n", "\n")
                .replace("@STATIC_FLAGS_PS@", "\$javaArgs += @(" + flags.joinToString(", ") { "'$it'" } + ")")
                .replace("@JAR@", jar(dir))
                .replace("@COROUTINES_DEBUG_AGENT@", "")
                // the fake bundled image holds a java.cmd (a fake java.exe cannot print): look for that name
                .replace("runtime-image\\bin\\java.exe", "runtime-image\\bin\\java.cmd")
            val script = dir.resolve("launcher.ps1")
            Files.writeString(script, rendered)
            beforeRun(dir)
            val process = ProcessBuilder(
                listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString()) + passArgs,
            ).redirectErrorStream(true)
                .also {
                    it.environment().remove("JAVA_TOOL_OPTIONS")
                    it.environment().remove("JDK_JAVA_OPTIONS")
                    it.environment().remove("UNIDRIVE_JAVA")
                    it.environment()["UNIDRIVE_DIAG_DIR"] = dir.resolve("diag").toString()
                    it.environment().putAll(env)
                }
            process.redirectInput(ProcessBuilder.Redirect.PIPE)
            val running = process.start()
            running.outputStream.close()
            val output = running.inputStream.bufferedReader().readText()
            assertTrue(running.waitFor(60, TimeUnit.SECONDS), "powershell.exe did not finish")
            return running.exitValue() to output
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the powershell launcher passes every diagnostics flag as an argument of its own`() {
        assumeTrue("Windows PowerShell 5.1 only exists on Windows", System.getProperty("os.name").startsWith("Windows"))
        val args = launcherArgs("daemon", "run", "p")
        val errorFile = args.filter { it.startsWith("-XX:ErrorFile=") }
        val dumpPath = args.filter { it.startsWith("-XX:HeapDumpPath=") }
        assertEquals(1, errorFile.size, "one -XX:ErrorFile argument, got: $args")
        assertEquals(1, dumpPath.size, "one -XX:HeapDumpPath argument, got: $args")
        assertTrue(!errorFile[0].removePrefix("-XX:ErrorFile=").contains("-XX:"), "the ErrorFile argument swallowed another flag: ${errorFile[0]}")
        assertTrue(dumpPath[0].endsWith("heap-p.hprof"), "the dump is the profile's own fixed file (no trailing separator, the JVM then treats it as a file name): ${dumpPath[0]}")
        assertTrue(args.any { it.startsWith("-Xlog:gc*:file=") }, "daemon run arms the GC log: $args")
    }

    // `autostart` is a one-word command and normally the LAST argument (`unidrive.ps1 autostart`,
    // `unidrive.ps1 -p work autostart`): a loop bounded by Count - 1 never looks at it.
    @Test
    fun `the powershell launcher arms the GC log for autostart as the last argument and for no one-shot command`() {
        assumeTrue("Windows PowerShell 5.1 only exists on Windows", System.getProperty("os.name").startsWith("Windows"))
        for (pass in listOf(arrayOf("autostart"), arrayOf("-p", "work", "autostart"), arrayOf("sync", "--watch"))) {
            assertTrue(launcherArgs(*pass).any { it.startsWith("-Xlog:gc*:file=") }, "GC log for ${pass.toList()}")
        }
        for (pass in listOf(arrayOf("status"), arrayOf("sync", "--once"), arrayOf("-p", "work", "ls"))) {
            assertTrue(launcherArgs(*pass).none { it.startsWith("-Xlog:gc*") }, "no GC log for the one-shot ${pass.toList()}")
        }
    }

    // Every daemon writes its own GC log, gc-<profile>.log: two daemons (one per profile) sharing one file
    // cannot rotate it ("Could not rename log file ... Permission denied"). The profile comes from the global
    // -p/--provider, the `daemon run <profile>` positional (which wins, as in the CLI), else `default`.
    @Test
    fun `the powershell launcher names the GC log after the profile`() {
        assumeTrue("Windows PowerShell 5.1 only exists on Windows", System.getProperty("os.name").startsWith("Windows"))
        val cases =
            listOf(
                arrayOf("-p", "x", "daemon", "run") to "gc-x.log",
                arrayOf("daemon", "run") to "gc-default.log",
                arrayOf("daemon", "run", "y") to "gc-y.log",
                arrayOf("-p", "x", "daemon", "run", "--poll-interval", "30", "y") to "gc-y.log",
                // gkrost/unidrive-cli#201: the value of a valued option is not the profile, whatever option it follows.
                arrayOf("-p", "x", "daemon", "run", "--upload-concurrency", "8") to "gc-x.log",
                arrayOf("-p", "x", "daemon", "run", "--poll-interval", "30") to "gc-x.log",
                arrayOf("-p", "x", "daemon", "run", "--upload-concurrency", "8", "--poll-interval", "30") to "gc-x.log",
                arrayOf("daemon", "run", "--upload-concurrency", "8", "y") to "gc-y.log",
                arrayOf("-p", "x", "daemon", "run", "--upload-concurrency=8") to "gc-x.log",
                arrayOf("--provider=z", "autostart") to "gc-z.log",
                arrayOf("--provider", "w-1", "sync", "--watch") to "gc-w-1.log",
                arrayOf("--profile=v_2", "autostart") to "gc-v_2.log",
                arrayOf("-p", "a b", "autostart") to "gc-default.log",
            )
        for ((pass, file) in cases) {
            val gc = launcherArgs(*pass).filter { it.startsWith("-Xlog:gc*:file=") }
            assertEquals(1, gc.size, "one GC log flag for ${pass.toList()}: $gc")
            val path = gc[0].removePrefix("-Xlog:gc*:file=").substringBefore(":time,")
            assertEquals(file, Path.of(path).fileName.toString(), "GC log file for ${pass.toList()}")
            assertTrue(gc[0].endsWith(":time,uptime:filecount=5,filesize=10m"), "the rotation bound stays: ${gc[0]}")
        }
    }

    // gkrost/unidrive-cli#201: the template names the valued options of `daemon run` in one line; every option of the
    // command that takes a value must be on it, or its value is taken for the profile. Read from the command itself, so a
    // new valued option fails here instead of in a diagnostics folder full of gc-8.log.
    @Test
    fun `the powershell launcher skips the value of every valued option of daemon run`() {
        val line = templateFile().toFile().readLines().single { it.trimStart().startsWith("\$daemonRunValued = @(") }
        val named = Regex("'(--[a-z-]+)'").findAll(line).map { it.groupValues[1] }.toSet()
        assertEquals(DaemonRunValuedOptions.fromCommand(), named, "valued options of daemon run vs the template: $line")
    }

    // archive#859: piped-in input is the script's $input; the JVM start must receive it (ignore_rules from a file,
    // auth login --password-stdin) instead of seeing an empty standard input and, for ignore_rules, clearing.
    @Test
    fun `the powershell launcher hands redirected standard input to the jvm`() {
        assumeTrue("Windows PowerShell 5.1 only exists on Windows", System.getProperty("os.name").startsWith("Windows"))
        val (dir, process) = runLauncherStub(listOf("*.tmp", "build/"))
        try {
            val finished = process.waitFor(60, TimeUnit.SECONDS)
            if (!finished) process.destroy()
            assertTrue(finished, "powershell.exe did not finish")
            val output = process.inputStream.bufferedReader().readText()
            assertTrue("STDIN:*.tmp" in output, "first stdin line reached the stub: $output")
            assertTrue("STDIN:build/" in output, "second stdin line reached the stub: $output")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // No redirected input: the launcher must still run to completion and pass the exit code through.
    @Test
    fun `the powershell launcher starts with an empty standard input`() {
        assumeTrue("Windows PowerShell 5.1 only exists on Windows", System.getProperty("os.name").startsWith("Windows"))
        val (dir, process) = runLauncherStub(null)
        try {
            val finished = process.waitFor(60, TimeUnit.SECONDS)
            if (!finished) process.destroy()
            assertTrue(finished, "powershell.exe did not finish")
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.exitValue(), "the stub ran to completion: $output")
            assertTrue(output.lines().any { it.startsWith("ARG:") }, "the argument list was still built: $output")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // Windows-ROOT lets the JVM trust what an antivirus that scans HTTPS installed in the Windows
    // store. It exists on Windows only (SunMSCAPI), and jvm-flags.txt is read by the Linux launchers too, so the flag lives in
    // this template alone and in DaemonAutospawn (on Windows).
    @Test
    fun `the powershell launcher trusts the Windows certificate store as one pair before the jar`() {
        assumeTrue("Windows PowerShell 5.1 only exists on Windows", System.getProperty("os.name").startsWith("Windows"))
        val args = launcherArgs("status")
        assertEquals(1, args.count { it == "-Djavax.net.ssl.trustStoreType=Windows-ROOT" }, args.toString())
        assertEquals(1, args.count { it == "-Djavax.net.ssl.trustStore=NONE" }, args.toString())
        assertTrue(args.indexOf("-Djavax.net.ssl.trustStoreType=Windows-ROOT") < args.indexOf("-jar"), args.toString())
    }

    @Test
    fun `a trust store set in the JVM option variables replaces the whole default of the powershell launcher`() {
        assumeTrue("Windows PowerShell 5.1 only exists on Windows", System.getProperty("os.name").startsWith("Windows"))
        for (variable in listOf("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS")) {
            val args = launcherArgs("status", env = mapOf(variable to "-Djavax.net.ssl.trustStore=C:/corp/roots.jks"))
            assertTrue(args.none { it.contains("javax.net.ssl.trustStore") }, "$variable: $args")
        }
        assertTrue("-Djavax.net.ssl.trustStoreType=Windows-ROOT" in launcherArgs("status", env = mapOf("JAVA_TOOL_OPTIONS" to "-Xshare:auto")))
    }

    // Platform independent: reads the files, runs nothing.
    @Test
    fun `the Windows trust store flag is in the powershell template only, not in the shell template or the shared flag list`() {
        val dir = templateFile().parent
        assertTrue("Windows-ROOT" in dir.resolve("unidrive.ps1.tmpl").toFile().readText())
        assertTrue("Windows-ROOT" !in dir.resolve("unidrive.sh.tmpl").toFile().readText(), "the shell template must not carry it")
        assertTrue(
            dir.resolve("jvm-flags.txt").toFile().readLines().none { !it.trimStart().startsWith("#") && "trustStore" in it },
            "jvm-flags.txt is read by the Linux launchers, where Windows-ROOT does not exist",
        )
    }

    // gkrost/unidrive-cli#572: the startup cache beside the jar, picked the way DaemonAutospawn picks it.
    // The AOT cache of the bundled image wins when one lies beside the jar (and no agent is armed, because
    // the agent disables the cache and errors); else the dynamic CDS archive, and only when the jar's
    // folder is writable: an unwritable archive path makes the JVM exit 127 after a successful run. JDK 27
    // refuses the two caches in one JVM, so exactly one of the two may be on the command line.
    @Test
    fun `the powershell launcher arms the dynamic CDS archive beside a writable jar folder`() {
        assumeTrue("Windows PowerShell 5.1 only exists on Windows", System.getProperty("os.name").startsWith("Windows"))
        val jarDir = Files.createTempDirectory("launcher-tmpl-deploy")
        try {
            val args = launcherArgs("status", jar = jarDir.resolve("unidrive.jar").toString())
            assertTrue("-XX:+AutoCreateSharedArchive" in args, args.toString())
            assertTrue(args.any { it.startsWith("-XX:SharedArchiveFile=") && it.endsWith("unidrive.jsa") }, args.toString())
            assertTrue("-Xlog:cds=off" in args, "the first run's CDS messages stay off stdout: $args")
            assertTrue(args.indexOf("-XX:+AutoCreateSharedArchive") < args.indexOf("-jar"), args.toString())
            assertFalse(Files.exists(jarDir.resolve(".unidrive-cds-probe")), "the write probe leaves nothing behind")
        } finally {
            jarDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the powershell launcher prefers the AOT cache beside the jar`() {
        assumeTrue("Windows PowerShell 5.1 only exists on Windows", System.getProperty("os.name").startsWith("Windows"))
        val jarDir = Files.createTempDirectory("launcher-tmpl-aot")
        try {
            Files.createFile(jarDir.resolve("unidrive.aot"))
            val args = launcherArgs("status", jar = jarDir.resolve("unidrive.jar").toString())
            assertTrue(args.any { it.startsWith("-XX:AOTCache=") && it.endsWith("unidrive.aot") }, args.toString())
            assertTrue("-Xlog:aot=off" in args, "a stale cache prints to stdout, which the client parses: $args")
            assertTrue(
                args.none { it.startsWith("-XX:+AutoCreateSharedArchive") || it.startsWith("-XX:SharedArchiveFile=") },
                "no CDS archive beside the AOT cache: $args",
            )
        } finally {
            jarDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the armed coroutine agent keeps the launcher off the AOT cache`() {
        assumeTrue("Windows PowerShell 5.1 only exists on Windows", System.getProperty("os.name").startsWith("Windows"))
        val jarDir = Files.createTempDirectory("launcher-tmpl-agent")
        try {
            Files.createFile(jarDir.resolve("unidrive.aot"))
            val agent = jarDir.resolve("coroutine-debug-agent.jar").toString()
            Files.createFile(jarDir.resolve("coroutine-debug-agent.jar"))
            val args = launcherArgs("status", jar = jarDir.resolve("unidrive.jar").toString(), debugAgent = agent, env = mapOf("UNIDRIVE_COROUTINE_DEBUG" to "1"))
            assertTrue(args.any { it.startsWith("-javaagent:") }, "the agent is armed: $args")
            assertTrue(args.none { it.startsWith("-XX:AOTCache=") }, "the agent disables the cache and errors: $args")
            assertTrue("-XX:+AutoCreateSharedArchive" in args, "the CDS archive still applies: $args")
        } finally {
            jarDir.toFile().deleteRecursively()
        }
    }

    // The runtime resolution and its floor, the Windows twin of LauncherShTemplateTest's cases: a stale JDK below
    // 21 is refused with guidance (the floor, naming the minimum and UNIDRIVE_JAVA) instead of a bare
    // class-file-version error from whatever java is first on PATH.
    @Test
    fun `a java older than 25 is refused with an actionable message`() {
        assumeTrue("Windows PowerShell 5.1 only exists on Windows", System.getProperty("os.name").startsWith("Windows"))
        val jdks = Files.createTempDirectory("launcher-fake-jdks")
        try {
            val stale = fakeJdk(jdks, "jdk17", "17.0.9")
            val (exit, output) = runResolution(mapOf("UNIDRIVE_JAVA" to stale.toString()), "status")
            assertEquals(1, exit, output)
            assertTrue("JAVA:jdk17" !in output, "the stale java must not be started: $output")
            assertTrue(output.contains("needs Java 25 or newer, found Java 17"), output)
        } finally {
            jdks.toFile().deleteRecursively()
        }
    }

    // gkrost/unidrive-cli#522, the Windows twin of LauncherShTemplateTest's cases: the JFR redact key *vault* keeps
    // the vault passphrase out of flight recordings, but Java 25 and 26 reject the option ("Unknown argument
    // 'redact-key'") and do not start. So only a runtime whose version reads as 27+ may receive it, before the jar.
    @Test
    fun `the powershell launcher passes the JFR vault redact key to Java 27 only, before the jar`() {
        assumeTrue("Windows PowerShell 5.1 only exists on Windows", System.getProperty("os.name").startsWith("Windows"))
        val flag = "-XX:FlightRecorderOptions=redact-key=+*vault*"
        val jdks = Files.createTempDirectory("launcher-fake-jdks")
        try {
            for ((name, version) in listOf("jdk25" to "25.0.4", "jdk26" to "26.0.2", "jdk27" to "27.0.1")) {
                val java = fakeJdk(jdks, name, version, echoArgs = true)
                val (exit, output) = runResolution(mapOf("UNIDRIVE_JAVA" to java.toString()), "status")
                assertEquals(0, exit, output)
                val line = output.lines().single { it.startsWith("JAVA:$name ") }
                val args = line.split(' ')
                if (name == "jdk27") {
                    assertTrue(flag in args, "$name: $line")
                    assertTrue(args.indexOf(flag) < args.indexOf("-jar"), "a JVM option goes before -jar: $line")
                } else {
                    assertTrue(args.none { it.startsWith("-XX:FlightRecorderOptions") }, "$name: $line")
                }
            }
        } finally {
            jdks.toFile().deleteRecursively()
        }
    }

    @Test
    fun `UNIDRIVE_JAVA beats the PATH java`() {
        assumeTrue("Windows PowerShell 5.1 only exists on Windows", System.getProperty("os.name").startsWith("Windows"))
        val jdks = Files.createTempDirectory("launcher-fake-jdks")
        try {
            val chosen = fakeJdk(jdks, "jdk25-chosen", "25.0.4")
            val (exit, output) = runResolution(mapOf("UNIDRIVE_JAVA" to chosen.toString()), "status")
            assertEquals(0, exit, output)
            assertTrue("JAVA:jdk25-chosen" in output, "UNIDRIVE_JAVA must be the java that runs: $output")
        } finally {
            jdks.toFile().deleteRecursively()
        }
    }

    /** PATH with [first] in front, so a fake java.cmd there is the `java` the launcher finds. */
    private fun pathWith(first: Path): String = first.toString() + ";" + System.getenv("PATH")

    @Test
    fun `UNIDRIVE_JAVA beats the bundled runtime image and the bundled image beats the PATH java`() {
        assumeTrue("Windows PowerShell 5.1 only exists on Windows", System.getProperty("os.name").startsWith("Windows"))
        val jdks = Files.createTempDirectory("launcher-fake-jdks")
        try {
            val chosen = fakeJdk(jdks, "jdk25-chosen", "25.0.4")
            val onPath = fakeJdk(jdks, "jdk25-path", "25.0.4")
            // the bundled image sits next to the rendered launcher
            val bundle = { dir: Path -> fakeJdk(dir, "runtime-image", "25.0.1"); Unit }
            val pathEnv = mapOf("PATH" to pathWith(onPath.parent))

            val (exit1, out1) = runResolution(pathEnv + ("UNIDRIVE_JAVA" to chosen.toString()), "status", beforeRun = bundle)
            assertEquals(0, exit1, out1)
            assertTrue("JAVA:jdk25-chosen" in out1, "UNIDRIVE_JAVA must beat the bundled image: $out1")

            val (exit2, out2) = runResolution(pathEnv, "status", beforeRun = bundle)
            assertEquals(0, exit2, out2)
            assertTrue("JAVA:runtime-image" in out2, "the bundled image must beat the PATH java: $out2")
            assertTrue("JAVA:jdk25-path" !in out2, "the PATH java must not run: $out2")
        } finally {
            jdks.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the bundled runtime image is found next to the fat jar when the jar is not beside the launcher`() {
        assumeTrue("Windows PowerShell 5.1 only exists on Windows", System.getProperty("os.name").startsWith("Windows"))
        val jdks = Files.createTempDirectory("launcher-fake-jdks")
        try {
            val onPath = fakeJdk(jdks, "jdk25-path", "25.0.4")
            val jarDir = Files.createDirectories(jdks.resolve("deploy"))
            fakeJdk(jarDir, "runtime-image", "25.0.1")
            val (exit, output) = runResolution(
                mapOf("PATH" to pathWith(onPath.parent)),
                "status",
                jar = { jarDir.resolve("unidrive.jar").toString() },
            )
            assertEquals(0, exit, output)
            assertTrue("JAVA:runtime-image" in output, "the image next to the jar must be found: $output")
        } finally {
            jdks.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an UNIDRIVE_JAVA that points at a missing file is an error and no other java runs`() {
        assumeTrue("Windows PowerShell 5.1 only exists on Windows", System.getProperty("os.name").startsWith("Windows"))
        val jdks = Files.createTempDirectory("launcher-fake-jdks")
        try {
            val onPath = fakeJdk(jdks, "jdk25-path", "25.0.4")
            val missing = jdks.resolve("nowhere/bin/java.cmd").toString()
            val (exit, output) = runResolution(
                mapOf("UNIDRIVE_JAVA" to missing, "PATH" to pathWith(onPath.parent)),
                "status",
                beforeRun = { dir -> fakeJdk(dir, "runtime-image", "25.0.1") },
            )
            assertEquals(1, exit, output)
            assertTrue("unidrive: UNIDRIVE_JAVA points at a missing file: $missing" in output, output)
            assertTrue("JAVA:" !in output, "no java may run: $output")
        } finally {
            jdks.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a java 1 dot 8 release is Java 8, not Java 1, and is refused`() {
        assumeTrue("Windows PowerShell 5.1 only exists on Windows", System.getProperty("os.name").startsWith("Windows"))
        val jdks = Files.createTempDirectory("launcher-fake-jdks")
        try {
            val old = fakeJdk(jdks, "jdk8", "1.8.0_402")
            val (exit, output) = runResolution(mapOf("UNIDRIVE_JAVA" to old.toString()), "status")
            assertEquals(1, exit, output)
            assertTrue(output.contains("found Java 8 at"), output)
        } finally {
            jdks.toFile().deleteRecursively()
        }
    }

    // A PATH shim (Oracle's javapath) has no release file two levels up: the floor reads `java -version` instead.
    @Test
    fun `a PATH java without a release file is asked for its version, and a stale one is refused`() {
        assumeTrue("Windows PowerShell 5.1 only exists on Windows", System.getProperty("os.name").startsWith("Windows"))
        val jdks = Files.createTempDirectory("launcher-fake-jdks")
        try {
            val shim = fakeJdk(jdks, "shim", null, versionBanner = "java version \"1.8.0_301\"")
            val (exit, output) = runResolution(mapOf("PATH" to pathWith(shim.parent)), "status")
            assertEquals(1, exit, output)
            assertTrue(output.contains("found Java 8 at"), output)
        } finally {
            jdks.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a java whose version cannot be read runs with a note that the floor was not checked`() {
        assumeTrue("Windows PowerShell 5.1 only exists on Windows", System.getProperty("os.name").startsWith("Windows"))
        val jdks = Files.createTempDirectory("launcher-fake-jdks")
        try {
            val unreadable = fakeJdk(jdks, "mystery", null)
            val (exit, output) = runResolution(mapOf("PATH" to pathWith(unreadable.parent)), "status")
            assertEquals(0, exit, output)
            assertTrue(output.contains("the Java 25 floor was not checked"), output)
            assertTrue("JAVA:mystery" in output, "the java still runs: $output")
        } finally {
            jdks.toFile().deleteRecursively()
        }
    }
}
