package org.krost.unidrive.internxt

// The items a full assembly leaves out of the drive, by why (InternxtProvider.FullAssembly).

// The items of a pass that sit below a folder which is gone for good: their remote ids, how many of them are files
// and folders, and the distinct gone folders they hang below.
internal class UnreachableItems {
    val ids = HashSet<String>()
    val goneFolders = HashSet<String>()
    var files = 0
    var folders = 0
    val count: Int get() = files + folders

    fun add(
        uuid: String,
        goneFolder: String,
        isFolder: Boolean,
    ) {
        if (ids.add(uuid)) {
            if (isFolder) folders++ else files++
        }
        goneFolders.add(goneFolder)
    }
}

// The items of a pass that hang below a folder outside the drive root (a tree of its own, such as a device backup), each
// with the top-level folder of its tree: listed and in place, only not part of the drive.
internal class OutsideDriveItems {
    private val tops = HashMap<String, String?>()
    val size: Int get() = tops.size

    fun add(
        uuid: String,
        top: String?,
    ) {
        tops[uuid] = top
    }

    /** The ids of those not in [listed] (the backup items a pass hands over), and the top-level folders they hang below. */
    fun without(listed: Set<String>): Pair<Set<String>, Set<String>> {
        if (tops.isEmpty()) return emptySet<String>() to emptySet()
        val ids = tops.keys.filterTo(HashSet()) { it !in listed }
        return ids to ids.mapNotNullTo(HashSet()) { tops[it] }
    }
}
