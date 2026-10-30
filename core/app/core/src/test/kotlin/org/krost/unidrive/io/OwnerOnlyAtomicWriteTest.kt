package org.krost.unidrive.io

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #222 item 2: the one owner-only, synced, atomic replace behind config.toml, the vault, the credentials and the verify report. */
class OwnerOnlyAtomicWriteTest {
    private lateinit var dir: Path
    private lateinit var target: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("unidrive-owner-atomic-")
        target = dir.resolve("secret.toml")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun folder(): List<String> = Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }

    @Test
    fun `the new file holds the bytes, is owner-only and no temp file is left`() {
        OwnerOnlyAtomicWrite.replace(target, "new".toByteArray())

        assertEquals("new", Files.readString(target))
        assertNull(OwnerOnly.grantProblem(target), "the replaced file is reachable by the current user only")
        assertEquals(listOf("secret.toml"), folder())
    }

    @Test
    fun `a destination name at the 255 character limit is still written`() {
        // `verify --json-report` takes any valid name: 240 letters and ".json" is 245, and name + "." + number + ".tmp" no longer fits.
        val long = dir.resolve("a".repeat(240) + ".json")

        OwnerOnlyAtomicWrite.replace(long, "report".toByteArray())

        assertEquals("report", Files.readString(long))
        assertEquals(listOf(long.fileName.toString()), folder())
    }

    @Test
    fun `a long multi-byte name gets a prefix that fits and is not cut inside a character`() {
        val name = "😀".repeat(60) + ".json" // 240 UTF-8 bytes of emoji: cut only between whole characters

        val prefix = OwnerOnlyAtomicWrite.tempPrefix(name)

        assertTrue(prefix.toByteArray(Charsets.UTF_8).size <= 101, "bounded")
        assertTrue(prefix.endsWith("😀."), "ends on a whole character: $prefix")
        assertEquals("secret.toml.", OwnerOnlyAtomicWrite.tempPrefix("secret.toml"))
    }

    @Test
    fun `it replaces an existing file and leaves no temp file`() {
        Files.writeString(target, "old")

        OwnerOnlyAtomicWrite.replace(target, "new".toByteArray())

        assertEquals("new", Files.readString(target))
        assertEquals(listOf("secret.toml"), folder())
    }

    @Test
    fun `a failure before the move leaves the old file as it was and no temp file`() {
        Files.writeString(target, "old")

        val failures =
            mapOf<String, () -> Unit>(
                "produce" to { OwnerOnlyAtomicWrite.replace(target, { throw IOException("serialize") }) },
                "beforeCommit" to { OwnerOnlyAtomicWrite.replace(target, "new".toByteArray(), beforeCommit = { throw IOException("crash") }) },
                "verify" to { OwnerOnlyAtomicWrite.replace(target, "new".toByteArray(), verify = { throw IOException("torn") }) },
            )
        for ((stage, run) in failures) {
            assertFailsWith<IOException>(stage) { run() }
            assertEquals("old", Files.readString(target), "$stage: the old file is untouched")
            assertEquals(listOf("secret.toml"), folder(), "$stage: no temp file is left behind")
        }
    }

    @Test
    fun `the bytes are asked for only once an owner-only temp file exists`() {
        var tempSeen: List<String>? = null
        var grantProblemOfTemp: String? = "not checked"

        OwnerOnlyAtomicWrite.replace(target, {
            tempSeen = folder().filter { it.endsWith(".tmp") }
            grantProblemOfTemp = tempSeen!!.singleOrNull()?.let { OwnerOnly.grantProblem(dir.resolve(it)) }
            "secret".toByteArray()
        })

        assertEquals(1, tempSeen!!.size, "the temp file exists before the secret is produced")
        assertNull(grantProblemOfTemp, "and it is already owner-only")
    }

    @Test
    fun `the temp file is verified with the bytes written to it`() {
        var verified: String? = null

        OwnerOnlyAtomicWrite.replace(target, "payload".toByteArray(), verify = { verified = Files.readString(it) })

        assertEquals("payload", verified)
    }

    @Test
    fun `a file that cannot be made owner-only is refused, nothing is produced and the old file stays`() {
        Files.writeString(target, "old")
        var produced = false

        assertFailsWith<IOException> {
            OwnerOnlyAtomicWrite.replace(
                target,
                { produced = true; "new".toByteArray() },
                null,
                {},
                {},
                { OwnerOnly.Outcome.Unsupported("a FAT volume") },
            )
        }

        assertEquals(false, produced, "no secret is serialized for a file that anyone could read")
        assertEquals("old", Files.readString(target))
        assertEquals(listOf("secret.toml"), folder())
    }

    @Test
    fun `a caller that opts into the warning gets the outcome and the write goes ahead`() {
        val reported = mutableListOf<OwnerOnly.Outcome>()

        OwnerOnlyAtomicWrite.replace(
            target,
            { "new".toByteArray() },
            { reported += it },
            {},
            {},
            { OwnerOnly.Outcome.Failed("share refused the ACL") },
        )

        assertEquals(listOf<OwnerOnly.Outcome>(OwnerOnly.Outcome.Failed("share refused the ACL")), reported)
        assertEquals("new", Files.readString(target))
        assertEquals(listOf("secret.toml"), folder())
    }

    @Test
    fun `the callback is not called when the restriction worked`() {
        var called = false

        OwnerOnlyAtomicWrite.replace(target, "new".toByteArray(), onUnrestricted = { called = true })

        assertTrue(!called)
    }
}
