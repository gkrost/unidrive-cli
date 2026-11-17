package org.krost.unidrive.sync

import org.krost.unidrive.auth.CredentialData
import org.krost.unidrive.auth.CredentialProtectionException
import org.krost.unidrive.auth.CredentialProtector
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * The parser-accepted spellings of a sensitive field beyond the plain `field = "value"` line (issue#786):
 * a literal string (`'...'`), a quoted key (`"secret_access_key" = ...`), TOML's escape grammar in the value,
 * and the multiline strings. What the parser reads as the secret is the truth the protection must cover —
 * a shape the rewriter cannot handle may never pass as protected.
 */
class ConfigSecretsParserShapesTest {
    /** In-memory stand-in for the native protectors, the same contract ConfigSecretsTest's fake has. */
    private class FakeProtector : CredentialProtector {
        private val values = mutableMapOf<String, String>()

        override fun protect(id: String, plaintext: String): String {
            val ref = "fakeref-${values.size}"
            values["$id|$ref"] = plaintext
            return ref
        }

        override fun decode(id: String, stored: String): CredentialData =
            if (!stored.startsWith("fakeref-")) CredentialData(stored, false)
            else CredentialData(values["$id|$stored"] ?: throw CredentialProtectionException("missing reference"), true)

        override fun delete(id: String) = Unit
    }

    private fun config(text: String) =
        Files.createTempDirectory("unidrive-config-shapes-").let { dir ->
            try {
                val path = dir.resolve("config.toml")
                Pair(path, text)
            } finally {
                // the tests never write the file; only the temp dir bookkeeping needs cleaning
                dir.toFile().deleteRecursively()
            }
        }

    @Test
    fun `a literal-string secret is protected alongside the basic-string fields`() {
        val (path, text) =
            config(
                "[providers.p]\ntype = \"s3\"\naccess_key_id = \"synthetic-id\"\nsecret_access_key = 'synthetic-secret'\n",
            )
        assertEquals("synthetic-secret", SyncConfig.parseRaw(text).providers.getValue("p")!!.secret_access_key)

        val migrated = assertNotNull(ConfigSecrets.migrateText(text, path, FakeProtector()))

        assertFalse("synthetic-secret" in migrated.text, "migration must not report success while a parsed secret stays plaintext")
    }

    @Test
    fun `a quoted sensitive key is protected`() {
        val (path, text) =
            config(
                "[providers.p]\ntype = \"s3\"\naccess_key_id = \"synthetic-id\"\n\"secret_access_key\" = \"synthetic-secret\"\n",
            )
        assertEquals("synthetic-secret", SyncConfig.parseRaw(text).providers.getValue("p")!!.secret_access_key)

        val migrated = assertNotNull(ConfigSecrets.migrateText(text, path, FakeProtector()))

        assertFalse("synthetic-secret" in migrated.text, "quoted keys are accepted by the config parser")
    }

    @Test
    fun `the common config write never writes a literal-string secret back as plaintext`() {
        val (path, text) =
            config(
                "[providers.p]\ntype = \"s3\"\nsecret_access_key = 'synthetic-secret'\n",
            )

        val written = ConfigSecrets.protectText(text, path, FakeProtector())

        assertFalse("synthetic-secret" in written, "the write path protects every parsed secret field or refuses")
    }

    @Test
    fun `an uppercase Unicode escape protects the parsed value, not the escape spelling`() {
        val (path, text) = config("[providers.p]\ntype = \"webdav\"\npassword = \"prefix-\\U0001F600\"\n")
        val original = SyncConfig.parseRaw(text).providers.getValue("p")!!.password
        assertEquals("prefix-\uD83D\uDE00", original)

        val protector = FakeProtector()
        val migrated = assertNotNull(ConfigSecrets.migrateText(text, path, protector))
        val decoded = ConfigSecrets.decode(SyncConfig.parseRaw(migrated.text).providers.getValue("p")!!, "p", path, protector)

        assertEquals(original, decoded!!.password, "the protected value decodes to what the parser read, not to the escape spelling")
    }

    @Test
    fun `a secret in a multiline string refuses the migration instead of passing as protected`() {
        val (path, text) =
            config(
                "[providers.p]\ntype = \"webdav\"\npassword = \"\"\"line-one\nline-two\"\"\"\n",
            )
        assertEquals("line-one\nline-two", SyncConfig.parseRaw(text).providers.getValue("p")!!.password)

        val refusal = assertFailsWith<IllegalArgumentException> { ConfigSecrets.migrateText(text, path, FakeProtector()) }

        assertEquals(true, "password" in refusal.message!! && "'p'" in refusal.message!!)
        assertFalse("line-one" in refusal.message!!, "the refusal names the profile and field, never the value")
    }
}
