package org.krost.unidrive.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `shared_folder_name` and `shared_view` in the profile section: the name of the read-only folder that lists the items the
 * account shares, and whether the profile shows it.
 */
class SyncConfigSharedViewTest {
    @Test
    fun `absent means the engine's default name, read as null here`() {
        val config = SyncConfig.parse("[providers.p]\ntype = \"internxt\"\n", "p")

        assertNull(config.sharedFolderName("p"))
        assertNull(config.sharedFolderName("unknown"))
    }

    @Test
    fun `a name is read per profile`() {
        val config =
            SyncConfig.parse(
                "[providers.a]\ntype = \"internxt\"\nshared_folder_name = \"Geteilt\"\n" +
                    "[providers.b]\ntype = \"internxt\"\n",
                "a",
            )

        assertEquals("Geteilt", config.sharedFolderName("a"))
        assertNull(config.sharedFolderName("b"))
    }

    @Test
    fun `the view follows the key of the profile, and an unset key follows the default`() {
        val config =
            SyncConfig.parse(
                "[providers.on]\ntype = \"internxt\"\nshared_view = true\n" +
                    "[providers.off]\ntype = \"internxt\"\nshared_view = false\n" +
                    "[providers.unset]\ntype = \"internxt\"\n",
                "on",
            )

        assertTrue(config.sharedView("on"))
        assertFalse(config.sharedView("off"))
        assertEquals(SyncConfig.DEFAULT_SHARED_VIEW, config.sharedView("unset"))
        assertEquals(SyncConfig.DEFAULT_SHARED_VIEW, config.sharedView("unknown"))
    }

    // The default is a decision of the owner, held in one constant: this is the one test that changes with it.
    @Test
    fun `the view is on by default`() {
        assertTrue(SyncConfig.DEFAULT_SHARED_VIEW)
        assertTrue(SyncConfig.parse("[providers.p]\ntype = \"internxt\"\n", "p").sharedView("p"))
    }
}
