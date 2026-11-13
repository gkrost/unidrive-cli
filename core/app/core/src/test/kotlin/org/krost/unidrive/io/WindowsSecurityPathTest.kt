package org.krost.unidrive.io

import java.nio.file.Paths
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** windows#454 item 7: the extended-length namespace never resolves "..", so the path is normalised first. */
class WindowsSecurityPathTest {
    @Test
    fun `a dot-dot segment is resolved before the prefix is added`() {
        val long = Path("C:/base", (1..30).map { "folder$it" }.joinToString("/"))
        val winding = long.resolve("sub/../leaf.txt")

        val win32 = WindowsSecurity.win32Path(winding)

        val prefix = "\\\\?\\"   // the extended-length prefix: backslash backslash question backslash
        assertTrue(win32.startsWith(prefix), win32)
        assertTrue(!win32.contains(".."), "the prefix would make GetNamedSecurityInfoW keep the dots: $win32")
        // Separator-agnostic: the JVM default filesystem joins with "/" on Linux and "\" on Windows.
        assertTrue(win32.endsWith("leaf.txt"), win32)
        assertTrue(!win32.contains("sub"), "the dot-dot segment must have consumed sub: $win32")
    }

    @Test
    fun `a short path is not prefixed`() {
        val plain = Paths.get("\\\\a\\\\b.txt").toAbsolutePath().normalize().toString()
        assertEquals(plain, WindowsSecurity.win32Path(Path("\\\\a\\\\b.txt")))
    }
}
