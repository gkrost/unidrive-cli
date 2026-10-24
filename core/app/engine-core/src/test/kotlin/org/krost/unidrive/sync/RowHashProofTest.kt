package org.krost.unidrive.sync

import org.krost.unidrive.ContentProof
import org.krost.unidrive.HashAlgorithm
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * archive#1219: the proof from the content hash a cloud row carries. The recorded values are Microsoft's published quickXorHash
 * answers (the known answers of QuickXorHashTest), not values computed by the code under test.
 */
class RowHashProofTest {
    // quickXorHash("abc") and quickXorHash("") as OneDrive reports them (base64 of 20 bytes).
    private val abc = "YRDDGAAAAAAAAAAAAwAAAAAAAAA="
    private val empty = "AAAAAAAAAAAAAAAAAAAAAAAAAAA="

    // SHA-256("abc"), what a Graph item without a quickXorHash may carry.
    private val sha256Abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    private fun file(content: String): Path = Files.createTempFile("row-hash-proof", ".bin").also { Files.writeString(it, content) }

    @Test
    fun `a file with the recorded quickXor is the same`() {
        assertEquals(ContentProof(true, "quickxor"), RowHashProof.prove(file("abc"), abc, HashAlgorithm.QuickXor))
        assertEquals(ContentProof(true, "quickxor"), RowHashProof.prove(file(""), empty, HashAlgorithm.QuickXor))
    }

    @Test
    fun `a file with another quickXor is different`() {
        assertEquals(ContentProof(false, "quickxor"), RowHashProof.prove(file("abd"), abc, HashAlgorithm.QuickXor))
        assertEquals(ContentProof(false, "quickxor"), RowHashProof.prove(file("abc"), empty, HashAlgorithm.QuickXor))
    }

    @Test
    fun `a row that carries only a SHA-256 is compared by SHA-256, in either letter case`() {
        assertEquals(ContentProof(true, "sha256"), RowHashProof.prove(file("abc"), sha256Abc, HashAlgorithm.QuickXor))
        assertEquals(ContentProof(true, "sha256"), RowHashProof.prove(file("abc"), sha256Abc.uppercase(), HashAlgorithm.QuickXor))
        assertEquals(ContentProof(false, "sha256"), RowHashProof.prove(file("abd"), sha256Abc, HashAlgorithm.QuickXor))
    }

    @Test
    fun `a value of no known shape is not judged, so a shape mismatch can never become a difference`() {
        for (odd in listOf("etag-todo", "h-/a.txt", "01a1003c-ab9b-75db-a427-fe819df52a0b|2026-10-03T05:28:59.000Z|8232164", "AAAA", abc + "=", "g".repeat(64))) {
            assertNull(RowHashProof.prove(file("abc"), odd, HashAlgorithm.QuickXor), odd)
        }
    }

    @Test
    fun `no hash, or no algorithm, is not judged`() {
        assertNull(RowHashProof.prove(file("abc"), null, HashAlgorithm.QuickXor))
        assertNull(RowHashProof.prove(file("abc"), "", HashAlgorithm.QuickXor))
        assertNull(RowHashProof.prove(file("abc"), "  ", HashAlgorithm.QuickXor))
        assertNull(RowHashProof.prove(file("abc"), abc, null))
    }

    @Test
    fun `an algorithm this proof does not cover is not judged and keeps its behaviour`() {
        // Dropbox, MD5 and the reserved SHA-256 provider are not wired: they answer null even with a matching hash.
        assertNull(RowHashProof.prove(file("abc"), sha256Abc, HashAlgorithm.DropboxContentHash))
        assertNull(RowHashProof.prove(file("abc"), sha256Abc, HashAlgorithm.Sha256Hex))
        assertNull(RowHashProof.prove(file("abc"), "900150983cd24fb0d6963f7d28e17f72", HashAlgorithm.Md5Hex))
    }
}
