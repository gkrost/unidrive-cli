package org.krost.unidrive.cli

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.CredentialProof
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.NotSignedInException
import org.krost.unidrive.ProviderException
import org.krost.unidrive.ProviderRegistry
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.TransientNetworkException
import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcServer
import org.krost.unidrive.sync.ProcessLock
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `auth check` (archive#1240): proves a profile's stored sign-in against the provider, once, without a daemon start, a scan or a
 * sync, and never by signing in. Every test drives the real command line against a temp config directory with a fake
 * provider (no network, no real account); a daemon is a real IPC server serving the real [AuthCheckRpcHandler].
 */
class AuthCheckTest {
    private lateinit var dir: Path
    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()
    private val originalOut = System.out
    private val originalErr = System.err

    /** The provider whose check is scripted; counts what was called so that "no sign-in, no sync" can be asserted. */
    private class CheckProvider(
        val check: suspend () -> CredentialProof,
    ) : CloudProvider {
        override val id: String = "stub"
        override val displayName: String = "Stub"
        override var isAuthenticated: Boolean = true
        var checks = 0
        var authenticates = 0
        var closed = false

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() {
            authenticates++
        }

        override suspend fun checkCredentials(): CredentialProof {
            checks++
            return check()
        }

        override fun close() {
            closed = true
        }

        override suspend fun listChildren(path: String): List<CloudItem> = error("a credential check lists nothing")

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
        ): DeltaPage = error("a credential check scans nothing")

        override suspend fun quota(): QuotaInfo = error("not used")
    }

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("auth-check")
        LifecycleHooks.terminalPassword = { null }
        LogoutHooks.socketPath = { dir.resolve("no-daemon").resolve("$it.sock") }
        System.setOut(PrintStream(out, true, Charsets.UTF_8))
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        Files.writeString(
            dir.resolve("config.toml"),
            "[general]\ndefault_profile = \"od\"\n\n[providers.od]\ntype = \"onedrive\"\nmode = \"mount\"\n\n" +
                "[providers.lo]\ntype = \"localfs\"\nmode = \"mount\"\nroot_path = \"/x\"\n",
        )
    }

    @AfterTest
    fun tearDown() {
        System.setOut(originalOut)
        System.setErr(originalErr)
        LifecycleHooks.providerFor = { main, target -> main.createProviderForName(target.name) }
        LifecycleHooks.factoryFor = { ProviderRegistry.get(it) }
        LogoutHooks.socketPath = { IpcServer.defaultSocketPath(it) }
        dir.toFile().deleteRecursively()
    }

    private fun run(vararg args: String): Int {
        out.reset()
        err.reset()
        val code = CommandLine(Main()).execute("-c", dir.toString(), *args)
        System.out.flush()
        System.err.flush()
        return code
    }

    private fun json(): JsonObject {
        val text = out.toString(Charsets.UTF_8).trim()
        assertEquals(1, text.lines().size, "stdout must be a single line of JSON, was: $text")
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun errorToken(): String {
        val r = json()
        assertFalse(r.getValue("ok").jsonPrimitive.boolean)
        return r.getValue("error").jsonPrimitive.content
    }

    private fun everything() = out.toString(Charsets.UTF_8) + err.toString(Charsets.UTF_8)

    private fun using(provider: CloudProvider) {
        LifecycleHooks.providerFor = { _, _ -> provider }
    }

    // -- a stopped profile: the CLI asks the provider itself ---------------------------

    @Test
    fun `an accepted sign-in is authenticated, with the account the provider names`() {
        val provider = CheckProvider { CredentialProof("Ada Example") }
        using(provider)

        assertEquals(0, run("auth", "check", "--json"), everything())

        val r = json()
        assertTrue(r.getValue("ok").jsonPrimitive.boolean)
        assertEquals("od", r.getValue("profile").jsonPrimitive.content, "the default profile")
        assertEquals("authenticated", r.getValue("status").jsonPrimitive.content)
        assertEquals("Ada Example", r.getValue("account").jsonPrimitive.content)
        assertEquals(1, provider.checks, "one authenticated call")
        assertEquals(0, provider.authenticates, "never the sign-in path")
        assertTrue(provider.closed)
    }

    @Test
    fun `the account is absent, not null, when the provider does not say it`() {
        using(CheckProvider { CredentialProof() })

        assertEquals(0, run("auth", "check", "--json"))

        assertFalse("account" in json(), out.toString())
    }

    @Test
    fun `a check that cannot reach the provider is network_error however deep the cause, as in the sign-in steps (#143)`() {
        val cases =
            listOf<Pair<Exception, String>>(
                UnresolvedAddressException() to "network_error",
                RuntimeException("wrapped", UnknownHostException("login.example.test")) to "network_error",
                IllegalStateException("outer", RuntimeException("inner", UnresolvedAddressException())) to "network_error",
                // The provider answered: its status decides, not the I/O failure under it.
                RuntimeException("wrapped", ProviderException("no drive", IOException("reset"), httpStatus = 403)) to "provider_error",
                // A local file is not the network.
                IllegalStateException("odd", AccessDeniedException("/x/token.json")) to "provider_error",
            )
        val wrong = mutableListOf<String>()
        for ((failure, token) in cases) {
            using(CheckProvider { throw failure })
            val code = run("auth", "check", "--json")
            val got = errorToken()
            if (code != 1 || got != token) wrong += "$failure (cause ${failure.cause}): $got (exit $code)"
        }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun `each way the check can fail is its own token and exit 1`() {
        val cases =
            listOf<Pair<Exception, String>>(
                NotSignedInException("none stored") to "not_signed_in",
                AuthenticationException("revoked") to "credentials_rejected",
                IOException("reset") to "network_error",
                TransientNetworkException("blip") to "network_error",
                ProviderException("gateway", httpStatus = 503) to "network_error",
                ProviderException("no response") to "network_error",
                ProviderException("no drive", httpStatus = 403) to "provider_error",
                IllegalStateException("odd") to "provider_error",
                UnsupportedOperationException("none") to "unsupported",
            )
        for ((failure, token) in cases) {
            using(CheckProvider { throw failure })

            assertEquals(1, run("auth", "check", "--json"), "$failure")

            assertEquals(token, errorToken(), "$failure")
        }
    }

    @Test
    fun `a refusal quotes nothing from the exception`() {
        using(CheckProvider { throw ProviderException("body: {\"access_token\":\"SECRET-VALUE\"}", httpStatus = 500) })

        assertEquals(1, run("auth", "check", "--json"))

        assertEquals("provider_error", errorToken())
        assertFalse("SECRET-VALUE" in everything(), everything())
    }

    @Test
    fun `a provider that does not answer in time is network_error, not a verdict`() {
        using(CheckProvider { delay(60_000).let { CredentialProof() } })
        val started = System.nanoTime()

        assertEquals(1, run("auth", "check", "--timeout", "1", "--json"))

        assertEquals("network_error", errorToken())
        assertTrue((System.nanoTime() - started) / 1_000_000 < 15_000, "the command's own limit ended the wait")
        assertTrue("1 s" in json().getValue("message").jsonPrimitive.content)
    }

    @Test
    fun `an out-of-range timeout is refused before anything is asked`() {
        val provider = CheckProvider { CredentialProof() }
        using(provider)

        for (bad in listOf("0", "-3", "301")) {
            assertEquals(1, run("auth", "check", "--timeout", bad, "--json"))
            assertEquals("invalid_timeout", errorToken())
        }
        assertEquals(0, provider.checks)
    }

    @Test
    fun `a profile without an account is unsupported and builds no provider`() {
        LifecycleHooks.providerFor = { _, _ -> error("no provider may be built for a profile without an account") }

        assertEquals(1, run("auth", "check", "--profile", "lo", "--json"))

        assertEquals("unsupported", errorToken())
    }

    @Test
    fun `a profile that is not configured is a refusal like the other auth commands`() {
        assertEquals(1, run("auth", "check", "--profile", "nope", "--json"))

        assertEquals("profile_not_found", errorToken())
    }

    @Test
    fun `a profile held by a running sync is profile_in_use`() {
        using(CheckProvider { error("the CLI must not call the provider while another process holds the profile") })
        Files.createDirectories(dir.resolve("od"))
        val holder = ProcessLock(dir.resolve("od").resolve(".lock"))
        assertTrue(holder.tryLock(ProcessLock.Mode.SYNC))
        try {
            assertEquals(1, run("auth", "check", "--json"))
            assertEquals("profile_in_use", errorToken())
        } finally {
            holder.unlock()
        }
    }

    // -- a daemon holds the profile: the check goes through the daemon's own provider ----

    /** A fake daemon of `od`: holds the lock in DAEMON mode and serves [AuthCheckRpcHandler] on a real authenticated socket. */
    private inner class FakeDaemon(
        provider: CloudProvider,
        withHandler: Boolean = true,
    ) : AutoCloseable {
        private val profileDir: Path = Files.createDirectories(dir.resolve("od"))
        private val socketDir: Path = Files.createTempDirectory("auth-check-daemon")
        private val lock = ProcessLock(profileDir.resolve(".lock")).also { assertTrue(it.tryLock(ProcessLock.Mode.DAEMON)) }
        private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        private val server: IpcServer
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()

        init {
            val socket = socketDir.resolve("od.sock")
            LogoutHooks.socketPath = { socket }
            server = IpcServer(socket, auth = IpcAuth.issue(profileDir, "od", "test"))
            if (withHandler) {
                val handler = AuthCheckRpcHandler("od", provider)
                server.registerHandler(AuthCheckRpcHandler.VERB) { _, line ->
                    requests += line
                    handler.check(line)
                }
            }
            server.start(scope)
        }

        override fun close() {
            server.close()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            lock.unlock()
            socketDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a profile held by a daemon is asked through that daemon, and the CLI calls no provider itself`() {
        LifecycleHooks.providerFor = { _, _ -> error("the CLI must not build a provider while a daemon holds the profile") }
        val daemonsProvider = CheckProvider { CredentialProof("Ada Example") }
        FakeDaemon(daemonsProvider).use { daemon ->
            assertEquals(0, run("auth", "check", "--timeout", "9", "--json"), everything())

            val r = json()
            assertEquals("authenticated", r.getValue("status").jsonPrimitive.content)
            assertEquals("Ada Example", r.getValue("account").jsonPrimitive.content)
            val request = Json.parseToJsonElement(daemon.requests.single()).jsonObject
            assertEquals("auth.check", request.getValue("verb").jsonPrimitive.content)
            assertEquals("9", request.getValue("timeout_seconds").jsonPrimitive.content)
            assertEquals(1, daemonsProvider.checks, "the daemon's own instance made the call")
            assertEquals(0, daemonsProvider.authenticates)
        }
    }

    @Test
    fun `the daemon's refusal passes through with its token`() {
        FakeDaemon(CheckProvider { throw AuthenticationException("revoked") }).use {
            assertEquals(1, run("auth", "check", "--json"))

            assertEquals("credentials_rejected", errorToken())
        }
    }

    @Test
    fun `a daemon that predates the verb is daemon_too_old, not profile_in_use`() {
        FakeDaemon(CheckProvider { CredentialProof() }, withHandler = false).use {
            assertEquals(1, run("auth", "check", "--json"))

            assertEquals("daemon_too_old", errorToken())
        }
    }

    // -- the handler on its own ----------------------------------------------------------

    @Test
    fun `the handler answers a request that is not one object, and a bad timeout, with a refusal`() =
        runBlocking {
            val provider = CheckProvider { CredentialProof() }
            val handler = AuthCheckRpcHandler("od", provider)

            val notObject = Json.parseToJsonElement(handler.check("[1]")).jsonObject
            assertEquals("invalid_request", notObject.getValue("error").jsonPrimitive.content)
            val badTimeout = Json.parseToJsonElement(handler.check("""{"verb":"auth.check","timeout_seconds":0}""")).jsonObject
            assertEquals("invalid_timeout", badTimeout.getValue("error").jsonPrimitive.content)
            assertEquals(0, provider.checks)
            val ok = Json.parseToJsonElement(handler.check("""{"verb":"auth.check"}""")).jsonObject
            assertTrue(ok.getValue("ok").jsonPrimitive.boolean)
            assertNull(ok["account"])
        }
}
