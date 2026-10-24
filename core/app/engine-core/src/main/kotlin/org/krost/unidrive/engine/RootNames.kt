package org.krost.unidrive.engine

import org.krost.unidrive.CaseSensitivity
import org.krost.unidrive.io.WindowsDeviceNames
import org.krost.unidrive.sync.PathNormalizer
import java.nio.file.Path
import java.util.Locale

/**
 * How two names are told apart: the one key function the collision rules of the engine compare names by. The gather
 * ([RemoteGather]) keys its remote paths with it, the virtual folders ([TrashNamespace], [ViewNamespaces]) and the
 * names inside a view ([ViewNames]) key their names with it, so a change to what counts as the same name is made here
 * and every one of them follows.
 *
 * Names reach it already in NFC (the ingestion boundaries and state.db see to that); [foldCase] is true where the
 * provider or the local file system folds case ([foldsCase]), so that case-only twins are one name. The mirror's planner
 * (`Reconciler`) compares by it too.
 */
object NameKey {
    /**
     * Whether the host's usual file system folds case (Windows, macOS): the default of the local half of [foldsCase] for
     * the callers that are not told what the sync root's file system does.
     */
    val platformFoldsCase: Boolean = CaseSensitivity.host.foldsCase

    /**
     * Whether the file system that holds [root] folds case, asked of that file system ([CaseSensitivity.ofDirectory]);
     * [platformFoldsCase] when it cannot tell.
     */
    fun localFoldsCase(root: Path): Boolean = CaseSensitivity.ofDirectory(root)?.foldsCase ?: platformFoldsCase

    /**
     * The case policy of one profile: two names that differ only in case are one name when the provider folds case (a
     * second such name would land on the first at the provider) or when the local file system does (both would be one
     * file on disk). Only a case-sensitive provider on a case-sensitive file system keeps them apart.
     */
    fun foldsCase(
        provider: CaseSensitivity,
        localFoldsCase: Boolean,
    ): Boolean = provider.foldsCase || localFoldsCase

    fun of(
        name: String,
        foldCase: Boolean,
    ): String = if (foldCase) name.lowercase(Locale.ROOT) else name
}

/**
 * The naming rules of a virtual top-level folder, shared by the trash namespace and the views: which text can be the
 * name of such a folder, and which name the folder takes when a live top-level item already has the one it was given.
 */
internal object RootNames {
    private const val MAX_NAME_CHARS = 100
    private val FORBIDDEN = setOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')

    /**
     * Whether [name] can be the name of the folder: one path segment, as it is written on every platform (no
     * separator, none of the characters Windows refuses, no control character, no `.` or `..`, no trailing
     * dot, not a Windows device name such as `NUL` or `COM1`, #1090), not blank once trimmed, not longer than
     * [MAX_NAME_CHARS].
     */
    fun isUsable(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty() || n.length > MAX_NAME_CHARS) return false
        if (n == "." || n == ".." || n.endsWith('.')) return false
        if (WindowsDeviceNames.isReserved(n)) return false
        return n.none { it in FORBIDDEN || it < ' ' || it == '\u007f' }
    }

    /** [name] as the folder carries it (trimmed, in NFC), or null when it is no usable folder name. */
    fun usable(name: String): String? = if (isUsable(name)) PathNormalizer.nfc(name.trim()) else null

    /**
     * The first of `<name>`, `<name> (2)`, `<name> (3)`, ... whose [NameKey] is not in [taken]: a live top-level item
     * keeps the name it has, the folder moves aside.
     */
    fun firstFree(
        name: String,
        taken: Set<String>,
        foldCase: Boolean,
    ): String {
        var candidate = name
        var n = 1
        while (NameKey.of(candidate, foldCase) in taken) {
            n++
            candidate = "$name ($n)"
        }
        return candidate
    }
}
