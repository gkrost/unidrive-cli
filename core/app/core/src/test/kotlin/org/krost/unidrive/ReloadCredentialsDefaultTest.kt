package org.krost.unidrive

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The SPI default of [CloudProvider.reloadCredentials] must never fall through to [CloudProvider.authenticate]:
 * an authenticate can prompt for a sign-in (a browser, a console prompt, a device code), and a running daemon's
 * reload has nobody to answer one. A provider that has not made its reload decision must fail loudly, not start
 * an interactive flow.
 */
class ReloadCredentialsDefaultTest {
    private var authenticateCalls = 0

    // A provider that does not override reloadCredentials: exactly the shape of a new adapter that forgot the decision.
    private val stubProvider =
        object : CloudProvider {
            override val id = "stub"
            override val displayName = "Stub Provider"
            override var isAuthenticated = false

            override fun capabilities(): Set<Capability> = emptySet()

            override suspend fun authenticate() {
                authenticateCalls++
            }

            override suspend fun listChildren(path: String) = emptyList<CloudItem>()

            override suspend fun getMetadata(path: String) =
                CloudItem(
                    id = "1",
                    name = "file.txt",
                    path = "/file.txt",
                    size = 42,
                    isFolder = false,
                    modified = Instant.now(),
                    created = Instant.now(),
                    hash = "abc",
                    mimeType = "text/plain",
                )

            override suspend fun download(
                remotePath: String,
                destination: Path,
            ): Long = 42L

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

            override suspend fun quota() = QuotaInfo(100, 50, 50)
        }

    @Test
    fun `reloadCredentials never runs authenticate`() =
        runBlocking<Unit> {
            val failure = runCatching { stubProvider.reloadCredentials() }.exceptionOrNull()

            assertEquals(0, authenticateCalls, "reloadCredentials fell through to authenticate(), which can prompt")
            assertNotNull(failure, "a provider without a reload decision must be refused loudly, not signed in")
        }
}
