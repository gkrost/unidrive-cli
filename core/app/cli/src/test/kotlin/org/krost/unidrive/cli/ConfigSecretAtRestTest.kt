package org.krost.unidrive.cli

import org.krost.unidrive.auth.CredentialData
import org.krost.unidrive.auth.CredentialProtectionException
import org.krost.unidrive.auth.CredentialProtector
import org.krost.unidrive.auth.CredentialProtectors
import org.krost.unidrive.sync.ConfigSecrets
import org.krost.unidrive.sync.SyncConfig
import java.io.ByteArrayInputStream
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
 * The config write path protects provider secret fields at rest (issue#786): `writeAtomically` is the
 * single choke point every config writer goes through, `profile add --secret-stdin` produces no
 * plaintext secret on disk, the start migration rewrites an older config in place, and a vault encrypt
 * moves the decoded values, never the at-rest references. A fake protector keeps this portable; on a
 * host without a native protector every path here writes plaintext, so the protector-conditional
 * assertions branch on [CredentialProtectors.atRestDefaultOrNull].
 */
class ConfigSecretAtRestTest {
    private lateinit var dir: Path
    private val protector = FakeProtector()

    private class FakeProtector : CredentialProtector {
        private val items = mutableMapOf<String, MutableMap<String, String>>()
        private var counter = 0

        override fun protect(id: String, plaintext: String): String {
            val reference = "fakeref-${++counter}"
            items.getOrPut(id) { mutableMapOf() }[reference] = plaintext
            return reference
        }

        override fun decode(id: String, stored: String): CredentialData =
            if (!stored.startsWith("fakeref-")) {
                CredentialData(stored, false)
            } else {
                CredentialData(
                    items[id]?.get(stored) ?: throw CredentialProtectionException("test: another config's reference"),
                    true,
                )
            }

        override fun delete(id: String) = items.remove(id).let { Unit }
    }

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("unidrive-config-secret-at-rest-")
        LifecycleHooks.factoryFor = { org.krost.unidrive.ProviderRegistry.get(it) }
    }

    @AfterTest
    fun tearDown() {
        LifecycleHooks.factoryFor = { org.krost.unidrive.ProviderRegistry.get(it) }
        LifecycleHooks.env = { System.getenv(it) }
        dir.toFile().deleteRecursively()
    }

    private val configPath: Path get() = dir.resolve("config.toml")

    private fun writeConfig(text: String) {
        Files.createDirectories(dir)
        Files.writeString(configPath, text)
    }

    // ── the write choke point ────────────────────────────────────────────────

    @Test
    fun `writeAtomically never writes a plaintext secret when a protector exists`() {
        val text =
            """
            [general]

            [providers.bench]
            type = "s3"
            mode = "mount"
            secret_access_key = "round-trip-secret"
            """.trimIndent() + "\n"

        ConfigFileTransaction.writeAtomically(configPath, text, protector)

        val onDisk = Files.readString(configPath)
        assertFalse("round-trip-secret" in onDisk, "the secret must not be on disk as plaintext")
        val parsed = SyncConfig.parseRaw(onDisk)
        assertEquals("round-trip-secret", ConfigSecrets.decode(parsed.providers.getValue("bench")!!, "bench", configPath, protector)!!.secret_access_key)
    }

    @Test
    fun `writeAtomically without a protector writes plaintext, as before`() {
        val text = "[providers.bench]\ntype = \"s3\"\nsecret_access_key = \"round-trip-secret\"\n"

        ConfigFileTransaction.writeAtomically(configPath, text, null)

        assertTrue("round-trip-secret" in Files.readString(configPath))
    }

    // ── profile add --secret-stdin ───────────────────────────────────────────

    @Test
    fun `profile add writes the stdin secret protected and it reads back through the decode`() {
        val original = System.`in`
        System.setIn(ByteArrayInputStream("round-trip-secret\n".toByteArray(Charsets.UTF_8)))
        val created =
            try {
                ProfileCreator.create(
                    dir,
                    NewProfile(
                        type = "s3",
                        name = "bench",
                        mode = "mount",
                        syncRoot = null,
                        label = null,
                        options = listOf("access_key_id=AKIDEXAMPLE123456", "bucket=unidrive-cli-bench"),
                        makeDefault = false,
                        secretStdin = true,
                    ),
                    secretProtector = protector,
                )
            } finally {
                System.setIn(original)
            }
        assertTrue(created.getValue("ok").toString().contains("true"))

        val onDisk = Files.readString(configPath)
        assertFalse("round-trip-secret" in onDisk, "the stdin secret must not be on disk as plaintext")
        assertFalse("AKIDEXAMPLE123456" in onDisk, "the access key id is protected at rest too (issue#786)")
        val parsed = SyncConfig.parseRaw(onDisk)
        assertEquals(
            "round-trip-secret",
            ConfigSecrets.decode(parsed.providers.getValue("bench")!!, "bench", configPath, protector)!!.secret_access_key,
        )
    }

    @Test
    fun `profile add with the platform default - the secret is protected where a protector exists`() {
        val original = System.`in`
        System.setIn(ByteArrayInputStream("round-trip-secret\n".toByteArray(Charsets.UTF_8)))
        val created =
            try {
                ProfileCreator.create(
                    dir,
                    NewProfile(
                        type = "s3",
                        name = "bench",
                        mode = "mount",
                        syncRoot = null,
                        label = null,
                        options = listOf("access_key_id=AKIDEXAMPLE123456", "bucket=unidrive-cli-bench"),
                        makeDefault = false,
                        secretStdin = true,
                    ),
                )
            } finally {
                System.setIn(original)
            }
        assertTrue(created.getValue("ok").toString().contains("true"))

        val onDisk = Files.readString(configPath)
        val platformProtector = CredentialProtectors.atRestDefaultOrNull()
        if (platformProtector == null) {
            assertTrue("round-trip-secret" in onDisk, "no protector on this host: plaintext, as before")
        } else {
            assertFalse("round-trip-secret" in onDisk, "a protector exists: the secret must not be plaintext")
            assertEquals(
                "round-trip-secret",
                ConfigSecrets.decode(SyncConfig.parseRaw(onDisk).providers.getValue("bench")!!, "bench", configPath)!!.secret_access_key,
            )
        }
    }

    // ── the first-start migration ────────────────────────────────────────────

    @Test
    fun `the start migration protects an older config in place and is a no-op afterwards`() {
        writeConfig(
            """
            [general]

            [providers.bench]
            type = "s3"
            mode = "mount"
            access_key_id = "AKIDEXAMPLE123456"
            secret_access_key = "round-trip-secret"
            """.trimIndent() + "\n",
        )

        ConfigSecretMigration.migrateAtStart(configPath, protector)

        val onDisk = Files.readString(configPath)
        assertFalse("round-trip-secret" in onDisk)
        assertEquals(
            "round-trip-secret",
            ConfigSecrets.decode(SyncConfig.parseRaw(onDisk).providers.getValue("bench")!!, "bench", configPath, protector)!!.secret_access_key,
        )

        // Second start: the config is already protected, nothing is rewritten.
        val before = onDisk
        ConfigSecretMigration.migrateAtStart(configPath, protector)
        assertEquals(before, Files.readString(configPath))
    }

    // ── vault interplay ──────────────────────────────────────────────────────

    @Test
    fun `a vault encrypt moves the decoded value and leaves no plaintext or reference behind`() {
        writeConfig(
            """
            [general]

            [providers.bench]
            type = "s3"
            mode = "mount"
            access_key_id = "AKIDEXAMPLE123456"
            secret_access_key = "round-trip-secret"
            """.trimIndent() + "\n",
        )
        // What VaultEncryptCommand runs: protect-on-write happened earlier, so the config on disk is
        // protected; the encrypt reads it back, decodes, and cuts the fields out.
        ConfigSecretMigration.migrateAtStart(configPath, protector)
        val configLines = Files.readAllLines(configPath)
        val raw = SyncConfig.parseRaw(configLines.joinToString("\n"), configPath.toString())

        val vaulted = mutableMapOf<String, Map<String, String>>()
        for ((profileName, rp) in raw.providers) {
            val creds = ConfigSecrets.decode(rp, profileName, configPath, protector)?.let {
                mapOf(
                    "access_key_id" to it.access_key_id,
                    "secret_access_key" to it.secret_access_key,
                ).filterValues { v -> !v.isNullOrBlank() }
            }
            if (!creds.isNullOrEmpty()) vaulted[profileName] = creds.mapValues { it.value!! }
        }
        assertEquals("round-trip-secret", vaulted.getValue("bench")["secret_access_key"], "the vault gets the real value")
        assertFalse(vaulted.getValue("bench").values.any { it.startsWith("fakeref-") }, "never the at-rest reference")

        val filtered = filterVaultedLines(configLines, vaulted)
        val remaining = SyncConfig.parseRaw(filtered.joinToString("\n"))
        assertNull(remaining.providers.getValue("bench")!!.secret_access_key, "the field is cut out")
        assertNull(remaining.providers.getValue("bench")!!.access_key_id)
    }

    @Test
    fun `a vault decrypt restores through the write path, which protects on a protected host`() {
        // restoreVaultCredentials verifies its own output before the write, so it must see the values it
        // inserts; the protection happens inside writeAtomically afterwards.
        val restored = restoreVaultCredentials("[general]\n\n[providers.bench]\ntype = \"s3\"\n", mapOf("bench" to mapOf("secret_access_key" to "round-trip-secret")))
        assertTrue("round-trip-secret" in restored, "the verifier sees the pre-write text")

        ConfigFileTransaction.writeAtomically(configPath, restored, protector)
        val onDisk = Files.readString(configPath)
        assertFalse("round-trip-secret" in onDisk)
        assertEquals(
            "round-trip-secret",
            ConfigSecrets.decode(SyncConfig.parseRaw(onDisk).providers.getValue("bench")!!, "bench", configPath, protector)!!.secret_access_key,
        )
    }
}
