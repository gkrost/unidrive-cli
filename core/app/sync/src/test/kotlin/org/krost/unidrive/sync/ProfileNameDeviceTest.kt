package org.krost.unidrive.sync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #1090: a profile name is a folder name, so the shared Windows device-name table applies to it. */
class ProfileNameDeviceTest {
    @Test
    fun `profile names that are Windows device names are refused`() {
        for (name in listOf("con", "PRN", "Aux", "nul", "COM0", "com1", "COM9", "LPT0", "lpt9")) {
            assertTrue(isReservedProfileName(name), "'$name' is a device name")
            assertFalse(isValidProfileName(name), "'$name' must not be a profile name")
        }
    }

    @Test
    fun `profile names that only resemble a device name stay valid`() {
        for (name in listOf("console", "com10", "lpt", "nulls", "com-1", "work")) {
            assertFalse(isReservedProfileName(name), "'$name' is no device name")
            assertTrue(isValidProfileName(name), "'$name' must stay valid")
        }
    }
}
