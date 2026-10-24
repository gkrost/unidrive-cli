package org.krost.unidrive.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `recents_folder_name`, `recents_view` and `recents_limit` in the profile section: the Recents view's folder, switch and length. */
class SyncConfigRecentsViewTest {
    @Test
    fun `absent keys mean the defaults`() {
        val config = SyncConfig.parse("[providers.p]\ntype = \"internxt\"\n", "p")

        assertNull(config.recents("p").folderName)
        assertEquals(SyncConfig.DEFAULT_RECENTS_VIEW, config.recents("p").view)
        assertEquals(SyncConfig.DEFAULT_RECENTS_LIMIT, config.recents("p").limit)
        assertEquals(SyncConfig.DEFAULT_RECENTS_LIMIT, config.recents("unknown").limit)
    }

    @Test
    fun `the keys are read per profile`() {
        val config =
            SyncConfig.parse(
                "[providers.a]\ntype = \"internxt\"\nrecents_folder_name = \"Zuletzt\"\nrecents_view = false\nrecents_limit = 50\n" +
                    "[providers.b]\ntype = \"internxt\"\n",
                "a",
            )

        assertEquals("Zuletzt", config.recents("a").folderName)
        assertFalse(config.recents("a").view)
        assertEquals(50, config.recents("a").limit)
        assertNull(config.recents("b").folderName)
        assertTrue(config.recents("b").view)
    }

    @Test
    fun `the view is on by default and shows fifty files`() {
        assertTrue(SyncConfig.DEFAULT_RECENTS_VIEW)
        assertEquals(50,SyncConfig.DEFAULT_RECENTS_LIMIT)
    }
}
