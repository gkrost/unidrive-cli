package org.krost.unidrive.dropbox

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.ProviderException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A server or proxy that answers a listing with a cursor it was already asked for would otherwise be paged for ever,
 * holding memory and a budget slot (#82 F). The listing fails as incomplete instead, which the engine retries.
 */
class DropboxPageTokenLoopTest {
    private lateinit var dir: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("dbx-tok")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `a repeated cursor fails listChildren instead of paging for ever`() = runTest {
        val fake = FakeDropbox()
        fake.json("files/list_folder", """{"entries":[${FakeDropbox.file("/a.txt", "idA")}],"cursor":"c1","has_more":true}""")
        // The first continue repeats c1 (the loop), the second ends the listing — so the guard-less code terminates
        // after two continues and only a guard stops at the first.
        fake.on("files/list_folder/continue") { FakeDropbox.Answer(body = """{"entries":[],"cursor":"c1","has_more":true}""") }
        fake.on("files/list_folder/continue") { FakeDropbox.Answer(body = """{"entries":[],"cursor":"c1","has_more":false}""") }
        val provider = FakeDropbox.provider(fake, dir)

        val e = assertFailsWith<ProviderException> { provider.listChildren("/") }

        assertTrue(e.message!!.contains("already asked for"), "the failure must name the repeat: ${e.message}")
        assertEquals(1, fake.callsTo("files/list_folder/continue").size, "the listing must stop at the page whose cursor repeats")
    }

    @Test
    fun `distinct cursors still list to the end`() = runTest {
        val fake = FakeDropbox()
        fake.json("files/list_folder", """{"entries":[${FakeDropbox.file("/a.txt", "idA")}],"cursor":"c1","has_more":true}""")
        fake.on("files/list_folder/continue") { FakeDropbox.Answer(body = """{"entries":[${FakeDropbox.file("/b.txt", "idB")}],"cursor":"c2","has_more":true}""") }
        fake.on("files/list_folder/continue") { FakeDropbox.Answer(body = """{"entries":[],"cursor":"c3","has_more":false}""") }
        val provider = FakeDropbox.provider(fake, dir)

        val items = provider.listChildren("/")

        assertEquals(listOf("/a.txt", "/b.txt"), items.map { it.path })
    }
}
