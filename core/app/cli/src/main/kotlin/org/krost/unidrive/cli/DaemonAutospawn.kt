package org.krost.unidrive.cli

import org.krost.unidrive.io.Os
import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcAuthClient
import org.krost.unidrive.sync.IpcEndpoint
import org.krost.unidrive.sync.IpcLineClient
import org.krost.unidrive.sync.IpcLineRead
import org.krost.unidrive.sync.IpcServer
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * archive#142: auto-spawn `unidrive daemon run` on the first client connection. The daemon-design spec used to
 * require the operator to start the daemon explicitly; a client that finds nothing at the socket now
 * starts it (mode-agnostic: whichever mode the profile has, that profile's daemon starts — one process
 * lock per profile stays, so a double spawn is refused by the lock, never doubled).
 *
 * The daemon must outlive the client that started it (mount exits, daemon stays), so the child is
 * detached: its output goes to a file under the profile's config folder, never to the client's
 * terminal, and nobody waits for it. The spawner polls for the socket file (the daemon binds it LAST,
 * after authentication and every handler are registered — its appearance is the ready signal) and
 * gives up after [DEFAULT_WAIT_TIMEOUT_MS], leaving the client's own error path to speak.
 *
 * Nothing here is mount- or mirror-specific: the daemon reads the profile's mode and serves it.
 */
object DaemonAutospawn {
    private val log = LoggerFactory.getLogger(DaemonAutospawn::class.java)

    private val LOCALE_SHAPE = Regex("^([A-Za-z]{2,3})(?:[_-]([A-Za-z]{2}))?$")

    const val DEFAULT_WAIT_TIMEOUT_MS: Long = 60_000

    /** JEP 536 JFR redaction: adds `*vault*` to the default patterns so UNIDRIVE_VAULT_PASS stays out of recordings.
     *  Java 25 and 26 reject the `redact-key` option and fail to start, hence the version gate. */
    internal const val JFR_REDACT_FLAG = "-XX:FlightRecorderOptions=redact-key=+*vault*"
    internal const val JFR_REDACT_MIN_JAVA = 27

    /** Who holds the profile lock; the tests' seam for the sidecar and the process table. */
    @Volatile internal var lockCheck: (String, Path) -> DaemonLockCheck = { name, dir -> checkDaemonLock(name, dir.resolve(".lock.pid")) }

    /**
     * Makes sure the profile's daemon is running: probes the socket, spawns the daemon when nothing
     * answers, and waits for the socket to appear. Returns true when a daemon is serving (pre-existing
     * or freshly spawned), false when the spawn was not possible — the caller then falls through to
     * its own operator-facing error. Never throws.
     *
     * [probe] and [spawn] are the tests' seams; [locateJar] stands in for "the jar this CLI runs from",
     * which does not exist when running from loose classes (tests, IDE).
     */
    fun ensureDaemonRunning(
        profileName: String,
        configDir: Path,
        socketPath: Path = IpcServer.defaultSocketPath(profileName),
        waitTimeoutMs: Long = DEFAULT_WAIT_TIMEOUT_MS,
        probe: (String, Path) -> Boolean = { name, socket -> daemonAnswers(name, socket, configDir) },
        locateJar: () -> Path? = ::thisJar,
        spawn: (List<String>, Path) -> SpawnedChild? = ::defaultSpawn,
    ): Boolean {
        if (probe(profileName, socketPath)) return true
        // Nothing valid answered. A live holder of the profile lock is not "no daemon": a second JVM would only lose the
        // lock and exit (gkrost/unidrive-cli#442). A daemon that holds it is busy or still starting: wait for it. Any
        // other live holder (a `sync --watch`, say) is named, and nothing is spawned over it.
        when (val lock = lockCheck(profileName, configDir)) {
            is DaemonLockCheck.Running -> return waitForDaemon(profileName, socketPath, waitTimeoutMs, probe, configDir.resolve("daemon-spawn.log"))
            is DaemonLockCheck.Refused -> {
                System.err.println("unidrive: the daemon for profile '$profileName' cannot be started: ${lock.message}")
                return false
            }
            is DaemonLockCheck.NotRunning -> Unit
        }
        val jar =
            runCatching { locateJar() }.getOrNull().also {
                if (it == null) {
                    System.err.println(
                        "unidrive: the daemon for profile '$profileName' is not running, and this command cannot start it " +
                            "(not running from a jar). Start it, then run this again: `unidrive -p $profileName daemon run`.",
                    )
                }
            } ?: return false
        val javaHome = Path.of(System.getProperty("java.home"))
        val javaBin = javaHome.resolve("bin").resolve(if (isWindows) "java.exe" else "java")
        val logFile = configDir.resolve("daemon-spawn.log")
        if (!Files.exists(jar.resolveSibling("jvm-flags.txt"))) {
            System.err.println(
                "unidrive: no jvm-flags.txt beside ${jar.fileName}; the spawned daemon starts without the standard static JVM flags (redeploy to restore them).",
            )
        }
        System.err.println(
            "unidrive: the daemon for profile '$profileName' is not running; starting it (output: $logFile) and waiting for it ...",
        )
        val child: SpawnedChild? =
            try {
                spawn(
                    spawnCommand(javaBin, jar, configDir.toAbsolutePath().parent, profileName),
                    logFile,
                )
            } catch (e: Exception) {
                log.warn("auto-spawn of the daemon failed", e)
                System.err.println(
                    "unidrive: the daemon could not be started (${e.javaClass.simpleName}). " +
                        "Start it manually: `unidrive -p $profileName daemon run`.",
                )
                return false
            }
        return waitForDaemon(profileName, socketPath, waitTimeoutMs, probe, logFile, child)
    }

