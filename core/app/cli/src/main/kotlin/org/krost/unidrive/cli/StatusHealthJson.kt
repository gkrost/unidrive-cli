package org.krost.unidrive.cli

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.krost.unidrive.RemoteChangeChannelStatus
import org.krost.unidrive.engine.ViewNamespaces
import org.krost.unidrive.engine.ViewStatus
import org.krost.unidrive.hydration.HydrationImpl
import org.krost.unidrive.hydration.PauseGate
import org.krost.unidrive.sync.ignore.HeldBackDir

/**
 * The health objects of the `daemon.status` reply (archive#658; docs/dev/specs/unidrive-daemon-design.md §4.3).
 * A value the daemon does not have is `null` here, never 0 and never a "healthy" default.
 */

/**
 * `uploads`: see [HydrationImpl.UploadHealth] for what each count includes and leaves out. `as_of_ms` (archive#747) is the epoch-milliseconds stamp of the reading: the last good one is served while state.db is busy, so a front-end tells fresh from frozen by the stamp not advancing. `blocked` is present only
 * while there is something blocked by name: absent means none, so a reply with nothing blocked is unchanged.
 */
internal fun HydrationImpl.UploadHealth.toJson(): JsonObject =
    buildJsonObject {
        put("pending", pending)
        put("in_flight", inFlight)
        put("failed", failed)
        put("oldest_pending_age_ms", oldestPendingAgeMs)
        // archive#747: when the counts were taken. The last good reading is served when state.db is busy, so a
        // front-end tells fresh from frozen by the stamp not advancing. Absent when never read.
        put("as_of_ms", asOfMs)
        // archive#1095: uploads in a retry backoff, and when the earliest next attempt starts (null while none waits).
        put("retrying", retrying)
        put("next_try_at_ms", nextTryAtMs)
        if (blocked.count > 0) put("blocked", blocked.toJson())
        if (running.count > 0) put("running", running.toJson())
    }

/** The `daemon.status` capability that says `uploads` names the uploads under way (`uploads.running`, archive#1200). */
internal const val RUNNING_UPLOADS_CAPABILITY = "uploads.running"

/** The `daemon.status` capability that says the daemon answers `hydration.matches_local` (archive#1219). */
internal const val MATCHES_LOCAL_CAPABILITY = "hydration.matches_local"

/** The `daemon.status` capability that says the daemon answers `hydration.refuse_name` (gkrost/unidrive-cli#24). */
internal const val REFUSE_NAME_CAPABILITY = "hydration.refuse_name"

/** The `daemon.status` capability that says `hydration.list` reads a folder from the cloud while the first scan has not brought it in (gkrost/unidrive-cli#27). */
internal const val LIST_LIVE_CAPABILITY = "hydration.list_live"

/**
 * The `daemon.status` capability that says a `hydration.list` file entry carries `content_id` wherever the row names its bytes
 * (gkrost/unidrive-cli#171), so a client tells "no id for this row" from "an engine that sends none".
 */
internal const val LIST_CONTENT_ID_CAPABILITY = "hydration.list_content_id"

/** The `daemon.status` capability that says the daemon answers `hydration.stat` (gkrost/unidrive-cli#415). */
internal const val HYDRATION_STAT_CAPABILITY = "hydration.stat"

/**
 * The `daemon.status` capability that says `hydration.cancel` with a `handle_id` ends the `open_read` of that handle and no upload,
 * and that a cancel or a closing connection stops a download nobody waits for (gkrost/unidrive-cli#23).
 */
internal const val CANCEL_READ_CAPABILITY = "hydration.cancel_read"

/**
 * `uploads.running` (archive#1200): the uploads holding a transfer permit right now, present only while there is one. `count` is all
 * of them, `items` the first [HydrationImpl.RUNNING_ITEMS_CAP] by start time, `more` the rest. Each item: `path`,
 * `started_at_ms` (epoch ms the attempt began), `bytes_done` (as the provider last reported) and `bytes_total` (null while the
 * provider has not said). A retry starts a new attempt, so `started_at_ms` is the current attempt's. Paths are user data:
 * they appear here and nowhere in the log.
 */
