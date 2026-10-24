package org.krost.unidrive.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #1090: a configured trash or view folder is a top-level name the client must be able to show, so it gets the device-name check too. */
class RootNamesTest {
    private val deviceNames =
        listOf("CON", "prn", "Aux.txt", "NUL", "NUL .x", "COM0", "COM1", "LPT9", "COM¹", "LPT²", "CONIN$", "CONOUT$")

    @Test
    fun `a Windows device name is no folder name`() {
        for (name in deviceNames) {
            assertFalse(RootNames.isUsable(name), "'$name' must be refused")
            assertNull(RootNames.usable(name), "'$name' must be refused")
        }
    }

    @Test
    fun `names that only resemble a device name stay usable`() {
        for (name in listOf("Console", "COM10", "Papierkorb", "Shared", "Nulls")) {
            assertTrue(RootNames.isUsable(name), "'$name' must be usable")
        }
        assertEquals("Papierkorb", RootNames.usable("  Papierkorb "))
    }
}
