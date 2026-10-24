package org.krost.unidrive.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #1090: the table every name check asks. The same names are run through `localNameIssue`, `RootNames`, the profile-name
 * check and OneDrive's rule in their own modules; the client's `WindowsNames.Problem` mirrors this table.
 */
class WindowsDeviceNamesTest {
    private val reserved =
        listOf(
            "CON", "con", "Con.txt", "PRN", "AUX", "aux.tar.gz", "NUL", "nul.txt",
            // trailing spaces before the dot are dropped by Windows: the device again
            "NUL .txt", "NUL  .tar.gz", "nul .",
            // COM0 and LPT0 are on Microsoft's list
            "COM0", "COM1", "COM9", "com5.txt", "LPT0", "LPT9", "lpt1.log",
            // the superscript digits, with and without an extension
            "COM¹", "COM².txt", "COM³", "LPT¹", "lpt²", "LPT³.log",
            // the console handles CreateFile opens by name
            "CONIN$", "conout$", "CONOUT$.log",
        )

    private val ordinary =
        listOf(
            "CONSOLE", "CON-1", "COM10", "COM", "COMA", "LPT", "LPT10", "NULL", "AUX1", "xCON", "a.CON", "report.txt",
            "COM¹a", "CONIN", "CON$", "Papierkorb",
            // a leading space is part of the name
            " NUL",
        )

    @Test
    fun `every reserved name is recognised, with or without an extension and in any case`() {
        for (name in reserved) assertTrue(WindowsDeviceNames.isReserved(name), "'$name' must be reserved")
    }

    @Test
    fun `ordinary names that merely contain or resemble a device name are not reserved`() {
        for (name in ordinary) assertFalse(WindowsDeviceNames.isReserved(name), "'$name' must not be reserved")
    }

    @Test
    fun `the table has exactly the documented stems`() {
        val expected =
            setOf("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$") +
                (0..9).map { "COM$it" } + (0..9).map { "LPT$it" } +
                listOf('¹', '²', '³').flatMap { listOf("COM$it", "LPT$it") }
        assertEquals(expected, WindowsDeviceNames.NAMES)
        assertEquals(6 + 20 + 6, WindowsDeviceNames.NAMES.size)
    }
}
