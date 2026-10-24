package org.krost.unidrive.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `favorites_folder_name` and `favorites_view` in the profile section: the Favorites view's folder and switch. */
class SyncConfigFavoritesViewTest {
    @Test
    fun `absent keys mean the defaults`() {
        val config = SyncConfig.parse("[providers.p]\ntype = \"internxt\"\n", "p")

        assertNull(config.favorites("p").folderName)
        assertEquals(SyncConfig.DEFAULT_FAVORITES_VIEW, config.favorites("p").view)
        assertTrue(config.favorites("unknown").view)
    }

    @Test
    fun `the keys are read per profile`() {
        val config =
            SyncConfig.parse(
                "[providers.a]\ntype = \"internxt\"\nfavorites_folder_name = \"Favoriten\"\nfavorites_view = false\n" +
                    "[providers.b]\ntype = \"internxt\"\n",
                "a",
            )

        assertEquals("Favoriten", config.favorites("a").folderName)
        assertFalse(config.favorites("a").view)
        assertNull(config.favorites("b").folderName)
        assertTrue(config.favorites("b").view)
    }

    @Test
    fun `the view is on by default`() {
        assertTrue(SyncConfig.DEFAULT_FAVORITES_VIEW)
    }
}
