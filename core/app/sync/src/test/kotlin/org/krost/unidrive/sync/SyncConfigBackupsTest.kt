package org.krost.unidrive.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `backups_folder_name` and `show_backups` in the profile section, and the flag `daemon run` passes as `showBackups`.
 * Unset stays off. `auto` is on only when the profile has no `sync_path`. No provider is asked.
 */
class SyncConfigBackupsTest {
    private fun config(body: String, profile: String = "p"): SyncConfig =
        SyncConfig.parse("[providers.$profile]\ntype = \"internxt\"\n$body", profile)

    @Test
    fun `absent means the engine default name and the folder stays off`() {
        val parsed = config("")

        assertNull(parsed.backups("p").folderName)
        assertFalse(parsed.backups("p").show)
        assertNull(parsed.backups("unknown").folderName)
        assertFalse(parsed.backups("unknown").show)
    }

    @Test
    fun `the folder name is read per profile`() {
        val parsed =
            SyncConfig.parse(
                "[providers.a]\ntype = \"internxt\"\nbackups_folder_name = \"Sicherungen\"\n" +
                    "[providers.b]\ntype = \"internxt\"\n",
                "a",
            )

        assertEquals("Sicherungen", parsed.backups("a").folderName)
        assertNull(parsed.backups("b").folderName)
    }

    @Test
    fun `true is on even when sync_path is set, and false or unset is off`() {
        assertTrue(config("show_backups = true\nsync_path = \"/inbox\"\n").backups("p").show)
        assertTrue(config("show_backups = \"true\"\nsync_path = \"/inbox\"\n").backups("p").show)
        assertFalse(config("show_backups = false\n").backups("p").show)
        assertFalse(config("show_backups = \"false\"\n").backups("p").show)
        assertFalse(config("sync_path = \"/inbox\"\n").backups("p").show)
    }

    @Test
    fun `auto is on only when the profile is unscoped`() {
        assertTrue(config("show_backups = \"auto\"\n").backups("p").show)
        assertTrue(config("show_backups = \"AUTO\"\n").backups("p").show)
        assertFalse(config("show_backups = \"auto\"\nsync_path = \"/inbox\"\n").backups("p").show)
        assertTrue(config("show_backups = \"auto\"\nsync_path = \"/\"\n").backups("p").show, "the drive root is unscoped")
        assertTrue(config("show_backups = \"auto\"\nsync_path = []\n").backups("p").show)

        val both =
            SyncConfig.parse(
                "[providers.open]\ntype = \"internxt\"\nshow_backups = \"auto\"\n" +
                    "[providers.scoped]\ntype = \"internxt\"\nshow_backups = \"auto\"\nsync_path = \"/inbox\"\n",
                "open",
            )
        assertTrue(both.backups("open").show)
        assertFalse(both.backups("scoped").show, "the other profile's sync_path, not the one this config was opened for")
    }

    @Test
    fun `anything but auto, true or false is refused`() {
        val error =
            assertFailsWith<Exception> {
                config("show_backups = \"yes\"\n")
            }
        val text = generateSequence(error as Throwable) { it.cause }.mapNotNull { it.message }.joinToString(" ")
        assertTrue("auto" in text, text)
    }
}
