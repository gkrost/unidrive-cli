package org.krost.unidrive.internxt

import org.krost.unidrive.internxt.model.InternxtFile
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * gkrost/unidrive-cli#747 finding 2: a file rebuilt from its staged row on a resumed gather is listed under the same name as
 * the listing that staged it. The staged name is the full recomposed name; splitting it on its last dot turned a name kept
 * whole (`a.txt.txt`, `a.`) into another one (`a.txt`, `a`), which the engine reads as a remote rename.
 */
class InternxtResumedNameTest {
    private fun listedName(file: InternxtFile): String = InternxtProvider.fileToDeltaCloudItem(file, "").name

    private fun resumedName(file: InternxtFile): String =
        with(InternxtProvider) { listedName(file.toStagedCloudItem("root").toResumedFile()) }

    @Test
    fun `a name kept whole with a doubled extension is listed the same after a resume`() {
        val file = InternxtFile(uuid = "u1", plainName = "a.txt.txt", type = null, size = "3")
        assertEquals("a.txt.txt", listedName(file))
        assertEquals("a.txt.txt", resumedName(file))
    }

    @Test
    fun `a name kept whole with a trailing dot is listed the same after a resume`() {
        val file = InternxtFile(uuid = "u2", plainName = "a.", type = null, size = "3")
        assertEquals("a.", listedName(file))
        assertEquals("a.", resumedName(file))
    }

    @Test
    fun `a split name keeps its name and its type after a resume`() {
        val file = InternxtFile(uuid = "u3", plainName = "report", type = "pdf", size = "3")
        val resumed = with(InternxtProvider) { file.toStagedCloudItem("root").toResumedFile() }
        assertEquals("report.pdf", listedName(resumed))
        assertEquals("pdf", resumed.type, "the type (the mime hint) survives the resume")
        assertEquals(InternxtProvider.fileToDeltaCloudItem(file, "").mimeType, InternxtProvider.fileToDeltaCloudItem(resumed, "").mimeType)
    }

    @Test
    fun `a dotfile and a name without a dot are listed the same after a resume`() {
        for (name in listOf(".bashrc", "Makefile", "Scan 1 .pdf")) {
            val file = InternxtFile(uuid = "u-$name", plainName = name, type = null, size = "3")
            assertEquals(listedName(file), resumedName(file), name)
        }
    }
}
