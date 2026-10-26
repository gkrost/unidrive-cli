package org.krost.unidrive.internxt

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Candidate P1 of the audit: the name a file is uploaded under must be the name it is listed and found under. The upload
 * splits the leaf into a plainName and a type at the last dot; the listing joins them again but drops a type the plainName
 * already ends in and trims the plainName. A name for which the two differ comes back as another name, and the engine follows
 * it as a rename of the user's file.
 */
class InternxtNameRoundTripTest {
    private fun assertRoundTrips(leaf: String) =
        runTest {
            val tmp = Files.createTempDirectory("ud-name-rt-")
            try {
                val local = Files.createTempFile(tmp, "src-", ".bin").also { Files.write(it, ByteArray(16) { b -> b.toByte() }) }
                val drive = UploadDrive()
                val provider = rootedInternxtProvider(tmp, drive.engine)
                try {
                    val item = provider.upload(local, "/$leaf", existingRemoteId = null, onProgress = null)
                    val (plain, type) = drive.createBodies.single()
                    assertEquals(leaf, item.name, "sent plainName='$plain' type='$type', came back under another name")
                    val found = runCatching { provider.getMetadata("/$leaf") }
                    assertEquals(true, found.isSuccess, "getMetadata('/$leaf') must find what was uploaded as '$leaf': ${found.exceptionOrNull()?.message}")
                    assertEquals(leaf, provider.listChildren("/").single().name)
                } finally {
                    provider.close()
                }
            } finally {
                tmp.toFile().deleteRecursively()
            }
        }

    @Test
    fun `a double extension round trips`() = assertRoundTrips("report.txt.txt")

    @Test
    fun `a name ending in a dot round trips`() = assertRoundTrips("report.")

    @Test
    fun `a dotfile round trips`() = assertRoundTrips(".env")

    @Test
    fun `a name with a space before the extension round trips`() = assertRoundTrips("Scan 1 .pdf")

    @Test
    fun `a plain extension round trips`() = assertRoundTrips("report.pdf")

    @Test
    fun `a multi-dot name round trips`() = assertRoundTrips("archive.tar.gz")

    @Test
    fun `a name without a dot round trips`() = assertRoundTrips("README")

    @Test
    fun `a rename to a double extension is listed under the new name`() =
        runTest {
            val tmp = Files.createTempDirectory("ud-name-rt-")
            try {
                val drive = UploadDrive()
                drive.files += UploadDriveFile("f1", "a", "txt", 4, "fid", "root-folder-uuid")
                val provider = rootedInternxtProvider(tmp, drive.engine)
                try {
                    val moved = provider.move("/a.txt", "/a.txt.txt")
                    assertEquals("a.txt.txt", moved.name, "renamed as ${drive.renames}")
                    val found = runCatching { provider.getMetadata("/a.txt.txt") }
                    assertEquals(true, found.isSuccess, "getMetadata('/a.txt.txt') after the rename: ${found.exceptionOrNull()?.message}")
                } finally {
                    provider.close()
                }
            } finally {
                tmp.toFile().deleteRecursively()
            }
        }
}
