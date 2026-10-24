package org.krost.unidrive.cli

import org.krost.unidrive.CloudProvider
import org.krost.unidrive.ProviderException
import org.krost.unidrive.ProviderFactory
import org.krost.unidrive.ProviderMetadata
import org.krost.unidrive.sync.IpcServer
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogoutCommandTest {
    private val configDir = Files.createTempDirectory("unidrive-logout-")
    private lateinit var socketDir: Path

    @BeforeTest
    fun isolateFromTheRealDaemonSocket() {
        // holderOf probes LogoutHooks.socketPath, which defaults to the real /run/user socket and ignores -c.
        // A live "work" daemon would otherwise receive auth.logout from these tests.
        socketDir = Files.createDirectories(configDir.resolve("sock"))
        LogoutHooks.socketPath = { socketDir.resolve("$it.sock") }
        LogoutHooks.requestLogout = { _, _ -> error("a logout test must not talk to a daemon") }
    }

    @AfterTest
    fun tearDown() {
        LogoutHooks.socketPath = { IpcServer.defaultSocketPath(it) }
        LogoutHooks.requestLogout = { endpoint, force -> sendAuthLogout(endpoint, force) }
        configDir.toFile().deleteRecursively()
    }

    @Test
    fun `logout removes pending device flow file`() {
        val root = Files.createDirectory(configDir.resolve("root"))
        val rootPathForToml = root.toString().replace('\\', '/')
        Files.writeString(
            configDir.resolve("config.toml"),
            "[providers.work]\ntype = \"localfs\"\nroot_path = \"$rootPathForToml\"\n",
        )
        val profileDir = Files.createDirectory(configDir.resolve("work"))
        val pendingFlow = Files.writeString(profileDir.resolve("device-flow.json"), "pending")

        val exitCode = CommandLine(Main()).execute("-c", configDir.toString(), "-p", "work", "logout")

        assertEquals(0, exitCode)
        assertFalse(Files.exists(pendingFlow))
    }

    @Test
    fun `logout of a profile whose root is gone signs out and keeps the root-seen marker`() {
        val profileDir = localfsProfileWithVanishedRoot()
        val pendingFlow = Files.writeString(profileDir.resolve("device-flow.json"), "pending")

        val exitCode = CommandLine(Main()).execute("-c", configDir.toString(), "-p", "work", "logout")

        assertEquals(0, exitCode)
        assertFalse(Files.exists(pendingFlow))
        assertTrue(Files.exists(profileDir.resolve("localfs-root-seen")), "the marker is state; a plain logout keeps it")
    }

    @Test
    fun `logout --delete-state removes the root-seen marker`() {
        val root = Files.createDirectory(configDir.resolve("root"))
        writeLocalfsConfig(root)
        val profileDir = Files.createDirectory(configDir.resolve("work"))
        val marker = Files.writeString(profileDir.resolve("localfs-root-seen"), "")

        val exitCode = CommandLine(Main()).execute("-c", configDir.toString(), "-p", "work", "logout", "--delete-state")

        assertEquals(0, exitCode)
        assertFalse(Files.exists(marker))
    }

    @Test
    fun `logout --delete-state of a profile whose root is gone removes the root-seen marker`() {
        val profileDir = localfsProfileWithVanishedRoot()

        val exitCode = CommandLine(Main()).execute("-c", configDir.toString(), "-p", "work", "logout", "--delete-state")

        assertEquals(0, exitCode)
        assertFalse(Files.exists(profileDir.resolve("localfs-root-seen")))
    }

    @Test
    fun `logout keeps the profile's stored user and logout --delete-state removes it`() {
        val root = Files.createDirectory(configDir.resolve("root"))
        writeLocalfsConfig(root)
        val profileDir = Files.createDirectory(configDir.resolve("work"))
        val owner = Files.writeString(profileDir.resolve(org.krost.unidrive.AccountOwner.FILE_NAME), "id-ada")
        Files.writeString(profileDir.resolve(org.krost.unidrive.AccountName.FILE_NAME), "Ada")

        assertEquals(0, CommandLine(Main()).execute("-c", configDir.toString(), "-p", "work", "logout"))

        assertFalse(Files.exists(profileDir.resolve(org.krost.unidrive.AccountName.FILE_NAME)), "the name goes with the credentials")
        assertTrue(Files.exists(owner), "the stored user belongs to the kept state: the next sign-in is compared with it")

        assertEquals(0, CommandLine(Main()).execute("-c", configDir.toString(), "-p", "work", "logout", "--delete-state"))

        assertFalse(Files.exists(owner), "deleting the state deletes whose state it was")
    }

    // An account (authType other than None) that cannot be built is not signed out: its credentials stay,
    // and the command does not report that logout completed. localfs is the one type whose build failure is ignored.
    @Test
    fun `an account provider that cannot be built is not signed out`() {
        val profileDir = Files.createDirectory(configDir.resolve("work"))
        Files.writeString(
            configDir.resolve("config.toml"),
            "[providers.work]\ntype = \"${LogoutAccountUnbuildableFactory.ID}\"\n",
        )
        val token = Files.writeString(profileDir.resolve("token.json"), """{"access_token":"keep"}""")
        val credentials = Files.writeString(profileDir.resolve("credentials.json"), """{"refresh_token":"keep"}""")
        val builtBefore = LogoutAccountUnbuildableFactory.builds.get()

        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        System.setOut(PrintStream(out, true, Charsets.UTF_8))
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        val exitCode =
            try {
                CommandLine(Main()).execute("-c", configDir.toString(), "-p", "work", "logout")
            } finally {
                System.setOut(originalOut)
                System.setErr(originalErr)
            }

        val reported = out.toString(Charsets.UTF_8) + err.toString(Charsets.UTF_8)
        assertEquals(
            builtBefore + 1,
            LogoutAccountUnbuildableFactory.builds.get(),
            "create never threw; the refusal was some earlier failure\n$reported",
        )
        assertTrue(exitCode != 0, reported)
        assertTrue(Files.exists(token), "token.json was removed")
        assertTrue(Files.exists(credentials), "credentials.json was removed")
        assertFalse("Logged out" in reported, reported)
        assertFalse("\"ok\":true" in reported.replace(" ", ""), reported)
    }

    // A profile that has used its root (the marker is there) and whose root no longer exists.
    private fun localfsProfileWithVanishedRoot(): Path {
        writeLocalfsConfig(configDir.resolve("gone"))
        val profileDir = Files.createDirectory(configDir.resolve("work"))
        Files.writeString(profileDir.resolve("localfs-root-seen"), "")
        return profileDir
    }

    private fun writeLocalfsConfig(root: Path) {
        val rootPathForToml = root.toString().replace('\\', '/')
        Files.writeString(
            configDir.resolve("config.toml"),
            "[providers.work]\ntype = \"localfs\"\nroot_path = \"$rootPathForToml\"\n",
        )
    }
}

// Account provider whose create throws ProviderException. Registered through ServiceLoader, the same way
// ProviderRegistry finds every other factory; there is no second registration seam. authType is not "None",
// so logout must not treat the failure as "nothing to revoke".
class LogoutAccountUnbuildableFactory : ProviderFactory {
    override val id: String = ID

    override val metadata: ProviderMetadata =
        ProviderMetadata(
            id = ID,
            displayName = "Logout account (test)",
            description = "Test-only account provider whose create always fails.",
            authType = "OAuth2",
            encryption = "None (test)",
            jurisdiction = "Test process",
            gdprCompliant = true,
            cloudActExposure = false,
            signupUrl = null,
            tier = "Global",
        )

    override fun create(
        properties: Map<String, String?>,
        tokenPath: Path,
    ): CloudProvider {
        builds.incrementAndGet()
        throw ProviderException("the account provider could not be built")
    }

    override fun isAuthenticated(
        properties: Map<String, String?>,
        profileDir: Path,
    ): Boolean = Files.exists(profileDir.resolve("token.json")) || Files.exists(profileDir.resolve("credentials.json"))

    companion object {
        const val ID: String = "logout-refusing"
        val builds: AtomicInteger = AtomicInteger()
    }
}
