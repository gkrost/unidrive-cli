package org.krost.unidrive.engine

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The OS file-name rules both front-ends refuse a name with (archive#230, archive#526). Moved here from
 * :app:sync's PlaceholderManagerTest by archive#560 U6 (the mount resolves them through engine-core now).
 */
class LocalNamesTest {
    private val w = listOf(true, false)

    @Test
    fun `localNameIssue allows normal names including spaces`() {
        for (windows in w) {
            assertNull(localNameIssue("/Documents/my report.txt", windows = windows))
            assertNull(localNameIssue("/a/b/c.pdf", windows = windows))
            assertNull(localNameIssue("/", windows = windows))
            assertNull(localNameIssue("", windows = windows))
            assertNull(localNameIssue("/a/", windows = windows))
            assertNull(localNameIssue("a/b", windows = windows))
        }
    }

    // A `.` or `..` segment or an empty one inside the path names another file than its text says once it is
    // resolved: `/a/../victim.txt` is `<root>/victim.txt`.
    @Test
    fun `localNameIssue refuses dot segments and an empty segment inside the path`() {
        for (windows in w) {
            for (p in listOf("/a/./b", "/a/../b", "/..", "/.", "/a/..", "../b", "/a//b", "//a")) {
                assertNotNull(localNameIssue(p, windows = windows), "expected '$p' refused, windows=$windows")
            }
        }
    }

    @Test
    fun `localNameIssue rejects Windows-invalid names only on Windows`() {
        val bad = listOf("/x/....", "/x/foo.", "/x/foo ", "/x/CON", "/x/nul.txt", "/x/a:b", "/x/a?b", "/x/a<b")
        for (p in bad) {
            assertNotNull(localNameIssue(p, windows = true), "expected '$p' rejected on Windows")
            assertNull(localNameIssue(p, windows = false), "expected '$p' allowed on POSIX")
        }
    }

    @Test
    fun `localNameIssue rejects an embedded NUL on every platform`() {
        val withNul = "/x/a" + Char(0) + "b"
        assertNotNull(localNameIssue(withNul, windows = true))
        assertNotNull(localNameIssue(withNul, windows = false))
    }

    // archive#1090: one table for the device names (WindowsDeviceNames); the same names are checked in WindowsDeviceNamesTest,
    // RootNamesTest, ProfileNameDeviceTest and OneDriveNameRulesTest, and the client's WindowsNames mirrors it.
    private val deviceNames =
        listOf(
            "CON", "PRN", "AUX", "NUL", "nul.txt", "NUL .txt", "NUL  .tar.gz", "COM0", "COM1", "COM9", "com5.txt", "LPT0", "LPT9",
            "COM¹", "COM².txt", "LPT³", "CONIN$", "CONOUT$.log",
        )
    private val notDeviceNames = listOf("CONSOLE", "COM10", "LPT10", "COM", "NULL", "xCON", "CON$", "COM¹a", "CONIN")

    @Test
    fun `localNameIssue refuses every device name on Windows, with and without an extension`() {
        for (name in deviceNames) {
            assertNotNull(localNameIssue("/x/$name", windows = true), "expected '$name' refused on Windows")
            assertNull(localNameIssue("/x/$name", windows = false), "expected '$name' allowed on POSIX")
        }
    }

    @Test
    fun `localNameIssue allows names that only resemble a device name`() {
        for (name in notDeviceNames) assertNull(localNameIssue("/x/$name", windows = true), "expected '$name' allowed")
    }

    @Test
    fun `localNameIssue refuses COM1 to COM9 and LPT1 to LPT9 by number`() {
        for (n in 1..9) {
            assertNotNull(localNameIssue("/x/COM$n", windows = true), "COM$n")
            assertNotNull(localNameIssue("/x/lpt$n.dat", windows = true), "lpt$n.dat")
        }
    }

    // archive#1088: the limit is 255 UTF-16 units on Windows and 255 UTF-8 bytes on POSIX. 128 x e-acute is 128 units and 256 bytes.
    @Test
    fun `localNameIssue counts 255 UTF-16 units on Windows and 255 UTF-8 bytes on POSIX`() {
        val units255 = "x".repeat(255)
        val units256 = "x".repeat(256)
        for (windows in listOf(true, false)) {
            assertNull(localNameIssue("/d/$units255", windows = windows), "255 units, windows=$windows")
            assertNotNull(localNameIssue("/d/$units256", windows = windows), "256 units, windows=$windows")
        }
        val eAcute128 = "é".repeat(128)
        assertNull(localNameIssue("/d/$eAcute128", windows = true), "128 units are fine on Windows")
        assertNotNull(localNameIssue("/d/$eAcute128", windows = false), "256 UTF-8 bytes are over the POSIX limit")
    }

    @Test
    fun `localNameIssue refuses a control character on Windows only`() {
        assertNotNull(localNameIssue("/x/a\u0001b", windows = true))
        assertNotNull(localNameIssue("/x/tab\there", windows = true))
        assertNull(localNameIssue("/x/a\u0001b", windows = false))
    }
}
