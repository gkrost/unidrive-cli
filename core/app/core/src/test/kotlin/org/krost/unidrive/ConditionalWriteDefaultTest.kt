package org.krost.unidrive

import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #268 (#47 item 4): [CloudProvider.conditionalWrite] is a behaviour descriptor, and its default fails closed. A provider
 * that declares nothing is taken to guard nothing, so no caller can count on an `ifMatchETag` the provider may drop; a
 * provider whose writes do check the token has to say so, and the conformance suite holds it to what it says.
 */
class ConditionalWriteDefaultTest {
    private val undeclared =
        object : CloudProvider {
            override val id = "undeclared"
            override val displayName = "Undeclared"
            override var isAuthenticated = true

            override fun capabilities(): Set<Capability> = setOf(Capability.Delta)

            override suspend fun authenticate() {}

            override suspend fun listChildren(path: String) = emptyList<CloudItem>()

            override suspend fun getMetadata(path: String) =
                CloudItem(path, path.substringAfterLast('/'), path, 1, false, Instant.EPOCH, Instant.EPOCH, null, null)

            override suspend fun download(
                remotePath: String,
                destination: Path,
            ): Long = 0L

            override suspend fun upload(
                localPath: Path,
                remotePath: String,
                existingRemoteId: String?,
                ifMatchETag: String?,
                onProgress: ((Long, Long) -> Unit)?,
            ): CloudItem = getMetadata(remotePath)

            override suspend fun delete(
                remotePath: String,
                ifMatchETag: String?,
            ) {}

            override suspend fun createFolder(path: String): CloudItem = getMetadata(path)

            override suspend fun move(
                fromPath: String,
                toPath: String,
            ): CloudItem = getMetadata(toPath)

            override suspend fun delta(
                cursor: String?,
                onPageProgress: ((itemsSoFar: Int) -> Unit)?,
                scanContext: ScanContext?,
            ) = DeltaPage(emptyList(), "", false)

            override suspend fun quota() = QuotaInfo(1, 0, 1)
        }

    @Test
    fun `a provider that declares no conditional write is taken to guard neither upload nor delete`() {
        assertEquals(ConditionalWrite.NONE, undeclared.conditionalWrite)
        assertFalse(undeclared.conditionalWrite.upload.checksToken, "an undeclared upload guard must not count as one")
        assertFalse(undeclared.conditionalWrite.delete.checksToken, "an undeclared delete guard must not count as one")
    }

    @Test
    fun `only an atomic guard is atomic, and an emulated one still checks the token`() {
        assertTrue(WriteGuard.ATOMIC.checksToken && WriteGuard.ATOMIC.atomic)
        assertTrue(WriteGuard.EMULATED.checksToken)
        assertFalse(WriteGuard.EMULATED.atomic, "a read-then-write has a window between the check and the write")
        assertFalse(WriteGuard.NONE.checksToken || WriteGuard.NONE.atomic)
    }
}
