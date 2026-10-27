package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.krost.unidrive.BeginAuthResult
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.CompleteAuthResult
import org.krost.unidrive.ProviderFactory
import org.krost.unidrive.ProviderMetadata
import org.krost.unidrive.ProviderRegistry
import org.krost.unidrive.internxt.BadCredentialsException
import org.krost.unidrive.internxt.InternxtConfig
import org.krost.unidrive.internxt.TfaInvalidException
import org.krost.unidrive.internxt.TfaRequiredException
import org.krost.unidrive.internxt.model.InternxtCredentials
import org.krost.unidrive.io.OwnerOnly
import org.krost.unidrive.sync.ProcessLock
import picocli.CommandLine
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.http.HttpConnectTimeoutException
import java.nio.channels.UnresolvedAddressException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `auth begin`, `auth complete` and `auth login`: the console-free halves of `auth`. Every test drives
 * the real command line against a temp config directory, with the provider calls replaced by fakes
 * through [LifecycleHooks] (no network, no real account).
 */
class AuthLifecycleTest {
    private lateinit var dir: Path
    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()
    private val originalOut = System.out
    private val originalErr = System.err
    private val originalIn = System.`in`
    private val realInternxtLogin = LifecycleHooks.internxtLogin

    /** A device-code provider whose poll results are scripted. */
    private class DeviceFactory(
        val polls: ArrayDeque<CompleteAuthResult>,
    ) : ProviderFactory {
        override val id = "onedrive"
        override val metadata = ProviderMetadata("onedrive", "Fake", "test", "oauth", "none", "x", true, false, null, "Test")
        val completedWith = mutableListOf<String>()
        val completedSecrets = mutableListOf<String?>()
        var beganIn: Path? = null

        override fun create(
            properties: Map<String, String?>,
            tokenPath: Path,
        ): CloudProvider = throw UnsupportedOperationException()

        override fun isAuthenticated(
            properties: Map<String, String?>,
            profileDir: Path,
        ) = false

        override fun supportsInteractiveAuth() = true

        override suspend fun beginInteractiveAuth(profileDir: Path): BeginAuthResult {
            beganIn = profileDir
            return BeginAuthResult.of(
                continuationHandle = "handle-1",
                fields =
                    linkedMapOf(
                        "verification_uri" to JsonPrimitive("https://example.test/device"),
                        "user_code" to JsonPrimitive("ABCD-1234"),
                        "interval_seconds" to JsonPrimitive(5),
                        "expires_in" to JsonPrimitive(900),
                        "message" to JsonPrimitive("Enter the code"),
                    ),
                expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
                retryAfterSeconds = 5,
            )
        }

        override suspend fun completeInteractiveAuth(
            profileDir: Path,
            continuationHandle: String,
            secret: String?,
        ): CompleteAuthResult {
            completedWith += continuationHandle
            completedSecrets += secret
            return polls.removeFirst()
        }
    }

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("auth-lifecycle")
        LifecycleHooks.terminalPassword = { null } // never the developer's own console
        // Never the real socket folder: a test that wants a daemon starts a fake one (FakeDaemon).
        LogoutHooks.socketPath = { dir.resolve("no-daemon").resolve("$it.sock") }
        System.setOut(PrintStream(out, true, Charsets.UTF_8))
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        Files.writeString(
            dir.resolve("config.toml"),
            "[general]\ndefault_profile = \"od\"\n\n[providers.od]\ntype = \"onedrive\"\nmode = \"mount\"\n\n" +
                "[providers.ix]\ntype = \"internxt\"\nmode = \"mount\"\n\n[providers.lo]\ntype = \"localfs\"\nmode = \"mount\"\nroot_path = \"/x\"\n",
        )
    }

    @AfterTest
    fun tearDown() {
        System.setOut(originalOut)
        System.setErr(originalErr)
        System.setIn(originalIn)
        LifecycleHooks.factoryFor = { ProviderRegistry.get(it) }
        LifecycleHooks.sleepSeconds = { Thread.sleep(it * 1000) }
        LifecycleHooks.terminalPassword = { null }
        LifecycleHooks.internxtLogin = realInternxtLogin
        LogoutHooks.socketPath = { org.krost.unidrive.sync.IpcServer.defaultSocketPath(it) }
        dir.toFile().deleteRecursively()
    }

    private fun run(
        vararg args: String,
        stdin: String = "",
    ): Int {
        out.reset()
        err.reset()
        System.setIn(ByteArrayInputStream(stdin.toByteArray(Charsets.UTF_8)))
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

    // -- auth begin / complete -------------------------------------------------------

    @Test
    fun `begin returns the code, the page and the handle, and keeps the profile folder owner-only`() {
        val factory = DeviceFactory(ArrayDeque())
        LifecycleHooks.factoryFor = { factory }

        assertEquals(0, run("auth", "begin", "--json"), err.toString())

        val r = json()
        assertTrue(r.getValue("ok").jsonPrimitive.boolean)
        assertEquals("od", r.getValue("profile").jsonPrimitive.content, "the default profile")
        assertEquals("handle-1", r.getValue("handle").jsonPrimitive.content)
        assertEquals("ABCD-1234", r.getValue("user_code").jsonPrimitive.content)
        assertEquals("https://example.test/device", r.getValue("verification_uri").jsonPrimitive.content)
        assertEquals(900L, r.getValue("expires_in").jsonPrimitive.long)
        assertEquals(5L, r.getValue("interval_seconds").jsonPrimitive.long)
        assertEquals("2030-01-01T00:00:00Z", r.getValue("expires_at").jsonPrimitive.content)
        assertEquals(dir.resolve("od"), factory.beganIn)
        // The folder was made owner-only by begin itself: re-applying changes nothing (the lock file the
        // command leaves inside is a descendant, which a re-application would also touch, so remove it).
        Files.deleteIfExists(dir.resolve("od").resolve(".lock"))
        val outcome = OwnerOnly.restrictDirectory(dir.resolve("od"))
        assertTrue(outcome is OwnerOnly.Outcome.Unchanged || outcome is OwnerOnly.Outcome.Unsupported, "$outcome")
    }

    @Test
    fun `begin takes the profile from --profile or from -p`() {
        val factory = DeviceFactory(ArrayDeque())
        LifecycleHooks.factoryFor = { factory }
        Files.writeString(
            dir.resolve("config.toml"),
            Files.readString(dir.resolve("config.toml")) + "\n[providers.od2]\ntype = \"onedrive\"\nmode = \"mount\"\n",
        )

        assertEquals(0, run("auth", "begin", "--profile", "od2", "--json"))
        assertEquals("od2", json().getValue("profile").jsonPrimitive.content)

        assertEquals(0, run("-p", "od2", "auth", "begin", "--json"))
        assertEquals("od2", json().getValue("profile").jsonPrimitive.content)
    }

    @Test
    fun `a Dropbox begin signs in with the app key from the profile`() {
        // The real Dropbox factory: begin only builds the authorize url, so nothing reaches the network.
        Files.writeString(
            dir.resolve("config.toml"),
            Files.readString(dir.resolve("config.toml")) +
                "\n[providers.db]\ntype = \"dropbox\"\nmode = \"mount\"\nclient_id = \"app-key-from-profile\"\n",
        )

        assertEquals(0, run("auth", "begin", "--profile", "db", "--json"), out.toString())

        val query = java.net.URI(json().getValue("authorize_url").jsonPrimitive.content).query.split("&")
        assertTrue("client_id=app-key-from-profile" in query, query.toString())
    }

    // What a sign-in may need before any token exists (the app key) reaches the provider; the profile's passwords and
    // secrets do not, whichever provider type the profile is.
    @Test
    fun `begin and complete hand the provider the profile's config without its secrets`() {
        Files.writeString(
            dir.resolve("config.toml"),
            Files.readString(dir.resolve("config.toml")) +
                "\n[providers.od3]\ntype = \"onedrive\"\nmode = \"mount\"\nclient_id = \"app-key\"\n" +
                "client_secret = \"cs-value\"\npassword = \"pw-value\"\nsecret_access_key = \"sak-value\"\n",
        )
        val delegate = DeviceFactory(ArrayDeque(listOf(CompleteAuthResult.Success())))
        val seen = mutableListOf<Map<String, String?>>()
        LifecycleHooks.factoryFor = {
            object : ProviderFactory by delegate {
                override suspend fun beginInteractiveAuth(
                    profileDir: Path,
                    properties: Map<String, String?>,
                ): BeginAuthResult {
                    seen += properties
                    return delegate.beginInteractiveAuth(profileDir)
                }

                override suspend fun completeInteractiveAuth(
                    profileDir: Path,
                    continuationHandle: String,
                    secret: String?,
                    properties: Map<String, String?>,
                ): CompleteAuthResult {
                    seen += properties
                    return delegate.completeInteractiveAuth(profileDir, continuationHandle, secret)
                }
            }
        }

        assertEquals(0, run("auth", "begin", "--profile", "od3", "--json"), err.toString())
        assertEquals(0, run("auth", "complete", "--profile", "od3", "--handle", "handle-1", "--json"), err.toString())

        assertEquals(2, seen.size, "begin and complete each handed over the profile's config")
        for (properties in seen) {
            assertEquals("app-key", properties["client_id"])
            val secrets = setOf("cs-value", "pw-value", "sak-value")
            assertTrue(properties.values.none { it in secrets }, "a secret value reached the provider: ${properties.keys}")
            assertTrue(properties.keys.none { it in setOf("password", "secret_access_key", "client_secret") }, properties.keys.toString())
        }
    }

    @Test
    fun `complete reports pending, then authenticated`() {
        val factory = DeviceFactory(ArrayDeque(listOf(CompleteAuthResult.Pending(5), CompleteAuthResult.Success())))
        LifecycleHooks.factoryFor = { factory }

        assertEquals(0, run("auth", "complete", "--handle", "handle-1", "--json"))
        val pending = json()
        assertTrue(pending.getValue("ok").jsonPrimitive.boolean)
        assertEquals("pending", pending.getValue("status").jsonPrimitive.content)
        assertEquals(5L, pending.getValue("retry_after_seconds").jsonPrimitive.long)

        assertEquals(0, run("auth", "complete", "--handle", "handle-1", "--json"))
        assertEquals("authenticated", json().getValue("status").jsonPrimitive.content)
        assertEquals(listOf("handle-1", "handle-1"), factory.completedWith)
    }

    @Test
    fun `complete --wait polls until the end, sleeping the provider's interval`() {
        val factory =
            DeviceFactory(ArrayDeque(listOf(CompleteAuthResult.Pending(7), CompleteAuthResult.Pending(0), CompleteAuthResult.Success())))
        LifecycleHooks.factoryFor = { factory }
        val slept = mutableListOf<Long>()
        LifecycleHooks.sleepSeconds = { slept += it }

        assertEquals(0, run("auth", "complete", "--handle", "h", "--wait", "--json"))

        assertEquals("authenticated", json().getValue("status").jsonPrimitive.content)
        assertEquals(listOf(7L, 1L), slept, "never a busy loop: at least one second between polls")
    }

    @Test
    fun `complete reports a failed or expired sign-in as an error`() {
        LifecycleHooks.factoryFor = {
            DeviceFactory(
                ArrayDeque(
                    listOf(
                        CompleteAuthResult.Failure(
                            "Device code expired. Call auth_begin again.",
                            CompleteAuthResult.Reason.EXPIRED,
                        ),
                    ),
                ),
            )
        }

        assertEquals(1, run("auth", "complete", "--handle", "h", "--json"))

        assertEquals("flow_expired", errorToken())
        assertTrue("expired" in json().getValue("message").jsonPrimitive.content)
    }

    @Test
    fun `complete maps stable provider failure reasons to distinct tokens`() {
        val cases =
            listOf(
                CompleteAuthResult.Reason.UNKNOWN_HANDLE to "unknown_handle",
                CompleteAuthResult.Reason.ACCESS_DENIED to "access_denied",
                CompleteAuthResult.Reason.ACCOUNT_CHANGED to "account_changed",
                CompleteAuthResult.Reason.ACCOUNT_UNVERIFIED to "account_unverified",
                null to "auth_failed",
            )
        for ((reason, token) in cases) {
            LifecycleHooks.factoryFor = {
                DeviceFactory(ArrayDeque(listOf(CompleteAuthResult.Failure("provider failure", reason))))
            }

            assertEquals(1, run("auth", "complete", "--handle", "h", "--json"))
            assertEquals(token, errorToken())
        }
    }

    @Test
    fun `complete bounds echoed provider failure text`() {
        val message = "provider response ".repeat(200)
        LifecycleHooks.factoryFor = { DeviceFactory(ArrayDeque(listOf(CompleteAuthResult.Failure(message)))) }

        assertEquals(1, run("auth", "complete", "--handle", "h", "--json"))

        val returnedMessage = json().getValue("message").jsonPrimitive.content
        assertTrue(returnedMessage.length <= 512, "provider failure text was not capped: ${returnedMessage.length} characters")
        assertTrue(message.startsWith(returnedMessage))
    }

    @Test
    fun `complete without a handle is a usage error, not a guess`() {
        LifecycleHooks.factoryFor = { DeviceFactory(ArrayDeque()) }
        assertEquals(2, run("auth", "complete", "--json"))
    }

    @Test
    fun `a profile without a device-code flow, an unknown profile and a missing config are refused`() {
        assertEquals(1, run("auth", "begin", "--profile", "lo", "--json"))
        assertEquals("no_device_code_flow", errorToken())

        assertEquals(1, run("auth", "begin", "--profile", "nope", "--json"))
        assertEquals("profile_not_found", errorToken())

        assertEquals(1, run("auth", "begin", "--profile", "../x", "--json"))
        assertEquals("invalid_profile_name", errorToken())

        Files.delete(dir.resolve("config.toml"))
        assertEquals(1, run("auth", "begin", "--json"))
        assertEquals("config_not_found", errorToken())
    }

    @Test
    fun `a profile held by a running process is profile_in_use, not an exit`() {
        LifecycleHooks.factoryFor = { DeviceFactory(ArrayDeque()) }
        Files.createDirectories(dir.resolve("od"))
        val holder = ProcessLock(dir.resolve("od").resolve(".lock"))
        assertTrue(holder.tryLock(ProcessLock.Mode.DAEMON))
        try {
            assertEquals(1, run("auth", "begin", "--json"))
            assertEquals("profile_in_use", errorToken())
            assertTrue("daemon" in json().getValue("message").jsonPrimitive.content)
        } finally {
            holder.unlock()
        }
    }

    @Test
    fun `a failing provider call is begin_failed and quotes nothing from the exception`() {
        LifecycleHooks.factoryFor = {
            object : ProviderFactory by DeviceFactory(ArrayDeque()) {
                // The overload the CLI calls: a delegated object would route the other one past this override.
                override suspend fun beginInteractiveAuth(
                    profileDir: Path,
                    properties: Map<String, String?>,
                ): BeginAuthResult = error("client_secret=SHHH")

                override fun supportsInteractiveAuth() = true
            }
        }

        assertEquals(1, run("auth", "begin", "--json"))

        assertEquals("begin_failed", errorToken())
        assertFalse("SHHH" in everything())
    }

    /** A device-code provider whose begin and complete both fail with [failure]. */
    private fun failingFactory(failure: () -> Exception) =
        object : ProviderFactory by DeviceFactory(ArrayDeque()) {
            override suspend fun beginInteractiveAuth(
                profileDir: Path,
                properties: Map<String, String?>,
            ): BeginAuthResult = throw failure()

            override suspend fun completeInteractiveAuth(
                profileDir: Path,
                continuationHandle: String,
                secret: String?,
                properties: Map<String, String?>,
            ): CompleteAuthResult = throw failure()

            override fun supportsInteractiveAuth() = true
        }

    // Every case is run and the wrong ones are listed together: a loop that stops at the first one hides the rest.
    private fun signInTokens(
        failures: List<Pair<String, () -> Exception>>,
        beginToken: String,
        completeToken: String,
    ): List<String> {
        val wrong = mutableListOf<String>()
        for ((name, failure) in failures) {
            LifecycleHooks.factoryFor = { failingFactory(failure) }
            for ((step, args, token) in
                listOf(
                    Triple("begin", arrayOf("auth", "begin", "--json"), beginToken),
                    Triple("complete", arrayOf("auth", "complete", "--handle", "handle-1", "--json"), completeToken),
                )) {
                val code = run(*args)
                val got = errorToken()
                if (code != 1 || got != token) wrong += "$step, $name: $got (exit $code)"
                assertFalse("SHHH" in everything(), "$step, $name must not echo the exception's message")
            }
        }
        return wrong
    }

    @Test
    fun `a sign-in step that cannot reach the provider is network_error, however deep the cause (#143)`() {
        assertEquals(emptyList(), signInTokens(unreachableFailures(), "network_error", "network_error"))
    }

    @Test
    fun `a sign-in step that fails without a network cause keeps begin_failed and complete_failed (#143)`() {
        assertEquals(emptyList(), signInTokens(reachableFailures(), "begin_failed", "complete_failed"))
    }

    @Test
    fun `an unexpected failure is still one JSON refusal with the exception type only`() {
        LifecycleHooks.factoryFor = { throw IllegalStateException("client_secret=SHHH") }

        assertEquals(1, run("auth", "begin", "--json"))

        assertEquals("internal_error", errorToken())
        assertTrue("IllegalStateException" in json().getValue("message").jsonPrimitive.content)
        assertFalse("SHHH" in everything())
    }

    // -- auth login ------------------------------------------------------------------

    private class LoginCall(
        val config: InternxtConfig,
        val email: String,
        val password: String,
        val tfa: String?,
    )

    private fun fakeLogin(
        calls: MutableList<LoginCall>,
        result: () -> Unit = {},
    ) {
        LifecycleHooks.internxtLogin = { config, email, password, tfa ->
            calls += LoginCall(config, email, password, tfa)
            result()
            InternxtCredentials(jwt = "JWT-SECRET", mnemonic = "MNEMONIC-SECRET", rootFolderId = "r", email = email, bridgeUser = email, bridgeUserId = "u", bucket = "b")
        }
    }

    @Test
    fun `login reads the password from stdin and never echoes it or the credentials`() {
        val calls = mutableListOf<LoginCall>()
        fakeLogin(calls)

        val code = run("auth", "login", "--profile", "ix", "--email", "me@example.test", "--password-stdin", "--totp", "123456", "--json", stdin = " pass word 1 \r\nsecond line\n")

        assertEquals(0, code, err.toString())
        val r = json()
        assertEquals("authenticated", r.getValue("status").jsonPrimitive.content)
        assertEquals("ix", r.getValue("profile").jsonPrimitive.content)
        assertEquals("me@example.test", r.getValue("account").jsonPrimitive.content)
        val call = calls.single()
        assertEquals(" pass word 1 ", call.password, "first line only, the line terminator removed, spaces kept")
        assertEquals("123456", call.tfa)
        assertEquals(dir.resolve("ix"), call.config.tokenPath)
        assertEquals("me@example.test", org.krost.unidrive.AccountName.read(dir.resolve("ix")), "the account name is kept next to the credentials")
        val seen = everything()
        for (secret in listOf("pass word 1", "123456", "JWT-SECRET", "MNEMONIC-SECRET")) {
            assertFalse(secret in seen, "'$secret' must not appear in stdout or stderr")
        }
    }

    /** A fake terminal: counts the masked reads and answers with [typed] (null: end of input). */
    private class FakeTerminal(
        private val typed: String?,
    ) : MaskedPasswordReader {
        var reads = 0

        override fun readPassword(): CharArray? {
            reads++
            return typed?.toCharArray()
        }
    }

    @Test
    fun `login on a terminal reads the password masked, prompts on stderr only and prints no secret`() {
        val calls = mutableListOf<LoginCall>()
        fakeLogin(calls)
        val terminal = FakeTerminal(" pass word 2 ")
        LifecycleHooks.terminalPassword = { terminal }

        // Whatever is on System.in is ignored: on a terminal the console reads, not the stream.
        val code = run("auth", "login", "--profile", "ix", "--email", "me@example.test", "--password-stdin", "--json", stdin = "from-the-stream\n")

        assertEquals(0, code, err.toString())
        assertEquals(1, terminal.reads, "one masked read")
        assertEquals(" pass word 2 ", calls.single().password, "the masked line as typed, spaces kept")
        assertEquals("authenticated", json().getValue("status").jsonPrimitive.content, "stdout is still exactly one JSON line")
        assertTrue(err.toString(Charsets.UTF_8).contains("Password"), "the prompt goes to stderr")
        assertFalse("Password" in out.toString(Charsets.UTF_8), "the prompt stays off stdout")
        for (secret in listOf("pass word 2", "from-the-stream", "JWT-SECRET", "MNEMONIC-SECRET")) {
            assertFalse(secret in everything(), "'$secret' must not appear in stdout or stderr")
        }
    }

    @Test
    fun `login as another user than the profile's is account_changed, exits 1 and keeps the stored name`() {
        val calls = mutableListOf<LoginCall>()
        fakeLogin(calls) { throw org.krost.unidrive.AccountChangedException() }
        Files.createDirectories(dir.resolve("ix"))
        org.krost.unidrive.AccountName.write(dir.resolve("ix"), "ada@example.test")

        assertEquals(1, run("auth", "login", "--profile", "ix", "--email", "bob@example.test", "--password-stdin", "--json", stdin = "pw\n"))

        assertEquals("account_changed", errorToken())
        assertEquals("ada@example.test", org.krost.unidrive.AccountName.read(dir.resolve("ix")))
    }

    @Test
    fun `login on a terminal with nothing typed is an empty_password refusal`() {
        val calls = mutableListOf<LoginCall>()
        fakeLogin(calls)
        for (typed in listOf("", null)) {
            LifecycleHooks.terminalPassword = { FakeTerminal(typed) }
            assertEquals(1, run("auth", "login", "--profile", "ix", "--email", "a@b.test", "--password-stdin", "--json", stdin = "pw\n"))
            assertEquals("empty_password", errorToken())
        }
        assertTrue(calls.isEmpty(), "no login attempt without a password")
    }

    @Test
    fun `login without a terminal reads standard input silently`() {
        val calls = mutableListOf<LoginCall>()
        fakeLogin(calls)
        LifecycleHooks.terminalPassword = { null }

        assertEquals(0, run("auth", "login", "--profile", "ix", "--email", "a@b.test", "--password-stdin", "--json", stdin = "pw\r\n"))

        assertEquals("pw", calls.single().password)
        assertEquals("", err.toString(Charsets.UTF_8), "no prompt when the password is piped in")
    }

    @Test
    fun `login without a totp sends none`() {
        val calls = mutableListOf<LoginCall>()
        fakeLogin(calls)

        assertEquals(0, run("auth", "login", "--profile", "ix", "--email", "a@b.test", "--password-stdin", "--json", stdin = "pw\n"))

        assertEquals(null, calls.single().tfa)
    }

    @Test
    fun `login maps the provider's failures to tokens without echoing their messages`() {
        val cases =
            listOf<Pair<() -> Nothing, String>>(
                { throw TfaRequiredException("server says: pw=SHHH") } to "tfa_required",
                { throw TfaInvalidException("Invalid 2FA code: SHHH") } to "tfa_invalid",
                { throw BadCredentialsException("Wrong password: SHHH") } to "bad_credentials",
                { throw org.krost.unidrive.AuthenticationException("Access failed: SHHH") } to "auth_failed",
                { throw IOException("timeout SHHH") } to "network_error",
                { throw IllegalStateException("SHHH") } to "login_failed",
            )
        for ((fail, token) in cases) {
            fakeLogin(mutableListOf()) { fail() }
            assertEquals(1, run("auth", "login", "--profile", "ix", "--email", "a@b.test", "--password-stdin", "--json", stdin = "pw\n"), token)
            assertEquals(token, errorToken())
            assertFalse("SHHH" in everything(), "$token must not echo the provider's message")
        }
    }

    private fun loginTokens(
        failures: List<Pair<String, () -> Exception>>,
        token: String,
    ): List<String> {
        val wrong = mutableListOf<String>()
        for ((name, failure) in failures) {
            fakeLogin(mutableListOf()) { throw failure() }
            val code = run("auth", "login", "--profile", "ix", "--email", "a@b.test", "--password-stdin", "--json", stdin = "pw\n")
            val got = errorToken()
            if (code != 1 || got != token) wrong += "$name: $got (exit $code)"
            assertFalse("SHHH" in everything(), "$name must not echo the exception's message")
        }
        return wrong
    }

    @Test
    fun `a login that cannot reach the provider is network_error, however deep the cause (#143)`() {
        assertEquals(emptyList(), loginTokens(unreachableFailures(), "network_error"))
    }

    @Test
    fun `a login that fails without a network cause stays login_failed (#143)`() {
        assertEquals(emptyList(), loginTokens(reachableFailures(), "login_failed"))
    }

    @Test
    fun `login refuses a missing password flag, an empty password, another provider and an argument password`() {
        val calls = mutableListOf<LoginCall>()
        fakeLogin(calls)

        assertEquals(1, run("auth", "login", "--profile", "ix", "--email", "a@b.test", "--json", stdin = "pw\n"))
        assertEquals("password_stdin_required", errorToken())

        assertEquals(1, run("auth", "login", "--profile", "ix", "--email", "a@b.test", "--password-stdin", "--json", stdin = "\n"))
        assertEquals("empty_password", errorToken())

        assertEquals(1, run("auth", "login", "--profile", "ix", "--email", " ", "--password-stdin", "--json", stdin = "pw\n"))
        assertEquals("missing_email", errorToken())

        assertEquals(1, run("auth", "login", "--profile", "od", "--email", "a@b.test", "--password-stdin", "--json", stdin = "pw\n"))
        assertEquals("login_not_supported", errorToken())

        // There is no --password option at all: a password cannot be put in an argument list.
        assertEquals(2, run("auth", "login", "--profile", "ix", "--email", "a@b.test", "--password", "SHHH", "--json"))
        assertFalse("SHHH" in out.toString(Charsets.UTF_8))

        assertTrue(calls.isEmpty(), "no refused call reached the provider")
    }

    @Test
    fun `the existing console auth is still the command's own action`() {
        val spec = CommandLine(Main()).subcommands.getValue("auth")
        assertEquals(setOf("begin", "complete", "login", "check"), spec.subcommands.keys)
        assertTrue(spec.commandSpec.findOption("--device-code") != null, "-d/--device-code is unchanged")
    }

    // -- archive#866: the stdin code of a no-redirect flow -------------------------------

    @Test
    fun `complete pipes the stdin code to the factory and never prints it`() {
        val factory = DeviceFactory(polls = ArrayDeque(listOf(CompleteAuthResult.Success())))
        LifecycleHooks.factoryFor = { factory }
        val code = "paste-the-code-Ab12-XYZ"

        val exit = run("auth", "complete", "--handle", "h1", "--code-stdin", "--json", stdin = code + System.lineSeparator())

        assertEquals(0, exit, out.toString() + err.toString())
        assertEquals(listOf<String?>(code), factory.completedSecrets, "the factory received the code out of band")
        val text = out.toString() + err.toString()
        assertTrue(code !in text, "the code never appears in any output: $text")
    }

    @Test
    fun `code-stdin without input refuses with missing_code`() {
        val factory = DeviceFactory(polls = ArrayDeque(listOf(CompleteAuthResult.Success())))
        LifecycleHooks.factoryFor = { factory }
        Files.createDirectories(dir.resolve("dp"))

        assertEquals(1, run("auth", "complete", "--handle", "h1", "--code-stdin"))
        assertTrue("standard input held no code" in err.toString(), "the text path words the refusal: " + err.toString())
        assertTrue(factory.completedSecrets.singleOrNull() == null, "the factory was never called with a secret")
    }

    // -- the signed-in account's name ----------------------------------------------

    private fun fixtureLines(name: String): List<String> =
        checkNotNull(javaClass.getResourceAsStream("/ipc-contract/cli/$name")) { "$name missing" }
            .bufferedReader().readLines().filter { it.isNotBlank() }

    private fun replayFixture(
        name: String,
        result: CompleteAuthResult,
    ) {
        val (request, reply) = fixtureLines(name)
        val argv = Json.parseToJsonElement(request).jsonObject.getValue("argv").let { (it as kotlinx.serialization.json.JsonArray).map { a -> a.jsonPrimitive.content } }
        LifecycleHooks.factoryFor = { DeviceFactory(ArrayDeque(listOf(result))) }

        assertEquals(0, run(*argv.toTypedArray()), err.toString())

        assertEquals(Json.parseToJsonElement(reply), json(), "the reply drifted from $name")
    }

    @Test
    fun `complete carries the account when the provider named it`() = replayFixture("auth.complete.ndjson", CompleteAuthResult.Success("Ada Lovelace"))

    @Test
    fun `complete has no account key when the provider has none`() =
        replayFixture("auth.complete-no-account.ndjson", CompleteAuthResult.Success())

    @Test
    fun `the account name is not written to stderr`() {
        LifecycleHooks.factoryFor = { DeviceFactory(ArrayDeque(listOf(CompleteAuthResult.Success("Ada Lovelace")))) }

        assertEquals(0, run("auth", "complete", "--handle", "handle-1", "--json"), err.toString())

        assertFalse("Ada" in err.toString(), err.toString())
    }

    // -- a daemon holds the profile: the sign-in is handed to it ---------------------

    /**
     * A fake daemon of [profile]: it holds the profile lock in DAEMON mode and serves [handlers] on a real,
     * authenticated IPC socket in a temp folder (the tokens next to the lock, as a daemon writes them). Nothing
     * here talks to a real daemon, a real socket folder or a real account.
     */
    private inner class FakeDaemon(
        profile: String,
        handlers: Map<String, (String) -> String>,
    ) : AutoCloseable {
        private val profileDir: Path = Files.createDirectories(dir.resolve(profile))
        private val socketDir: Path = Files.createTempDirectory("auth-daemon")
        private val lock = ProcessLock(profileDir.resolve(".lock")).also { assertTrue(it.tryLock(ProcessLock.Mode.DAEMON)) }
        private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        private val server: org.krost.unidrive.sync.IpcServer
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()

        init {
            val socket = socketDir.resolve("$profile.sock")
            LogoutHooks.socketPath = { socket }
            val auth = org.krost.unidrive.sync.IpcAuth.issue(profileDir, profile, "test")
            server = org.krost.unidrive.sync.IpcServer(socket, auth = auth)
            for ((verb, answer) in handlers) {
                server.registerHandler(verb) { _, line ->
                    requests += line
                    answer(line)
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
    fun `auth login while a daemon holds the profile hands over and succeeds`() {
        LifecycleHooks.internxtLogin = { _, _, _, _ -> error("the CLI must not sign in itself while a daemon holds the profile") }
        FakeDaemon("ix", mapOf("auth.login" to { _ -> """{"ok":true,"profile":"ix","status":"authenticated","account":"me@example.test"}""" })).use { daemon ->
            val code = run("auth", "login", "--profile", "ix", "--email", "me@example.test", "--password-stdin", "--totp", "123456", "--json", stdin = "pw-SECRET-1\n")

            assertEquals(0, code, everything())
            val r = json()
            assertTrue(r.getValue("ok").jsonPrimitive.boolean)
            assertEquals("authenticated", r.getValue("status").jsonPrimitive.content)
            assertEquals("me@example.test", r.getValue("account").jsonPrimitive.content)
            val request = Json.parseToJsonElement(daemon.requests.single()).jsonObject
            assertEquals("auth.login", request.getValue("verb").jsonPrimitive.content)
            assertEquals("me@example.test", request.getValue("email").jsonPrimitive.content)
            assertEquals("pw-SECRET-1", request.getValue("password").jsonPrimitive.content, "the password travels in the request body")
            assertEquals("123456", request.getValue("totp").jsonPrimitive.content)
            for (secret in listOf("pw-SECRET-1", "123456")) assertFalse(secret in everything(), "'$secret' must not appear in stdout or stderr")
        }
    }

    @Test
    fun `the daemon's refusal of a login passes through with its token`() {
        FakeDaemon("ix", mapOf("auth.login" to { _ -> """{"ok":false,"error":"bad_credentials","message":"wrong e-mail or password"}""" })).use {
            assertEquals(1, run("auth", "login", "--profile", "ix", "--email", "me@example.test", "--password-stdin", "--json", stdin = "pw-SECRET-2\n"))

            assertEquals("bad_credentials", errorToken())
            assertFalse("pw-SECRET-2" in everything())
        }
    }

    @Test
    fun `a daemon without the sign-in verbs is daemon_too_old, not profile_in_use`() {
        // A daemon that predates the verbs answers unknown_verb: here, one that registers nothing.
        FakeDaemon("ix", emptyMap()).use {
            assertEquals(1, run("auth", "login", "--profile", "ix", "--email", "me@example.test", "--password-stdin", "--json", stdin = "pw\n"))

            assertEquals("daemon_too_old", errorToken())
        }
    }

    @Test
    fun `begin and complete --wait are handed to the daemon, the code in the body only`() {
        LifecycleHooks.factoryFor = { DeviceFactory(ArrayDeque()) } // the CLI's own provider calls: never reached
        val sleeps = mutableListOf<Long>()
        LifecycleHooks.sleepSeconds = { sleeps += it }
        val polls = ArrayDeque(listOf(
            """{"ok":true,"profile":"od","status":"pending","retry_after_seconds":3}""",
            """{"ok":true,"profile":"od","status":"authenticated","account":"Ada Lovelace"}""",
        ))
        FakeDaemon(
            "od",
            mapOf(
                "auth.begin" to { _ -> """{"ok":true,"profile":"od","handle":"handle-9","user_code":"ABCD-1234","verification_uri":"https://example.test/device","expires_at":"2030-01-01T00:00:00Z"}""" },
                "auth.complete" to { _ -> polls.removeFirst() },
            ),
        ).use { daemon ->
            assertEquals(0, run("auth", "begin", "--json"), everything())
            assertEquals("handle-9", json().getValue("handle").jsonPrimitive.content)

            assertEquals(0, run("auth", "complete", "--handle", "handle-9", "--wait", "--json"), everything())
            assertEquals("authenticated", json().getValue("status").jsonPrimitive.content)
            assertEquals(listOf(3L), sleeps, "the daemon's interval between two polls")

            polls.addLast("""{"ok":true,"profile":"od","status":"authenticated"}""")
            assertEquals(0, run("auth", "complete", "--handle", "handle-9", "--code-stdin", "--json", stdin = "CODE-SECRET\n"), everything())

            val requests = daemon.requests.map { Json.parseToJsonElement(it).jsonObject }
            assertEquals(listOf("auth.begin", "auth.complete", "auth.complete", "auth.complete"), requests.map { it.getValue("verb").jsonPrimitive.content })
            assertEquals("handle-9", requests[1].getValue("handle").jsonPrimitive.content)
            assertFalse("code" in requests[1], "no code without --code-stdin")
            assertEquals("CODE-SECRET", requests[3].getValue("code").jsonPrimitive.content, "the piped code travels in the body")
            assertFalse("CODE-SECRET" in everything())
        }
    }

    @Test
    fun `a profile held by a sync is still profile_in_use`() {
        Files.createDirectories(dir.resolve("ix"))
        val holder = ProcessLock(dir.resolve("ix").resolve(".lock"))
        assertTrue(holder.tryLock(ProcessLock.Mode.SYNC))
        try {
            assertEquals(1, run("auth", "login", "--profile", "ix", "--email", "me@example.test", "--password-stdin", "--json", stdin = "pw\n"))
            assertEquals("profile_in_use", errorToken())
        } finally {
            holder.unlock()
        }
    }

    private companion object {
        /** What a sign-in step throws with no network: each exception directly, and wrapped one and two causes deep. */
        fun unreachableFailures(): List<Pair<String, () -> Exception>> {
            val roots =
                listOf<Pair<String, () -> Exception>>(
                    "UnresolvedAddressException" to { UnresolvedAddressException() },
                    "UnknownHostException" to { UnknownHostException("login.example.test SHHH") },
                    "ConnectException" to { ConnectException("refused SHHH") },
                    "HttpConnectTimeoutException" to { HttpConnectTimeoutException("timed out SHHH") },
                )
            return roots.flatMap { (name, root) ->
                listOf(
                    name to root,
                    "$name in one cause" to { RuntimeException("wrapped SHHH", root()) },
                    "$name in two causes" to { IllegalStateException("outer SHHH", RuntimeException("inner SHHH", root())) },
                )
            }
        }

        /** Failures that are not the network: a plain bug, and a local file the provider could not write. */
        fun reachableFailures(): List<Pair<String, () -> Exception>> =
            listOf(
                "IllegalStateException" to { IllegalStateException("SHHH") },
                "AccessDeniedException in a cause" to { IllegalStateException("SHHH", AccessDeniedException("/x/token.json")) },
            )
    }
}
