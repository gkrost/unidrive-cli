package org.krost.unidrive.cli

import org.krost.unidrive.engine.DuplicateNames
import org.krost.unidrive.sync.PathNormalizer
import org.krost.unidrive.sync.model.SyncEntry
import java.time.Instant

/**
 * The duplicate sets of a profile (archive#403): two or more live remote items of one name in one folder, which the
 * engine keeps side by side, the canonical holder at the name and every other twin under `name (dup-xxxxxxxx).ext`
 * ([DuplicateNames]). Found from the alive rows of state.db alone; nothing here calls a provider or writes a row.
 */
internal object DuplicateSets {
    /** One twin: [canonical] for the row at the name itself, false for a decorated one. */
    data class Member(
        val id: String,
        val localPath: String,
        val size: Long,
        val modified: Instant?,
        val isFolder: Boolean,
        val canonical: Boolean,
        val hash: String?,
        val hashIsContent: Boolean,
        val contentId: String?,
    )

    enum class Content(
        val token: String,
    ) {
        SAME("same"),
        DIFFERENT("different"),
        UNKNOWN("unknown"),
    }

    /** A duplicate set: the canonical cloud [path] and its members, the canonical holder first. */
    data class Group(
        val path: String,
        val members: List<Member>,
    ) {
        val kind: String
            get() =
                when {
                    members.all { it.isFolder } -> "folder"
                    members.none { it.isFolder } -> "file"
                    else -> "mixed"
                }

        val content: Content get() = contentOf(members)
    }

    /**
     * The duplicate sets among [rows]. A set is the rows of one local folder whose cloud name is the same and whose local
     * name is that name or a twin decoration of it, at least one decorated. Rows below a twin folder are not a set of
     * their own: they lie in another local folder than the rows below the canonical one. Rows without a cloud id (an
     * upload not done yet) take no part.
     */
    fun find(rows: List<SyncEntry>): List<Group> =
        rows
            .mapNotNull { row -> memberOrNull(row)?.let { member -> memberKey(row) to (row to member) } }
            .groupBy({ it.first }, { it.second })
            .values
            .filter { set -> set.size >= 2 && set.any { (_, member) -> !member.canonical } }
            .map { set ->
                val canonicalRemote = PathNormalizer.nfc(set.first().first.let { it.remotePath ?: it.path })
                Group(canonicalRemote, set.map { it.second }.sortedWith(compareBy({ !it.canonical }, { it.id })))
            }.sortedBy { it.path }

    /** (local parent, cloud name): the members of one set share it. */
    private fun memberKey(row: SyncEntry): Pair<String, String> = row.path.substringBeforeLast('/', "") to cloudName(row)

    private fun cloudName(row: SyncEntry): String = PathNormalizer.nfc((row.remotePath ?: row.path).substringAfterLast('/'))

    /** The row as a member of a set, or null for a row that cannot be a twin: no cloud id yet, or another local name. */
    private fun memberOrNull(row: SyncEntry): Member? {
        val id = row.remoteId?.takeUnless { it.startsWith("local:") } ?: return null
        val name = cloudName(row)
        val local = row.path.substringAfterLast('/')
        if (local != name && !DuplicateNames.isDecorationOf(local, name)) return null
        return Member(
            id = id,
            localPath = row.path,
            size = row.remoteSize,
            modified = row.remoteModified,
            isFolder = row.isFolder,
            canonical = local == name,
            hash = row.remoteHash,
            // A row with a write token of its own keeps the content hash in remoteHash; without one, remoteHash is the
            // provider's version token (Internxt), which differs for every item whatever the bytes are.
            hashIsContent = row.remoteVersion != null,
            contentId = row.remoteContentId,
        )
    }

    /**
     * Same only when proven: one stored object, or one content hash, at one size. Different when the sizes differ or two
     * content hashes do. Everything else (folders, version tokens, two stored objects that may hold the same bytes) is
     * unknown, never guessed.
     */
    private fun contentOf(members: List<Member>): Content {
        if (members.any { it.isFolder }) return Content.UNKNOWN
        if (members.map { it.size }.distinct().size > 1) return Content.DIFFERENT
        val contentIds = members.map { it.contentId }
        if (contentIds.all { it != null } && contentIds.distinct().size == 1) return Content.SAME
        if (members.all { it.hashIsContent && it.hash != null }) {
            return if (members.map { it.hash }.distinct().size == 1) Content.SAME else Content.DIFFERENT
        }
        return Content.UNKNOWN
    }
}

/** Which member of a duplicate set stays: `newest`, `largest`, or `id:<remote id>`. */
internal sealed interface KeepRule {
    val token: String

    data object Newest : KeepRule {
        override val token = "newest"
    }

    data object Largest : KeepRule {
        override val token = "largest"
    }

    data class ById(
        val id: String,
    ) : KeepRule {
        override val token get() = "id:$id"
    }

    companion object {
        /** The rule [text] names, or an `invalid_keep` refusal. */
        fun parse(text: String): KeepRule =
            when {
                text == "newest" -> Newest
                text == "largest" -> Largest
                text.startsWith("id:") && text.length > "id:".length -> ById(text.removePrefix("id:"))
                else -> throw LifecycleError("invalid_keep", "--keep takes newest, largest or id:<remote id>, not '$text'")
            }
    }
}

/**
 * What would happen to one duplicate set. [action] is `none` (no keep rule, or an `id:` rule for another set), `trash`
 * ([keep] stays, every one of [trash] would go to the provider's trash by its id), or `skip` with a [reason]: `folder`
 * (a folder twin holds a tree of its own; this command resolves file twins only), `tie` (the rule names no single
 * member) or `modified_unknown` (a member has no modification time to compare).
 */
internal class DedupePlan(
    val group: DuplicateSets.Group,
    val action: String,
    val keep: DuplicateSets.Member? = null,
    val trash: List<DuplicateSets.Member> = emptyList(),
    val reason: String? = null,
) {
    companion object {
        fun of(
            group: DuplicateSets.Group,
            rule: KeepRule?,
        ): DedupePlan {
            if (rule == null) return DedupePlan(group, "none")
            if (group.kind != "file") return DedupePlan(group, "skip", reason = "folder")
            val members = group.members
            val keeper: DuplicateSets.Member =
                when (rule) {
                    is KeepRule.ById -> members.firstOrNull { it.id == rule.id } ?: return DedupePlan(group, "none")
                    KeepRule.Largest -> single(members, members.maxOf { it.size }) { it.size } ?: return skip(group, "tie")
                    KeepRule.Newest -> {
                        val times = members.mapNotNull { it.modified }
                        if (times.size < members.size) return skip(group, "modified_unknown")
                        single(members, times.max()) { it.modified } ?: return skip(group, "tie")
                    }
                }
            return DedupePlan(group, "trash", keeper, members.filter { it !== keeper })
        }

        private fun skip(
            group: DuplicateSets.Group,
            reason: String,
        ) = DedupePlan(group, "skip", reason = reason)

        private fun <T> single(
            members: List<DuplicateSets.Member>,
            best: T,
            value: (DuplicateSets.Member) -> T,
        ): DuplicateSets.Member? = members.filter { value(it) == best }.singleOrNull()
    }
}
