package org.krost.unidrive.cli

import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcAuthClient
import org.krost.unidrive.sync.IpcEndpoint
import org.krost.unidrive.sync.ProfileMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.krost.unidrive.testing.TestScratch

/**
 * A daemon that starts locked (issue#796, D6): it binds the socket before the provider, serves the
 * locked `daemon.status` shape, refuses the vault verbs on a read-scope connection, unlocks through
 * `vault.unlock` (the provider tail runs, the locked shape yields to the full one), and locks again
 * through `vault.lock` (the dependents pause, `locked` comes back). No passphrase reaches a log
 * line.
 */
class VaultLockIpcTest {
    private val passphrase = "the-right-passphrase"
    private val wrongPassphrase = "the-wrong-passphrase"

    private fun runtimeAt(
        tempDir: Path,
        socketPath: Path,
        dropData: () -> Unit = {},
    ): DaemonRuntime {
        val runtime =
            DaemonRuntime(
                profileMode = ProfileMode.MOUNT,
                profileName = "locked_profile",
                lockFile = tempDir.resolve(".lock"),
                dbPath = tempDir.resolve("state.db"),
                syncRoot = tempDir,
                socketPath = socketPath,
                providerFactory = { StubProvider() },
                providerHasQuota = false,
            )
        runtime.vault =
            DaemonVault(
                gate = VaultGate.lockedStart("the unlock helper declined (declined)"),
                unlockAttempt = { pass ->
                    if (String(pass) == passphrase) VaultUnlockOutcome.Ok(emptyMap()) else VaultUnlockOutcome.BadPassphrase
                },
                dropData = dropData,
            )
        return runtime
    }

