package org.krost.unidrive.engine

import kotlinx.coroutines.delay
import org.krost.unidrive.Capability
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
import org.krost.unidrive.ViewKind
import org.krost.unidrive.ViewRef
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A provider with scripted views, for the tests of the view framework: [answers] says what [listView] returns per
 * kind (Unsupported where unset), [latencyMs] how long it takes (virtual time under runTest), [failure] what it throws.
 * Every other call records itself in [calls] and does nothing.
 */
internal class FakeViewProvider(
    kinds: Set<ViewKind> = ViewKind.entries.toSet(),
) : CloudProvider {
    val viewCalls = CopyOnWriteArrayList<Pair<ViewKind, Int>>()
    val calls = CopyOnWriteArrayList<String>()
    val answers = ConcurrentHashMap<ViewKind, CapabilityResult<List<ViewRef>>>()

    @Volatile
    var latencyMs: Long = 0

    @Volatile
    var failure: Exception? = null

    override val id = "fake-views"
    override val displayName = "fake-views"
    override var isAuthenticated = true

    private val declared: Set<Capability> = kinds.map { Capability.View(it) }.toSet()

    override fun capabilities(): Set<Capability> = declared

    fun serve(
        kind: ViewKind,
        vararg refs: ViewRef,
    ) {
        answers[kind] = CapabilityResult.Success(refs.toList())
    }

    // True: a list answers at most the limit asked for, as a real provider does; false: the whole scripted list.
    @Volatile
    var honoursLimit: Boolean = false

    override suspend fun listView(
        kind: ViewKind,
        limit: Int,
    ): CapabilityResult<List<ViewRef>> {
        viewCalls += kind to limit
        if (latencyMs > 0) delay(latencyMs)
        failure?.let { throw it }
        val answer = answers[kind] ?: return CapabilityResult.Unsupported(Capability.View(kind), "fake: no $kind view")
        if (honoursLimit && answer is CapabilityResult.Success) return CapabilityResult.Success(answer.value.take(limit))
        return answer
    }

    override suspend fun authenticate() {}

    private fun item(path: String) = CloudItem("id-$path", path.substringAfterLast('/'), path, 0, false, null, null, null, null)

    override suspend fun listChildren(path: String): List<CloudItem> = emptyList<CloudItem>().also { calls += "listChildren $path" }

    override suspend fun getMetadata(path: String): CloudItem = item(path).also { calls += "getMetadata $path" }

    override suspend fun download(
        remotePath: String,
        destination: Path,
    ): Long = 0L.also { calls += "download $remotePath" }

    override suspend fun downloadById(
        remoteId: String,
        remotePath: String,
        destination: Path,
    ): Long = 0L.also { calls += "downloadById $remoteId $remotePath" }

    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        existingRemoteId: String?,
        ifMatchETag: String?,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem = item(remotePath).also { calls += "upload $remotePath $existingRemoteId" }

    override suspend fun delete(
        remotePath: String,
        ifMatchETag: String?,
    ) {
        calls += "delete $remotePath"
    }

    override suspend fun deleteById(
        remoteId: String,
        remotePath: String,
    ) {
        calls += "deleteById $remoteId $remotePath"
    }

    override suspend fun createFolder(path: String): CloudItem = item(path).also { calls += "createFolder $path" }

    override suspend fun move(
        fromPath: String,
        toPath: String,
    ): CloudItem = item(toPath).also { calls += "move $fromPath $toPath" }

    override suspend fun moveById(
        remoteId: String,
        fromPath: String,
        toPath: String,
    ): CloudItem = item(toPath).also { calls += "moveById $remoteId $fromPath $toPath" }

    override suspend fun delta(
        cursor: String?,
        onPageProgress: ((itemsSoFar: Int) -> Unit)?,
        scanContext: ScanContext?,
    ): DeltaPage = DeltaPage(emptyList(), "c", false)

    override suspend fun quota(): QuotaInfo = error("not used")
}