    /**
     * Whether a daemon is actually serving the socket: the file alone lies (a kill -9'd daemon leaves
     * it behind), so one real authenticated round-trip decides. The reply must be a whole line that says
     * `ok:true` and carries a `protocol_version`: a host that answers `unknown_verb` (a `sync --watch` serves only
     * `sync.subscribe`) is not a daemon. `daemon.status` is a read verb, so the probe asks with read scope. Anything
     * that fails to connect, authenticate or reply in time reads as "not serving"; the caller then looks at the
     * profile lock before it believes the daemon is absent.
     */
    fun daemonAnswers(
        profileName: String,
        socketPath: Path,
        configDir: Path,
        replyTimeoutMs: Long = IpcAuthClient.HANDSHAKE_TIMEOUT_MS,
    ): Boolean {
        if (!Files.exists(socketPath)) return false
        return try {
            IpcAuthClient.connect(IpcEndpoint(socketPath, configDir, profileName), IpcAuth.Scope.READ).use { channel ->
                channel.configureBlocking(false)
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(replyTimeoutMs)
                val lines = IpcLineClient(channel)
                if (!lines.writeLine("""{"verb":"daemon.status"}""", deadline)) return false
                val reply = (lines.readLine(deadline) as? IpcLineRead.Line)?.text?.asJsonObjectOrNull()
                reply?.isOkReply() == true && reply.containsKey("protocol_version")
            }
        } catch (_: IOException) {
            false
        }
    }

