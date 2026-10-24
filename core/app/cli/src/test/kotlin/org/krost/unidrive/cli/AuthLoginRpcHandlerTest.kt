package org.krost.unidrive.cli

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.BeginAuthResult
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.CompleteAuthResult
import org.krost.unidrive.CredentialHealth
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ProviderFactory
import org.krost.unidrive.ProviderMetadata
import org.krost.unidrive.ProviderRegistry
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.internxt.BadCredentialsException
import org.krost.unidrive.internxt.model.InternxtCredentials
import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcAuthClient
import org.krost.unidrive.sync.IpcEndpoint
import org.krost.unidrive.sync.ProfileMode
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sign-in verbs `auth.begin`, `auth.complete` and `auth.login` (docs/dev/specs/unidrive-daemon-design.md §4.11, archive#813):
 * the running daemon signs its account in again. The library sign-in and the device-code factory are fakes on the
 * [LifecycleHooks] seams; every password, code and token here is made up. No test reaches a network, a real account or
 * the user's own sockets.
 */
class AuthLoginRpcHandlerTest {
    private lateinit var dir: Path
    private val realLogin = LifecycleHooks.internxtLogin
    private val logs = ListAppender<ILoggingEvent>()
    private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    private var rootLevel: Level? = null

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("auth-login-handler")
        rootLevel = root.level
        root.level = Level.TRACE
        logs.start()
        root.addAppender(logs)
    }

    @AfterTest
    fun tearDown() {
        root.detachAppender(logs)
        root.level = rootLevel
        LifecycleHooks.internxtLogin = realLogin
        LifecycleHooks.factoryFor = { ProviderRegistry.get(it) }
        dir.toFile().deleteRecursively()
    }

    private val credentials: Path get() = dir.resolve("credentials.json")

    /** The fake library sign-in: one good password; it stores the credentials, as the real one does, only when it accepts. */
    private fun fakeInternxt(goodPassword: String = "pw-GOOD-1") {
        LifecycleHooks.internxtLogin = { config, email, password, _ ->
            if (password != goodPassword) throw BadCredentialsException("rejected: $password")
            Files.writeString(config.tokenPath.resolve("credentials.json"), """{"jwt":"JWT-NEW-SECRET"}""")
            InternxtCredentials(jwt = "JWT-NEW-SECRET", mnemonic = "MNEMONIC-SECRET", rootFolderId = "r", email = email, bridgeUser = email, bridgeUserId = "u", bucket = "b")
        }
    }

    private fun internxtTarget() = ProfileTarget("ix", "internxt", dir)

    private fun handler(
        target: ProfileTarget? = internxtTarget(),
        onSignedIn: suspend () -> Unit = {},
    ) = AuthLoginRpcHandler(target, onSignedIn)

    private fun parse(reply: String): JsonObject = Json.parseToJsonElement(reply).jsonObject

    // AppenderBase appends under its own monitor; the daemon logs from other threads while a test reads.
    private fun loggedText(): String = synchronized(logs) { logs.list.toList() }.joinToString("\n") { it.formattedMessage + (it.throwableProxy?.message ?: "") }

    @Test
    fun `a login stores the credentials, reloads them into the daemon and answers like auth login`() = runBlocking {
        fakeInternxt()
        var reloads = 0
        val h = handler { reloads++ }

        val reply = h.login("""{"verb":"auth.login","email":"user@example.test","totp":"654321","password":"pw-GOOD-1"}""")

        val r = parse(reply)
        assertTrue(r.getValue("ok").jsonPrimitive.boolean)
        assertEquals("authenticated", r.getValue("status").jsonPrimitive.content)
        assertEquals("ix", r.getValue("profile").jsonPrimitive.content)
        assertEquals("user@example.test", r.getValue("account").jsonPrimitive.content)
        assertEquals(1, reloads, "the daemon's provider reloads the stored credentials once")
        assertTrue(Files.exists(credentials))
        for (secret in listOf("pw-GOOD-1", "654321", "JWT-NEW-SECRET", "MNEMONIC-SECRET")) {
            assertFalse(secret in reply, "'$secret' must not be in the reply")
            assertFalse(secret in loggedText(), "'$secret' must not be logged")
            assertFalse(secret in h.toString(), "'$secret' must not be in toString()")
        }
    }

    @Test
    fun `a wrong password leaves the stored credentials byte-identical and reloads nothing`() = runBlocking {
        fakeInternxt()
        val old = """{"jwt":"JWT-OLD-SECRET"}""".toByteArray()
        Files.write(credentials, old)
        var reloads = 0

        val reply = handler { reloads++ }.login("""{"verb":"auth.login","email":"user@example.test","password":"pw-WRONG-2"}""")

        assertEquals("bad_credentials", parse(reply).getValue("error").jsonPrimitive.content)
        assertContentEquals(old, Files.readAllBytes(credentials))
        assertEquals(0, reloads)
        assertFalse("pw-WRONG-2" in reply)
        assertFalse("pw-WRONG-2" in loggedText(), "the provider's message quotes the password here: it must not be logged")
    }

    @Test
    fun `a login without a password, an e-mail or an Internxt profile is refused before any sign-in`() = runBlocking {
        LifecycleHooks.internxtLogin = { _, _, _, _ -> error("no sign-in may run") }
        val h = handler()
        assertEquals("empty_password", parse(h.login("""{"verb":"auth.login","email":"user@example.test"}""")).getValue("error").jsonPrimitive.content)
        assertEquals("missing_email", parse(h.login("""{"verb":"auth.login","password":"x"}""")).getValue("error").jsonPrimitive.content)
        val onedrive = handler(ProfileTarget("od", "onedrive", dir))
        assertEquals("login_not_supported", parse(onedrive.login("""{"verb":"auth.login","email":"a@example.test","password":"x"}""")).getValue("error").jsonPrimitive.content)
    }

    @Test
    fun `a daemon without a sign-in target and a request that is not an object are refused`() = runBlocking {
        assertEquals("sign_in_unavailable", parse(handler(target = null).login("""{"verb":"auth.login"}""")).getValue("error").jsonPrimitive.content)
        assertEquals("invalid_request", parse(handler().begin("""[1]""")).getValue("error").jsonPrimitive.content)
    }

    @Test
    fun `stored credentials the daemon cannot load are reload_failed`() = runBlocking {
        fakeInternxt()
        val reply = handler { throw IllegalStateException("token JWT-NEW-SECRET") }.login("""{"verb":"auth.login","email":"user@example.test","password":"pw-GOOD-1"}""")

        assertEquals("reload_failed", parse(reply).getValue("error").jsonPrimitive.content)
        assertFalse("JWT-NEW-SECRET" in reply)
        assertFalse("JWT-NEW-SECRET" in loggedText())
    }

    /** A device-code factory whose polls are scripted; it records the secrets it was handed. */
    private class DeviceFactory(
        val polls: ArrayDeque<CompleteAuthResult>,
    ) : ProviderFactory {
        override val id = "onedrive"
        override val metadata = ProviderMetadata("onedrive", "Fake", "test", "oauth", "none", "x", true, false, null, "Test")
        val secrets = mutableListOf<String?>()

        override fun create(
            properties: Map<String, String?>,
            tokenPath: Path,
        ): CloudProvider = throw UnsupportedOperationException()

        override fun isAuthenticated(
            properties: Map<String, String?>,
            profileDir: Path,
        ) = false

        override fun supportsInteractiveAuth() = true

        override suspend fun beginInteractiveAuth(profileDir: Path): BeginAuthResult =
            BeginAuthResult.of(
                continuationHandle = "handle-1",
                fields = linkedMapOf("user_code" to JsonPrimitive("ABCD-1234"), "verification_uri" to JsonPrimitive("https://example.test/device")),
                expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
                retryAfterSeconds = 5,
            )

        override suspend fun completeInteractiveAuth(
            profileDir: Path,
            continuationHandle: String,
            secret: String?,
        ): CompleteAuthResult {
            secrets += secret
            return polls.removeFirst()
        }
    }

    @Test
    fun `begin and complete run the device-code flow, and only a finished sign-in reloads`() = runBlocking {
        val factory = DeviceFactory(ArrayDeque(listOf(CompleteAuthResult.Pending(5), CompleteAuthResult.Success("Ada Lovelace"))))
        LifecycleHooks.factoryFor = { factory }
        var reloads = 0
        val h = handler(ProfileTarget("od", "onedrive", dir)) { reloads++ }

        val begun = parse(h.begin("""{"verb":"auth.begin"}"""))
        assertEquals("handle-1", begun.getValue("handle").jsonPrimitive.content)
        assertEquals("ABCD-1234", begun.getValue("user_code").jsonPrimitive.content)

        assertEquals("pending", parse(h.complete("""{"verb":"auth.complete","handle":"handle-1"}""")).getValue("status").jsonPrimitive.content)
        assertEquals(0, reloads, "a pending poll stored nothing")
        val done = h.complete("""{"verb":"auth.complete","handle":"handle-1","code":"CODE-SECRET-3"}""")
        assertEquals("authenticated", parse(done).getValue("status").jsonPrimitive.content)
        assertEquals(1, reloads)
        assertEquals(listOf(null, "CODE-SECRET-3"), factory.secrets)
        assertFalse("CODE-SECRET-3" in done)
        assertFalse("CODE-SECRET-3" in loggedText())
    }

    @Test
    fun `a failed or expired device-code sign-in is its token and reloads nothing`() = runBlocking {
        LifecycleHooks.factoryFor = { DeviceFactory(ArrayDeque(listOf(CompleteAuthResult.Failure("expired", CompleteAuthResult.Reason.EXPIRED)))) }
        var reloads = 0
        val h = handler(ProfileTarget("od", "onedrive", dir)) { reloads++ }

        assertEquals("missing_handle", parse(h.complete("""{"verb":"auth.complete"}""")).getValue("error").jsonPrimitive.content)
        assertEquals("flow_expired", parse(h.complete("""{"verb":"auth.complete","handle":"handle-1"}""")).getValue("error").jsonPrimitive.content)
        assertEquals(0, reloads)
    }

    // -- another user than the profile's (account_changed) --------------------------------

    @Test
    fun `a device-code sign-in as another user is account_changed and reloads nothing`() = runBlocking {
        val refusal = CompleteAuthResult.Failure("another account", CompleteAuthResult.Reason.ACCOUNT_CHANGED)
        LifecycleHooks.factoryFor = { DeviceFactory(ArrayDeque(listOf(refusal))) }
        var reloads = 0
        val h = handler(ProfileTarget("od", "onedrive", dir)) { reloads++ }

        val r = parse(h.complete("""{"verb":"auth.complete","handle":"handle-1"}"""))

        assertEquals("account_changed", r.getValue("error").jsonPrimitive.content)
        assertEquals(0, reloads, "the running daemon keeps the credentials it has")
    }

    @Test
    fun `a device-code sign-in whose user could not be read is account_unverified and reloads nothing`() = runBlocking {
        val refusal = CompleteAuthResult.Failure("unknown", CompleteAuthResult.Reason.ACCOUNT_UNVERIFIED)
        LifecycleHooks.factoryFor = { DeviceFactory(ArrayDeque(listOf(refusal))) }
        var reloads = 0
        val h = handler(ProfileTarget("od", "onedrive", dir)) { reloads++ }

        assertEquals("account_unverified", parse(h.complete("""{"verb":"auth.complete","handle":"handle-1"}""")).getValue("error").jsonPrimitive.content)
        assertEquals(0, reloads)
    }

    @Test
    fun `a login as another user is account_changed, keeps the stored credentials and the name, and reloads nothing`() = runBlocking {
        LifecycleHooks.internxtLogin = { _, _, _, _ -> throw org.krost.unidrive.AccountChangedException() }
        val old = """{"jwt":"JWT-OLD-SECRET"}""".toByteArray()
        Files.write(credentials, old)
        org.krost.unidrive.AccountName.write(dir, "ada@example.test")
        var reloads = 0

        val reply = handler { reloads++ }.login("""{"verb":"auth.login","email":"bob@example.test","password":"pw-GOOD-1"}""")

        assertEquals("account_changed", parse(reply).getValue("error").jsonPrimitive.content)
        assertContentEquals(old, Files.readAllBytes(credentials))
        assertEquals("ada@example.test", org.krost.unidrive.AccountName.read(dir), "the typed e-mail is not written")
        assertEquals(0, reloads)
    }

    // -- over a live socket: the running daemon picks the new credentials up --------------

    /** A provider that is signed in exactly when a credential file is stored; it rejects the credentials on request. */
    private inner class FileBackedProvider : CloudProvider {
        val rejectCredentials = AtomicBoolean(false)
        var reloads = 0
        override val id: String = "stub"
        override val displayName: String = "Stub"
        override var isAuthenticated: Boolean = true

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() {}

        override suspend fun reloadCredentials() {
            reloads++
            isAuthenticated = Files.exists(credentials)
        }

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
            if (rejectCredentials.get()) throw AuthenticationException("Authentication expired. Please re-authenticate.")
            return DeltaPage(items = emptyList(), cursor = "cursor-1", hasMore = false)
        }

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    private val socketPath: Path get() = dir.resolve("daemon.sock")

    private fun request(line: String): JsonObject {
        val channel = IpcAuthClient.connect(IpcEndpoint(socketPath, dir, "ix"), IpcAuth.Scope.FULL)
        try {
            channel.configureBlocking(false)
            channel.write(ByteBuffer.wrap((line + "\n").toByteArray(Charsets.UTF_8)))
            val collected = java.io.ByteArrayOutputStream()
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline && '\n'.code.toByte() !in collected.toByteArray()) {
                val buf = ByteBuffer.allocate(4096)
                val n = channel.read(buf)
                if (n > 0) collected.write(buf.array(), 0, n) else Thread.sleep(20)
            }
            return Json.parseToJsonElement(collected.toString(Charsets.UTF_8).substringBefore('\n')).jsonObject
        } finally {
            channel.close()
        }
    }

    private suspend fun awaitStatus(
        what: String,
        predicate: (JsonObject) -> Boolean,
    ): JsonObject {
        var last: JsonObject? = null
        return try {
            withTimeout(20_000) {
                var found: JsonObject? = null
                while (found == null) {
                    val s = request("""{"verb":"daemon.status"}""")
                    last = s
                    if (predicate(s)) found = s else delay(50)
                }
                found
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw IllegalStateException("status never showed $what; last: $last", e)
        }
    }

    private fun JsonObject.credentialState(): String = getValue("credential_health").jsonObject.getValue("state").jsonPrimitive.content

    private fun JsonObject.authenticated(): Boolean = getValue("authenticated").jsonPrimitive.boolean

    @Test
    fun `a running daemon signed out and in again reports the account authenticated at once, without a restart`() = runBlocking<Unit> {
        fakeInternxt()
        Files.writeString(credentials, """{"jwt":"JWT-OLD-SECRET"}""")
        val provider = FileBackedProvider()
        val runtime =
            DaemonRuntime(
                profileMode = ProfileMode.MOUNT,
                profileName = "ix",
                lockFile = dir.resolve(".lock"),
                dbPath = dir.resolve("state.db"),
                syncRoot = dir,
                socketPath = socketPath,
                providerFactory = { provider },
                providerHasQuota = false,
                // The offline check reads the credential file, as the providers' checks do. A long TTL: only a sign-in or the
                // rejection below may move the state.
                credentialHealthCheck = { if (Files.exists(credentials)) CredentialHealth.Ok else CredentialHealth.Missing("no credentials") },
                credentialHealthTtlMs = 3_600_000L,
            )
        runtime.signInTarget = internxtTarget()
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath, daemonJob)
        try {
            assertEquals("ok", awaitStatus("the first credential check") { it.credentialState() == "ok" }.credentialState())

            // The credentials stop working: the provider rejects them, and the status says expired.
            request("""{"verb":"sync.enumerate"}""")
            awaitStatus("a completed enumeration") { it.getValue("provider_health").jsonObject["last_contact_ms"] !is kotlinx.serialization.json.JsonNull }
            provider.rejectCredentials.set(true)
            delay(30)
            request("""{"verb":"sync.enumerate"}""")
            awaitStatus("the rejection") { it.credentialState() == "expired" }
            provider.rejectCredentials.set(false)

            // A wrong password: nothing changes.
            assertEquals("bad_credentials", request("""{"verb":"auth.login","email":"user@example.test","password":"pw-WRONG-4"}""").getValue("error").jsonPrimitive.content)
            assertEquals("expired", request("""{"verb":"daemon.status"}""").credentialState())

            // The sign-in: the same daemon, the same provider instance, the new credentials at once.
            val signedIn = request("""{"verb":"auth.login","email":"user@example.test","password":"pw-GOOD-1"}""")
            assertTrue(signedIn.getValue("ok").jsonPrimitive.boolean, signedIn.toString())
            val status = request("""{"verb":"daemon.status"}""")
            assertTrue(status.authenticated())
            assertEquals("ok", status.credentialState(), "a rejection of the replaced credentials is history")
            assertEquals(1, provider.reloads)
            assertTrue(daemonJob.isActive, "the daemon was not restarted")
            for (secret in listOf("pw-GOOD-1", "pw-WRONG-4", "JWT-NEW-SECRET")) assertFalse(secret in loggedText(), "'$secret' must not be logged")
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    @Test
    fun `a read-only connection may not sign in`() = runBlocking<Unit> {
        LifecycleHooks.internxtLogin = { _, _, _, _ -> error("a read connection must not reach the sign-in") }
        val runtime =
            DaemonRuntime(
                profileMode = ProfileMode.MOUNT,
                profileName = "ix",
                lockFile = dir.resolve(".lock"),
                dbPath = dir.resolve("state.db"),
                syncRoot = dir,
                socketPath = socketPath,
                providerFactory = { FileBackedProvider() },
                providerHasQuota = false,
            )
        runtime.signInTarget = internxtTarget()
        val daemonJob = launch { runtime.start() }
        awaitDaemonSocket(socketPath, daemonJob)
        try {
            for (verb in listOf("auth.begin", "auth.complete", "auth.login")) {
                val channel = IpcAuthClient.connect(IpcEndpoint(socketPath, dir, "ix"), IpcAuth.Scope.READ)
                try {
                    channel.configureBlocking(false)
                    channel.write(ByteBuffer.wrap(("""{"verb":"$verb","email":"a@example.test","password":"pw"}""" + "\n").toByteArray()))
                    val collected = StringBuilder()
                    val deadline = System.currentTimeMillis() + 10_000
                    while (System.currentTimeMillis() < deadline && !collected.contains('\n')) {
                        val buf = ByteBuffer.allocate(1024)
                        val n = channel.read(buf)
                        if (n > 0) collected.append(String(buf.array(), 0, n)) else Thread.sleep(20)
                    }
                    assertEquals("forbidden", Json.parseToJsonElement(collected.toString().substringBefore('\n')).jsonObject.getValue("error").jsonPrimitive.content, verb)
                } finally {
                    channel.close()
                }
            }
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }
}