internal fun HydrationImpl.RunningUploads.toJson(): JsonObject =
    buildJsonObject {
        put("count", count)
        put("more", more)
        putJsonArray("items") {
            for (item in items) {
                add(
                    buildJsonObject {
                        put("path", item.path)
                        put("started_at_ms", item.startedAtMs)
                        put("bytes_done", item.bytesDone)
                        put("bytes_total", item.bytesTotal)
                    },
                )
            }
        }
    }

/**
 * `uploads.blocked`: the entries the drive refuses by their name. `items` is capped (see [HydrationImpl.BLOCKED_ITEMS_CAP]);
 * `more` counts the rest. A `similar_name` item carries `sibling` (the name it conflicts with, null when unknown); a
 * `blocked_by_parent` item carries `parent` (the blocked folder it waits for).
 */
internal fun HydrationImpl.BlockedUploads.toJson(): JsonObject =
    buildJsonObject {
        put("count", count)
        put("more", more)
        putJsonArray("items") {
            for (item in items) {
                add(
                    buildJsonObject {
                        put("path", item.path)
                        put("reason", item.reason)
                        if (item.parent != null) put("parent", item.parent) else put("sibling", item.sibling)
                    },
                )
            }
        }
    }

/** `cache`: `bytes` is null until the cache directory has been measured, `budget_bytes` null without a budget. */
internal fun HydrationImpl.CacheHealth.toJson(): JsonObject =
    buildJsonObject {
        put("bytes", bytes)
        put("budget_bytes", budgetBytes)
    }

/** The `daemon.status` capability that says the reply carries [notificationsStatusJson]. */
internal const val NOTIFICATIONS_STATUS_CAPABILITY = "notifications.status"

/**
 * `notifications` in `daemon.status` (archive#111): the provider's remote-change push channel. A provider that reports none
 * ([org.krost.unidrive.CloudProvider.remoteChangeChannelStatus] null) is `unsupported`. Never the endpoint: a credential.
 */
internal fun notificationsStatusJson(status: RemoteChangeChannelStatus?): JsonObject =
    buildJsonObject {
        put("state", status?.state?.wireName ?: "unsupported")
        put("last_hint_at_ms", status?.lastHintAtMs)
        put("hints", status?.hints ?: 0L)
        put("expires_at_ms", status?.expiresAtMs)
        put("rotations", status?.rotations ?: 0L)
    }

/** `provider_health`: `last_contact_ms` is null until the provider has answered once since the daemon started. `strict_names` is present only for a provider that runs a strict-name mode (Internxt's `internxt_strict_names`). `skipped` is present only for a provider that reports skipped listing entries and has skipped some (gkrost/unidrive-cli#405). */
internal fun providerHealthJson(
    lastContactMs: Long?,
    strictNames: JsonObject? = null,
    skipped: JsonObject? = null,
): JsonObject =
    buildJsonObject {
        put("last_contact_ms", lastContactMs)
        if (strictNames != null) put("strict_names", strictNames)
        if (skipped != null) put("skipped", skipped)
    }

/**
 * `provider_health.skipped` (gkrost/unidrive-cli#405): the listing entries the provider deliberately leaves out —
 * OneDrive's Personal Vault stub and its package entries — so an operator sees that the drive carries something
 * this engine does not sync, without reading the log. Present only while the counts are not both zero: the
 * provider reports them only once it has listed, and a restart starts at 0.
 */
internal fun skippedEntriesJson(
    vault: Long,
    packageEntries: Long,
): JsonObject =
    buildJsonObject {
        put("vault", vault)
        put("package", packageEntries)
    }

