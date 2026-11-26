package org.krost.unidrive.onedrive

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.NameRefusedException
import org.krost.unidrive.PermanentUploadFailureException
import org.krost.unidrive.ProviderException
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#950: OneDrive judges siblings on a key that folds case, NFKC, zero-width characters and emoji
 * variation selectors, answers a create of a folded name 409 "Name already exists" (like a true duplicate), and the
 * keep-both `rename` then lands the file as "<sibling> 1.ext" with the original missing. Owner decision 2026-11-26:
 * the engine refuses the second name, names the sibling and blocks the row, like `similar_name`.
 *
 * The drive is [SimilarNameGraph] with the fold key written out here, independently of the provider's own rule, and
 * the "duplicate" answer measured in the issue.
 */
class OneDriveNameFoldTest {
    private lateinit var tmp: Path

    private fun driveKey(name: String): String {
        val kept = StringBuilder()
        Normalizer.normalize(name, Normalizer.Form.NFKC).codePoints().forEach { cp ->
            val zeroWidth = cp in 0x200B..0x200D || cp == 0x2060 || cp == 0xFEFF
            val variation = cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF
            if (!zeroWidth && !variation) kept.appendCodePoint(cp)
        }
        return kept.toString().lowercase(Locale.ROOT).uppercase(Locale.ROOT).lowercase(Locale.ROOT)
    }

