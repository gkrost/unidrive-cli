package org.krost.unidrive.cli

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.CredentialProof
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.NotSignedInException
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
import org.krost.unidrive.sync.ProfileMode
import org.krost.unidrive.sync.StateDatabase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The daemon's quota fetch never signs in (#211): a status poll asks [CloudProvider.checkCredentials], which
 * refuses a profile whose credentials are not stored, and never [CloudProvider.authenticate], whose answer to
 * a signed-out profile is a browser sign-in on the daemon host. A cancelled fetch is not a quota error.
 */
class DaemonQuotaFetchTest {
    private lateinit var tempDir: Path
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("daemon-quota-fetch-test")
        db = StateDatabase(tempDir.resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        runCatching { tempDir.toFile().deleteRecursively() }
    }

    private class QuotaProvider : CloudProvider {
        override val id = "fake-quota"
        override val displayName = "Fake (quota fetch test)"
        override var isAuthenticated = true
        val calls = mutableListOf<String>()
        var failCheckWith: Exception? = null

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)
        override suspend fun authenticate() {
            calls.add("authenticate")
        }

        // archive#1240: the status-style check never takes authenticate()'s browser fallback.
        override suspend fun checkCredentials(): CredentialProof {
            calls.add("checkCredentials")
            failCheckWith?.let { throw it }
            return CredentialProof("Tester")
        }

        override suspend fun quota(): QuotaInfo {
            calls.add("quota")
            return QuotaInfo(total = 10, used = 3, remaining = 7)
        }

        override suspend fun listChildren(path: String): List<CloudItem> = error("not used")
        override suspend fun getMetadata(path: String): CloudItem = error("not used")
        override suspend fun download(remotePath: String, destination: Path): Long = error("not used")
        override suspend fun downloadById(remoteId: String, remotePath: String, destination: Path): Long = error("not used")
        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("not used")
        override suspend fun delete(remotePath: String, ifMatchETag: String?) = error("not used")
        override suspend fun createFolder(path: String): CloudItem = error("not used")
        override suspend fun move(fromPath: String, toPath: String): CloudItem = error("not used")
        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: ScanContext?,
        ): DeltaPage = error("not used")
    }

    private fun runtime() =
        DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "quota_fetch_test",
            lockFile = tempDir.resolve(".lock"),
            dbPath = tempDir.resolve("state.db"),
            syncRoot = tempDir,
            socketPath = tempDir.resolve("daemon.sock"),
            providerFactory = { error("the fetch takes the provider it is handed") },
        )

    @Test
    fun `the quota fetch checks credentials and never signs in`() {
        val runtime = runtime()
        val provider = QuotaProvider()

        val snapshot = runBlocking { runtime.refreshQuotaSnapshotWith(provider) }

        assertEquals(listOf("checkCredentials", "quota"), provider.calls, "no authenticate() in the fetch: a status poll must not sign in")
        assertNotNull(snapshot)
        assertEquals(false, snapshot!!.stale)
        assertEquals(null, snapshot.error)
        assertEquals(3, snapshot.usedBytes)
        assertEquals(7, snapshot.remainingBytes)
    }

    @Test
    fun `a signed-out profile is refused without a browser call and the snapshot names it`() {
        val runtime = runtime()
        val provider = QuotaProvider().apply { failCheckWith = NotSignedInException("no token is stored for this profile") }

        val snapshot = runBlocking { runtime.refreshQuotaSnapshotWith(provider) }

        assertEquals(listOf("checkCredentials"), provider.calls, "the refusal comes before any transfer, and authenticate() is never tried")
        assertNotNull(snapshot)
        assertTrue(snapshot!!.stale)
        assertTrue("NotSignedInException" in snapshot.error.orEmpty(), snapshot.error)
    }

    @Test
    fun `a cancelled fetch is not a quota error`() {
        val runtime = runtime()
        val provider = QuotaProvider().apply { failCheckWith = CancellationException("the daemon is stopping") }

        assertFailsWith<CancellationException> { runBlocking { runtime.refreshQuotaSnapshotWith(provider) } }

        assertEquals(listOf("checkCredentials"), provider.calls)
    }
}
