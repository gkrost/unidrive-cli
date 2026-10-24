package org.krost.unidrive.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `allow_drive_root` in a localfs profile section: accept a drive/filesystem root as `root_path`. `profile add` writes it
 * quoted like every other provider option; a hand edit may write it bare. Both read the same.
 */
class SyncConfigAllowDriveRootTest {
    private fun read(line: String): Boolean? =
        SyncConfig.parseRaw("[providers.p]\ntype = \"localfs\"\nroot_path = \"/data\"\n$line").providers.getValue("p").allow_drive_root

    @Test
    fun `absent is null`() {
        assertNull(read(""))
    }

    @Test
    fun `a bare and a quoted value read the same`() {
        assertEquals(true, read("allow_drive_root = true\n"))
        assertEquals(true, read("allow_drive_root = \"true\"\n"))
        assertEquals(false, read("allow_drive_root = false\n"))
        assertEquals(false, read("allow_drive_root = \"false\"\n"))
    }
}
