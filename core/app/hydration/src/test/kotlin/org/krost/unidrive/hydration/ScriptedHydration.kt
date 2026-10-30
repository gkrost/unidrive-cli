package org.krost.unidrive.hydration

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.nio.file.Path
import java.nio.file.Paths

internal data class HydrationCall(val verb: String, val args: List<Any?>)

/** Records what the handler hands over and answers what the test scripted (every verb answers Ok by default). */
internal class ScriptedHydration : Hydration {
    val calls = mutableListOf<HydrationCall>()
    val cache: Path = Paths.get("/cache/unidrive/hydration/docs/report.txt")
    var open: OpenResult = OpenResult.Ok(cache)
    var create: CreateResult = CreateResult.Ok(cache, "h-2")
    var list: ListResult = ListResult.Ok(emptyList())
    var matches: MatchesLocalResult = MatchesLocalResult.Same("quickxor")
    var viewPage: ViewPageResult = ViewPageResult.UnknownView
    var cleared = false
    var cancelled = false

    override suspend fun openForRead(connectionId: String, handleId: String, path: String): OpenResult {
        calls += HydrationCall("open_read", listOf(connectionId, handleId, path))
        return open
    }

    override suspend fun openForWrite(connectionId: String, handleId: String, path: String, cachePath: Path, baseEtag: String?): OpenResult {
        calls += HydrationCall("open_write", listOf(connectionId, handleId, path, cachePath.toString(), baseEtag))
        return open
    }

    override suspend fun closeHandle(connectionId: String, handleId: String) {
        calls += HydrationCall("close_handle", listOf(connectionId, handleId))
    }

    override suspend fun cancelUpload(path: String): Boolean {
        calls += HydrationCall("cancel", listOf(path))
        return cancelled
    }

    override suspend fun hydrate(path: String): HydrateResult {
        calls += HydrationCall("hydrate", listOf(path))
        return HydrateResult.Ok
    }

    override suspend fun dehydrate(path: String): DehydrateResult {
        calls += HydrationCall("dehydrate", listOf(path))
        return DehydrateResult.Ok
    }

    override suspend fun lastSynced(path: String): LastSyncedResult {
        calls += HydrationCall("last_synced", listOf(path))
        return LastSyncedResult.Ok(1234567890123L)
    }

    override suspend fun list(prefix: String): ListResult {
        calls += HydrationCall("list", listOf(prefix))
        return list
    }

    override suspend fun matchesLocal(path: String, localPath: Path): MatchesLocalResult {
        calls += HydrationCall("matches_local", listOf(path, localPath.toString()))
        return matches
    }

    override suspend fun refuseName(path: String, sibling: String?): RefuseNameResult {
        calls += HydrationCall("refuse_name", listOf(path, sibling))
        return RefuseNameResult.Ok
    }

    override suspend fun clearNameRefusal(path: String): Boolean {
        calls += HydrationCall("clear_name_refusal", listOf(path))
        return cleared
    }

    override suspend fun viewPage(kind: String, offset: Int, limit: Int): ViewPageResult {
        calls += HydrationCall("view_page", listOf(kind, offset, limit))
        return viewPage
    }

    override suspend fun mkdir(path: String): MkdirResult {
        calls += HydrationCall("mkdir", listOf(path))
        return MkdirResult.Ok
    }

    override suspend fun unlink(path: String, permanent: Boolean): UnlinkResult {
        calls += HydrationCall("unlink", listOf(path, permanent))
        return UnlinkResult.Ok
    }

    override suspend fun rmdir(path: String, recursive: Boolean, permanent: Boolean): RmdirResult {
        calls += HydrationCall("rmdir", listOf(path, recursive, permanent))
        return RmdirResult.Ok
    }

    override suspend fun create(connectionId: String, handleId: String, path: String): CreateResult {
        calls += HydrationCall("create", listOf(connectionId, handleId, path))
        return create
    }

    override suspend fun openWriteBegin(connectionId: String, path: String, handleId: String?): OpenResult {
        calls += HydrationCall("open_write_begin", listOf(connectionId, path, handleId))
        return open
    }

    override suspend fun rename(oldPath: String, newPath: String, replace: Boolean): RenameResult {
        calls += HydrationCall("rename", listOf(oldPath, newPath, replace))
        return RenameResult.Ok
    }

    override val events: Flow<HydrationEvent> = emptyFlow()

    override fun onConnectionClosed(connectionId: String) {}
}
