package org.krost.unidrive.cli

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.Capability
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #615: the `share.create` verb. The provider's capability answer is the guard; a provider that throws answers
 * `provider_error` and the daemon keeps serving. The wire shapes themselves are pinned by the corpus
 * (`share.create.ndjson`, `sessions/share.ndjson`).
 */
class ShareRpcHandlerTest {
    private class ShareProvider(
        val link: String? = null,
        val failWith: Exception? = null,
    ) : CloudProvider {
        override val id: String = "stub"
        override val displayName: String = "Stub"
        override var isAuthenticated: Boolean = true

        override fun capabilities(): Set<Capability> = if (link != null) setOf(Capability.Share) else emptySet()

        var askedPath: String? = null
        var askedExpiry: Int? = null

        override suspend fun share(
            path: String,
            expiryHours: Int,
            password: String?,
        ): CapabilityResult<String> {
            askedPath = path
            askedExpiry = expiryHours
            failWith?.let { throw it }
            return link?.let { CapabilityResult.Success(it) } ?: CapabilityResult.Unsupported(Capability.Share, "no links here")
        }

        override suspend fun authenticate() { /* no-op: already authenticated */
        }

        override suspend fun listChildren(path: String): List<org.krost.unidrive.CloudItem> = emptyList()

        override suspend fun getMetadata(path: String): org.krost.unidrive.CloudItem = error("not used")

        override suspend fun download(remotePath: String, destination: java.nio.file.Path): Long = error("not used")

        override suspend fun upload(
            localPath: java.nio.file.Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): org.krost.unidrive.CloudItem = error("not used")

        override suspend fun delete(remotePath: String, ifMatchETag: String?) = error("not used")

        override suspend fun createFolder(path: String): org.krost.unidrive.CloudItem = error("not used")

        override suspend fun move(fromPath: String, toPath: String): org.krost.unidrive.CloudItem = error("not used")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ): DeltaPage = DeltaPage(items = emptyList(), cursor = "x", hasMore = false)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    @Test
    fun `a share-capable provider answers the link`() = runTest {
        val provider = ShareProvider(link = "https://cloud.example/s/abc")
        val handler = ShareRpcHandler(provider, Dispatchers.Unconfined)

        assertEquals("""{"ok":true,"url":"https://cloud.example/s/abc"}""", handler.handle("""{"verb":"share.create","path":"/a b/notes.txt"}"""))
        assertEquals("/a b/notes.txt", provider.askedPath)
        // The verb carries no expiry yet: the engine's default applies, the same one `unidrive share` uses.
        assertEquals(24, provider.askedExpiry)
    }

    @Test
    fun `a path is required`() = runTest {
        val handler = ShareRpcHandler(ShareProvider(), Dispatchers.Unconfined)

        assertEquals("""{"ok":false,"error":"missing_path"}""", handler.handle("""{"verb":"share.create"}"""))
        assertEquals("""{"ok":false,"error":"missing_path"}""", handler.handle("""{"verb":"share.create","path":42}"""))
        assertEquals("""{"ok":false,"error":"missing_path"}""", handler.handle("""{"verb":"share.create","path":""}"""))
        assertEquals("""{"ok":false,"error":"missing_path"}""", handler.handle("not json"))
    }

    @Test
    fun `a provider without the capability answers unsupported with its reason`() = runTest {
        val handler = ShareRpcHandler(ShareProvider(), Dispatchers.Unconfined)

        assertEquals("""{"ok":false,"error":"unsupported","message":"no links here"}""", handler.handle("""{"verb":"share.create","path":"/x"}"""))
    }

    @Test
    fun `a provider that throws answers provider_error and the message is kept`() = runTest {
        val handler = ShareRpcHandler(ShareProvider(link = "unused", failWith = java.io.IOException("GET https://api.example.test/shares failed")), Dispatchers.Unconfined)

        assertEquals("""{"ok":false,"error":"provider_error","message":"GET https://api.example.test/shares failed"}""", handler.handle("""{"verb":"share.create","path":"/x"}"""))
    }
}
