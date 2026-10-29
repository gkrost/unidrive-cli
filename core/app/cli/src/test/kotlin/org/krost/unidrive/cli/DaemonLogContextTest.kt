package org.krost.unidrive.cli

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.RemoteChangeChannelStatus
import org.krost.unidrive.http.RequestId
import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcAuthClient
import org.krost.unidrive.sync.IpcEndpoint
import org.krost.unidrive.sync.ProfileMode
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#225: every line a daemon writes carries its profile and its build (the `profile` and `build` MDC keys
 * the log pattern prints), on whichever thread it is written: two daemons of two profiles write one unidrive.log. The MDC
 * is read while the line is logged, on the thread that logs it.
 */
class DaemonLogContextTest {
    private lateinit var tempDir: Path
    private val recorder = MdcRecorder(PROBE, REQUEST_ID, IPC_SERVER, IPC_AUTH)

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("daemon-log-context-test")
        recorder.attach()
    }

    @AfterTest
    fun tearDown() {
        recorder.detach()
        runCatching { tempDir.toFile().deleteRecursively() }
    }

    @Test
    fun `a coroutine of the serve scope on Dispatchers IO logs with the daemon's profile and build`() =
        runBlocking(Dispatchers.IO) {
            daemon(ALPHA) {
                // The start's catch-up poll runs on the serve scope; the provider's listing goes to Dispatchers.IO, as a real one does.
                val lines = recorder.await("the listing's line") { it.logger == PROBE && it.message == "delta of $ALPHA" }
                assertTrue(lines.all { it.thread.startsWith("DefaultDispatcher-worker") }, "written on Dispatchers.IO: $lines")
                assertTagged(lines, ALPHA)
            }
        }

    @Test
    fun `the lines of an IPC request carry the daemon's profile and build`() =
        runBlocking(Dispatchers.IO) {
            daemon(ALPHA) { dir, socket ->
                request(socket, dir, ALPHA, """{"verb":"daemon.status"}""")
                // The IPC server's accept loop, the handshake, and the status handler (it asks the provider for its push channel).
                assertTagged(recorder.await("the accept line") { it.logger == IPC_SERVER && it.message.startsWith("IPC: client connected") }, ALPHA)
                assertTagged(recorder.await("the handshake line") { it.logger == IPC_AUTH && it.message.contains("authenticated") }, ALPHA)
                assertTagged(recorder.await("the handler's line") { it.logger == PROBE && it.message == "status of $ALPHA asked" }, ALPHA)
            }
        }

    @Test
    fun `the RequestId lines of an HTTP call made from the serve scope carry the daemon's profile and build`() =
        runBlocking(Dispatchers.IO) {
            daemon(ALPHA) {
                assertTagged(requestIdLines(ALPHA), ALPHA)
            }
        }

    @Test
    fun `two daemons of two profiles in one JVM tag their lines with their own profile`() =
        runBlocking(Dispatchers.IO) {
            daemon(ALPHA) {
                daemon(BETA) {
                    assertTagged(recorder.await("alpha's listing") { it.logger == PROBE && it.message == "delta of $ALPHA" }, ALPHA)
                    assertTagged(recorder.await("beta's listing") { it.logger == PROBE && it.message == "delta of $BETA" }, BETA)
                    assertTagged(requestIdLines(ALPHA), ALPHA)
                    assertTagged(requestIdLines(BETA), BETA)
                }
            }
        }

    /** The request and the response line of the listing call of [profile]'s provider (the response line has no URL: its id). */
    private suspend fun requestIdLines(profile: String): List<MdcRecorder.Line> {
        val sent = recorder.await("$profile's request line") { it.logger == REQUEST_ID && it.message.contains("/$profile/delta") }
        val id = sent.first().message.substringAfter("req=").substringBefore(' ')
        val received = recorder.await("$profile's response line") { it.logger == REQUEST_ID && it.message.startsWith("← req=$id ") }
        return sent + received
    }

    private fun assertTagged(
        lines: List<MdcRecorder.Line>,
        profile: String,
    ) {
        for (line in lines) {
            assertEquals(profile, line.mdc["profile"], "the profile of $line")
            assertEquals(BuildInfo.COMMIT, line.mdc["build"], "the build of $line")
        }
    }

    /** Runs a mount daemon of [profile] (its own folder and socket) with the poll on, for [block], then stops it. */
    private suspend fun CoroutineScope.daemon(
        profile: String,
        block: suspend (dir: Path, socket: Path) -> Unit,
    ) {
        val dir = Files.createDirectories(tempDir.resolve(profile))
        val socket = dir.resolve("daemon.sock")
        val runtime =
            DaemonRuntime(
                profileMode = ProfileMode.MOUNT,
                profileName = profile,
                lockFile = dir.resolve(".lock"),
                dbPath = dir.resolve("state.db"),
                syncRoot = dir,
                socketPath = socket,
                providerFactory = { ProbingProvider(profile) },
                pollIntervalMs = 3_600_000,
                providerHasQuota = false,
            )
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socket, daemonJob)
        try {
            block(dir, socket)
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    private suspend fun CoroutineScope.daemon(
        profile: String,
        block: suspend () -> Unit,
    ) = daemon(profile) { _, _ -> block() }

    private fun request(
        socket: Path,
        dir: Path,
        profile: String,
        line: String,
    ): String {
        val channel = IpcAuthClient.connect(IpcEndpoint(socket, dir, profile), IpcAuth.Scope.FULL)
        try {
            channel.configureBlocking(false)
            channel.write(ByteBuffer.wrap((line + "\n").toByteArray()))
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
            check(collected.contains('\n')) { "no reply line; collected: $collected" }
            return collected.toString().substringBefore('\n')
        } finally {
            channel.close()
        }
    }

    /**
     * A provider whose listing goes to Dispatchers.IO and makes one HTTP call there through a client with [RequestId] (a
     * MockEngine, never a provider's server), and whose push-channel status (asked by `daemon.status`) logs a line.
     */
    private class ProbingProvider(
        private val profile: String,
    ) : CloudProvider {
        private val probe = LoggerFactory.getLogger(PROBE)
        private val http =
            HttpClient(MockEngine { respond(content = "ok", status = HttpStatusCode.OK) }) {
                install(RequestId)
            }

        override val id: String = "stub-probing"
        override val displayName: String = "Stub (probing)"
        override var isAuthenticated: Boolean = true

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() {}

        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

        override suspend fun getMetadata(path: String): CloudItem = error("not used")

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long = error("not used")

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("not used")

        override suspend fun delete(
            remotePath: String,
            ifMatchETag: String?,
        ) = error("not used")

        override suspend fun createFolder(path: String): CloudItem = error("not used")

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ): CloudItem = error("not used")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ): DeltaPage {
            withContext(Dispatchers.IO) {
                probe.info("delta of {}", profile)
                http.get("https://example.invalid/$profile/delta").bodyAsText()
            }
            return DeltaPage(items = emptyList(), cursor = "cursor-1", hasMore = false)
        }

        override fun remoteChangeChannelStatus(): RemoteChangeChannelStatus? {
            probe.info("status of {} asked", profile)
            return null
        }

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)

        override fun close() = http.close()
    }

    /** The lines of the named loggers, each with the MDC of the thread that wrote it, read while it is written. */
    private class MdcRecorder(
        vararg names: String,
    ) : AppenderBase<ILoggingEvent>() {
        data class Line(
            val logger: String,
            val message: String,
            val thread: String,
            val mdc: Map<String, String>,
        )

        private val loggers = names.map { LoggerFactory.getLogger(it) as Logger }
        private val lines = CopyOnWriteArrayList<Line>()

        override fun append(event: ILoggingEvent) {
            lines += Line(event.loggerName, event.formattedMessage, event.threadName, HashMap(event.mdcPropertyMap))
        }

        fun attach() {
            start()
            loggers.forEach { it.addAppender(this) }
        }

        fun detach() {
            loggers.forEach { it.detachAppender(this) }
            stop()
        }

        /** Every line that matches [predicate], once there is one. */
        suspend fun await(
            step: String,
            predicate: (Line) -> Boolean,
        ): List<Line> {
            val found =
                withTimeoutOrNull(IPC_STEP_BUDGET_MS) {
                    var hits = lines.filter(predicate)
                    while (hits.isEmpty()) {
                        delay(20)
                        hits = lines.filter(predicate)
                    }
                    hits
                }
            return found ?: error("no line for $step; the lines seen: $lines")
        }
    }

    private companion object {
        const val ALPHA = "log-ctx-alpha"
        const val BETA = "log-ctx-beta"
        const val PROBE = "org.krost.unidrive.cli.DaemonLogContextTest.probe"
        const val REQUEST_ID = "org.krost.unidrive.http.RequestId"
        const val IPC_SERVER = "org.krost.unidrive.sync.IpcServer"
        const val IPC_AUTH = "org.krost.unidrive.sync.IpcAuth"
    }
}