/**
 * `provider_health.strict_names` (issue 29): the provider's strict-name mode (`on`) and how many names it currently
 * blocks (`blocked`) — the writes it refused and the cloud items the gather quarantined, the rows behind
 * `uploads.blocked` whose reason is `name_incompatible`. Zero is a reading, not a default: the mode is on and
 * nothing is blocked now.
 */
internal fun strictNamesStatusJson(
    on: Boolean,
    blocked: Int,
): JsonObject =
    buildJsonObject {
        put("on", on)
        put("blocked", blocked)
    }

/**
 * `remote_incomplete` in `daemon.status`: present only while the daemon remembers a cloud object whose stored copy is
 * of a different size than the drive reports (reads of it are refused with `remote_incomplete`). `count` is how many it
 * remembers in this run, without names; an object is forgotten when a read finds it whole or its cloud version
 * (size, hash, modification time) changes, so after a restart the count starts at 0 until a read meets one again.
 */
internal fun remoteIncompleteJson(count: Int): JsonObject =
    buildJsonObject {
        put("count", count)
    }

/**
 * `paused` (archive#778): present only while the daemon is paused. `since_ms` is epoch ms of the first request of the
 * pause; `auto` says whether a rule of the client (true) or a person (false) paused; `until_ms` (epoch ms at which
 * the daemon resumes by itself) is present only for a timed pause. The same object is the reply of
 * `daemon.pause` and the body of the `paused` event.
 */
internal fun PauseGate.State.toJson(): JsonObject =
    buildJsonObject {
        put("reason", reason)
        put("since_ms", sinceMs)
        put("auto", auto)
        untilMs?.let { put("until_ms", it) }
    }

/**
 * archive#813: the credential rejection `credential_health` still has to report: [authRejectedAtMs] unless a sign-in through
 * the daemon loaded new credentials after it ([signedInAtMs]); a rejection of the replaced credentials is history.
 */
internal fun rejectionAfterSignIn(
    authRejectedAtMs: Long?,
    signedInAtMs: Long?,
): Long? = authRejectedAtMs?.takeIf { signedInAtMs == null || it > signedInAtMs }

/**
 * When the provider last answered (epoch ms): the later of the last completed enumeration and the last
 * transfer that succeeded through the daemon. Failures move neither, so an outage keeps the last success.
 */
internal fun lastProviderContactMs(
    enumerationLastSuccessAtMs: Long?,
    lastTransferReachedAtMs: Long?,
): Long? =
    when {
        enumerationLastSuccessAtMs == null -> lastTransferReachedAtMs
        lastTransferReachedAtMs == null -> enumerationLastSuccessAtMs
        else -> maxOf(enumerationLastSuccessAtMs, lastTransferReachedAtMs)
    }

/**
 * `credential_health` (archive#586): `state` is one of `ok`, `expiring`, `expired`, `missing`, `unknown`. `expires_in_ms` is the
 * time left at this reply (0 for `expired`), null when no expiry is known; `checked_at_ms` is when the daemon last
 * checked, or saw the provider reject the credentials, null while it has not checked yet.
 */
internal fun CredentialHealthMonitor.Reading.toJson(nowMs: Long): JsonObject =
    buildJsonObject {
        put("state", state.wire)
        put("expires_in_ms", expiresInMs(nowMs))
        put("checked_at_ms", checkedAtMs)
    }

/**
 * `trash` in `daemon.status`: present only while something is in the provider's trash. [rootPath] is where the read-only
 * folder that shows it is now (`/Papierkorb`), `folder_name` its name on disk (the configured name, suffixed with ` (2)`, ...
 * when a live top-level item already has it) and `configured_name` the name the profile asks for (`trash_folder_name`, or the
 * default), so a client sees at once whether the two differ.
 */
internal fun trashStatusJson(
    rootPath: String,
    configuredName: String,
): JsonObject =
    buildJsonObject {
        put("root_path", rootPath)
        put("folder_name", rootPath.substringAfterLast('/'))
        put("configured_name", configuredName)
    }

