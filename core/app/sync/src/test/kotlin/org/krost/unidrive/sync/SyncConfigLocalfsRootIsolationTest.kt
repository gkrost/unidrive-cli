package org.krost.unidrive.sync

import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * A localfs profile treats its `root_path` as the "cloud": the engine reads and deletes below it. A sync root of any
 * profile that lies inside it (or a `root_path` inside a sync root) lets one profile's sync delete the other
 * profile's files, so `detectRootIsolationConflicts` has to compare `root_path` as it compares `sync_root`.
 */
class SyncConfigLocalfsRootIsolationTest {
    @Test
    fun `a localfs root_path that contains another profile's sync_root is refused`() {
        val raw =
            SyncConfig.parseRaw(
                """
                [providers.box]
                type = "localfs"
                root_path = "/tmp/ud-iso/outer"
                sync_root = "/tmp/ud-iso/box-mirror"

                [providers.od]
                type = "onedrive"
                sync_root = "/tmp/ud-iso/outer/OneDrive"
                """.trimIndent(),
            )

        assertNotNull(SyncConfig.detectRootIsolationConflicts(raw, caseInsensitive = false))
    }

    @Test
    fun `a localfs root_path that contains its own sync_root is refused`() {
        val raw =
            SyncConfig.parseRaw(
                """
                [providers.box]
                type = "localfs"
                root_path = "/tmp/ud-iso/outer"
                sync_root = "/tmp/ud-iso/outer/mirror"
                """.trimIndent(),
            )

        assertNotNull(SyncConfig.detectRootIsolationConflicts(raw, caseInsensitive = false))
    }

    @Test
    fun `a sync_root that contains a localfs root_path is refused`() {
        val raw =
            SyncConfig.parseRaw(
                """
                [providers.box]
                type = "localfs"
                root_path = "/tmp/ud-iso/outer/store"
                sync_root = "/tmp/ud-iso/box-mirror"

                [providers.od]
                type = "onedrive"
                sync_root = "/tmp/ud-iso/outer"
                """.trimIndent(),
            )

        assertNotNull(SyncConfig.detectRootIsolationConflicts(raw, caseInsensitive = false))
    }
}
