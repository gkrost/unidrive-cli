package org.krost.unidrive.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import org.junit.Assume.assumeTrue
import org.krost.unidrive.testing.FakeKeychainProtector
import org.krost.unidrive.testing.FakePlatformProtector
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#797 (P3), owner decision on #943: the seal sits INSIDE the platform layer, so what each platform
 * store holds is the sealed value, never the mnemonic. One test per platform protector: DPAPI (self-contained
 * ciphertext in the file), the Linux Secret Service (an item behind a reference, through a fake `secret-tool`) and the
 * macOS Keychain (an item behind a reference, a fake where the Keychain is not available). The real DPAPI and Keychain
 * variants run only on their OS.
 */
class KeyedCredentialProtectorPlatformTest {
    @Serializable
    data class Creds(
        val jwt: String,
        val mnemonic: String,
    )

    private lateinit var dir: Path
    private val seal = CredentialSealKey(CredentialSealKey.generateSecret(), enabled = true)
    private val value = Creds(jwt = "jwt-value", mnemonic = "synthetic mnemonic words ${UUID.randomUUID()}")

    @BeforeTest
    fun setUp() {
        dir = TestScratch.directory("keyed-platform-test")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun store(protector: CredentialProtector): CredentialStore<Creds> =
        CredentialStore(dir, "credentials.json", serializer<Creds>(), protector = protector)

    private fun onDisk(): String = Files.readString(dir.resolve("credentials.json"))

    private fun id(): String = dir.resolve("credentials.json").toRealPath().toString()

    /** What the platform layer alone recovers from the file: the value behind the keyed marker, through [platform]. */
    private fun platformView(platform: CredentialProtector): String {
        val stored = onDisk()
        assertTrue(stored.startsWith(KeyedCredentialProtector.PREFIX), "the keyed marker is on disk: ${stored.take(24)}")
        return platform.decode(id(), stored.removePrefix(KeyedCredentialProtector.PREFIX)).plaintext
    }

    private fun assertSealedInside(platform: CredentialProtector, platformPrefix: String) {
        store(KeyedCredentialProtector(platform) { seal }).save(value)
        val reference = onDisk().removePrefix(KeyedCredentialProtector.PREFIX)
        assertTrue(reference.startsWith(platformPrefix), "the keyed marker is followed by the platform reference: ${reference.take(24)}")
        val held = platformView(platform)
        assertFalse(held.contains(value.mnemonic), "the platform layer must hold only the sealed value")
        // base64 of the sealed bytes can hold "jwt" by chance (1 run in about 2,000), so the check looks for what base64 cannot
        // hold: the quoted JSON key and the value itself (a quote and a hyphen are not base64 characters).
        assertFalse(held.contains("\"jwt\""), "the platform layer must hold no JSON at all")
        assertFalse(held.contains(value.jwt), "the platform layer must hold no JWT value")
        assertEquals(value, store(KeyedCredentialProtector(platform) { seal }).load(), "the keyed reader opens it")
    }

    @Test
    fun `DPAPI-shaped store holds the sealed value, not the mnemonic`() {
        assertSealedInside(FakePlatformProtector(), FakePlatformProtector.PREFIX)
    }

    @Test
    fun `real DPAPI holds the sealed value, not the mnemonic`() {
        assumeTrue("DPAPI integration requires Windows", System.getProperty("os.name").lowercase().contains("windows"))
        assertSealedInside(WindowsDpapiCredentialProtector(), WindowsDpapiCredentialProtector.PREFIX)
    }

    @Test
    fun `Keychain-shaped store holds the sealed value, not the mnemonic`() {
        val keychain = FakeKeychainProtector()
        assertSealedInside(keychain, FakeKeychainProtector.PREFIX)
        assertTrue(keychain.items.values.none { it.contains(value.mnemonic) }, "no Keychain item holds the mnemonic")
    }

    @Test
    fun `real macOS Keychain holds the sealed value, not the mnemonic`() {
        assumeTrue("macOS Keychain integration test", System.getProperty("os.name").lowercase().contains("mac"))
        val keychain = MacKeychainCredentialProtector()
        try {
            assertSealedInside(keychain, MacKeychainCredentialProtector.STORED_PREFIX)
        } finally {
            keychain.delete(id())
        }
    }

    @Test
    fun `Secret Service item holds the sealed value, not the mnemonic`() {
        val items = fakeSecretTool()
        assertSealedInside(LinuxSecretServiceCredentialProtector(dir.resolve("secret-tool").toString()), LinuxSecretServiceCredentialProtector.STORED_PREFIX)
        val held = Files.list(items).use { s -> s.map { Files.readString(it) }.toList() }
        assertEquals(1, held.size, "one item: $held")
        assertTrue(held.none { it.contains(value.mnemonic) }, "no Secret Service item holds the mnemonic")
    }

    @Test
    fun `re-sealing a Secret Service credential leaves no plaintext item behind`() {
        val items = fakeSecretTool()
        val platform = LinuxSecretServiceCredentialProtector(dir.resolve("secret-tool").toString())
        store(platform).save(value)
        assertEquals(value, store(KeyedCredentialProtector(platform) { seal }).load(), "the first unlocked read migrates")
        val held = Files.list(items).use { s -> s.map { Files.readString(it) }.toList() }
        assertEquals(1, held.size, "the platform-only item was discarded: $held")
        assertTrue(held.none { it.contains(value.mnemonic) }, "the remaining item is sealed")
    }

    @Test
    fun `re-sealing a Keychain credential leaves no plaintext item behind`() {
        val keychain = FakeKeychainProtector()
        store(keychain).save(value)
        assertEquals(value, store(KeyedCredentialProtector(keychain) { seal }).load())
        assertEquals(1, keychain.items.size, "the platform-only item was discarded: ${keychain.items.keys}")
        assertTrue(keychain.items.values.none { it.contains(value.mnemonic) })
    }

    // The fake `secret-tool` of LinuxSecretServiceCredentialProtectorTest: items are files named by revision.
    private fun fakeSecretTool(): Path {
        assumeTrue("Fake secret-tool harness requires Linux", System.getProperty("os.name").lowercase().contains("linux"))
        val items = Files.createDirectory(dir.resolve("items"))
        val executable = dir.resolve("secret-tool")
        Files.writeString(
            executable,
            """
            #!/bin/sh
            set -eu
            command="${'$'}1"
            shift
            case "${'$'}command" in
              store) cat > '$items'/"${'$'}7" ;;
              lookup) [ -f '$items'/"${'$'}6" ] || exit 1; cat '$items'/"${'$'}6" ;;
              clear) if [ "${'$'}#" -ge 6 ]; then rm -f '$items'/"${'$'}6"; else rm -f '$items'/*; fi ;;
              *) exit 2 ;;
            esac
            """.trimIndent(),
        )
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
        return items
    }
}