    @BeforeTest
    fun setUp() {
        tmp = TestScratch.directory("ud-name-fold")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun drive(vararg existing: String): SimilarNameGraph =
        SimilarNameGraph(keyOf = ::driveKey, foldsAsDuplicate = true).also { g ->
            g.seed("/fold", isFolder = true)
            existing.forEach { g.seed("/fold/$it", bytes = "old".toByteArray()) }
        }

    private fun providerWith(graph: SimilarNameGraph): OneDriveProvider {
        val config = OneDriveConfig(tokenPath = tmp.resolve("token"))
        val api = GraphApiService(config = config, httpClient = graph.client(), tokenProvider = { _, _ -> "test-token" })
        return OneDriveProvider(config, graphApi = api)
    }

    private fun local(): Path = TestScratch.file(tmp, "src", ".bin").also { Files.write(it, "new".toByteArray()) }

    private fun assertRefusedNamingSibling(
        existing: String,
        folded: String,
    ) = runTest {
        val graph = drive(existing)
        val provider = providerWith(graph)

        val refusal = assertFailsWith<NameRefusedException> { provider.upload(local(), "/fold/$folded", existingRemoteId = null) }

        assertEquals(NameRefusedException.SIMILAR_NAME, refusal.reason)
        assertEquals(existing, refusal.sibling, "the sibling the name folds onto")
        assertEquals(listOf(existing), graph.find("/fold")!!.children.keys.toList(), "no ' 1' copy and no second item on the drive")
        assertEquals("old", String(assertNotNull(graph.find("/fold/$existing")).bytes), "the sibling is untouched")
        assertTrue(graph.requests.none { it.contains("conflictBehavior=rename") }, "never retried as a keep-both copy: ${graph.requests}")
        provider.close()
    }

    @Test
    fun `a name OneDrive folds onto an existing sibling is refused naming that sibling and never uploaded as a renamed copy - case`() {
        assertRefusedNamingSibling(existing = "STRASSE.txt", folded = "Straße.txt")
        assertRefusedNamingSibling(existing = "Σ.txt", folded = "ς.txt")
        assertRefusedNamingSibling(existing = "report.txt", folded = "Report.txt")
    }

    @Test
    fun `a name OneDrive folds onto an existing sibling is refused naming that sibling and never uploaded as a renamed copy - NFKC`() {
        assertRefusedNamingSibling(existing = "files.txt", folded = "ﬁles.txt")
    }

    @Test
    fun `a name OneDrive folds onto an existing sibling is refused naming that sibling and never uploaded as a renamed copy - zero width`() {
        assertRefusedNamingSibling(existing = "ab.txt", folded = "a⁠b.txt")
        assertRefusedNamingSibling(existing = "a⁠b.txt", folded = "a‍b.txt")
    }

    @Test
    fun `a name OneDrive folds onto an existing sibling is refused naming that sibling and never uploaded as a renamed copy - variation selector`() {
        assertRefusedNamingSibling(existing = "❤.txt", folded = "❤️.txt")
    }

    @Test
    fun `a large create of a folded name is refused through its upload session too`() =
        runTest {
            val graph = drive("STRASSE.bin")
            val provider = providerWith(graph)
            val big = TestScratch.file(tmp, "big", ".bin").also { Files.write(it, ByteArray(5 * 1024 * 1024)) }

            val refusal = assertFailsWith<NameRefusedException> { provider.upload(big, "/fold/Straße.bin", existingRemoteId = null) }

            assertEquals("STRASSE.bin", refusal.sibling)
            assertEquals(listOf("STRASSE.bin"), graph.find("/fold")!!.children.keys.toList())
            provider.close()
        }

    @Test
    fun `distinct names that OneDrive does not fold still upload`() =
        runTest {
            val graph = drive("STRASSE.txt", "ab.txt", "files.txt")
            val provider = providerWith(graph)

            for (name in listOf("Strasse2.txt", "ac.txt", "fil es.txt", "STRASSE.md")) {
                val item = provider.upload(local(), "/fold/$name", existingRemoteId = null)
                assertEquals(name, item.name)
                assertEquals("new", String(assertNotNull(graph.find("/fold/$name")).bytes))
            }
            assertNull(graph.find("/fold/STRASSE 1.txt"))
            provider.close()
        }

    @Test
    fun `a 409 with no exact-name sibling is refused as similar_name, never kept as a renamed copy`() =
        runTest {
            // A fold our key does not know: the drive ignores digits, the provider's key does not.
            val graph = SimilarNameGraph(keyOf = { n -> n.filterNot { it.isDigit() }.lowercase() }, foldsAsDuplicate = true)
            graph.seed("/fold", isFolder = true)
            graph.seed("/fold/a.txt", bytes = "old".toByteArray())
            val provider = providerWith(graph)

            val refusal = assertFailsWith<NameRefusedException> { provider.upload(local(), "/fold/a7.txt", existingRemoteId = null) }

            assertEquals(NameRefusedException.SIMILAR_NAME, refusal.reason)
            assertNull(refusal.sibling, "no key-matched sibling to name")
            assertEquals(listOf("a.txt"), graph.find("/fold")!!.children.keys.toList(), "no ' 1' copy")
            assertTrue(graph.requests.none { it.contains("conflictBehavior=rename") }, "never retried as keep-both: ${graph.requests}")
            provider.close()
        }

    @Test
    fun `a listing that errors after the 409 is retried and the later listing decides, never a renamed copy`() =
        runTest {
            val graph = drive("STRASSE.txt")
            val provider = providerWith(graph)
            graph.failedListings = 1

            val first = assertFailsWith<ProviderException> { provider.upload(local(), "/fold/Straße.txt", existingRemoteId = null) }

            assertTrue(first !is PermanentUploadFailureException, "retryable: the upload ladder tries again")
            assertTrue(graph.requests.none { it.contains("conflictBehavior=rename") }, "no keep-both on an unknown answer: ${graph.requests}")
            assertEquals(listOf("STRASSE.txt"), graph.find("/fold")!!.children.keys.toList(), "no copy exists")

            // The retry: the listing answers and shows the folded sibling.
            val second = assertFailsWith<NameRefusedException> { provider.upload(local(), "/fold/Straße.txt", existingRemoteId = null) }

            assertEquals(NameRefusedException.SIMILAR_NAME, second.reason)
            assertEquals("STRASSE.txt", second.sibling)
            assertEquals(listOf("STRASSE.txt"), graph.find("/fold")!!.children.keys.toList())
            provider.close()
        }

    @Test
    fun `a listing that keeps failing names the listing error and never lands a renamed copy`() =
        runTest {
            val graph = drive("STRASSE.txt")
            val provider = providerWith(graph)
            graph.failedListings = -1

            repeat(3) {
                val failure = assertFailsWith<ProviderException> { provider.upload(local(), "/fold/Straße.txt", existingRemoteId = null) }
                assertIs<ProviderException>(failure)
                assertTrue(failure.message.orEmpty().contains("listing failed"), "names the listing error: ${failure.message}")
            }

            assertTrue(graph.requests.none { it.contains("conflictBehavior=rename") }, "no keep-both: ${graph.requests}")
            assertEquals(listOf("STRASSE.txt"), graph.find("/fold")!!.children.keys.toList(), "no copy exists")
            provider.close()
        }

    @Test
    fun `a true duplicate of the exact name still keeps both`() =
        runTest {
            val graph = drive("STRASSE.txt")
            val provider = providerWith(graph)

            val item = provider.upload(local(), "/fold/STRASSE.txt", existingRemoteId = null)

            assertEquals("STRASSE 1.txt", item.name)
            assertEquals("old", String(assertNotNull(graph.find("/fold/STRASSE.txt")).bytes), "the pre-existing item is untouched")
            provider.close()
        }

    @Test
    fun `a capital sharp S folds onto its sibling spelled SS or sharp s`() {
        assertRefusedNamingSibling(existing = "STRASSE.txt", folded = "STRA\u1E9EE.txt")
        assertRefusedNamingSibling(existing = "Straße.txt", folded = "STRA\u1E9EE.txt")
    }

    @Test
    fun `a rename onto a folded name is refused naming the sibling, by path and by id`() =
        runTest {
            val graph = drive("STRASSE.txt", "draft.txt", "draft2.txt")
            val provider = providerWith(graph)

            val byPath = assertFailsWith<NameRefusedException> { provider.move("/fold/draft.txt", "/fold/Straße.txt") }
            val byId = assertFailsWith<NameRefusedException> { provider.moveById(graph.find("/fold/draft2.txt")!!.id, "/fold/draft2.txt", "/fold/Straße.txt") }

            assertEquals("STRASSE.txt", byPath.sibling)
            assertEquals("STRASSE.txt", byId.sibling)
            assertEquals(NameRefusedException.SIMILAR_NAME, byPath.reason)
            assertNotNull(graph.find("/fold/draft.txt"), "the renamed item stays where it was")
            provider.close()
        }

    @Test
    fun `a rename onto an exact sibling name keeps the plain 409`() =
        runTest {
            val graph = drive("STRASSE.txt", "draft.txt")
            val provider = providerWith(graph)

            val refusal = assertFailsWith<GraphApiException> { provider.move("/fold/draft.txt", "/fold/STRASSE.txt") }

            assertEquals(409, refusal.statusCode)
            provider.close()
        }

    @Test
    fun `a folder create beside a case-folded or NFKC-folded sibling lands under a server name, an exact one keeps the 409`() =
        runTest {
            val graph = drive()
            graph.seed("/fold/report", isFolder = true)
            graph.seed("/fold/STRASSE", isFolder = true)
            val provider = providerWith(graph)

            val upper = provider.createFolder("/fold/Report")
            val sharp = provider.createFolder("/fold/Straße")
            val exact = assertFailsWith<GraphApiException> { provider.createFolder("/fold/report") }

            assertTrue(upper.nameOverridden && upper.path.startsWith("/fold/Report"), "landed under a server name: ${upper.path}")
            assertTrue(sharp.nameOverridden && sharp.path.startsWith("/fold/Stra"), "landed under a server name: ${sharp.path}")
            assertEquals(409, exact.statusCode)
            provider.close()
        }
}
