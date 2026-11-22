package org.krost.unidrive.onedrive

import kotlinx.serialization.Serializable
import org.krost.unidrive.io.ResumeSessionStore
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * #69: the empty items this profile's own upload sessions reserved, by remote path and item id.
 *
 * On OneDrive Personal, `createUploadSession` with `conflictBehavior=fail` creates the target item at once: an empty
 * file under the real name, visible in a listing before any chunk is sent, that the final chunk fills in place (same id)
 * and that a `DELETE` of the session removes (measured 2026-11-22 on a test account). When the session is lost without
 * that `DELETE` (the cancel failed, the store entry is gone), the empty item stays. The next create of the same file then
 * meets it as a name conflict. Only an item whose id is recorded here, and that is still empty, is this profile's own
 * unfinished target and may be completed into; any other empty file at the name is someone's, and is kept.
 *
 * A reservation outlives its session (the item can stay after the session expires), so it is kept in its own file and
 * dropped when an upload to the path completes, or after [MAX_AGE]. A stale entry is harmless: an id is never reused,
 * and the item must still be empty.
 */
internal class UploadReservationStore(
    storeDir: Path,
    private val now: () -> Instant = Instant::now,
) {
    @Serializable
    data class Reservation(
        val itemId: String,
        val recordedAt: String, // ISO-8601 Instant
    )

    private val store = ResumeSessionStore(storeDir, Reservation.serializer(), fileName = FILE_NAME)

    /** Records that this profile's upload session reserved the empty item [itemId] at [remotePath]. */
    @Synchronized
    fun record(
        remotePath: String,
        itemId: String,
    ) {
        val cutoff = now().minus(MAX_AGE)
        val map = store.load().filterValues { Instant.parse(it.recordedAt).isAfter(cutoff) }.toMutableMap()
        map[remotePath] = Reservation(itemId, now().toString())
        store.replace(map)
    }

    /** Whether [itemId] is the item an upload session of this profile reserved at [remotePath]. */
    @Synchronized
    fun holds(
        remotePath: String,
        itemId: String,
    ): Boolean {
        val reservation = store.load()[remotePath] ?: return false
        return reservation.itemId == itemId && Instant.parse(reservation.recordedAt).isAfter(now().minus(MAX_AGE))
    }

    /** Drops the reservation of [remotePath]: an upload to it completed. */
    @Synchronized
    fun release(remotePath: String) {
        val map = store.load().toMutableMap()
        if (map.remove(remotePath) != null) store.replace(map)
    }

    companion object {
        const val FILE_NAME: String = "upload_reservations.json"
        val MAX_AGE: Duration = Duration.ofDays(30)
    }
}

private val reservationLog = org.slf4j.LoggerFactory.getLogger(UploadReservationStore::class.java)

// A `fail` session was just created for a new name: the item at the path is the empty one it reserved (no other upload can
// create that name while the session holds it). Its id is recorded. Best effort: a failed lookup records nothing, and a
// later collision with the item then keeps both, as for any empty file that is not known to be this profile's.
internal suspend fun GraphApiService.recordReservedItem(remotePath: String) {
    val item =
        try {
            getItemByPath(remotePath)
        } catch (e: GraphApiException) {
            return reservationLookupFailed(remotePath, e)
        } catch (e: java.io.IOException) {
            return reservationLookupFailed(remotePath, e)
        } catch (e: kotlinx.serialization.SerializationException) {
            return reservationLookupFailed(remotePath, e)
        }
    if (item.isFolder || item.file == null || item.size != 0L) return
    uploadReservations.record(remotePath, item.id)
    reservationLog.debug("Upload session of {} reserved the empty item {}", remotePath, item.id)
}

private fun reservationLookupFailed(
    remotePath: String,
    e: Exception,
) = reservationLog.debug("The item reserved by the upload session of {} could not be looked up ({})", remotePath, e.message)
