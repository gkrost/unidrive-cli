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
    fun `a write whose move fails leaves no temporary file behind`() {
        // The target name is a folder that is not empty, so the move over it fails.
        val target = dir.resolve(ResumeSessionStore.FILE_NAME)
        Files.createDirectory(target)
        Files.writeString(target.resolve("x"), "x")

        assertFailsWith<java.io.IOException> { store().replace(mapOf("/a" to Entry("u1", 5))) }

        assertEquals(listOf(ResumeSessionStore.FILE_NAME), folder())
    }
}
