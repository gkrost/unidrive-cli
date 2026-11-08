package org.krost.unidrive.s3

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.RemoteConflictException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * gkrost/unidrive-cli#176: a move onto a taken key is a conflict, not an overwrite. Every other provider refuses a
 * taken destination (Dropbox answers path/conflict, a Graph rename refuses nameAlreadyExists); the copy-then-delete
 * move here used to replace whatever the key held, on the file leg and under the folder. The check is per key the
 * move writes: keys it does not write are never touched, so a folder move beside foreign keys merges as it always
 * has — a leftover of an interrupted attempt is indistinguishable from a foreign key once the retry's source keys
 * are partly deleted, so refusing those would break the retry convergence archive#1063 pins in [S3ProviderTest].
 * A key that already holds exactly the source's bytes — what that attempt's copy leaves behind — is no conflict:
 * the copy may go over it again.
 */
class S3MoveConflictTest {
    private val dir = Files.createTempDirectory("s3-move-conflict")

    private fun provider(fake: FakeS3) = S3Provider(
        S3Config("b", "us-east-1", "http://localhost:9000", "AK", "SK", dir),
        fake.engine,
    )

    @Test
    fun `a file move onto a taken key throws, keeps the destination and keeps the source`() =
        runTest {
            val fake = FakeS3()
            fake.put("a.txt", "the source's bytes")
            fake.put("b.txt", "the other device's bytes")

            assertFailsWith<RemoteConflictException> { provider(fake).move("/a.txt", "/b.txt") }

            assertEquals("the other device's bytes", fake.text("b.txt"))
            assertEquals(listOf("a.txt", "b.txt"), fake.objects.keys.toList())
        }

    @Test
    fun `a folder move onto a taken key under the destination throws, keeps the destination and keeps the source`() =
        runTest {
            val fake = FakeS3()
            fake.put("d/a.txt", "the source tree's bytes")
            fake.put("e/a.txt", "the other tree's bytes")

            assertFailsWith<RemoteConflictException> { provider(fake).move("/d", "/e") }

            assertEquals("the other tree's bytes", fake.text("e/a.txt"))
            assertEquals(listOf("d/a.txt", "e/a.txt"), fake.objects.keys.toList())
        }

    // The listing of the destination can carry an object without an ETag; it is present all the same and its
    // bytes are unknown, so the move refuses instead of treating the key as free.
    @Test
    fun `a folder move refuses a taken key whose listing carries no ETag`() =
        runTest {
            val fake = FakeS3()
            fake.put("d/a.txt", "the source tree's bytes")
            fake.putWithoutEtag("e/a.txt", "the other tree's bytes")

            assertFailsWith<RemoteConflictException> { provider(fake).move("/d", "/e") }

            assertEquals("the other tree's bytes", fake.text("e/a.txt"))
            assertEquals(listOf("d/a.txt", "e/a.txt"), fake.objects.keys.toList())
        }

    @Test
    fun `a folder move does not clobber a deeper key whose name collides`() =
        runTest {
            val fake = FakeS3()
            fake.put("d/nested/a.txt", "the source tree's bytes")
            fake.put("e/nested/a.txt", "the other tree's bytes")

            assertFailsWith<RemoteConflictException> { provider(fake).move("/d", "/e") }

            assertEquals("the other tree's bytes", fake.text("e/nested/a.txt"))
            assertEquals(listOf("d/nested/a.txt", "e/nested/a.txt"), fake.objects.keys.toList())
        }

    @Test
    fun `a file move whose copy already landed converges on the retry`() =
        runTest {
            val fake = FakeS3()
            fake.put("a.txt", "same bytes")
            // What an interrupted attempt's copy left at the destination: the source's own bytes, the same ETag.
            fake.put("b.txt", "same bytes")

            provider(fake).move("/a.txt", "/b.txt")

            assertEquals("same bytes", fake.text("b.txt"))
            assertEquals(listOf("b.txt"), fake.objects.keys.toList())
        }

    @Test
    fun `a folder move whose copies already landed converges on the retry`() =
        runTest {
            val fake = FakeS3()
            // The first attempt copied everything and then lost "d/b.txt" to a store that refused the delete:
            // the destination holds the source tree's bytes under the same ETags, the source holds the rest.
            fake.put("d/b.txt", "b")
            fake.put("e/a.txt", "a")
            fake.put("e/b.txt", "b")

            provider(fake).move("/d", "/e")

            assertEquals(setOf("e/a.txt", "e/b.txt"), fake.objects.keys.toSet())
        }

    @Test
    fun `a folder move beside keys it does not write merges, as it always has`() =
        runTest {
            val fake = FakeS3()
            fake.put("d/a.txt", "a")
            fake.put("e/x.txt", "foreign")

            provider(fake).move("/d", "/e")

            assertEquals("a", fake.text("e/a.txt"))
            assertEquals("foreign", fake.text("e/x.txt"))
            assertEquals(setOf("e/a.txt", "e/x.txt"), fake.objects.keys.toSet())
        }

    @Test
    fun `a folder move onto an empty destination folder subsumes its marker`() =
        runTest {
            val fake = FakeS3()
            fake.put("d/a.txt", "a")
            fake.put("e/", "")

            provider(fake).move("/d", "/e")

            assertEquals("a", fake.text("e/a.txt"))
            assertEquals(setOf("e/", "e/a.txt"), fake.objects.keys.toSet())
        }
}
