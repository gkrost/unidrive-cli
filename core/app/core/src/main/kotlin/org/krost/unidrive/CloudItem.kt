package org.krost.unidrive

import java.time.Instant

data class CloudItem(
    val id: String,
    val name: String,
    val path: String,
    val size: Long,
    val isFolder: Boolean,
    val modified: Instant?,
    val created: Instant?,
    val hash: String?,
    val mimeType: String?,
    val deleted: Boolean = false,
    val hydrated: Boolean = true,
    // Parent's provider UUID. Internxt populates from folderUuid/parentUuid so
    // state.db can index alive children of a folder via (parent_uuid, status).
    // Null = drive root (or provider doesn't expose parent identity).
    val parentId: String? = null,
    // archive#183: Graph `@microsoft.graph.removed` with `state="removed"` signals that
    // access was revoked (shared link revoked, item moved out of scope). The item
    // still physically exists on the remote — the local copy MUST be kept — but the
    // DB row should be retired (TRASHED) so it is no longer an unreapable orphan.
    // Distinct from [deleted]=true which means a hard delete and triggers local
    // file removal. [accessRevoked] is only set by OneDriveProvider; all other
    // providers and construction sites leave it at the safe default of false.
    val accessRevoked: Boolean = false,
    // The provider stored the item under a different name than the one asked for, because the drive
    // refuses that name (OneDrive: a name it compares as equal to a sibling's). [path] and [name] are where
    // and as what it landed. The engine records the difference in the row (SyncEntry.remotePath) so the
    // local name stays and later operations address the item under its server name. False for every item a
    // caller did not ask to place under another name, and for a keep-both copy named by the server.
    val nameOverridden: Boolean = false,
    // The item is live, but it sits in the provider's trash: a folder above it was trashed (Internxt trashes a folder
    // and leaves what is in it as it was, so the items below keep reporting `EXISTS`), or it is such a folder itself
    // while something live is below it. [path] is where the provider's own tree has it, the original place; the engine
    // shows these items apart, under the trash namespace (see `TrashNamespace`), and never writes to them. Distinct from
    // [deleted], which says the item is gone: a trashed item is still there, to be read and copied out. False for every
    // provider that does not report it.
    val trashed: Boolean = false,
    // The provider's write token for this version of the item, kept apart from the content [hash]: the value a later
    // conditional write of the item hands back as `ifMatchETag` (OneDrive: the Graph eTag, which changes with every
    // content or metadata change). Null for a provider whose [hash] is its token already (Internxt) or that has none,
    // and for an item whose answer carried none. See [CloudProvider.reportsItemVersions].
    val version: String? = null,
    // The provider refused to replace the item it was asked to update, because its write token no longer matched (a
    // newer version is in the cloud), and stored the bytes as a NEW item beside it instead, under a name the server
    // chose: [id], [path] and [name] are the copy's. The item that was to be replaced is untouched. False for every
    // other answer, a create that kept both included.
    val keptBoth: Boolean = false,
    // gkrost/unidrive-cli#164: the provider's name for the item's bytes, for a provider whose [hash] is no content hash: it
    // changes with the bytes and with nothing else, where Internxt's token also moves with a rename or a move (its modification
    // time). Internxt: the stored object (`fileId`, which a new upload replaces) and the size. Null for a provider whose [hash] is
    // the content already, or that has neither, and for an item whose answer named no stored object.
    val contentId: String? = null,
)
