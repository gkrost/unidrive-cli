package org.krost.unidrive.sync

import kotlinx.serialization.descriptors.elementNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A profile section can carry the sync policy of its own (direction, versioning, trash), which wins over
 * the same key in `[general]` for that profile only; `unidrive backup add` writes them there. A key a
 * profile section does not know is reported instead of being dropped without a word.
 */
class SyncConfigProfilePolicyTest {
    private val toml =
        """
        [general]
        sync_direction = "bidirectional"
        trash_retention_days = 7

        [providers.backup]
        type = "localfs"
        mode = "mirror"
        sync_direction = "upload"
        file_versioning = true
        max_versions = 3
        trash_emulation = true
        trash_retention_days = 30

        [providers.plain]
        type = "localfs"
        mode = "mirror"
        """.trimIndent()

    @Test
    fun `a profile's own policy keys are read from its section`() {
        val raw = SyncConfig.parseRaw(toml)
        val backup = raw.providers.getValue("backup")
        assertEquals("upload", backup.sync_direction)
        assertEquals(true, backup.file_versioning)
        assertEquals(3, backup.max_versions)
        assertEquals(true, backup.trash_emulation)
        assertEquals(30, backup.trash_retention_days)
    }

    @Test
    fun `the profile's policy wins for that profile and general stays for the others`() {
        val backup = SyncConfig.parse(toml, "backup")
        assertEquals(SyncDirection.UPLOAD, backup.syncDirection)
        assertTrue(backup.fileVersioning)
        assertEquals(3, backup.maxVersions)
        assertTrue(backup.trashEmulation)
        assertEquals(30, backup.trashRetentionDays)

        val plain = SyncConfig.parse(toml, "plain")
        assertEquals(SyncDirection.BIDIRECTIONAL, plain.syncDirection)
        assertFalse(plain.fileVersioning)
        assertFalse(plain.trashEmulation)
        assertEquals(7, plain.trashRetentionDays)
    }

    @Test
    fun `a key the profile section does not know is reported with its line`() {
        val text =
            """
            [providers.a]
            type = "localfs"
            sync_direktion = "upload"
            mode = "mirror"

            [providers.a.pin_patterns]
            include = ["x"]

            [providers.b]
            type = "localfs"
            """.trimIndent()
        val unknown = validateProviderKeys(text)
        assertEquals(listOf(Triple("a", "sync_direktion", 3)), unknown.map { Triple(it.profile, it.key, it.lineNumber) })
    }

    @Test
    fun `every key a profile section may hold is known`() {
        val keys = RawProvider.serializer().descriptor.elementNames
        val body = keys.joinToString("\n") { "$it = 1" }
        assertEquals(emptyList(), validateProviderKeys("[providers.a]\n$body\n"))
    }
}