/**
 * `backups` in `daemon.status` (docs/dev/specs/unidrive-daemon-design.md section 4.3): present only while the read-only backups
 * folder exists. As `trash`: `root_path`, `folder_name` (suffixed with ` (2)`, ... when a live top-level item or the trash
 * holds the configured name) and `configured_name`; [devices] is how many backup devices are listed in it.
 */
internal fun backupsStatusJson(
    rootPath: String,
    configuredName: String,
    devices: Int,
): JsonObject =
    buildJsonObject {
        put("root_path", rootPath)
        put("folder_name", rootPath.substringAfterLast('/'))
        put("configured_name", configuredName)
        put("devices", devices)
    }

/**
 * `views` in `daemon.status`: present only while the profile shows at least one read-only view (Recents, ...), one object
 * per view in kind order. `root_path` is where its folder is now, `folder_name` its name on disk (suffixed with ` (2)`, ...
 * when a live top-level item already has the configured name), `configured_name` the name the profile asks for. `entries` and
 * `skipped` are as of the view's last listing (the entries it showed, the references that had no row to show); nothing is
 * counted before the first listing. `as_of_ms` is when the list was fetched, null while it never was. `state` is `ok`, `stale`
 * (the last refresh failed, the list before it is served) or `unavailable` (no list: never fetched, or the provider has no such
 * view). `truncated` is true while the list was cut (at the view's limit or at a cap of the provider): there are more items
 * than the view shows. Nothing here asks the provider.
 */
internal fun viewsStatusJson(views: List<ViewStatus>): JsonArray =
    buildJsonArray {
        for (v in views) {
            add(
                buildJsonObject {
                    put("kind", ViewNamespaces.wireName(v.kind))
                    put("root_path", v.rootPath)
                    put("folder_name", v.folderName)
                    put("configured_name", v.configuredName)
                    put("entries", v.entries)
                    put("skipped", v.skipped)
                    put("as_of_ms", v.asOfMs)
                    put("state", v.state.wire)
                    put("truncated", v.truncated)
                },
            )
        }
    }

/**
 * `ignore` in `daemon.status` (archive#660): the folders whose uploads are held back because a rule file in them cannot be
 * read (fail closed). `held_back.count` counts the folders found so far: a folder's rules are read when something below
 * it is written or asked about, so a folder nothing was written to is not known yet. `items` is capped
 * ([HELD_BACK_ITEMS_CAP]) and `more` counts the rest; each item names the folder, the rule file and why it cannot be read.
 */
internal fun ignoreHealthJson(heldBack: List<HeldBackDir>): JsonObject =
    buildJsonObject {
        put(
            "held_back",
            buildJsonObject {
                put("count", heldBack.size)
                put("more", (heldBack.size - HELD_BACK_ITEMS_CAP).coerceAtLeast(0))
                putJsonArray("items") {
                    for (h in heldBack.take(HELD_BACK_ITEMS_CAP)) {
                        add(
                            buildJsonObject {
                                put("dir", h.dir)
                                put("file", h.file)
                                put("reason", h.reason)
                            },
                        )
                    }
                }
            },
        )
    }

internal const val HELD_BACK_ITEMS_CAP = 20

/**
 * `ipc` (gkrost/unidrive-cli#460): `requests` holds, per verb, how many requests its handler was given since the daemon
 * started (the [org.krost.unidrive.sync.IpcServer.requestCounts] reading). A verb not asked yet is absent (0); a request
 * the authentication gate answered or refused, or one for an unknown verb, is not counted. Names verbs only, never a path.
 */
internal fun ipcStatusJson(requestCounts: Map<String, Long>): JsonObject =
    buildJsonObject {
        put("requests", buildJsonObject { requestCounts.forEach { (verb, n) -> put(verb, n) } })
    }
