package org.krost.unidrive.io

import kotlin.test.Test
import kotlin.test.assertEquals

class OsTest {
    @Test
    fun `of classifies the names a JVM reports`() {
        val cases =
            listOf(
                "Windows 11" to Triple(true, false, false),
                "Windows Server 2022" to Triple(true, false, false),
                "Mac OS X" to Triple(false, true, false),
                "Linux" to Triple(false, false, true),
                "Darwin" to Triple(false, false, false),
                "FreeBSD" to Triple(false, false, false),
                "" to Triple(false, false, false),
            )
        for ((name, expected) in cases) {
            val kind = Os.of(name)
            assertEquals(expected.first, kind.isWindows, name)
            assertEquals(expected.second, kind.isMac, name)
            assertEquals(expected.third, kind.isLinux, name)
        }
        assertEquals(false, Os.of(null).isWindows)
    }
}
