package org.krost.unidrive.cli

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeFalse
import org.krost.unidrive.auth.CredentialData
import org.krost.unidrive.auth.CredentialProtector
import org.krost.unidrive.auth.CredentialSealKey
import org.krost.unidrive.auth.CredentialStore
import org.krost.unidrive.auth.KeyedCredentialProtector
import org.krost.unidrive.internxt.AuthService
import org.krost.unidrive.internxt.InternxtCrypto
import org.krost.unidrive.internxt.authenticateInternxt
import org.krost.unidrive.internxt.model.InternxtCredentials
import org.krost.unidrive.io.Os
import org.krost.unidrive.sync.ConfigSecrets
import org.krost.unidrive.sync.ProfileInfo
import org.krost.unidrive.sync.RawProvider
import org.krost.unidrive.sync.SyncConfig
import org.krost.unidrive.sync.Vault
import org.krost.unidrive.testing.TestScratch
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#861 (Windows Hello P5, engine side): the vault keeps an Internxt profile's sign-in (e-mail and password)
 * as the reserved entry `.internxt-password:<profile>`, opt-in and removed whole when cleared; the vault.internxt_password
 * verbs write it through the daemon; and an expired Internxt session is signed in again by the engine itself with it, through
 * the real sign-in (`auth.login`'s handler, `authenticateInternxt`, its initialize-for-sign-in before the save). A two-factor
 * account is never signed in that way: it reports `totp_required`. Every password and token here is made up; no network.
 */
class InternxtVaultPasswordTest {
    private lateinit var dir: Path
    private val vaultPass = "synthetic-vault-pass".toCharArray()
    private val realLogin = LifecycleHooks.internxtLogin
    private val logs = ListAppender<ILoggingEvent>()
    private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    private var rootLevel: Level? = null

    @BeforeTest
    fun setUp() {
        dir = TestScratch.directory("internxt-vault-password")
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
        dir.toFile().deleteRecursively()
    }

    private fun loggedText(): String = synchronized(logs) { logs.list.toList() }.joinToString("\n") { it.formattedMessage }

    private fun parse(reply: String): JsonObject = Json.parseToJsonElement(reply).jsonObject

    private fun JsonObject.error(): String? = this["error"]?.jsonPrimitive?.content

    // ── the vault entry ─────────────────────────────────────────────────────

    @Test
    fun `the password is stored under the profile's reserved entry and read back with its e-mail`() {
        val data = withVaultInternxtPassword(mapOf("work" to mapOf("password" to "cfg")), "work", "ada@example.test", "pw-VAULT-1")
        assertEquals(mapOf("password" to "cfg"), data["work"], "the profile's own vaulted fields are untouched")
        assertTrue(isReservedVaultSection(internxtPasswordSection("work")))
        val signIn = vaultInternxtSignIn(data, "work")
        assertEquals("ada@example.test", signIn?.email)
        assertEquals("pw-VAULT-1", signIn?.password)
        assertFalse("pw-VAULT-1" in signIn.toString())
        assertNull(vaultInternxtSignIn(data, "other"), "opt-in per profile: another profile has none")
    }

    @Test
    fun `clearing the password removes the entry entirely`() {
        val data = withVaultInternxtPassword(emptyMap(), "work", "ada@example.test", "pw-VAULT-1")
        val cleared = withoutVaultInternxtPassword(data, "work")
        assertEquals(emptyMap(), cleared)
        assertNull(vaultInternxtSignIn(cleared, "work"))
    }

    @Test
    fun `vault decrypt keeps the Internxt password in the vault and out of config toml`() {
        Files.writeString(dir.resolve("config.toml"), "[general]\n\n[providers.work]\ntype = \"internxt\"\n")
        val vault = Vault(dir.resolve("vault.enc"))
        vault.write(vaultPass, withVaultInternxtPassword(mapOf("work" to mapOf("password" to "p")), "work", "ada@example.test", "pw-VAULT-1"))
        val warnings = mutableListOf<String>()
        decryptVault(dir, vaultPass) { warnings += it }
        assertTrue(warnings.isEmpty(), "the password entry is not an orphan profile: $warnings")
        assertEquals(setOf(internxtPasswordSection("work")), vault.read(vaultPass).keys)
        assertFalse("pw-VAULT-1" in Files.readString(dir.resolve("config.toml")))
        val config = dir.resolve("config.toml")
        assertEquals("p", ConfigSecrets.decode(SyncConfig.parseRaw(Files.readString(config)).providers.getValue("work"), "work", config)?.password)
    }

    @Test
    fun `forgetting a profile's vault entry drops its Internxt password too`() {
        val vault = Vault(dir.resolve("vault.enc"))
        vault.write(vaultPass, withVaultInternxtPassword(mapOf("keep" to mapOf("password" to "k")), "gone", "ada@example.test", "pw-VAULT-1"))
        assertTrue(forgetVaultEntry(vault, vaultPass, "gone"), "the password was the profile's only entry: still dropped")
        assertEquals(setOf("keep"), vault.read(vaultPass).keys)
    }

    // ── the IPC verbs ───────────────────────────────────────────────────────

    private fun daemonMain(
        data: Map<String, Map<String, String>>,
        start: DaemonVaultStart = DaemonVaultStart.Ready(emptyMap()),
    ): Pair<Main, VaultRpcHandler> {
        Vault(dir.resolve("vault.enc")).write(vaultPass, data)
        Files.writeString(dir.resolve("config.toml"), "[general]\n\n[providers.work]\ntype = \"internxt\"\n")
        val main = Main().apply { configDir = dir.toString() }
        if (start is DaemonVaultStart.Ready) main.vaultDataCache = data else main.daemonVaultLocked = true
        return main to VaultRpcHandler(main.daemonVault(start, internxtProfile = "work"), failureDelayMs = 0)
    }

    private fun setRequest(
        passphrase: String = String(vaultPass),
        password: String = "pw-VAULT-1",
    ) = """{"verb":"vault.internxt_password.set","passphrase":"$passphrase","email":"ada@example.test","password":"$password"}"""

    @Test
    fun `the set verb stores the password and the daemon's in-memory vault data has it at once`() = runBlocking {
        val (main, handler) = daemonMain(mapOf("other" to mapOf("password" to "o")))
        val reply = handler.internxtPasswordSetVerb(setRequest())
        assertEquals("""{"ok":true}""", reply)
        assertEquals("pw-VAULT-1", vaultInternxtSignIn(Vault(dir.resolve("vault.enc")).read(vaultPass), "work")?.password)
        assertEquals("pw-VAULT-1", main.vaultedInternxtSignIn("work")?.password, "no second vault.unlock needed")
        assertEquals(mapOf("password" to "o"), main.vaultDataCache?.get("other"))
        assertFalse("pw-VAULT-1" in loggedText() || String(vaultPass) in loggedText())
    }

    @Test
    fun `the clear verb removes the entry and says whether there was one`() = runBlocking {
        val (main, handler) = daemonMain(withVaultInternxtPassword(emptyMap(), "work", "ada@example.test", "pw-VAULT-1"))
        assertEquals("""{"ok":true,"removed":true}""", handler.internxtPasswordClearVerb("""{"passphrase":"${String(vaultPass)}"}"""))
        assertEquals(emptyMap(), Vault(dir.resolve("vault.enc")).read(vaultPass))
        assertNull(main.vaultedInternxtSignIn("work"))
        assertEquals("""{"ok":true,"removed":false}""", handler.internxtPasswordClearVerb("""{"passphrase":"${String(vaultPass)}"}"""))
    }

    @Test
    fun `with a locked vault the verbs refuse with vault_locked and write nothing`() = runBlocking {
        val (_, handler) = daemonMain(emptyMap(), DaemonVaultStart.Locked("no passphrase source"))
        val before = Files.readAllBytes(dir.resolve("vault.enc"))
        assertEquals("vault_locked", parse(handler.internxtPasswordSetVerb(setRequest())).error())
        assertEquals("vault_locked", parse(handler.internxtPasswordClearVerb("""{"passphrase":"${String(vaultPass)}"}""")).error())
        assertTrue(before.contentEquals(Files.readAllBytes(dir.resolve("vault.enc"))))
    }

    @Test
    fun `the set verb refuses a wrong passphrase and malformed requests`() = runBlocking {
        val (_, handler) = daemonMain(emptyMap())
        assertEquals("vault_bad_passphrase", parse(handler.internxtPasswordSetVerb(setRequest(passphrase = "not-the-vault-pass"))).error())
        assertEquals("missing_passphrase", parse(handler.internxtPasswordSetVerb("""{"password":"x","email":"a@b.test"}""")).error())
        assertEquals("missing_email", parse(handler.internxtPasswordSetVerb("""{"passphrase":"${String(vaultPass)}","password":"x"}""")).error())
        assertEquals("missing_password", parse(handler.internxtPasswordSetVerb("""{"passphrase":"${String(vaultPass)}","email":"a@b.test"}""")).error())
        assertEquals("invalid_password", parse(handler.internxtPasswordSetVerb("""{"passphrase":"${String(vaultPass)}","email":"a@b.test","password":""}""")).error())
        assertEquals(emptyMap(), Vault(dir.resolve("vault.enc")).read(vaultPass))
    }

    @Test
    fun `a daemon whose profile is not Internxt refuses the verbs`() = runBlocking {
        Vault(dir.resolve("vault.enc")).write(vaultPass, emptyMap())
        val main = Main().apply { configDir = dir.toString() }
        main.vaultDataCache = emptyMap()
        val handler = VaultRpcHandler(main.daemonVault(DaemonVaultStart.Ready(emptyMap()), internxtProfile = null), failureDelayMs = 0)
        assertEquals("login_not_supported", parse(handler.internxtPasswordSetVerb(setRequest())).error())
    }

    // ── the engine's own sign-in ────────────────────────────────────────────

    /** A platform protector stand-in (reversible, its own prefix): what DPAPI / the Keychain would be on the machine. */
    private class TestPlatformProtector : CredentialProtector {
        override fun protect(id: String, plaintext: String): String = "test-platform:" + Base64.getEncoder().encodeToString(plaintext.toByteArray())

        override fun decode(id: String, stored: String): CredentialData =
            if (stored.startsWith("test-platform:")) {
                CredentialData(String(Base64.getDecoder().decode(stored.removePrefix("test-platform:"))), true)
            } else {
                CredentialData(stored, false)
            }

        override fun delete(id: String) = Unit
    }

    private val accessPosts = AtomicInteger()
    private var sealKey: CredentialSealKey? = null
    private val protector = KeyedCredentialProtector(TestPlatformProtector()) { sealKey }

    /**
     * The real library sign-in ([authenticateInternxt], so the initialize-for-sign-in before the save runs) over a fake
     * HTTP layer: one good password, and a two-factor flag. The credentials are stored by the library itself.
     */
    private fun realSignInOverFakeHttp(
        goodPassword: String = "pw-VAULT-1",
        tfa: Boolean = false,
    ) {
        LifecycleHooks.internxtLogin = { config, email, password, totp ->
            authenticateInternxt(config.copy(credentialProtector = protector), email, password, totp) { cfg ->
                object : AuthService(
                    cfg,
                    crypto =
                        object : InternxtCrypto() {
                            override fun decryptMnemonic(
                                encryptedMnemonicHex: String,
                                password: CharArray,
                            ): String = "MNEMONIC-SECRET"

                            override fun hashPassword(
                                password: CharArray,
                                sKey: String,
                                cryptoKey: String,
                            ): String = if (String(password) == goodPassword) "hash-good" else "hash-bad"
                        },
                ) {
                    override suspend fun postLoginChallenge(email: String): String = """{"sKey":"deadbeef","tfa":$tfa}"""

                    override suspend fun postLoginAccess(body: kotlinx.serialization.json.JsonObject): String {
                        accessPosts.incrementAndGet()
                        if (body["password"]?.jsonPrimitive?.content != "hash-good") {
                            throw org.krost.unidrive.internxt.BadCredentialsException("Wrong password")
                        }
                        return """{"newToken":"JWT-NEW-SECRET","user":{"mnemonic":"enc","rootFolderId":"root","uuid":"uuid-ada"}}"""
                    }
                }
            }
        }
    }

    private fun storedCredentials(): InternxtCredentials? =
        CredentialStore(dir, "credentials.json", InternxtCredentials.serializer(), protector = protector).load()

    private fun relogin(signIn: VaultedInternxtSignIn? = VaultedInternxtSignIn("ada@example.test", "pw-VAULT-1")) = InternxtVaultRelogin { signIn }

    private fun loginHandler(onSignedIn: suspend () -> Unit = {}) = AuthLoginRpcHandler(ProfileTarget("work", "internxt", dir), onSignedIn)

    @Test
    fun `an expired session is signed in again with the vaulted password through the real sign-in`() = runBlocking {
        assumeFalse(Os.isWindows())
        realSignInOverFakeHttp()
        // The expired session's file, sealed under a vault key this process no longer holds: only the sign-in's own
        // initialize (initializeForSignIn) gets past it; a plain initialize before the save would throw.
        sealKey = CredentialSealKey(CredentialSealKey.generateSecret(), enabled = true)
        CredentialStore(dir, "credentials.json", InternxtCredentials.serializer(), protector = protector)
            .save(InternxtCredentials(jwt = "JWT-EXPIRED", mnemonic = "old", rootFolderId = "root", email = "ada@example.test"))
        sealKey = null
        var reloads = 0
        val r = relogin()

        val outcome = r.attempt(loginHandler { reloads++ }::login)

        assertEquals(InternxtVaultRelogin.Outcome.SIGNED_IN, outcome)
        assertEquals("JWT-NEW-SECRET", storedCredentials()?.jwt, "the library stored the new session")
        assertEquals(1, reloads, "the running daemon reloads the new credentials, as after auth.login")
        assertEquals("ada@example.test", org.krost.unidrive.AccountName.read(dir))
        assertFalse(r.totpRequired)
        for (secret in listOf("pw-VAULT-1", "JWT-NEW-SECRET", "MNEMONIC-SECRET")) assertFalse(secret in loggedText(), "'$secret' logged")
    }

    @Test
    fun `a two-factor account is not signed in by the engine and reports totp_required`() = runBlocking {
        assumeFalse(Os.isWindows())
        realSignInOverFakeHttp(tfa = true)
        val r = relogin()
        assertEquals(InternxtVaultRelogin.Outcome.TOTP_REQUIRED, r.attempt(loginHandler()::login))
        assertEquals(0, accessPosts.get(), "the password is never sent without the code")
        assertNull(storedCredentials())
        assertTrue(r.totpRequired)
        assertEquals("totp_required", authStatusOf(CredentialHealthMonitor.State.EXPIRED, dir, totpRequired = r.totpRequired).reason)
        assertEquals(InternxtVaultRelogin.Outcome.SKIPPED, r.attempt(loginHandler()::login), "the same stored sign-in is not tried again")
    }

    @Test
    fun `a rejected stored password is not tried again`() = runBlocking {
        assumeFalse(Os.isWindows())
        realSignInOverFakeHttp(goodPassword = "pw-CHANGED-ELSEWHERE")
        val r = relogin()
        assertEquals(InternxtVaultRelogin.Outcome.REJECTED, r.attempt(loginHandler()::login))
        assertEquals(InternxtVaultRelogin.Outcome.SKIPPED, r.attempt(loginHandler()::login))
        assertEquals(1, accessPosts.get())
        assertFalse(r.totpRequired)
    }

    @Test
    fun `without a vaulted password nothing is tried`() = runBlocking {
        realSignInOverFakeHttp()
        assertEquals(InternxtVaultRelogin.Outcome.NO_PASSWORD, relogin(signIn = null).attempt(loginHandler()::login))
        assertEquals(0, accessPosts.get())
    }

    @Test
    fun `the watcher signs in only while the session needs it`() = runBlocking {
        assumeFalse(Os.isWindows())
        realSignInOverFakeHttp()
        var needs = false
        var checks = 0
        val r = relogin()
        val job =
            launch {
                r.watch(intervalMs = 5, needsSignIn = { checks++; needs }, login = loginHandler()::login)
            }
        while (checks < 3) kotlinx.coroutines.delay(5)
        assertEquals(0, accessPosts.get(), "a good session is left alone")
        needs = true
        while (accessPosts.get() == 0) kotlinx.coroutines.delay(5)
        job.cancel()
        assertEquals("JWT-NEW-SECRET", storedCredentials()?.jwt)
    }

    @Test
    fun `a daemon start with an expired session and a vaulted password signs in instead of refusing`() {
        assumeFalse(Os.isWindows())
        realSignInOverFakeHttp()
        val base = dir.resolve("base").also { Files.createDirectories(it.resolve("work")) }
        Files.writeString(base.resolve("config.toml"), "[general]\n\n[providers.work]\ntype = \"internxt\"\n")
        val main = Main().apply { configDir = base.toString() }
        main.vaultDataCache = withVaultInternxtPassword(emptyMap(), "work", "ada@example.test", "pw-VAULT-1")
        val profile = ProfileInfo("work", "internxt", base.resolve("work"), RawProvider(type = "internxt"))

        assertEquals(InternxtVaultRelogin.Outcome.SIGNED_IN, main.reloginBeforeStartRefusal(profile))
        assertEquals(1, accessPosts.get())
        main.vaultDataCache = emptyMap()
        assertEquals(null, main.reloginBeforeStartRefusal(profile), "no vaulted password: the refusal stands as before")
    }

    @Test
    fun `the capability is listed for an Internxt daemon with a vault only`() {
        val on = daemonCapabilitiesJson(org.krost.unidrive.sync.ProfileMode.MOUNT, false, false, org.krost.unidrive.engine.ViewNamespaces.NONE, false, vaultUnlockable = true, internxtPassword = true)
        val off = daemonCapabilitiesJson(org.krost.unidrive.sync.ProfileMode.MOUNT, false, false, org.krost.unidrive.engine.ViewNamespaces.NONE, false, vaultUnlockable = true)
        assertTrue("\"$VAULT_INTERNXT_PASSWORD_CAPABILITY\"" in on)
        assertFalse(VAULT_INTERNXT_PASSWORD_CAPABILITY in off)
    }
}
