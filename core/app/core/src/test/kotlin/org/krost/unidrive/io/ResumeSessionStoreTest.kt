package org.krost.unidrive.io

import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ResumeSessionStoreTest {
    @Serializable
    data class Entry(
        val url: String,
        val offset: Long,
    )

    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("resume-session-store")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun store() = ResumeSessionStore(dir, Entry.serializer())

    private fun folder(): List<String> = Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }

    @Test
    fun `sessions written are read back and no temporary file stays`() {
        store().replace(mapOf("/a" to Entry("u1", 5)))

        assertEquals(mapOf("/a" to Entry("u1", 5)), store().load())
        assertEquals(listOf(ResumeSessionStore.FILE_NAME), folder())
    }

    @Test
    fun `a torn file is quarantined and the store starts empty`() {
        Files.writeString(dir.resolve(ResumeSessionStore.FILE_NAME), "{ not json")

        assertEquals(emptyMap(), store().load())
        assertEquals(0, folder().count { it == ResumeSessionStore.FILE_NAME }, "the torn file was moved aside")
        assertEquals(1, folder().count { it.startsWith(ResumeSessionStore.FILE_NAME + ".torn-") })
    }

    @Test
    fun `a read that fails is an error, not an empty store, and the file is not quarantined`() {
        // windows#454 item 2: an I/O failure (here: the store name is a DIRECTORY, so readString throws) must
        // propagate — the sessions in the file are still resumable, and quarantining on it would restart every
        // one of them from byte 0. Only a file that was read and cannot be PARSED is torn.
        val target = dir.resolve(ResumeSessionStore.FILE_NAME)
        Files.createDirectory(target)
        Files.writeString(target.resolve("x"), "{ \"sessions\" : true }")

        assertFailsWith<java.io.IOException> { store().load() }
        assertEquals(
            listOf(ResumeSessionStore.FILE_NAME),
            folder().filter { it.startsWith(ResumeSessionStore.FILE_NAME) },
            "nothing was moved aside",
        )
    }

    @Test
    fun `a write whose move fails leaves no temporary file behind`() {
        // The target name is a folder that is not empty, so the move over it fails.
        val target = dir.resolve(ResumeSessionStore.FILE_NAME)
        Files.createDirectory(target)
        Files.writeString(target.resolve("x"), "x")

        assertFailsWith<java.io.IOException> { store().replace(mapOf("/a" to Entry("u1", 5))) }

        assertEquals(listOf(ResumeSessionStore.FILE_NAME), folder())
    }
}