    /**
     * The command that starts the profile's daemon child, carrying the same flag set every launcher
     * passes: the heap cap (UNIDRIVE_XMX, default 2g), the static flags from `jvm-flags.txt` beside
     * the jar (the launchers' single source; a missing file degrades to no static flags, never to a
     * failed spawn), the locale (UNIDRIVE_LOCALE, else the parent's own user.language/user.country),
     * the Windows trust store pair on Windows ([TrustStorePolicy]), the pinned unix-domain temp dir, the coroutine-debug agent when armed, the per-install
     * startup cache beside the jar ([startupCacheFlags]), and the post-mortem
     * diagnostics flags into the diagnostics dir — always daemon work here, so the bounded GC log
     * (the profile's own `gc-<profile>.log`) rides unconditioned.
     *
     * [env], [props] and [windows] are the tests' seams for the process environment, the system
     * properties (java.specification.version is the Java feature version the child runs on) and the OS family.
     */
    internal fun spawnCommand(
        javaBin: Path,
        jar: Path,
        configRoot: Path,
        profileName: String,
        env: (String) -> String? = System::getenv,
        props: (String) -> String? = { System.getProperty(it) },
        windows: Boolean = isWindows,
    ): List<String> =
        buildList {
            add(javaBin.toString())
            add("-Xmx" + (env("UNIDRIVE_XMX")?.takeIf { it.isNotBlank() } ?: "2g"))
            addAll(staticJvmFlags(jar.resolveSibling("jvm-flags.txt")))
            addAll(TrustStorePolicy.flags(windows, env))
            // JFR redaction of the vault passphrase (gkrost/unidrive-cli#522); the child runs on the parent's java,
            // and Java 25/26 reject the option, so only 27+ gets it.
            if ((props("java.specification.version")?.toIntOrNull() ?: 0) >= JFR_REDACT_MIN_JAVA) add(JFR_REDACT_FLAG)
            addAll(localeArgs(env, props))
            props("java.io.tmpdir")?.takeIf { it.isNotBlank() }?.let { add("-Djdk.net.unixdomain.tmpdir=$it") }
            val agent = if (CoroutineDebug.enabled(env)) CoroutineDebug.agentJarBeside(jar) else null
            agent?.let { add("-javaagent:$it") }
            addAll(startupCacheFlags(jar, agentArmed = agent != null))
            addAll(diagArgs(diagDir(env, props, windows), profileName))
            add("-jar")
            add(jar.toString())
            add("--config-dir")
            add(configRoot.toString())
            add("daemon")
            add("run")
            add(profileName)
        }

