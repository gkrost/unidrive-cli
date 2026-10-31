package org.krost.unidrive.internxt

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.NameRefusedException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The profile key `internxt_strict_names`: with it on, a create, an upload, a move or a rename to a name the official
 * Internxt clients would not accept is refused before any request, with the typed [NameRefusedException] whose reason is
 * `name_incompatible: <rule>`. With it off the same calls go to the drive as before.
 */
class InternxtStrictNamesTest {
    private val tmp: Path = Files.createTempDirectory("ud-strict-names-")
    private val requests = AtomicInteger()

    // Every request is counted and refused as a bad request: the refusals under test must come before the first one.
    private val engine = MockEngine { _ -> requests.incrementAndGet().let { respond("{}", HttpStatusCode.BadRequest) } }

    @AfterTest
    fun cleanUp() {
        tmp.toFile().deleteRecursively()
    }

    private fun provider(strict: Boolean): InternxtProvider =
        internxtProviderOn(engine, InternxtConfig(tokenPath = tmp, credentialProtector = null, strictNames = strict))

    private fun localFile(): Path = Files.writeString(tmp.resolve("local.bin"), "content")

    private suspend fun refusal(block: suspend () -> Unit): NameRefusedException = assertFailsWith<NameRefusedException> { block() }

    @Test
    fun `with the mode on mkdir create rename and a long upload are refused before any request`() =
        runTest {
            val p = provider(strict = true)

            assertEquals("name_incompatible: windows_character", refusal { p.createFolder("/a:b") }.reason)
            assertEquals("name_incompatible: trailing_whitespace", refusal { p.upload(localFile(), "/x ", null, null, null) }.reason)
            assertEquals("name_incompatible: device_name", refusal { p.move("/f.txt", "/CON.txt") }.reason)
            assertEquals("name_incompatible: device_name", refusal { p.moveById("uuid-1", "/f.txt", "/CON.txt") }.reason)
            assertEquals("name_incompatible: too_long_utf16", refusal { p.upload(localFile(), "/" + "n".repeat(300), null, null, null) }.reason)
            assertEquals("name_incompatible: windows_character", refusal { p.createFolders(listOf("/ok", "/a|b")) }.reason)

            assertEquals(0, requests.get(), "no request reaches the drive")
        }

    @Test
    fun `with the mode on a folder above the name is checked too`() =
        runTest {
            val p = provider(strict = true)

            assertEquals("name_incompatible: windows_character", refusal { p.upload(localFile(), "/a:b/f.txt", null, null, null) }.reason)
            assertEquals(0, requests.get())
        }

    @Test
    fun `the refusal is the reason the provider gives for a new name before the call, and none with the mode off`() {
        assertEquals("name_incompatible: windows_character", provider(strict = true).newNameIssue("/a:b"))
        assertNull(provider(strict = true).newNameIssue("/Documents/report.pdf"))
        assertNull(provider(strict = false).newNameIssue("/a:b"))
    }

    @Test
    fun `with the mode off the same calls reach the drive as before`() =
        runTest {
            val p = provider(strict = false)

            for (call in listOf<suspend () -> Unit>(
                { p.createFolder("/a:b") },
                { p.upload(localFile(), "/x ", null, null, null) },
                { p.move("/f.txt", "/CON.txt") },
            )) {
                val before = requests.get()
                val failure = runCatching { call() }.exceptionOrNull()
                assertTrue(failure !is NameRefusedException, "not refused by name: $failure")
                assertTrue(requests.get() > before, "the call reached the drive")
            }
        }

    @Test
    fun `with the mode on a rename that makes a name compatible is let through`() =
        runTest {
            val failure = runCatching { provider(strict = true).move("/a:b.txt", "/a-b.txt") }.exceptionOrNull()

            assertTrue(failure !is NameRefusedException, "not refused by name: $failure")
            assertTrue(requests.get() > 0)
        }

    @Test
    fun `with the mode on the generated archive and conflict names stay within 255 UTF-8 bytes`() {
        val plain = "é".repeat(240)
        val now = Instant.parse("2026-10-19T10:20:30Z")

        val archive = InternxtProvider.keepOverwrittenArchiveName(plain, now, 2, type = "txt", strict = true)
        val conflict = InternxtProvider.conflictName(plain, "2026-10-19", 3, type = "txt", strict = true)

        assertNull(InternxtNameRules.issue("$archive.txt"), archive)
        assertNull(InternxtNameRules.issue("$conflict.txt"), conflict)
        assertTrue(archive.endsWith(".unidrive-prev-2026-10-19T10-20-30-2"), archive)
        assertTrue(conflict.endsWith(" (conflict 2026-10-19) (3)"), conflict)
        // With the mode off the names keep today's UTF-16 limit only.
        assertEquals(
            255,
            (InternxtProvider.keepOverwrittenArchiveName(plain, now, 2, type = "txt") + ".txt").length,
        )
    }
}