    private class StubProvider : CloudProvider {
        override val id: String = "stub"
        override val displayName: String = "Stub"
        override var isAuthenticated: Boolean = true

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() {}

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
        ): DeltaPage = DeltaPage(items = emptyList(), cursor = "x", hasMore = false)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    private suspend fun awaitDaemonSocket(socketPath: Path) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!Files.exists(socketPath)) {
            check(System.currentTimeMillis() < deadline) { "the daemon socket did not appear" }
            delay(25)
        }
    }

    private suspend fun readFirstLine(channel: SocketChannel, timeoutMs: Long = 5_000): String {
        channel.configureBlocking(false)
        val deadline = System.currentTimeMillis() + timeoutMs
        val buffer = ByteBuffer.allocate(1 shl 16)
        val text = StringBuilder()
        while (System.currentTimeMillis() < deadline) {
            val n = channel.read(buffer)
            if (n > 0) {
                text.append(Charsets.UTF_8.decode(ByteBuffer.wrap(buffer.array(), 0, buffer.position())))
                buffer.clear()
                val soFar = text.toString()
                if (soFar.contains("\n")) return soFar.substringBefore("\n")
            } else {
                delay(10)
            }
        }
        error("no reply line within ${timeoutMs}ms; got: $text")
    }

    private suspend fun ask(
        endpoint: IpcEndpoint,
        scope: IpcAuth.Scope,
        request: String,
    ): JsonObject {
        val channel = IpcAuthClient.connect(endpoint, scope)
        try {
            channel.write(ByteBuffer.wrap((request + "\n").toByteArray()))
            return Json.parseToJsonElement(readFirstLine(channel)) as JsonObject
        } finally {
            channel.close()
        }
    }

    private suspend fun statusUntil(
        endpoint: IpcEndpoint,
        deadlineMs: Long = 10_000,
        predicate: (JsonObject) -> Boolean,
    ): JsonObject {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (true) {
            val status = ask(endpoint, IpcAuth.Scope.READ, """{"verb":"daemon.status"}""")
            if (predicate(status)) return status
            check(System.currentTimeMillis() < deadline) { "the status never satisfied the predicate: $status" }
            delay(50)
        }
    }

    @Test
    fun `a locked daemon serves the locked status, unlocks over the verb and locks again`() = runBlocking {
        val tempDir = TestScratch.directory("vault-lock-ipc")
        val socketPath = tempDir.resolve("daemon.sock")
        val dropped = AtomicBoolean(false)
        val runtime = runtimeAt(tempDir, socketPath, dropData = { dropped.set(true) })
        val daemonJob = launch { runtime.start() }
        try {
            awaitDaemonSocket(socketPath)
            val endpoint = IpcEndpoint(socketPath, tempDir, "locked_profile")

            // The locked shape: bound before the provider exists, `locked: true`, nothing of it.
            val lockedStatus = statusUntil(endpoint) { it["locked"] == JsonPrimitive(true) }
            assertEquals("mount", (lockedStatus.getValue("mode") as JsonPrimitive).content)
            assertEquals("true", (lockedStatus.getValue("locked") as JsonPrimitive).content)
            assertNull(lockedStatus["provider"], "no provider is built while the vault is locked")
            assertTrue(
                (lockedStatus.getValue("capabilities") as kotlinx.serialization.json.JsonArray)
                    .contains(JsonPrimitive("vault-unlock:v1")),
                "the locked daemon advertises the vault verbs",
            )

            // Read scope is refused on both verbs before the handler runs.
            val unlockRefusal = ask(endpoint, IpcAuth.Scope.READ, """{"verb":"vault.unlock","passphrase":"$passphrase"}""")
            assertEquals("forbidden", (unlockRefusal.getValue("error") as JsonPrimitive).content)
            val lockRefusal = ask(endpoint, IpcAuth.Scope.READ, """{"verb":"vault.lock"}""")
            assertEquals("forbidden", (lockRefusal.getValue("error") as JsonPrimitive).content)

            // A wrong passphrase is the refusal; the daemon stays locked.
            val wrong = ask(endpoint, IpcAuth.Scope.FULL, """{"verb":"vault.unlock","passphrase":"$wrongPassphrase"}""")
            assertEquals("vault_bad_passphrase", (wrong.getValue("error") as JsonPrimitive).content)
            assertTrue((ask(endpoint, IpcAuth.Scope.READ, """{"verb":"daemon.status"}""")).containsKey("locked"))

            // The right passphrase runs the provider tail: the locked shape yields to the full one.
            assertEquals("""{"ok":true}""".trim(), ask(endpoint, IpcAuth.Scope.FULL, """{"verb":"vault.unlock","passphrase":"$passphrase"}""").toString())
            val unlockedStatus = statusUntil(endpoint) { it.containsKey("provider") }
            assertNull(unlockedStatus["locked"], "the unlocked daemon does not carry the member")
            assertEquals("stub", (unlockedStatus.getValue("provider") as JsonPrimitive).content)

            // Locking a running daemon drops the data and pauses the dependents.
            assertEquals("""{"ok":true}""".trim(), ask(endpoint, IpcAuth.Scope.FULL, """{"verb":"vault.lock"}""").toString())
            assertTrue(dropped.get(), "vault.lock dropped the in-memory vault data")
            val relocked = statusUntil(endpoint) { it.containsKey("locked") }
            assertEquals("vault_locked", ((relocked.getValue("paused") as JsonObject).getValue("reason") as JsonPrimitive).content)

            // Unlocking resumes them.
            assertEquals("""{"ok":true}""".trim(), ask(endpoint, IpcAuth.Scope.FULL, """{"verb":"vault.unlock","passphrase":"$passphrase"}""").toString())
            val resumed = statusUntil(endpoint) { !it.containsKey("locked") }
            assertNull(resumed["paused"], "the vault_locked pause ended with the unlock")
        } finally {
            runtime.close()
            daemonJob.join()
            runCatching { tempDir.toFile().deleteRecursively() }
        }
    }

    @Test
    fun `no passphrase reaches a log line`() = runBlocking {
        val tempDir = TestScratch.directory("vault-lock-logs")
        val socketPath = tempDir.resolve("daemon.sock")
        val runtime = runtimeAt(tempDir, socketPath)
        val root = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
        val capture = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
        root.addAppender(capture)
        capture.start()
        val daemonJob = launch { runtime.start() }
        try {
            awaitDaemonSocket(socketPath)
            val endpoint = IpcEndpoint(socketPath, tempDir, "locked_profile")
            // Wrong, then right, both through the verb that carries the passphrase in its body.
            ask(endpoint, IpcAuth.Scope.FULL, """{"verb":"vault.unlock","passphrase":"$wrongPassphrase"}""")
            ask(endpoint, IpcAuth.Scope.FULL, """{"verb":"vault.unlock","passphrase":"$passphrase"}""")
            statusUntil(endpoint) { it.containsKey("provider") }
            val seen = capture.list.joinToString("\n") { "${it.formattedMessage}|${it.throwableProxy?.message ?: ""}" }
            assertFalse(seen.contains(passphrase), "the passphrase leaked into a log line: $seen")
            assertFalse(seen.contains(wrongPassphrase), "the wrong passphrase leaked into a log line: $seen")
        } finally {
            capture.stop()
            root.detachAppender(capture)
            runtime.close()
            daemonJob.join()
            runCatching { tempDir.toFile().deleteRecursively() }
        }
    }
}
