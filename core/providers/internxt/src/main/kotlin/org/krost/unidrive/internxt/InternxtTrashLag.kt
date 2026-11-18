package org.krost.unidrive.internxt

import kotlinx.coroutines.delay
import org.krost.unidrive.ProviderException
import org.krost.unidrive.internxt.InternxtProvider.Companion.isLive
import org.krost.unidrive.internxt.model.InternxtFolder

/**
 * Live finding E3 (test drive 2, 2026-11-18): Internxt keeps listing a folder this client trashed as live for a while
 * (40 to 160 s measured). The folders trashed or deleted through this provider are remembered for [ttlMs], at most
 * [capacity] of them, so that neither a create's 409 recovery nor a path walk settles on one while the listing lags.
 */
internal class RecentlyTrashedFolders(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val trashedAt = LinkedHashMap<String, Long>()

    /** How long a create held by such a folder waits before each repeat; the last repeat fails typed. */
    @Volatile var createRetryDelaysMs: List<Long> = DEFAULT_CREATE_RETRY_DELAYS_MS

    fun remember(uuid: String) =
        synchronized(trashedAt) {
            trashedAt.remove(uuid)
            trashedAt[uuid] = clock()
            while (trashedAt.size > capacity) trashedAt.remove(trashedAt.keys.first())
        }

    fun contains(uuid: String): Boolean =
        synchronized(trashedAt) {
            val at = trashedAt[uuid] ?: return false
            if (clock() - at <= ttlMs) return true
            trashedAt.remove(uuid)
            false
        }

    companion object {
        const val DEFAULT_TTL_MS: Long = 5 * 60_000L
        const val DEFAULT_CAPACITY: Int = 1_000

        // About two minutes in all, the upper end of the trash lag measured.
        val DEFAULT_CREATE_RETRY_DELAYS_MS: List<Long> = listOf(5_000L, 10_000L, 20_000L, 40_000L, 45_000L)
    }
}

/**
 * A folder create is refused (409) by a folder of that name that this provider trashed itself and that Internxt still lists
 * as live (the trash lag); the repeats of the create did not outlast it. Nothing is wrong with the parent: the same create
 * goes once the trash has landed, so this is retryable and never a parent-gone refusal.
 */
class FolderNameHeldByTrashException(
    message: String,
    cause: Throwable? = null,
) : ProviderException(message, cause, httpStatus = 409)

/**
 * The create of folder [name] below [parentUuid], with the 409 recovery: the one live folder of that name is adopted, two
 * are ambiguous ([ambiguous]). A live folder this provider trashed itself ([RecentlyTrashedFolders]) does not count: when it
 * is the only one, the create waits and is repeated ([RecentlyTrashedFolders.createRetryDelaysMs]), then fails with
 * [FolderNameHeldByTrashException]. A 409 with no live folder of the name is rethrown.
 */
@Suppress("ThrowsCount") // the refusals of the ladder are throws
internal suspend fun createFolderPastTrashLag(
    api: InternxtApiService,
    parentUuid: String,
    name: String,
    encryptedName: String,
    log: org.slf4j.Logger,
    ambiguous: (Int) -> Nothing,
): InternxtFolder {
    var attempt = 0
    while (true) {
        try {
            return api.createFolder(parentUuid, name, encryptedName)
        } catch (e: InternxtApiException) {
            if (e.statusCode != 409) throw e
            // UD-317: the child name is sanitised before matching; a trashed twin is never adopted.
            val named =
                api.getFolderContents(parentUuid).children.filter {
                    it.isLive() && InternxtProvider.sanitizeName(it.plainName ?: it.name ?: "") == name
                }
            val live = named.filterNot { api.trashedFolders.contains(it.uuid) }
            when {
                live.size == 1 -> return live.single()
                live.size > 1 -> ambiguous(live.size)
                named.isEmpty() -> throw e // 409 but no live folder of that name: the original error
            }
            val delays = api.trashedFolders.createRetryDelaysMs
            if (attempt >= delays.size) {
                throw FolderNameHeldByTrashException(
                    "folder '$name' cannot be created yet: the name is held by a folder this client trashed that Internxt " +
                        "still lists (trash lag); it will be retried",
                    e,
                )
            }
            log.info("folder create of '{}' refused by a folder this client trashed (trash lag); repeating in {} ms", name, delays[attempt])
            delay(delays[attempt++])
        }
    }
}
