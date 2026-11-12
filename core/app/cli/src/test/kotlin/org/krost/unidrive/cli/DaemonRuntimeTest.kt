package org.krost.unidrive.cli

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Job
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanProgress
import org.krost.unidrive.io.OwnerOnly
import org.krost.unidrive.io.grantProblem
import org.krost.unidrive.sync.ProfileMode
import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcAuthClient
import org.krost.unidrive.sync.IpcAuthException
import org.krost.unidrive.sync.IpcEndpoint
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.time.Instant
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec test T1: `daemon_binds_socket_and_serves_hydration_verbs`.
 *
 * Pins the end-to-end happy path of the daemon lifecycle from
 * unidrive-daemon-design.md §3.2: ProcessLock(DAEMON) acquired →
 * StateDatabase opened → provider.authenticateAndLog() succeeded →
 * IpcServer bound → hydration.* handlers wired → graceful close.
 *
 * Does NOT use mockk — :app:cli has only kotlin-test on its test
 * classpath. The test stubs CloudProvider directly with a tiny inline
 * object since the only method T1 exercises is authenticateAndLog().
 */
class DaemonRuntimeTest {
    private lateinit var tempDir: Path
    private lateinit var lockFile: Path
    private lateinit var dbPath: Path
    private lateinit var socketPath: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("daemon-runtime-test")
        lockFile = tempDir.resolve(".lock")
        dbPath = tempDir.resolve("state.db")
        socketPath = tempDir.resolve("daemon.sock")
    }

    @AfterTest
    fun tearDown() {
        runCatching { Files.deleteIfExists(socketPath) }
        runCatching { Files.deleteIfExists(dbPath) }
        runCatching { Files.deleteIfExists(lockFile) }
        runCatching { Files.deleteIfExists(lockFile.resolveSibling(".lock.pid")) }
        runCatching { tempDir.toFile().deleteRecursively() }
    }

    // The daemon writes its IPC tokens next to the lock (the profile folder); every client authenticates.
    private fun connect(scope: IpcAuth.Scope = IpcAuth.Scope.FULL): SocketChannel =
        IpcAuthClient.connect(IpcEndpoint(socketPath, tempDir, "test_profile"), scope)

    private fun rawConnect(): SocketChannel = SocketChannel.open(UnixDomainSocketAddress.of(socketPath))

    @Test
    fun `the start line names the transfer budget, the upload queue depth and the cache grace`() {
        assertEquals(
            "hydration limits: 4 concurrent transfers, upload queue depth 256, cache access grace 60 s",
            DaemonRuntime.hydrationLimitsLine(4, 256, 60_000),
        )
    }

    @Test
    fun `autospawn recognizes a daemon whose tokens are separate from its socket`() =
        runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            socketPath = Files.createDirectory(tempDir.resolve("ipc")).resolve("daemon.sock")
            val runtime = startDaemon(StubProvider())
            val daemonJob = launch { runtime.start() }
            try {
                awaitSocket()
                assertTrue(DaemonAutospawn.ensureDaemonRunning(
                    profileName = "test_profile",
                    configDir = tempDir,
                    socketPath = socketPath,
                    locateJar = { error("a running authenticated daemon must not be spawned") },
                ))
            } finally {
                runtime.close()
                daemonJob.join()
            }
        }

    @Test
    fun `the daemon writes owner-only tokens before it listens and serves only authenticated connections`() =
        runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            val runtime = startDaemon(StubProvider())
            val daemonJob = launch { runtime.start() }
            try {
                awaitSocket(daemonJob)
                for (scope in IpcAuth.Scope.entries) {
                    val token = IpcAuth.tokenFile(tempDir, scope)
                    assertTrue(Files.exists(token), "token file $token must exist once the socket does")
                    assertEquals(null, OwnerOnly.grantProblem(token), "token file must be owner-only")
                }

                rawConnect().use { ch ->
                    ch.configureBlocking(false)
                    ch.write(ByteBuffer.wrap("{\"verb\":\"hydration.list\",\"prefix\":\"/\"}\n".toByteArray()))
                    assertEquals("""{"ok":false,"error":"auth_required"}""", readFirstReplyLine(ch, 5_000))
                    ch.write(ByteBuffer.wrap("{\"verb\":\"daemon.status\"}\n".toByteArray()))
                    val status = Json.parseToJsonElement(readFirstReplyLine(ch, 5_000)).jsonObject
                    // A client without a token file decides on exactly these two: version 2 = cannot
                    // authenticate, version 1 = a daemon without the handshake. Numbers and booleans, not strings.
                    assertEquals(setOf("ok", "protocol_version", "engine_version", "auth_required"), status.keys, "minimal before authentication")
                    assertEquals(kotlinx.serialization.json.JsonPrimitive(2), status.getValue("protocol_version"))
                    assertEquals(kotlinx.serialization.json.JsonPrimitive(true), status.getValue("auth_required"))
                    assertEquals(kotlinx.serialization.json.JsonPrimitive(true), status.getValue("ok"))
                }

                connect(IpcAuth.Scope.READ).use { ch ->
                    ch.configureBlocking(false)
                    ch.write(ByteBuffer.wrap("{\"verb\":\"daemon.shutdown\"}\n".toByteArray()))
                    assertEquals("""{"ok":false,"error":"forbidden","scope":"read"}""", readFirstReplyLine(ch, 5_000))
                    ch.write(ByteBuffer.wrap("{\"verb\":\"daemon.status\"}\n".toByteArray()))
                    assertTrue("\"uptime_ms\"" in readFirstReplyLine(ch, 5_000), "a read client gets the full status")
                }
            } finally {
                runtime.close()
                daemonJob.join()
            }
        }

    @Test
    fun `the daemon rotates its tokens at every start`() =
        runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            val first = startDaemon(StubProvider())
            val job1 = launch { first.start() }
            awaitSocket(job1)
            val oldToken = Files.readString(IpcAuth.tokenFile(tempDir, IpcAuth.Scope.FULL))
            first.close()
            job1.join()

            val second = startDaemon(StubProvider())
            val job2 = launch { second.start() }
            try {
                awaitSocket(job2)
                val newToken = Files.readString(IpcAuth.tokenFile(tempDir, IpcAuth.Scope.FULL))
                assertTrue(oldToken != newToken, "a new start writes new tokens")
                // A client still holding the previous token is refused; one that re-reads the file is not.
                val stale = Files.createDirectories(tempDir.resolve("stale"))
                Files.writeString(IpcAuth.tokenFile(stale, IpcAuth.Scope.FULL), oldToken)
                kotlin.test.assertFailsWith<IpcAuthException> {
                    IpcAuthClient.connect(IpcEndpoint(socketPath, stale, "test_profile"), IpcAuth.Scope.FULL)
                }
                connect().close()
            } finally {
                second.close()
                job2.join()
            }
        }

    @Test
    fun `a second start that loses the profile lock leaves the running daemon's tokens untouched`() =
        runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            val first = startDaemon(StubProvider())
            val job = launch { first.start() }
            try {
                awaitSocket(job)
                val files = IpcAuth.Scope.entries.map { IpcAuth.tokenFile(tempDir, it) }
                // Digests, so a failure message never shows a token.
                fun state() =
                    files.map {
                        java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(it)).joinToString("") { b -> "%02x".format(b) } to
                            Files.getLastModifiedTime(it)
                    }
                val before = state()

                // Same profile (lock), its own socket path: only the lock can stop it.
                var exitCode: Int? = null
                val secondSocket = tempDir.resolve("second.sock")
                val second =
                    DaemonRuntime(
                        profileMode = ProfileMode.MOUNT,
                        profileName = "test_profile",
                        lockFile = lockFile,
                        dbPath = dbPath,
                        syncRoot = tempDir,
                        socketPath = secondSocket,
                        providerFactory = { error("a start that lost the lock must not get this far") },
                        exitProcess = { exitCode = it },
                    )
                second.start()

                assertEquals(1, exitCode, "the second start is refused by the lock")
                assertEquals(before, state(), "token files untouched (content digest and mtime)")
                assertFalse(Files.exists(secondSocket))
                connect().close() // the running daemon's clients still authenticate
            } finally {
                first.close()
                job.join()
            }
        }

    @Test
    fun `a daemon that cannot write its token files refuses to start and never binds the socket`() =
        runBlocking {
            // A regular file where the profile folder should be: the token files cannot be created.
            val notAFolder = Files.writeString(tempDir.resolve("not-a-folder"), "x")
            val runtime =
                DaemonRuntime(
                    profileMode = ProfileMode.MOUNT,
                    profileName = "test_profile",
                    lockFile = lockFile,
                    dbPath = dbPath,
                    syncRoot = tempDir,
                    socketPath = socketPath,
                    providerFactory = { StubProvider() },
                    ipcTokenDir = notAFolder,
                )
            val stderr = java.io.ByteArrayOutputStream()
            val originalErr = System.err
            System.setErr(java.io.PrintStream(stderr, true, Charsets.UTF_8))
            val ex =
                try {
                    runCatching { runtime.start() }.exceptionOrNull()
                } finally {
                    System.setErr(originalErr)
                }

            assertTrue(ex is IpcAuth.StartupRefused, "got $ex")
            assertTrue(ex.message!!.startsWith("IPC startup refused: "), ex.message)
            assertTrue(notAFolder.toString() in ex.message!!, "the line names the path: ${ex.message}")
            val lines = stderr.toString(Charsets.UTF_8).lines().filter { it.startsWith("IPC startup refused:") }
            assertEquals(listOf(ex.message), lines, "exactly one refusal line on stderr")
            assertFalse(Files.exists(socketPath), "the socket is never bound without its tokens")
            assertFalse(Files.exists(lockFile.resolveSibling("${lockFile.fileName}.pid")), "the lock is released")
            assertEquals(78, IpcAuth.STARTUP_REFUSED_EXIT_CODE)
        }

    @Test
    fun `a corrupt state_db stops a mirror daemon with the typed error and leaves the file, no socket and no lock`() = runBlocking {
        // gkrost/unidrive-cli#581: outside mount mode the daemon only reports; the command turns this into one stderr
        // line and exit 65.
        val garbage = ByteArray(8192) { (it * 31 + 7).toByte() }
        Files.write(dbPath, garbage)
        val runtime =
            DaemonRuntime(
                profileMode = ProfileMode.MIRROR,
                profileName = "test_profile",
                lockFile = lockFile,
                dbPath = dbPath,
                syncRoot = tempDir,
                socketPath = socketPath,
                providerFactory = { StubProvider() },
            )
        val ex = runCatching { runtime.start() }.exceptionOrNull()

        assertTrue(ex is org.krost.unidrive.sync.StateDbCorruptException, "got $ex")
        assertEquals(dbPath, ex.dbPath)
        assertEquals(65, org.krost.unidrive.sync.StateDbCorruptException.EXIT_CODE)
        assertTrue(garbage.contentEquals(Files.readAllBytes(dbPath)), "a non-mount daemon must not move or rewrite a corrupt state.db")
        assertFalse(Files.exists(socketPath), "the socket is never bound")
        assertFalse(Files.exists(lockFile.resolveSibling("${lockFile.fileName}.pid")), "the lock is released")
    }

    @Test
    fun `a corrupt state_db in mount mode is moved aside, the daemon starts and its status says so`() = runBlocking {
        // Owner decision on gkrost/unidrive-cli#581.
        val garbage = ByteArray(8192) { (it * 31 + 7).toByte() }
        Files.write(dbPath, garbage)
        val runtime =
            DaemonRuntime(
                profileMode = ProfileMode.MOUNT,
                profileName = "test_profile",
                lockFile = lockFile,
                dbPath = dbPath,
                syncRoot = tempDir,
                socketPath = socketPath,
                providerFactory = { StubProvider() },
            )
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath)
        try {
            val reply = sendOneRequest("""{"verb":"daemon.status"}""")
            val recovery = kotlinx.serialization.json.Json.parseToJsonElement(reply)
                .let { it as kotlinx.serialization.json.JsonObject }["state_recovery"]
                .let { it as kotlinx.serialization.json.JsonObject }
            fun field(k: String) = (recovery[k] as kotlinx.serialization.json.JsonPrimitive).content
            assertEquals("corrupt", field("reason"))
            assertEquals("true", field("local_changes_lost"))
            assertTrue("lost" in field("message") && "rebuilt from the cloud" in field("message"), field("message"))
            val kept = java.nio.file.Path.of(field("moved_to"))
            assertTrue(kept.fileName.toString().startsWith("state.db.corrupt-"), "kept as state.db.corrupt-<time>: $kept")
            assertTrue(garbage.contentEquals(Files.readAllBytes(kept)), "the corrupt copy is kept byte for byte")
            assertTrue(Files.size(dbPath) > 8192 || !garbage.contentEquals(Files.readAllBytes(dbPath)), "a fresh state.db was created")
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    @Test
    fun `a healthy state_db in mount mode leaves status without a state_recovery`() = runBlocking {
        val runtime =
            DaemonRuntime(
                profileMode = ProfileMode.MOUNT,
                profileName = "test_profile",
                lockFile = lockFile,
                dbPath = dbPath,
                syncRoot = tempDir,
                socketPath = socketPath,
                providerFactory = { StubProvider() },
            )
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath)
        try {
            val reply = sendOneRequest("""{"verb":"daemon.status"}""")
            assertFalse("state_recovery" in reply, reply)
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    @Test
    fun daemon_fail_fast_on_auth_failure_does_not_bind_socket() = runBlocking {
        // Spec T4: provider.authenticate() throws → daemon refuses to bind
        // the socket, releases the lock, exits non-zero.
        val provider: CloudProvider = AuthFailingStubProvider()

        val runtime = DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "test_profile",
            lockFile = lockFile,
            dbPath = dbPath,
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { provider },
        )

        // start() rethrows the auth exception (per DaemonRuntime catch+rethrow).
        // For T4 to be cleanly testable, DaemonRuntime.start() must propagate
        // auth exceptions BEFORE the socket is bound. The catch block in start()
        // rethrows after cleanup() — that's the design contract this test pins.
        val ex = kotlin.runCatching {
            runtime.start()
        }.exceptionOrNull()

        assertTrue(
            ex is org.krost.unidrive.AuthenticationException ||
                ex?.cause is org.krost.unidrive.AuthenticationException,
            "auth failure must propagate; got: $ex",
        )

        // Invariant I4: socket file must NOT exist after auth failure.
        assertTrue(
            !Files.exists(socketPath),
            "socket file must not be left behind after auth failure; found $socketPath",
        )

        // Lock must be released — the .lock.pid sidecar should be gone.
        val pidFile = lockFile.resolveSibling("${lockFile.fileName}.pid")
        assertTrue(
            !Files.exists(pidFile),
            ".lock.pid sidecar must be cleaned up after auth failure; found $pidFile",
        )
    }

    @Test
    fun daemon_binds_socket_and_serves_hydration_verbs() = runBlocking {
        val provider: CloudProvider = StubProvider()

        val runtime = DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "test_profile",
            lockFile = lockFile,
            dbPath = dbPath,
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { provider },
        )

        val daemonJob = launch { runtime.start() }

        awaitDaemonSocket(socketPath)

        val channel = connect()
        try {
            // hydration.list takes "prefix", not "path" (cf. HydrationIpcHandler.handle()).
            // On a brand-new empty StateDatabase, list("/") returns Ok(emptyList()) → ok:true.
            val req = """{"verb":"hydration.list","prefix":"/"}""" + "\n"
            channel.write(ByteBuffer.wrap(req.toByteArray()))
            val buf = ByteBuffer.allocate(4096)
            channel.read(buf)
            buf.flip()
            val reply = String(buf.array(), 0, buf.limit())
            assertTrue(reply.contains("\"ok\":true"), "expected ok:true reply; got: $reply")
        } finally {
            channel.close()
        }

        runtime.close()
        daemonJob.join()
    }

    /**
     * A request for every documented verb, sent the instant the socket file
     * appears, must get a reply line. Pins the startup ordering: the daemon
     * binds the socket only after all handlers are registered, so there is no
     * window in which an accepted request is dropped ("no handler") and the
     * sender hangs. An unknown verb must also be answered (unknown_verb), not
     * dropped. daemon.shutdown goes last because its ack stops the daemon.
     */
    @Test
    fun verbs_sent_as_soon_as_the_socket_appears_all_get_a_reply() =
        runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            val runtime = DaemonRuntime(
                profileMode = ProfileMode.MOUNT,
                profileName = "test_profile",
                lockFile = lockFile,
                dbPath = dbPath,
                syncRoot = tempDir,
                socketPath = socketPath,
                providerFactory = { StubProvider() },
            )
            val daemonJob = launch { runtime.start() }
            try {
                awaitDaemonSocket(socketPath)

                // Each documented verb with its minimal real request. Distinct
                // paths so state-mutating verbs (create, open_write_begin) never
                // interact; every one of these is answered by a registered
                // handler, whether the outcome is ok or a typed error.
                val requests = listOf(
                    """{"verb":"daemon.status"}""",
                    // archive#778: a pause is followed by its resume at once, so the verbs after them meet a running daemon.
                    """{"verb":"daemon.pause","reason":"user","auto":false}""",
                    """{"verb":"daemon.resume"}""",
                    """{"verb":"hydration.open_read","handle_id":"h1","path":"/startup/read.txt"}""",
                    // The cache path is a JSON string: a Windows path's backslashes must be escaped.
                    """{"verb":"hydration.open_write","handle_id":"h2","path":"/startup/write.txt","cache_path":${kotlinx.serialization.json.JsonPrimitive(tempDir.resolve("write-cache.bin").toString())}}""",
                    """{"verb":"hydration.open_write_begin","path":"/startup/truncate.txt"}""",
                    """{"verb":"hydration.close_handle","handle_id":"h3"}""",
                    """{"verb":"hydration.handle_status","handle_id":"h5"}""", // gkrost/unidrive-cli#437: an unknown handle is an answer ("unknown"), not silence
                    """{"verb":"hydration.hydrate","path":"/startup/hydrate.txt"}""",
                    """{"verb":"hydration.dehydrate","path":"/startup/dehydrate.txt"}""",
                    """{"verb":"hydration.subscribe"}""",
                    """{"verb":"hydration.last_synced","path":"/startup/ls.txt"}""",
                    """{"verb":"hydration.matches_local","path":"/startup/ml.txt","local_path":${kotlinx.serialization.json.JsonPrimitive(tempDir.resolve("ml.bin").toString())}}""", // archive#1219: no such row is an answer ("unknown"), not silence
                    """{"verb":"hydration.list","prefix":"/"}""",
                    """{"verb":"hydration.stat","path":"/startup/stat.txt"}""", // gkrost/unidrive-cli#415: no such row is an answer ("unknown_path"), not silence
                    """{"verb":"hydration.locate","remote_ids":["startup-remote-id"]}""", // gkrost/unidrive-cli#632: an id no row or provider knows is an answer ("unknown"), not silence
                    """{"verb":"hydration.mkdir","path":"/startup"}""",
                    """{"verb":"hydration.unlink","path":"/startup/unlink.txt"}""",
                    """{"verb":"hydration.rmdir","path":"/startup/rmdir.txt"}""",
                    """{"verb":"hydration.cancel","path":"/startup/cancel.txt"}""",
                    """{"verb":"hydration.create","handle_id":"h4","path":"/startup/create.txt"}""",
                    """{"verb":"hydration.rename","old_path":"/startup/r1.txt","new_path":"/startup/r2.txt"}""",
                    """{"verb":"hydration.refuse_name","path":"/startup/twin.txt","sibling":"Twin.txt"}""", // gkrost/unidrive-cli#24
                    """{"verb":"sync.subscribe"}""",
                    """{"verb":"history.list"}""",
                    """{"verb":"history.subscribe"}""",
                    """{"verb":"refresh.run"}""",
                    """{"verb":"sync.enumerate"}""",
                    """{"verb":"metrics.query"}""",
                    """{"verb":"ignore.explain","path":"/startup/build/out.o"}""",
                    // A profile without views: the handler answers unknown_view.
                    """{"verb":"view.page","kind":"recents"}""",
                    // No handler exists for this verb; the client must still learn that.
                    """{"verb":"daemon.statusx"}""",
                    // §4.10: signs the stub account out (the corpus pins the reply shape); the daemon keeps running.
                    """{"verb":"auth.logout"}""",
                    // §4.11: this runtime has no sign-in target, so each answers sign_in_unavailable (still a reply).
                    """{"verb":"auth.begin"}""",
                    """{"verb":"auth.complete","handle":"h"}""",
                    """{"verb":"auth.login","email":"user@example.test","password":"x"}""",
                    // archive#1240: the stub provider has no credential check, so this answers unsupported (still a reply).
                    """{"verb":"auth.check"}""",
                    // share.create is answered (ok or a typed error), never unknown_verb: the stub provider cannot share, so it answers unsupported.
                    """{"verb":"share.create","path":"/startup/share.txt"}""",
                    """{"verb":"daemon.shutdown"}""",
                )
                // The IPC verb classes (IpcAuth) name exactly these verbs: each reaches a handler below,
                // so the class table holds no verb the daemon does not register, and none is missing.
                // (A pattern, not a JSON parse: the open_write line carries a raw Windows path there.)
                assertEquals(
                    IpcAuth.VERB_CLASSES.keys,
                    requests.map { Regex(""""verb":"([^"]+)"""").find(it)!!.groupValues[1] }.toSet() - "daemon.statusx",
                )
                for (request in requests) {
                    val channel = connect()
                    try {
                        channel.configureBlocking(false)
                        val req = request + "\n"
                        val w = ByteBuffer.wrap(req.toByteArray(Charsets.UTF_8))
                        while (w.hasRemaining()) channel.write(w)
                        val reply = readFirstReplyLine(channel, timeoutMs = IPC_STEP_BUDGET_MS)
                        val json = kotlinx.serialization.json.Json.parseToJsonElement(reply)
                        assertTrue(
                            json is kotlinx.serialization.json.JsonObject && json.containsKey("ok"),
                            "verb must be answered with a reply carrying ok; sent $request, got: $reply",
                        )
                        // A reply carrying ok is not enough: the server's own fallback for a
                        // verb with no handler is {"ok":false,"error":"unknown_verb"}, so with
                        // the socket bound before the handlers were registered every documented
                        // verb would still "get a reply" and this test would stay green. A
                        // documented verb must reach its handler; only the made-up one is unknown.
                        val error = json["error"]
                            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                        if (request.contains("daemon.statusx")) {
                            assertTrue(error == "unknown_verb", "an unregistered verb must be answered unknown_verb, got: $reply")
                        } else {
                            assertTrue(
                                error != "unknown_verb" && error != "missing_verb",
                                "documented verb reached no handler (socket bound before registration?); sent $request, got: $reply",
                            )
                        }
                    } finally {
                        channel.close()
                    }
                }
            } finally {
                runtime.close()
                daemonJob.join()
            }
        }

    /** Reads one \n-terminated reply line, failing loudly when none arrives in [timeoutMs]. */
    private suspend fun readFirstReplyLine(channel: SocketChannel, timeoutMs: Long): String {
        val collected = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !collected.contains('\n')) {
            val buf = ByteBuffer.allocate(8192)
            val n = channel.read(buf)
            if (n > 0) {
                buf.flip()
                collected.append(String(buf.array(), 0, buf.limit()))
            } else {
                delay(20)
            }
        }
        val newline = collected.indexOf('\n')
        check(newline >= 0) { "no reply line within ${timeoutMs}ms; collected: $collected" }
        return collected.substring(0, newline)
    }

    @Test
    fun `#419 graceful shutdown removes the socket file and its meta sibling`() = runBlocking {
        // IpcServer.defaultSocketPath writes this `.meta` next to a hashed socket name so a UI can
        // recover the profile name; a stopped daemon must not leave it (or the socket) behind.
        val metaPath = socketPath.resolveSibling("${socketPath.fileName}.meta")
        Files.writeString(metaPath, "test_profile\n")

        val runtime = DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "test_profile",
            lockFile = lockFile,
            dbPath = dbPath,
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { StubProvider() },
        )

        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath)

        runtime.close()
        daemonJob.join()

        assertFalse(Files.exists(socketPath), "socket file must be gone after the daemon stopped")
        assertFalse(Files.exists(metaPath), "meta file must be gone after the daemon stopped")
    }

    @Test
    fun `shutdownAndWait returns only after cleanup has released the socket and the lock`() = runBlocking {
        val runtime = DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "test_profile",
            lockFile = lockFile,
            dbPath = dbPath,
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { StubProvider() },
        )
        // Dispatchers.IO: shutdownAndWait blocks its caller, so start() must not share runBlocking's thread.
        val daemonJob = launch(kotlinx.coroutines.Dispatchers.IO) { runtime.start() }
        // Startup is fixture setup; the shutdown deadline below is the behavior under test.
        withTimeout(30_000) {
            while (!Files.exists(socketPath)) {
                check(!daemonJob.isCompleted) { "daemon stopped before binding its socket" }
                delay(50)
            }
        }
        val pidFile = lockFile.resolveSibling("${lockFile.fileName}.pid")
        assertTrue(Files.exists(pidFile), "daemon must hold the lock before shutdown")

        // What the JVM shutdown hook does: the hook thread must not return (letting the JVM halt)
        // before the main thread's cleanup has finished.
        val clean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runtime.shutdownAndWait(DaemonRuntime.SHUTDOWN_DEADLINE_MS)
        }

        assertTrue(clean, "shutdownAndWait must report that cleanup completed within the deadline")
        assertFalse(Files.exists(socketPath), "socket file must be gone when shutdownAndWait returns")
        assertFalse(Files.exists(pidFile), ".lock.pid must be gone when shutdownAndWait returns")
        daemonJob.join()
    }

    @Test
    fun `shutdownAndWait on a runtime that never started does not block`() {
        val runtime = DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "test_profile",
            lockFile = lockFile,
            dbPath = dbPath,
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { StubProvider() },
        )
        val t0 = System.nanoTime()
        assertTrue(runtime.shutdownAndWait(10_000))
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000, "must return immediately when nothing was started")
    }

    @Test
    fun `daemon_shutdown verb acks then runs the clean shutdown path`() = runBlocking {
        val runtime = DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "test_profile",
            lockFile = lockFile,
            dbPath = dbPath,
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { StubProvider() },
        )
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath)
        val pidFile = lockFile.resolveSibling("${lockFile.fileName}.pid")
        assertTrue(Files.exists(pidFile), "daemon must hold the lock before shutdown")

        val reply = sendOneRequest("""{"verb":"daemon.shutdown"}""")
        assertTrue(reply.contains("\"ok\":true"), "daemon.shutdown must ack before stopping; got: $reply")

        // start() returning means the serve scope was cancelled and cleanup() ran.
        kotlinx.coroutines.withTimeout(10_000) { daemonJob.join() }
        assertFalse(Files.exists(socketPath), "socket file must be gone after daemon.shutdown")
        assertFalse(Files.exists(pidFile), ".lock.pid must be gone after daemon.shutdown")
    }

    @Test
    fun `shutdown cancels and joins a hydration upload before closing the state database`() = runBlocking(Dispatchers.IO) {
        val provider = BlockingUploadProvider()
        // gkrost/unidrive-cli#72: this upload never ends, so it outlasts the drain and is cancelled; a short drain keeps the test fast.
        val runtime = startDaemon(provider, uploadDrainMs = 200)
        val daemonJob = launch { runtime.start() }
        try {
            awaitSocket(daemonJob)
            val created = Json.parseToJsonElement(
                sendOneRequest("""{"verb":"hydration.create","handle_id":"create-1","path":"/shutdown.txt"}"""),
            ).jsonObject
            assertTrue(created.getValue("ok").jsonPrimitive.content.toBoolean(), "create should succeed: $created")
            val cachePath = Path.of(created.getValue("cache_path").jsonPrimitive.content)
            Files.writeString(cachePath, "upload in flight")

            val cacheJson = kotlinx.serialization.json.JsonPrimitive(cachePath.toString()).toString()
            val opened = Json.parseToJsonElement(
                sendOneRequest("""{"verb":"hydration.open_write","handle_id":"write-1","path":"/shutdown.txt","cache_path":$cacheJson}"""),
            ).jsonObject
            assertTrue(opened.getValue("ok").jsonPrimitive.content.toBoolean(), "open_write should start the upload: $opened")
            withTimeout(5_000) { provider.uploadStarted.await() }

            runtime.close()
            withTimeout(10_000) { daemonJob.join() }
            assertTrue(provider.uploadStopped.isCompleted, "the upload scope must be joined before state.db closes")

            val reopened = StateDatabase(dbPath)
            reopened.initialize()
            try {
                val row = assertNotNull(reopened.getEntry("/shutdown.txt"))
                assertNull(row.remoteId, "an interrupted upload stays pending for startup replay")
                assertTrue("/shutdown.txt" in reopened.pendingUploadPaths())
            } finally {
                reopened.close()
            }
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    @Test
    fun `#72 a stop lets an upload that is transferring finish within the stop deadline`() = runBlocking(Dispatchers.IO) {
        val provider = HeldUploadProvider()
        val runtime = startDaemon(provider) // the production drain, HydrationImpl.UPLOAD_DRAIN_MS
        val daemonJob = launch { runtime.start() }
        try {
            awaitSocket(daemonJob)
            val created = Json.parseToJsonElement(
                sendOneRequest("""{"verb":"hydration.create","handle_id":"create-1","path":"/drain.txt"}"""),
            ).jsonObject
            assertTrue(created.getValue("ok").jsonPrimitive.content.toBoolean(), "create should succeed: $created")
            val cachePath = Path.of(created.getValue("cache_path").jsonPrimitive.content)
            Files.writeString(cachePath, "upload in flight")
            val cacheJson = kotlinx.serialization.json.JsonPrimitive(cachePath.toString()).toString()
            val opened = Json.parseToJsonElement(
                sendOneRequest("""{"verb":"hydration.open_write","handle_id":"write-1","path":"/drain.txt","cache_path":$cacheJson}"""),
            ).jsonObject
            assertTrue(opened.getValue("ok").jsonPrimitive.content.toBoolean(), "open_write should start the upload: $opened")
            withTimeout(5_000) { provider.uploadStarted.await() }

            // The upload lands one second into the stop, well inside the drain.
            val release = launch {
                delay(1_000)
                provider.gate.complete(Unit)
            }
            val startedAt = System.nanoTime()
            val clean = runtime.shutdownAndWait(DaemonRuntime.SHUTDOWN_DEADLINE_MS)
            val tookMs = (System.nanoTime() - startedAt) / 1_000_000
            release.join()
            withTimeout(5_000) { daemonJob.join() }

            assertTrue(clean, "the stop completes within SHUTDOWN_DEADLINE_MS while the upload drains")
            assertTrue(tookMs < DaemonRuntime.SHUTDOWN_DEADLINE_MS, "the stop took $tookMs ms")
            assertEquals(1, provider.uploads.get(), "the upload ran once and was not cancelled")
            val reopened = StateDatabase(dbPath)
            reopened.initialize()
            try {
                val row = assertNotNull(reopened.getEntry("/drain.txt"))
                assertNotNull(row.remoteId, "the drained upload is synced: nothing is replayed at the next start")
                assertFalse("/drain.txt" in reopened.pendingUploadPaths())
            } finally {
                reopened.close()
            }
        } finally {
            provider.gate.complete(Unit)
            runtime.close()
            daemonJob.join()
        }
    }

    @Test
    fun `#72 the stop deadlines leave room for the upload drain`() {
        assertEquals(
            org.krost.unidrive.hydration.HydrationImpl.UPLOAD_DRAIN_MS + DaemonRuntime.SHUTDOWN_CLEANUP_MS,
            DaemonRuntime.SHUTDOWN_DEADLINE_MS,
        )
        assertTrue(DaemonStopCommand.STOP_DEADLINE_MS > DaemonRuntime.SHUTDOWN_DEADLINE_MS, "daemon stop waits longer than the daemon's own stop")
        assertEquals(10_000L, SyncShutdown.DEADLINE_MS, "unidrive sync drains no mount uploads: its deadline stays")
    }

    @Test
    fun `daemon_status reports the effective scope`() = runBlocking {
        val runtime = DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "test_profile",
            lockFile = lockFile,
            dbPath = dbPath,
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { StubProvider() },
            syncPaths = listOf("/_INBOX", "/Docs \"x\""),
        )
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath)
        try {
            val reply = sendOneRequest("""{"verb":"daemon.status"}""")
            val scope = kotlinx.serialization.json.Json.parseToJsonElement(reply)
                .let { it as kotlinx.serialization.json.JsonObject }["sync_paths"]
                .let { it as kotlinx.serialization.json.JsonArray }
                .map { (it as kotlinx.serialization.json.JsonPrimitive).content }
            assertEquals(listOf("/_INBOX", "/Docs \"x\""), scope, "daemon.status must list the scope as sync_paths; got: $reply")
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    // archive#463: a status client shows how often the daemon polls the cloud (0 = off).
    @Test
    fun `daemon_status reports the effective poll interval`() = runBlocking {
        val runtime = DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "test_profile",
            lockFile = lockFile,
            dbPath = dbPath,
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { StubProvider() },
            pollIntervalMs = 60_000,
        )
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath)
        try {
            val reply = sendOneRequest("""{"verb":"daemon.status"}""")
            val interval = kotlinx.serialization.json.Json.parseToJsonElement(reply)
                .let { it as kotlinx.serialization.json.JsonObject }["poll_interval_ms"]
                .let { (it as kotlinx.serialization.json.JsonPrimitive).content }
            assertEquals("60000", interval, "daemon.status must report poll_interval_ms; got: $reply")
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    // gkrost/unidrive-cli#392: transfer_budget is how many hydration downloads a client should run at once. It comes from the
    // provider TYPE's audited limits, which the daemon now hands the engine (it passed no provider id before, so every
    // profile ran at the fallback of 4), and it narrows to the transfer cap after the provider throttled.
    private fun transferBudgetOf(reply: String): Int =
        (Json.parseToJsonElement(reply).jsonObject["transfer_budget"] ?: error("daemon.status must carry transfer_budget: $reply"))
            .jsonPrimitive.int

    private class ProviderOfType(
        override val id: String,
    ) : StubProvider() {
        @Volatile
        var sink: org.krost.unidrive.http.RequestMetricsSink? = null

        override fun attachRequestMetrics(sink: org.krost.unidrive.http.RequestMetricsSink?) {
            this.sink = sink
        }
    }

    @Test
    fun `daemon_status reports the transfer budget of the provider type`() = runBlocking {
        for ((type, expected) in listOf("internxt" to 8, "onedrive" to 8, "stub" to 4)) {
            val runtime = DaemonRuntime(
                profileMode = ProfileMode.MOUNT,
                profileName = "test_profile",
                lockFile = lockFile,
                dbPath = dbPath,
                syncRoot = tempDir,
                socketPath = socketPath,
                providerFactory = { ProviderOfType(type) },
            )
            val daemonJob = launch { runtime.start() }
            awaitDaemonSocket(socketPath)
            try {
                val reply = sendOneRequest("""{"verb":"daemon.status"}""")
                assertEquals(expected, transferBudgetOf(reply), "transfer_budget of a $type profile; got: $reply")
            } finally {
                runtime.close()
                daemonJob.join()
            }
        }
    }

    @Test
    fun `daemon_status narrows the transfer budget after the provider throttled`() = runBlocking {
        val provider = ProviderOfType("internxt")
        val runtime = DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "test_profile",
            lockFile = lockFile,
            dbPath = dbPath,
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { provider },
        )
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath)
        try {
            assertEquals(8, transferBudgetOf(sendOneRequest("""{"verb":"daemon.status"}""")))
            val sink = assertNotNull(provider.sink, "the daemon attaches a request sink to every provider, recording or not")
            sink.record(
                org.krost.unidrive.http.RequestAttempt(
                    startedAtMs = System.currentTimeMillis(),
                    provider = "internxt",
                    endpointClass = "GET /files/meta",
                    method = "GET",
                    outcome = org.krost.unidrive.http.AttemptOutcome.THROTTLED,
                    headersMs = 5,
                    totalMs = 5,
                    bytesUp = null,
                    bytesDown = null,
                    retryNo = 0,
                    throttled = true,
                ),
            )
            assertEquals(2, transferBudgetOf(sendOneRequest("""{"verb":"daemon.status"}""")), "a throttle narrows it to the transfer cap")
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    // gkrost/unidrive-cli#74: the provider's own account of its HTTP calls, so a stalled transfer can be diagnosed
    // without reading the log. Present for a provider that can account for them, absent for one that cannot.
    @Test
    fun `daemon_status reports the provider's requests in flight and free slots`() = runBlocking {
        val runtime =
            DaemonRuntime(
                profileMode = ProfileMode.MOUNT,
                profileName = "test_profile",
                lockFile = lockFile,
                dbPath = dbPath,
                syncRoot = tempDir,
                socketPath = socketPath,
                providerFactory = {
                    object :
                        StubProvider(),
                        org.krost.unidrive.RequestStatusReporting {
                        override fun requestStatus(): org.krost.unidrive.http.RequestStatusSnapshot =
                            org.krost.unidrive.http.RequestStatusSnapshot(
                                listOf(org.krost.unidrive.http.InFlightRequests("GET /drive/files", 2, 45_000)),
                                mapOf("drive" to 0, "bridge" to 4),
                                mapOf("GET /drive/files" to 7L, "PUT /bridge/shard" to 2L),
                            )
                    }
                },
            )
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath)
        try {
            val reply = sendOneRequest("""{"verb":"daemon.status"}""")
            val requests =
                Json.parseToJsonElement(reply).jsonObject["requests"]
                    ?: error("daemon.status must carry requests for a provider that reports them: $reply")
            val open = requests.jsonObject["in_flight"]!!.jsonArray.single().jsonObject
            assertEquals("GET /drive/files", open["class"]!!.jsonPrimitive.content)
            assertEquals(2, open["count"]!!.jsonPrimitive.int)
            assertEquals(45_000L, open["oldest_ms"]!!.jsonPrimitive.long)
            assertEquals(0, requests.jsonObject["free_slots"]!!.jsonObject["drive"]!!.jsonPrimitive.int)
            assertEquals(4, requests.jsonObject["free_slots"]!!.jsonObject["bridge"]!!.jsonPrimitive.int)
            // gkrost/unidrive-cli#460: attempts per class since the start, as the provider counted them.
            val total = requests.jsonObject["total"]!!.jsonObject
            assertEquals(setOf("GET /drive/files", "PUT /bridge/shard"), total.keys)
            assertEquals(7L, total["GET /drive/files"]!!.jsonPrimitive.long)
            assertEquals(2L, total["PUT /bridge/shard"]!!.jsonPrimitive.long)
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    // A budget name is the provider service's own string; a quote or backslash in it must not break the reply.
    @Test
    fun `daemon_status escapes a free-slots budget name that needs JSON escaping`() = runBlocking {
        val awkward = "dr\"ive\\x"
        val runtime =
            DaemonRuntime(
                profileMode = ProfileMode.MOUNT,
                profileName = "test_profile",
                lockFile = lockFile,
                dbPath = dbPath,
                syncRoot = tempDir,
                socketPath = socketPath,
                providerFactory = {
                    object :
                        StubProvider(),
                        org.krost.unidrive.RequestStatusReporting {
                        override fun requestStatus(): org.krost.unidrive.http.RequestStatusSnapshot =
                            org.krost.unidrive.http.RequestStatusSnapshot(emptyList(), mapOf(awkward to 3))
                    }
                },
            )
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath)
        try {
            val reply = sendOneRequest("""{"verb":"daemon.status"}""")
            val slots = Json.parseToJsonElement(reply).jsonObject["requests"]!!.jsonObject["free_slots"]!!.jsonObject
            assertEquals(3, slots[awkward]!!.jsonPrimitive.int)
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    @Test
    fun `daemon_status carries no requests object for a provider that cannot account for its calls`() = runBlocking {
        val runtime =
            DaemonRuntime(
                profileMode = ProfileMode.MOUNT,
                profileName = "test_profile",
                lockFile = lockFile,
                dbPath = dbPath,
                syncRoot = tempDir,
                socketPath = socketPath,
                providerFactory = { StubProvider() },
            )
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath)
        try {
            val reply = sendOneRequest("""{"verb":"daemon.status"}""")
            assertNull(
                Json.parseToJsonElement(reply).jsonObject["requests"],
                "no provider account, no field: $reply",
            )
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    // gkrost/unidrive-cli#460: daemon.status counts the requests each verb's handler served since the start, so a client's
    // choice of verbs (one hydration.stat instead of a folder hydration.list) can be checked on a live daemon.
    @Test
    fun `daemon_status ipc requests move by exactly the calls made`() = runBlocking {
        val runtime =
            DaemonRuntime(
                profileMode = ProfileMode.MOUNT,
                profileName = "test_profile",
                lockFile = lockFile,
                dbPath = dbPath,
                syncRoot = tempDir,
                socketPath = socketPath,
                providerFactory = { StubProvider() },
            )
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath)
        try {
            fun counts(reply: String): Map<String, Long> =
                Json.parseToJsonElement(reply).jsonObject["ipc"]!!.jsonObject["requests"]!!.jsonObject
                    .mapValues { it.value.jsonPrimitive.long }

            val before = counts(sendOneRequest("""{"verb":"daemon.status"}"""))
            assertEquals(1L, before["daemon.status"], "a daemon.status reply counts itself: $before")
            repeat(3) { sendOneRequest("""{"verb":"hydration.list","prefix":"/"}""") }
            repeat(2) { sendOneRequest("""{"verb":"hydration.stat","path":"/nothing-here.txt"}""") }
            // Not counted: a verb no handler serves (a client cannot add keys) and a line without a verb.
            sendOneRequest("""{"verb":"no.such.verb"}""")
            sendOneRequest("""{"no_verb":true}""")
            val after = counts(sendOneRequest("""{"verb":"daemon.status"}"""))

            val moved = (before.keys + after.keys).associateWith { (after[it] ?: 0L) - (before[it] ?: 0L) }.filterValues { it != 0L }
            assertEquals(
                mapOf("daemon.status" to 1L, "hydration.list" to 3L, "hydration.stat" to 2L),
                moved,
                "every call made, and only those: before=$before after=$after",
            )
            assertTrue("no.such.verb" !in after, "an unknown verb is not a key: $after")
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    private fun sendOneRequest(request: String): String {
        val channel = connect()
        try {
            channel.configureBlocking(false)
            channel.write(ByteBuffer.wrap((request + "\n").toByteArray()))
            val collected = StringBuilder()
            val deadline = System.currentTimeMillis() + IPC_STEP_BUDGET_MS
            while (System.currentTimeMillis() < deadline && !collected.contains('\n')) {
                val buf = ByteBuffer.allocate(4096)
                val n = channel.read(buf)
                if (n > 0) {
                    buf.flip()
                    collected.append(String(buf.array(), 0, buf.limit()))
                } else {
                    Thread.sleep(20)
                }
            }
            check(collected.contains('\n')) { "no reply line within ${IPC_STEP_BUDGET_MS}ms; collected: $collected" }
            return collected.toString().substringBefore('\n')
        } finally {
            channel.close()
        }
    }

    @Test
    fun refresh_run_emits_terminal_event_after_completing_enumeration() = runBlocking(Dispatchers.IO) {
        // Spec T5: subscribe -> refresh.run -> await refresh.done terminal event.
        // Then re-issue refresh.run; must succeed (not stuck in 'busy').
        val provider: CloudProvider = StubProvider()

        val runtime = DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "test_profile",
            lockFile = lockFile,
            dbPath = dbPath,
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { provider },
        )

        val daemonJob = launch { runtime.start() }

        awaitDaemonSocket(socketPath)

        val channel = connect()
        try {
            // Non-blocking reads so the deadline loop below actually polls the
            // deadline. With blocking reads, a read with nothing buffered hangs
            // forever and the test JVM never exits even on assertion failure.
            channel.configureBlocking(false)
            // Subscribe first
            // Every wait below is a ceiling on the shared budget (IPC_STEP_BUDGET_MS), not a pace: it returns
            // the moment the line arrives, and a stall names its step and the time spent.
            channel.write(ByteBuffer.wrap(("""{"verb":"sync.subscribe"}""" + "\n").toByteArray()))
            val subReply = awaitIpcLine(channel, "sync.subscribe reply") { !it.contains("\"event\"") }
            assertTrue(subReply.contains("\"ok\":true"), "subscribe must succeed; got: $subReply")

            // Issue refresh.run
            channel.write(ByteBuffer.wrap(("""{"verb":"refresh.run"}""" + "\n").toByteArray()))

            // Read until we see both the run's reply and the terminal event "refresh.done". A run on an empty
            // remote can finish before the reply line is written, so the two may arrive in either order. The
            // event is judged on its own line: the reply also says ok:true, which once made this check vacuous.
            val (runReply, terminal) =
                awaitIpcLines(
                    channel,
                    "refresh.run reply and refresh.done terminal event",
                    IPC_STEP_BUDGET_MS,
                    { !it.contains("\"event\"") },
                    { it.contains("\"event\":\"refresh.done\"") },
                )
            assertTrue(runReply.contains("\"ok\":true"), "refresh.run must be accepted; got: $runReply")
            assertTrue(terminal.contains("\"ok\":true"), "expected ok:true on the refresh.done event; got: $terminal")

            // Issue refresh.run again - must NOT be 'busy' since first one completed. The reply is the next line
            // that is not an event (a pushed enumerate.done carries a job_id too).
            channel.write(ByteBuffer.wrap(("""{"verb":"refresh.run"}""" + "\n").toByteArray()))
            val secondReply = awaitIpcLine(channel, "second refresh.run reply") { !it.contains("\"event\"") }
            assertTrue(
                secondReply.contains("\"ok\":true"),
                "second refresh.run must succeed (not busy); got: $secondReply",
            )
        } finally {
            channel.close()
        }

        runtime.close()
        daemonJob.join()
    }

    @Test
    fun view_invalidated_pushed_on_detected_remote_change_after_subscribe() = runBlocking {
        // Reactive-freshness invariant: when the FUSE co-daemon issues hydration.subscribe
        // on mount, the daemon runs ONE reactive enumerate; because the provider's delta
        // reports a remote file, state.db is mutated and the engine pushes a view.invalidated
        // event back over the SAME subscribe stream — without any manual `refresh` or poll loop.
        val provider: CloudProvider = OneRemoteFileStubProvider()

        val runtime = DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "test_profile",
            lockFile = lockFile,
            dbPath = dbPath,
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { provider },
        )

        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath, daemonJob)

        val channel = connect()
        try {
            channel.configureBlocking(false)
            // Subscribe via hydration.subscribe (the verb the co-daemon issues on mount).
            channel.write(ByteBuffer.wrap(("""{"verb":"hydration.subscribe"}""" + "\n").toByteArray()))
            // The subscribe ok-reply and the view.invalidated event (pushed once the auto-enumerate has mutated
            // state.db) are awaited as COMPLETE lines under the shared budget (IPC_STEP_BUDGET_MS, a ceiling and not
            // a pace). The event used to be found as a substring of whatever had been read so far, which can stop in
            // the middle of its line, and under a 10 s ceiling that named nothing when it ran out.
            val (reply, event) = awaitViewInvalidated(channel)
            assertTrue(reply.contains("\"ok\":true"), "subscribe must succeed; got: $reply")
            assertEquals(
                listOf("/remote-new.txt"),
                Json.parseToJsonElement(event).jsonObject.getValue("paths").jsonArray.map { it.jsonPrimitive.content },
                "view.invalidated must name the newly-enumerated path; got: $event",
            )
        } finally {
            channel.close()
        }

        runtime.close()
        daemonJob.join()
    }

    /** The subscribe reply and the view.invalidated line that follows it, each a complete line. */
    private suspend fun awaitViewInvalidated(channel: SocketChannel): Pair<String, String> {
        val (reply, event) =
            awaitIpcLines(
                channel,
                "hydration.subscribe reply and the view.invalidated push of the subscribe-triggered enumerate",
                IPC_STEP_BUDGET_MS,
                { !it.contains("\"event\"") },
                { it.contains("\"event\":\"view.invalidated\"") },
            )
        return reply to event
    }

    @Test
    fun `the subscribe-triggered enumerate does not hold the subscriber connection's reader`() = runBlocking(Dispatchers.IO) {
        // #436: the enumerate a full-scope hydration.subscribe triggers runs detached from the connection's
        // reader. It used to be awaited inside the post-reply hook, which runs on the reader coroutine: for a
        // first scan that runs long the socket went unread for the whole pass — a client that went away was
        // noticed only when the scan ended (close listeners delayed, the connection still counted against the
        // client cap) and no later request on the connection was answered. The provider's delta is gated
        // below, so a reply on this same connection can only come from a reader that stayed free.
        val gate = CompletableDeferred<Unit>()
        val runtime = startDaemon(ScriptedDeltaProvider(gate = gate))
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath, daemonJob)
        val channel = connect()
        try {
            channel.configureBlocking(false)
            channel.write(ByteBuffer.wrap(("""{"verb":"hydration.subscribe"}""" + "\n").toByteArray()))
            val reply = awaitIpcLine(channel, "hydration.subscribe reply") { !it.contains("\"event\"") }
            assertTrue(reply.contains("\"ok\":true"), "subscribe must succeed; got: $reply")

            // The triggered enumerate holds inside the provider's gate: running with the provider's own
            // progress report (reported before the gate), so it is mid-pass and stays so until the finally.
            awaitEnumeration { it.getValue("state").jsonPrimitive.content == "running" && it["items"]?.jsonPrimitive?.int == 1_234 }

            // The discriminating step: a request on the SAME connection must be answered while the
            // enumerate still holds. A reader parked in the post-reply await cannot answer it.
            channel.write(ByteBuffer.wrap(("""{"verb":"daemon.status"}""" + "\n").toByteArray()))
            val status =
                awaitIpcLine(channel, "daemon.status while the triggered enumerate still holds", budgetMs = 20_000) {
                    it.contains("\"uptime_ms\"")
                }
            val enumeration = Json.parseToJsonElement(status).jsonObject.getValue("enumeration").jsonObject
            assertEquals("running", enumeration.getValue("state").jsonPrimitive.content, "the enumerate is still in flight; got: $status")
        } finally {
            gate.complete(Unit)
            channel.close()
        }

        runtime.close()
        daemonJob.join()
    }

    @Test
    fun `a read-scope subscribe is accepted and starts no enumerate`() = runBlocking {
        // An observer (a status UI) holds a READ token: it may subscribe to events, but a subscribe
        // must not start a scan of the remote on its behalf. Only a full-scope mount client's
        // subscribe triggers the reactive enumerate.
        val provider = OneRemoteFileStubProvider()
        val runtime = startDaemon(provider)
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath, daemonJob)

        val channel = connect(IpcAuth.Scope.READ)
        try {
            channel.configureBlocking(false)
            channel.write(ByteBuffer.wrap(("""{"verb":"hydration.subscribe"}""" + "\n").toByteArray()))
            val reply = readFirstReplyLine(channel, 5_000)
            assertTrue(reply.startsWith("""{"ok":true,"tail_seq":"""), "the reply does not depend on the scope: $reply")

            // The enumerate of a full-scope subscribe pushes view.invalidated within milliseconds
            // (see the test above); give a wrongly scheduled one ample time to show.
            val pushed = readUntil(channel, "view.invalidated", timeoutMs = 2_000)
            assertTrue(!pushed.contains("view.invalidated"), "a read-scope subscribe must not enumerate; got: $pushed")
            assertEquals(0, provider.deltaCalls.get(), "no enumerate job may start")

            val status = Json.parseToJsonElement(sendOneRequest("""{"verb":"daemon.status"}""")).jsonObject
            assertTrue(status.getValue("refresh_job_id") is kotlinx.serialization.json.JsonNull, "no refresh job: $status")
            assertEquals(0, provider.deltaCalls.get())
        } finally {
            channel.close()
        }

        runtime.close()
        daemonJob.join()
    }

    @Test
    fun `the subscribe-time enumerate is for full scope or an unauthenticated server only`() {
        // The daemon always runs with authentication; the auth-off branch is the protocol-1 fallback.
        assertTrue(DaemonRuntime.subscribeEnumerates(authEnabled = true, scope = "full"))
        assertTrue(!DaemonRuntime.subscribeEnumerates(authEnabled = true, scope = "read"))
        assertTrue(!DaemonRuntime.subscribeEnumerates(authEnabled = true, scope = null), "an unknown scope is not full")
        assertTrue(DaemonRuntime.subscribeEnumerates(authEnabled = false, scope = null))
    }

    @Test
    fun `a full-scope subscribe enumerates exactly once`() = runBlocking {
        val provider = OneRemoteFileStubProvider()
        val runtime = startDaemon(provider)
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath, daemonJob)

        val channel = connect(IpcAuth.Scope.FULL)
        try {
            channel.configureBlocking(false)
            channel.write(ByteBuffer.wrap(("""{"verb":"hydration.subscribe"}""" + "\n").toByteArray()))
            // Fails with the step and every line seen when the push never comes (shared budget, complete lines).
            awaitViewInvalidated(channel)
            delay(500) // a second, unwanted enumerate would have started by now
            assertEquals(1, provider.deltaCalls.get(), "exactly one enumerate per subscribe")
        } finally {
            channel.close()
        }

        runtime.close()
        daemonJob.join()
    }

    @Test
    fun daemon_status_returns_uptime_clients_and_refresh_state() = runBlocking {
        val provider: CloudProvider = StubProvider()

        val runtime = DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "test_profile",
            lockFile = lockFile,
            dbPath = dbPath,
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { provider },
        )

        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath, daemonJob)

        val channel = connect()
        channel.configureBlocking(false)
        try {
            channel.write(ByteBuffer.wrap(("""{"verb":"daemon.status"}""" + "\n").toByteArray()))
            val reply = awaitIpcLine(channel, "daemon.status reply") { !it.contains("\"event\"") }
            assertTrue(reply.contains("\"ok\":true"), "expected ok:true; got: $reply")
            assertTrue(
                reply.contains("\"protocol_version\":${DaemonRuntime.IPC_PROTOCOL_VERSION}"),
                "expected protocol_version handshake field; got: $reply",
            )
            assertTrue(reply.contains("\"uptime_ms\""), "expected uptime_ms field; got: $reply")
            assertTrue(reply.contains("\"clients_connected\""), "expected clients_connected; got: $reply")
            assertTrue(reply.contains("\"refresh_in_flight\":false"), "expected refresh_in_flight:false; got: $reply")
            assertTrue(reply.contains("\"refresh_job_id\":null"), "expected refresh_job_id:null; got: $reply")
            assertTrue(reply.contains("\"provider\":\"stub\""), "expected provider id; got: $reply")
            assertTrue(reply.contains("\"provider_name\":\"Stub\""), "expected provider display name; got: $reply")
            assertTrue(reply.contains("\"authenticated\":true"), "expected auth state; got: $reply")
        } finally {
            channel.close()
        }

        runtime.close()
        daemonJob.join()
    }

    /**
     * Poll [channel] (configured non-blocking) into a StringBuilder until either
     * [needle] appears in collected bytes or [timeoutMs] elapses. Used by T5 to
     * avoid the blocking-read hang that would otherwise prevent the test JVM
     * from exiting on assertion failure or daemon stall.
     */
    private suspend fun readUntil(
        channel: SocketChannel,
        needle: String,
        timeoutMs: Long,
    ): String {
        val collected = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !collected.contains(needle)) {
            val buf = ByteBuffer.allocate(4096)
            val n = channel.read(buf)
            if (n > 0) {
                buf.flip()
                collected.append(String(buf.array(), 0, buf.limit()))
            } else {
                delay(20)
            }
        }
        return collected.toString()
    }

    // ---- the enumeration object of daemon.status -----------------------------------------------------------------------

    private fun enumerationOf(reply: String): JsonObject = Json.parseToJsonElement(reply).jsonObject.getValue("enumeration").jsonObject

    // ── a profile signed in before ids were kept: the daemon records the user of its credentials at start ──────────────

    private class IdentityStubProvider(
        private val userId: String?,
        private val failure: Exception? = null,
    ) : StubProvider() {
        @Volatile var identityCalls: Int = 0

        override suspend fun currentAccountId(): String? {
            identityCalls += 1
            failure?.let { throw it }
            return userId
        }
    }

    private suspend fun awaitAccountId(timeoutMs: Long = 10_000): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            org.krost.unidrive.AccountOwner.read(tempDir)?.let { return it }
            delay(50)
        }
        return null
    }

    @Test
    fun `a daemon start with credentials and no account id records the id of the user`() =
        runBlocking(Dispatchers.IO) {
            val provider = IdentityStubProvider("user-1")
            val runtime = startDaemon(provider)
            val daemonJob = launch { runtime.start() }
            try {
                awaitSocket(daemonJob)
                assertEquals("user-1", awaitAccountId())
                assertEquals(1, provider.identityCalls)
            } finally {
                runtime.close()
                daemonJob.join()
            }
        }

    @Test
    fun `a daemon start with a stored account id leaves it and asks nobody`() =
        runBlocking(Dispatchers.IO) {
            org.krost.unidrive.AccountOwner.record(tempDir, "user-1")
            val provider = IdentityStubProvider("user-2")
            val runtime = startDaemon(provider)
            val daemonJob = launch { runtime.start() }
            try {
                awaitSocket(daemonJob)
                delay(500)
                assertEquals("user-1", org.krost.unidrive.AccountOwner.read(tempDir))
                assertEquals(0, provider.identityCalls)
            } finally {
                runtime.close()
                daemonJob.join()
            }
        }

    @Test
    fun `a failing identity call at start records nothing and the daemon serves`() =
        runBlocking(Dispatchers.IO) {
            val provider = IdentityStubProvider("user-1", failure = java.io.IOException("connection reset"))
            val runtime = startDaemon(provider)
            val daemonJob = launch { runtime.start() }
            try {
                awaitSocket(daemonJob)
                connect(IpcAuth.Scope.READ).use { ch ->
                    ch.write(ByteBuffer.wrap("{\"verb\":\"daemon.status\"}\n".toByteArray()))
                    assertTrue(readFirstReplyLine(ch, 5_000).contains("\"ok\":true"))
                }
                assertTrue(provider.identityCalls >= 1)
                assertNull(org.krost.unidrive.AccountOwner.read(tempDir))
            } finally {
                runtime.close()
                daemonJob.join()
            }
        }

    private fun startDaemon(
        provider: CloudProvider,
        quotaRefreshMs: Long = 15 * 60_000L,
        providerHasQuota: Boolean = true,
        startupIoDispatcher: CoroutineDispatcher = Dispatchers.IO,
        uploadDrainMs: Long = org.krost.unidrive.hydration.HydrationImpl.UPLOAD_DRAIN_MS,
    ) = DaemonRuntime(
        profileMode = ProfileMode.MOUNT,
        profileName = "test_profile",
        lockFile = lockFile,
        dbPath = dbPath,
        syncRoot = tempDir,
        socketPath = socketPath,
        providerFactory = { provider },
        quotaRefreshMs = quotaRefreshMs,
        providerHasQuota = providerHasQuota,
        startupIoDispatcher = startupIoDispatcher,
        uploadDrainMs = uploadDrainMs,
        )

    // Startup is fixture setup: delegates to the shared archive#625 helper (30 s ceiling, died-start check).
    private suspend fun awaitSocket(daemonJob: Job? = null) = awaitDaemonSocket(socketPath, daemonJob)

    // ── archive#678: startup work must not delay a stop ─────────────────────────────────────────────

    /** A dispatcher that queues tasks until [release], then runs them on the caller's thread. */
    private class HoldingDispatcher : CoroutineDispatcher() {
        private val queue = java.util.ArrayDeque<Runnable>()

        @Volatile private var released = false

        val size: Int
            get() = synchronized(queue) { queue.size }

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            synchronized(queue) {
                if (released) block.run() else queue.add(block)
            }
        }

        /** Run everything queued so far. Called after the stop was issued, so the queued startup
         * work observes the cancellation and the shutdown's join can complete. */
        fun release() {
            val toRun =
                synchronized(queue) {
                    released = true
                    val out = queue.toList()
                    queue.clear()
                    out
                }
            toRun.forEach { it.run() }
        }
    }

    /**
     * The startup replay and the cache sweep are blocking walks with no suspension point of their
     * own. They run off the event loop (the injected dispatcher stands in for Dispatchers.IO): the
     * socket binds while both are still queued, and once the stop was issued the queued work runs,
     * observes the cancellation, and the daemon exits. On the event loop they starved
     * closeSignal.await() and a stop waited for the whole replay (archive#678's 38-65 s stalls).
     */
    @Test
    fun `a stop is honoured while the startup replay and sweep are still queued on their dispatcher`() =
        runBlocking(Dispatchers.IO) {
            // A backlog the replay would walk; its records are never reached while it is queued.
            val seeded = StateDatabase(dbPath)
            seeded.initialize()
            try {
                for (i in 1..25) {
                    seeded.upsertEntry(
                        SyncEntry(
                            path = "/q/f$i.txt",
                            remoteId = null,
                            remoteHash = null,
                            remoteSize = 0,
                            remoteModified = null,
                            localMtime = 1,
                            localSize = 1,
                            isFolder = false,
                            isPinned = false,
                            isHydrated = true,
                            lastSynced = Instant.now(),
                        ),
                    )
                }
            } finally {
                seeded.close()
            }

            val holding = HoldingDispatcher()
            val runtime = startDaemon(StubProvider(), startupIoDispatcher = holding)
            val daemonJob = launch { runtime.start() }
            try {
                awaitSocket(daemonJob)
                assertTrue(holding.size >= 2, "precondition: replay and sweep are queued off the loop")
                runtime.close()
                holding.release() // the queued work now runs and observes the cancellation
                withTimeout(10_000) { daemonJob.join() }
            } finally {
                holding.release()
                runtime.close()
                runCatching { withTimeout(10_000) { daemonJob.join() } }
            }
        }

    // ── archive#655: the quota snapshot in daemon.status ───────────────────────────

    // Seeds the sync_state cache the same way `quota` (UD-214) persists it, with an
    // old fetched_at so the daemon's first status sees an expired TTL.
    private fun seedQuotaCache(
        used: Long,
        total: Long,
        fetchedAt: Instant,
    ) {
        val db = StateDatabase(dbPath)
        db.initialize()
        try {
            db.setSyncState("quota_used", used.toString())
            db.setSyncState("quota_total", total.toString())
            db.setSyncState("quota_remaining", (total - used).toString())
            db.setSyncState("quota_fetched_at", fetchedAt.toString())
        } finally {
            db.close()
        }
    }

    private suspend fun awaitQuotaCalls(stub: StubProvider, count: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (stub.quotaCalls < count && System.currentTimeMillis() < deadline) delay(50)
    }

    @Test
    fun `daemon status carries the quota snapshot and respects the ttl`() =
        runBlocking(Dispatchers.IO) {
            val stub = StubProvider()
            seedQuotaCache(used = 5, total = 50, fetchedAt = Instant.now().minusSeconds(7_200))
            val daemon = startDaemon(stub, quotaRefreshMs = 60_000)
            val job = launch { daemon.start() }
            try {
                awaitSocket()
                // The first status sees an expired TTL and triggers the fetch; its own
                // reply may still carry the cached (stale) values — the refresh lands
                // for the next one.
                sendOneRequest("""{"verb":"daemon.status"}""")
                awaitQuotaCalls(stub, 1)
                assertEquals(1, stub.quotaCalls, "one refresh, single-flight")

                val reply = sendOneRequest("""{"verb":"daemon.status"}""")
                val quota = Json.parseToJsonElement(reply).jsonObject.getValue("quota").jsonObject
                assertEquals(1L, quota.getValue("used_bytes").jsonPrimitive.long, "the fresh fetch replaced the cache")
                assertEquals(10L, quota.getValue("total_bytes").jsonPrimitive.long)
                assertEquals(false, quota.getValue("stale").jsonPrimitive.boolean)
                assertTrue(quota.getValue("fetched_at_ms").jsonPrimitive.long > 0)
                assertEquals(1, stub.quotaCalls, "inside the TTL the snapshot is served from memory")

                // The cache is updated for offline `quota --json` reads too.
                val db = StateDatabase(dbPath)
                db.initialize()
                try {
                    assertEquals("1", db.getSyncState("quota_used"), "the shared cache tuple is refreshed")
                } finally {
                    db.close()
                }
            } finally {
                daemon.close()
                job.join()
            }
        }

    @Test
    fun `a failed quota refresh keeps the cached values flagged stale with the error named`() =
        runBlocking(Dispatchers.IO) {
            val stub = StubProvider()
            stub.quotaFailure = RuntimeException("quota endpoint down")
            seedQuotaCache(used = 5, total = 50, fetchedAt = Instant.now().minusSeconds(7_200))
            val daemon = startDaemon(stub, quotaRefreshMs = 60_000)
            val job = launch { daemon.start() }
            try {
                awaitSocket()
                val reply = sendOneRequest("""{"verb":"daemon.status"}""")
                awaitQuotaCalls(stub, 1)

                val deadline = System.currentTimeMillis() + 5_000
                var quota: JsonObject? = null
                while (System.currentTimeMillis() < deadline) {
                    quota =
                        Json.parseToJsonElement(sendOneRequest("""{"verb":"daemon.status"}"""))
                            .jsonObject.getValue("quota").jsonObject
                    if (quota.getValue("stale").jsonPrimitive.boolean) break
                    delay(50)
                }
                val q = assertNotNull(quota)
                assertTrue(q.getValue("stale").jsonPrimitive.boolean, "the failed refresh flags the snapshot stale")
                assertTrue(
                    (q.getValue("error")!!.jsonPrimitive.content).contains("quota endpoint down"),
                    "the error is named on the snapshot",
                )
                assertEquals(5L, q.getValue("used_bytes").jsonPrimitive.long, "the last known values are kept")
            } finally {
                daemon.close()
                job.join()
            }
        }

    @Test
    fun `a provider without an account quota omits the quota field`() =
        runBlocking(Dispatchers.IO) {
            val stub = StubProvider()
            val daemon = startDaemon(stub, providerHasQuota = false)
            val job = launch { daemon.start() }
            try {
                awaitSocket()
                val reply = sendOneRequest("""{"verb":"daemon.status"}""")
                assertNull(Json.parseToJsonElement(reply).jsonObject["quota"], "no quota field for a provider without an account quota")
                assertEquals(0, stub.quotaCalls, "and no fetch is attempted")
            } finally {
                daemon.close()
                job.join()
            }
        }

    @Test
    fun `the quota is not fetched when no client ever connects`() =
        runBlocking(Dispatchers.IO) {
            val stub = StubProvider()
            val daemon = startDaemon(stub)
            val job = launch { daemon.start() }
            try {
                awaitSocket()
                delay(300)
                assertEquals(0, stub.quotaCalls, "no status request, no provider call — no polling for nobody")
            } finally {
                daemon.close()
                job.join()
            }
        }


    // Polls daemon.status until its enumeration object satisfies [predicate]; fails with the last reply otherwise.
    private suspend fun awaitEnumeration(
        timeoutMs: Long = IPC_STEP_BUDGET_MS,
        predicate: (JsonObject) -> Boolean,
    ): JsonObject {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = ""
        while (System.currentTimeMillis() < deadline) {
            last = sendOneRequest("""{"verb":"daemon.status"}""")
            enumerationOf(last).let { if (predicate(it)) return it }
            delay(50)
        }
        error("the enumeration object never matched; last daemon.status reply: $last")
    }

    // A client that finds the verb busy asks again: the status turns failed or idle a moment before the verb's
    // in-flight guard is released, and the next request may come in between.
    private suspend fun requestEnumerationUntilAccepted() {
        val deadline = System.currentTimeMillis() + IPC_STEP_BUDGET_MS
        while (true) {
            val reply = sendOneRequest("""{"verb":"sync.enumerate"}""")
            if (reply.contains(""""ok":true""")) return
            check(reply.contains(""""error":"busy"""")) { "sync.enumerate was refused for another reason: $reply" }
            check(System.currentTimeMillis() < deadline) { "sync.enumerate stayed busy: $reply" }
            delay(20)
        }
    }

    @Test
    fun `daemon_status carries an enumeration object that is idle before any enumeration and leaves the other fields alone`() = runBlocking {
        val runtime = startDaemon(StubProvider())
        val daemonJob = launch { runtime.start() }
        awaitSocket(daemonJob)
        try {
            val reply = sendOneRequest("""{"verb":"daemon.status"}""")

            val status = Json.parseToJsonElement(reply).jsonObject
            assertEquals(DaemonRuntime.IPC_PROTOCOL_VERSION, status.getValue("protocol_version").jsonPrimitive.int)
            assertEquals("stub", status.getValue("provider").jsonPrimitive.content)
            assertEquals("Stub", status.getValue("provider_name").jsonPrimitive.content)
            assertTrue(status.getValue("authenticated").jsonPrimitive.boolean)
            assertFalse(status.getValue("refresh_in_flight").jsonPrimitive.boolean)
            assertTrue(status.getValue("uptime_ms").jsonPrimitive.long >= 0)
            val enumeration = status.getValue("enumeration").jsonObject
            assertEquals(setOf("state", "first", "attempt"), enumeration.keys, "nothing else is known before a run: $reply")
            assertEquals("idle", enumeration.getValue("state").jsonPrimitive.content)
            assertTrue(enumeration.getValue("first").jsonPrimitive.boolean, "no enumeration has completed")
            assertEquals(0, enumeration.getValue("attempt").jsonPrimitive.int)
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    @Test
    fun `daemon_status follows an enumeration from running to done`() = runBlocking(Dispatchers.IO) {
        val gate = CompletableDeferred<Unit>()
        val runtime = startDaemon(ScriptedDeltaProvider(gate = gate))
        val daemonJob = launch { runtime.start() }
        awaitSocket(daemonJob)
        try {
            val started = sendOneRequest("""{"verb":"sync.enumerate"}""")
            assertTrue(started.contains("\"ok\":true"), started)

            // A running enumeration shows its items (0) before the provider has reported anything: wait for the
            // provider's own report, which is what the assertions below describe.
            val running =
                awaitEnumeration {
                    it.getValue("state").jsonPrimitive.content == "running" && it["items"]?.jsonPrimitive?.int == 1_234
                }
            assertEquals("listing", running.getValue("phase").jsonPrimitive.content)
            assertEquals("tree", running.getValue("listing").jsonPrimitive.content)
            assertTrue(running.getValue("first").jsonPrimitive.boolean)
            assertEquals(1, running.getValue("attempt").jsonPrimitive.int)
            assertEquals(1_234, running.getValue("items").jsonPrimitive.int)
            assertEquals(10, running.getValue("folders_done").jsonPrimitive.int)
            assertEquals(40, running.getValue("folders_known").jsonPrimitive.int)
            assertEquals(1, running.getValue("folders_skipped").jsonPrimitive.int)
            assertTrue(running.getValue("started_at_ms").jsonPrimitive.long > 0)
            assertTrue(running.getValue("elapsed_ms").jsonPrimitive.long >= 0)
            assertFalse("last_success_at_ms" in running)

            gate.complete(Unit)
            val done = awaitEnumeration { it.getValue("state").jsonPrimitive.content == "idle" && "last_success_at_ms" in it }
            assertFalse(done.getValue("first").jsonPrimitive.boolean, "the cursor is promoted: the view is complete")
            assertEquals(0, done.getValue("attempt").jsonPrimitive.int)
            assertTrue(done.getValue("last_success_at_ms").jsonPrimitive.long > 0)
            assertFalse("items" in done, "an idle enumeration shows no progress")
            assertFalse("phase" in done)
        } finally {
            gate.complete(Unit)
            runtime.close()
            daemonJob.join()
        }
    }

    @Test
    fun `daemon_status reports a failed enumeration with a reason that names no path`() = runBlocking(Dispatchers.IO) {
        val runtime = startDaemon(ScriptedDeltaProvider(failure = ProviderException("cannot list /Secret/Plans: boom")))
        val daemonJob = launch { runtime.start() }
        awaitSocket(daemonJob)
        try {
            sendOneRequest("""{"verb":"sync.enumerate"}""")
            val failed = awaitEnumeration { it.getValue("state").jsonPrimitive.content == "failed" }

            assertEquals(1, failed.getValue("attempt").jsonPrimitive.int)
            assertEquals("cannot list <path>: boom", failed.getValue("last_error").jsonPrimitive.content)
            assertTrue(failed.getValue("first").jsonPrimitive.boolean, "the view is still incomplete")
            assertFalse("next_attempt_at_ms" in failed, "no poller runs, so nothing is scheduled")
            assertFalse("phase" in failed)

            requestEnumerationUntilAccepted()
            val again = awaitEnumeration { it.getValue("state").jsonPrimitive.content == "failed" && it.getValue("attempt").jsonPrimitive.int == 2 }
            assertEquals(2, again.getValue("attempt").jsonPrimitive.int, "attempts since the last success")
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    /**
     * Minimal CloudProvider stub. Only authenticateAndLog() (via authenticate())
     * is exercised by T1's happy-path lifecycle; the rest must compile but never
     * runs in this test.
     */
    private open class StubProvider : CloudProvider {
        override val id: String = "stub"
        override val displayName: String = "Stub"
        override var isAuthenticated: Boolean = true

        // archive#655: quota snapshot fixtures. A failure injected here exercises the stale path.
        var quotaCalls: Int = 0
        var quotaResult: QuotaInfo = QuotaInfo(total = 10L, used = 1L, remaining = 9L)
        var quotaFailure: Exception? = null

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() { /* no-op: already authenticated */ }

        // #211: the quota fetch goes through the credential check, never authenticate()'s browser fallback.
        override suspend fun checkCredentials(): org.krost.unidrive.CredentialProof = org.krost.unidrive.CredentialProof("Stub")

        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

        override suspend fun getMetadata(path: String): CloudItem =
            error("not used in T1")

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long = error("not used in T1")

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("not used in T1")

        override suspend fun delete(remotePath: String, ifMatchETag: String?) = error("not used in T1")

        override suspend fun createFolder(path: String): CloudItem = error("not used in T1")

        override suspend fun move(fromPath: String, toPath: String): CloudItem =
            error("not used in T1")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ): DeltaPage = DeltaPage(items = emptyList(), cursor = "x", hasMore = false)

        override suspend fun quota(): QuotaInfo {
            quotaCalls += 1
            quotaFailure?.let { throw it }
            return quotaResult
        }
    }

    private class BlockingUploadProvider : StubProvider() {
        val uploadStarted = CompletableDeferred<Unit>()
        val uploadStopped = CompletableDeferred<Unit>()

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem {
            uploadStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                uploadStopped.complete(Unit)
            }
        }
    }

    // gkrost/unidrive-cli#72: an upload that holds until [gate] completes, then lands as a new item.
    private class HeldUploadProvider : StubProvider() {
        val uploadStarted = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val uploads = java.util.concurrent.atomic.AtomicInteger(0)

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem {
            uploads.incrementAndGet()
            uploadStarted.complete(Unit)
            onProgress?.invoke(1L, 2L)
            gate.await()
            val size = Files.size(localPath)
            onProgress?.invoke(size, size)
            return CloudItem(
                id = "uploaded-$remotePath",
                name = remotePath.substringAfterLast('/'),
                path = remotePath,
                size = size,
                isFolder = false,
                modified = Instant.now(),
                created = null,
                hash = null,
                mimeType = null,
            )
        }
    }

    /**
     * A provider whose listing reports tree progress to the engine, then waits for [gate] and fails with
     * [failure] or ends with an empty page: what a long first enumeration looks like from outside.
     */
    private class ScriptedDeltaProvider(
        private val gate: CompletableDeferred<Unit>? = null,
        private val failure: Exception? = null,
    ) : StubProvider() {
        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ): DeltaPage {
            scanContext?.onProgress?.invoke(
                ScanProgress(items = 1_234, foldersDone = 10, foldersKnown = 40, foldersSkipped = 1, listing = ScanProgress.LISTING_TREE),
            )
            gate?.await()
            failure?.let { throw it }
            return DeltaPage(items = emptyList(), cursor = "cursor-1", hasMore = false)
        }
    }

    /**
     * Stub that throws AuthenticationException from authenticate().
     * Used by T4 to pin the fail-fast contract: socket must NOT be bound
     * when auth fails.
     */
    private class AuthFailingStubProvider : CloudProvider {
        override val id: String = "stub-auth-failing"
        override val displayName: String = "Stub (auth fails)"
        override var isAuthenticated: Boolean = false

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() {
            throw org.krost.unidrive.AuthenticationException("test auth failure (T4)")
        }

        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

        override suspend fun getMetadata(path: String): CloudItem =
            error("not used in T4")

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long = error("not used in T4")

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("not used in T4")

        override suspend fun delete(remotePath: String, ifMatchETag: String?) = error("not used in T4")

        override suspend fun createFolder(path: String): CloudItem = error("not used in T4")

        override suspend fun move(fromPath: String, toPath: String): CloudItem =
            error("not used in T4")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ): DeltaPage = DeltaPage(items = emptyList(), cursor = "x", hasMore = false)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    /**
     * Stub whose delta reports a single remote file on a complete (hasMore=false)
     * enumeration. Used by the reactive-freshness test to make the subscribe-triggered enumerate
     * mutate state.db so a view.invalidated event fires on the subscribe stream.
     */
    private class OneRemoteFileStubProvider : CloudProvider {
        override val id: String = "stub-one-file"
        override val displayName: String = "Stub (one remote file)"
        override var isAuthenticated: Boolean = true

        /** How many times the daemon asked this provider for a delta page, i.e. started an enumeration. */
        val deltaCalls = java.util.concurrent.atomic.AtomicInteger()

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() { /* no-op */ }

        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

        override suspend fun getMetadata(path: String): CloudItem = error("not used")

        override suspend fun download(remotePath: String, destination: Path): Long = error("not used")

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("not used")

        override suspend fun delete(remotePath: String, ifMatchETag: String?) = error("not used")

        override suspend fun createFolder(path: String): CloudItem = error("not used")

        override suspend fun move(fromPath: String, toPath: String): CloudItem = error("not used")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ): DeltaPage {
            deltaCalls.incrementAndGet()
            return DeltaPage(
                items = listOf(
                    CloudItem(
                        id = "remote-1",
                        name = "remote-new.txt",
                        path = "/remote-new.txt",
                        size = 7L,
                        isFolder = false,
                        modified = java.time.Instant.now(),
                        created = java.time.Instant.now(),
                        hash = "abc123",
                        mimeType = "text/plain",
                    ),
                ),
                cursor = "cursor-1",
                hasMore = false,
            )
        }

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }
}
