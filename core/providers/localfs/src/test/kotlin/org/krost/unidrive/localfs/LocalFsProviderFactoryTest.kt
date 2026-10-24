package org.krost.unidrive.localfs

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.ConfigurationException
import org.krost.unidrive.ProviderException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * archive#910: `profile add` must refuse an unusable `root_path` with a token the wizard can map,
 * instead of accepting the profile and failing forever on the first scan. Drive roots are
 * their own token: a two-way mirror over `X:\` would pull in the system folders and
 * everything else on the volume.
 */
class LocalFsProviderFactoryTest {
    private val factory = LocalFsProviderFactory()
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("localfs-factory")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun create(
        root: Path,
        vararg extra: Pair<String, String>,
    ) {
        factory.create(mapOf("root_path" to root.toString()) + extra.toMap(), dir.resolve("unused-token"))
    }

    // A root below a mount point that is not mounted is gone; re-created empty, it reads as a complete
    // empty drive and the profile's next sync deletes every mirrored file. Once a profile has used its
    // root, a missing root is refused, not re-created: by the factory, so a command fails before it opens
    // any state, and by authenticate for a provider that was built while the root was still there.
    @Test
    fun `the missing root of a profile that has used it before is refused, not re-created`() {
        val profileDir = Files.createDirectories(dir.resolve("profile"))
        val root = dir.resolve("drive")
        val provider = factory.create(mapOf("root_path" to root.toString()), profileDir)
        runBlocking { provider.authenticate() }
        Files.delete(root)

        // A ProviderException like authenticate's, not a ConfigurationException: Main ends the process on that one.
        val refused =
            assertFailsWith<ProviderException> { factory.create(mapOf("root_path" to root.toString()), profileDir) }
        assertTrue("root_not_creatable" in (refused.message ?: ""), "got: ${refused.message}")
        assertFalse(Files.exists(root), "the factory must not re-create the root empty")

        val e = assertFailsWith<ProviderException> { runBlocking { provider.authenticate() } }
        assertTrue("root_not_creatable" in (e.message ?: ""), "got: ${e.message}")
        assertFalse(Files.exists(root), "the root must not be re-created empty")
    }

    // profile add does not create the folder, and a root that is not there is refused: a typo is no profile.
    @Test
    fun `profile add refuses a missing root and does not create it`() {
        assertEquals("root_not_found", factory.preflightAdd(mapOf("root_path" to dir.resolve("new-drive").toString()))?.token)
        assertFalse(Files.exists(dir.resolve("new-drive")))
    }

    @Test
    fun `profile add refuses an allow_drive_root that is not true or false`() {
        val root = Files.createDirectories(dir.resolve("new-drive")).toString()
        assertEquals("invalid_value", factory.preflightAdd(mapOf("root_path" to root, "allow_drive_root" to "yes"))?.token)
        assertEquals(null, factory.preflightAdd(mapOf("root_path" to root, "allow_drive_root" to "false")))
    }

    // The same for a write that arrives while the daemon runs: an upload's or a folder's createDirectories would
    // make the vanished root again, with that one file in it, and the next listing would be complete and nearly empty.
    @Test
    fun `a write below the vanished root of a profile that has used it before does not create the root again`() {
        val profileDir = Files.createDirectories(dir.resolve("profile"))
        val root = dir.resolve("drive")
        val provider = factory.create(mapOf("root_path" to root.toString()), profileDir)
        runBlocking { provider.authenticate() }
        Files.delete(root)
        val source = Files.writeString(dir.resolve("source.txt"), "bytes")

        val writes: Map<String, suspend () -> Unit> =
            mapOf(
                "upload" to { provider.upload(source, "/folder/file.txt") },
                "createFolder" to { provider.createFolder("/folder") },
                "move" to { provider.move("/folder/a.txt", "/other/a.txt") },
            )
        for ((name, write) in writes) {
            val e = assertFailsWith<ProviderException>(name) { runBlocking { write() } }
            assertTrue("root_not_creatable" in (e.message ?: ""), "$name: got: ${e.message}")
            assertFalse(Files.exists(root), "$name must not re-create the root")
        }
    }

    @Test
    fun `the missing root of a new profile is created, even when its state database already exists`() {
        val profileDir = Files.createDirectories(dir.resolve("profile"))
        // The daemon opens state.db before it builds the provider: that alone does not make the profile used.
        Files.writeString(profileDir.resolve("state.db"), "")
        val root = dir.resolve("drive")

        val provider = factory.create(mapOf("root_path" to root.toString()), profileDir)
        runBlocking { provider.authenticate() }
        assertTrue(Files.isDirectory(root))
        assertTrue(provider.isAuthenticated)
    }

    @Test
    fun `missing root_path is refused`() {
        val e = assertFailsWith<ConfigurationException> { factory.create(emptyMap(), dir.resolve("unused-token")) }
        assertTrue("root_path" in e.message, "got: ${e.message}")
    }

    @Test
    fun `a drive root is refused with root_is_drive_root unless allow_drive_root is set`() {
        val root = Path.of(System.getProperty("user.home")).root
        val e = assertFailsWith<ConfigurationException> { create(root) }
        assertTrue("root_is_drive_root" in e.message, "got: ${e.message}")
        // The override gets past the policy refusal; the access probes then decide on their own.
        try {
            create(root, "allow_drive_root" to "true")
        } catch (e2: ConfigurationException) {
            assertTrue(
                "root_is_drive_root" !in e2.message,
                "the override must bypass the policy refusal; got: ${e2.message}",
            )
        }
    }

    @Test
    fun `a path that exists but is a file is refused with root_not_directory`() {
        val file = Files.writeString(dir.resolve("a-file"), "x")
        val e = assertFailsWith<ConfigurationException> { create(file) }
        assertTrue("root_not_directory" in e.message, "got: ${e.message}")
    }

    @Test
    fun `a path that cannot be created under a file parent is refused with root_not_creatable`() {
        val file = Files.writeString(dir.resolve("blocker"), "x")
        val e = assertFailsWith<ConfigurationException> { create(file.resolve("child")) }
        assertTrue("root_not_creatable" in e.message, "got: ${e.message}")
    }

    @Test
    fun `an unreadable directory is refused with root_not_readable`() {
        val root = Files.createDirectories(dir.resolve("locked"))
        val restore = denyRead(root)
            org.junit.Assume.assumeTrue("this platform cannot deny access to a directory", restore != null)
        try {
            val e = assertFailsWith<ConfigurationException> { create(root) }
            assertTrue("root_not_readable" in e.message, "got: ${e.message}")
        } finally {
            restore?.invoke()
        }
    }

    @Test
    fun `an unwritable directory is refused with root_not_writable`() {
        val root = Files.createDirectories(dir.resolve("readonly"))
        val restore = denyWrite(root)
        org.junit.Assume.assumeTrue("this platform cannot deny access to a directory", restore != null)
        try {
            val e = assertFailsWith<ConfigurationException> { create(root) }
            assertTrue("root_not_writable" in e.message, "got: ${e.message}")
        } finally {
            restore?.invoke()
        }
    }
}