    /** The static flags from the launchers' single source beside the jar; a missing or unreadable
     *  file means no static flags (the deploy ships the file, jvm-flags.txt names this reader). */
    private fun staticJvmFlags(flagsFile: Path): List<String> =
        runCatching { Files.readAllLines(flagsFile) }.getOrNull()
            ?.map { it.substringBefore('#').trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    /** The child inherits the parent's locale: UNIDRIVE_LOCALE (xx / xx_YY) wins, else the parent
     *  JVM's own user.language/user.country — which its launcher derived from --locale/UNIDRIVE_LOCALE. */
    private fun localeArgs(
        env: (String) -> String?,
        props: (String) -> String?,
    ): List<String> {
        val parsed = env("UNIDRIVE_LOCALE")?.takeIf { it.isNotBlank() }?.let(LOCALE_SHAPE::matchEntire)
        val language = parsed?.groupValues?.get(1)?.lowercase() ?: props("user.language")
        if (language.isNullOrBlank()) return emptyList()
        val country =
            (
                parsed?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.uppercase()
                    ?: props("user.country")
            )?.takeIf { it.isNotBlank() }
        return buildList {
            add("-Duser.language=$language")
            if (country != null) add("-Duser.country=$country")
        }
    }

    /** The diagnostics dir: UNIDRIVE_DIAG_DIR overrides; the default sits under the OS data root
     *  (%LOCALAPPDATA%\unidrive\diagnostics, ~/.local/share/unidrive/diagnostics) — dumps are
     *  multi-GB transients, so never the roaming profile data. */
    internal fun diagDir(
        env: (String) -> String?,
        props: (String) -> String?,
        windows: Boolean,
    ): Path {
        env("UNIDRIVE_DIAG_DIR")?.takeIf { it.isNotBlank() }?.let { return Path.of(it) }
        return if (windows) {
            val localAppData =
                env("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                    ?: Path.of(props("user.home"), "AppData", "Local").toString()
            Path.of(localAppData, "unidrive", "diagnostics")
        } else {
            Path.of(props("user.home"), ".local", "share", "unidrive", "diagnostics")
        }
    }

    /** The child daemon's post-mortem flags into [dir]: where fatal-crash hs_err logs, the OOM
     *  heap dump (the dump's -XX:+HeapDumpOnOutOfMemoryError rides jvm-flags.txt) and the
     *  bounded GC log land, one per profile ([heapDumpFileName], [gcLogFileName]). The JVM
     *  silently skips a dump into a missing directory: the directory is created here, the paths
     *  are FILE names (no trailing separator). A directory that cannot be created degrades to the JVM's silent
     *  skip — the spawn itself never fails on diagnostics. */
    private fun diagArgs(
        dir: Path,
        profileName: String,
    ): List<String> {
        runCatching { Files.createDirectories(dir) }
        val sep = dir.fileSystem.separator
        val diag = dir.toString().removeSuffix(sep) + sep
        return listOf(
            "-XX:ErrorFile=${diag}hs_err_pid%p.log",
            "-XX:HeapDumpPath=$diag${heapDumpFileName(profileName)}",
            "-Xlog:gc*:file=$diag${gcLogFileName(profileName)}:time,uptime:filecount=5,filesize=10m",
        )
    }

    /** The profile's own GC log file, `gc-<profile>.log`: two daemons (one per profile) sharing one file
     *  cannot rotate it (the second JVM's rename of a file the first holds open fails with "Permission
     *  denied"); one daemon per profile (the profile lock) leaves no collision. The launchers build the same
     *  name (dist/launcher/unidrive.ps1.tmpl, unidrive.sh.tmpl). The profile-name rule already limits a name
     *  to letters, digits, `-` and `_`, which is file-safe. A name outside it is never escaped: it gives
     *  `gc-default.log`, as in the launchers, because the spawn never fails on diagnostics. */
    internal fun gcLogFileName(profileName: String): String =
        if (FILE_SAFE_PROFILE.matches(profileName)) "gc-$profileName.log" else "gc-default.log"

    private val isWindows: Boolean
        get() = Os.isWindows()

    /** The jar this CLI class was loaded from; null when running from loose classes (tests, IDE). */
    fun thisJar(): Path? {
        val location = DaemonAutospawn::class.java.protectionDomain.codeSource.location ?: return null
        val path =
            runCatching { Path.of(location.toURI()) }.getOrNull()
                ?: return null
        return if (path.toString().endsWith(".jar")) path else null
    }

    /** Detached start: the child outlives this client; its output goes to [logFile], not the terminal. */
    private fun defaultSpawn(
        command: List<String>,
        logFile: Path,
    ): SpawnedChild {
        Files.createDirectories(logFile.parent)
        val pb = ProcessBuilder(command)
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
        val process = pb.start() // deliberately not waited for: the daemon outlives the client that started it
        return { if (process.isAlive) null else process.exitValue() }
    }
}

/** A started daemon process: answers its exit code once it has ended, null while it runs (gkrost/unidrive-cli#455). */
internal typealias SpawnedChild = () -> Int?

/** How far back from the end of the spawn log [logTail] looks. The log is appended to by every spawn and never
 *  rotated, so reading it whole would make the error path cost as much as the log has grown. */
private const val LOG_TAIL_WINDOW_BYTES = 64 * 1024

/** The longest line [logTail] shows; a longer one (a minified stack, a binary dump) keeps its end. */
private const val LOG_TAIL_LINE_MAX_CHARS = 500

/** The last [lines] lines of [logFile] for an error message, each indented; empty when the file cannot be read.
 *  Reads at most [LOG_TAIL_WINDOW_BYTES] from the end of the file, so the work and memory do not grow with the log. */
internal fun logTail(
    logFile: Path,
    lines: Int = 5,
): String =
    runCatching {
        RandomAccessFile(logFile.toFile(), "r").use { file ->
            val start = maxOf(0L, file.length() - LOG_TAIL_WINDOW_BYTES)
            val bytes = ByteArray((file.length() - start).toInt())
            file.seek(start)
            file.readFully(bytes)
            val text = String(bytes, Charsets.UTF_8)
            // A window that starts mid-file begins inside a line: drop that fragment.
            val whole = if (start > 0) text.substringAfter('\n', "") else text
            whole.lines().dropLastWhile { it.isEmpty() }.takeLast(lines)
        }
    }.getOrDefault(emptyList())
        .joinToString("") { "\n    ${it.takeLast(LOG_TAIL_LINE_MAX_CHARS)}" }

/** The profile-name rule of the diagnostics file names: letters, digits, `-` and `_`, which is file-safe. */
private val FILE_SAFE_PROFILE = Regex("[A-Za-z0-9_-]+")

/** The profile's own OOM heap dump, `heap-<profile>.hprof`, under the profile-name rule of
 *  [DaemonAutospawn.gcLogFileName]. One FIXED file per profile: HotSpot does not overwrite an existing dump, so
 *  an OOM restart loop cannot stack a fresh ~2 GB hprof — which holds decrypted tokens — on every restart; the
 *  first dump stays for the operator to prune, and deleting it lets the next OOM dump again. The launchers build
 *  the same name (dist/launcher/unidrive.ps1.tmpl, unidrive.sh.tmpl). A pure name rule, so it sits beside
 *  [DaemonAutospawn] rather than in it: one function more in the object trips detekt's TooManyFunctions limit
 *  for objects (11). */
internal fun heapDumpFileName(profileName: String): String =
    if (FILE_SAFE_PROFILE.matches(profileName)) "heap-$profileName.hprof" else "heap-default.hprof"

/** The startup-cache flags for a JVM launched from [jar], the same choice the launcher templates make
 *  (dist/launcher/unidrive.ps1.tmpl, unidrive.sh.tmpl): the AOT cache of a bundled image wins when one
 *  lies beside the jar — and no coroutine-debug agent is armed, because the agent disables the cache and
 *  errors; else the dynamic CDS archive, and only when the jar's folder is writable, because an
 *  unwritable archive path makes the JVM exit 127 after an otherwise successful run (verified on JDK
 *  27). The -Xlog switches keep the first run's and a jar change's cache messages off stdout, which the
 *  client parses; a failed dump or map degrades to a run without a cache, never to a failed command.
 *  JDK 27 refuses the two caches in one JVM ("Option AOTCache cannot be used at the same time with ...
 *  SharedArchiveFile"), so exactly one of the two applies. Beside [DaemonAutospawn] for the same reason
 *  as [heapDumpFileName]: one function more in the object trips detekt's TooManyFunctions limit. */
internal fun startupCacheFlags(
    jar: Path,
    agentArmed: Boolean,
): List<String> {
    val aot = jar.resolveSibling("unidrive.aot")
    if (!agentArmed && Files.isRegularFile(aot)) {
        return listOf("-XX:AOTCache=$aot", "-Xlog:aot=off")
    }
    val dir = jar.parent
    if (dir != null && Files.isDirectory(dir) && Files.isWritable(dir)) {
        return listOf(
            "-XX:+AutoCreateSharedArchive",
            "-XX:SharedArchiveFile=${dir.resolve("unidrive.jsa")}",
            "-Xlog:cds=off",
        )
    }
    return emptyList()
}

private fun waitForDaemon(
    profileName: String,
    socketPath: Path,
    waitTimeoutMs: Long,
    probe: (String, Path) -> Boolean,
    logFile: Path,
    child: SpawnedChild? = null,
): Boolean {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitTimeoutMs)
    while (System.nanoTime() < deadline) {
        if (probe(profileName, socketPath)) return true
        // gkrost/unidrive-cli#455: a daemon that exits at once (credentials refused, profile lock held, IPC token files not writable)
        // will never bind: say so now, with its exit code and the log's tail, instead of waiting out the deadline.
        val exitCode = child?.invoke()
        if (exitCode != null) {
            // The daemon may have bound in its last moments before it ended: one more look settles that.
            if (probe(profileName, socketPath)) return true
            System.err.println(
                "unidrive: the daemon for profile '$profileName' exited at once (exit code $exitCode). Its output: $logFile" +
                    logTail(logFile) + "\nStart it in a terminal to see the error: `unidrive -p $profileName daemon run`.",
            )
            return false
        }
        Thread.sleep(200)
    }
    System.err.println(
        "unidrive: the daemon for profile '$profileName' did not come up within ${waitTimeoutMs / 1000} s. " +
            "Its output: $logFile. Start it in a terminal to see the error: `unidrive -p $profileName daemon run`.",
    )
    return false
}
