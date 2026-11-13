package org.krost.unidrive

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * #268: the id-addressed defaults ([CloudProvider.deleteById], [CloudProvider.deletePermanentlyById],
 * [CloudProvider.moveById]) fall back to the path. Where names are unique per folder the path is the item; on a provider
 * that allows same-named siblings it may be the twin, so an adapter that forgot the override fails before any request.
 */
class IdAddressedDefaultsTest {
    private class PathOnlyProvider(
        override val allowsDuplicateNames: Boolean,
    ) : CloudProvider {
        val calls = mutableListOf<String>()
        override val id = "path-only"
        override val displayName = "Path only"
        override var isAuthenticated = true

        override fun capabilities(): Set<Capability> = setOf(Capability.PermanentDelete)

        override suspend fun authenticate() {}

        override suspend fun listChildren(path: String) = emptyList<CloudItem>()

        override suspend fun getMetadata(path: String) =
            CloudItem(
                id = path,
                name = path.substringAfterLast('/'),
                path = path,
                size = 1,
                isFolder = false,
                modified = Instant.EPOCH,
                created = Instant.EPOCH,
                hash = null,
                mimeType = null,
            )

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
        ) {
            calls += "delete $remotePath"
        }

        override suspend fun deletePermanently(remotePath: String) {
            calls += "deletePermanently $remotePath"
        }

        override suspend fun createFolder(path: String): CloudItem = getMetadata(path)

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ): CloudItem {
            calls += "move $fromPath $toPath"
            return getMetadata(toPath)
        }

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((itemsSoFar: Int) -> Unit)?,
            scanContext: ScanContext?,
        ) = DeltaPage(emptyList(), "", false)

        override suspend fun quota() = QuotaInfo(1, 0, 1)
    }

    @Test
    fun `where names are unique the id-addressed defaults act on the path`() =
        runBlocking {
            val p = PathOnlyProvider(allowsDuplicateNames = false)

            p.deleteById("id-1", "/a.txt")
            p.deletePermanentlyById("id-2", "/b.txt")
            p.moveById("id-3", "/c.txt", "/d.txt")

            assertEquals(listOf("delete /a.txt", "deletePermanently /b.txt", "move /c.txt /d.txt"), p.calls)
        }

    @Test
    fun `where twins may exist the id-addressed defaults refuse before any call`() =
        runBlocking {
            val p = PathOnlyProvider(allowsDuplicateNames = true)

            val e = assertFailsWith<UnsupportedOperationException> { p.deleteById("id-1", "/a.txt") }
            assertFailsWith<UnsupportedOperationException> { p.deletePermanentlyById("id-2", "/b.txt") }
            assertFailsWith<UnsupportedOperationException> { p.moveById("id-3", "/c.txt", "/d.txt") }

            assertEquals(emptyList(), p.calls)
            assertEquals(true, e.message?.contains("deleteById"), e.message)
            assertEquals(true, e.message?.contains("/a.txt"), e.message)
        }
}
