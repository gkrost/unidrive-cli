package org.krost.unidrive.io

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A Windows path cannot hold `\` inside one name, so the element list is passed in directly. The `Path` case is the
 * split Windows already does: `docs` and `slash.txt`, not a file whose name contains a backslash.
 */
class EngineKeyTest {
    @Test
    fun `a backslash stays inside a name element and Windows elements stay split`() {
        assertEquals("/docs/back\\slash.txt", engineKey(listOf("docs", "back\\slash.txt")))
        assertEquals("/docs/slash.txt", engineKey(listOf("docs", "slash.txt")))
        assertEquals("/docs/slash.txt", engineKey(Path.of("docs", "slash.txt")))
        assertEquals("/", engineKey(emptyList()))
        assertEquals("/", engineKey(Path.of("")))
    }
}
