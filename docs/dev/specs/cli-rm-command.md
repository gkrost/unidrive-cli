# `unidrive rm`: delete a remote file or folder

`unidrive -p <profile> rm [-r] [--permanent] [--yes] [--json] <remotePath>` deletes one remote file, or with `-r` one remote folder
with everything in it, through the provider's own delete. Source: `core/app/cli/.../RmCommand.kt`.

## Which profiles

| Profile | What `rm` does |
|---|---|
| mount, daemon running | The daemon deletes: `hydration.unlink`, and for a folder `hydration.rmdir` with `"recursive":true`, over a full-scope IPC connection. state.db and the mount view drop the item at once; the daemon's guards apply. |
| mount, daemon holds the profile but does not answer | Refused, `daemon_not_answering`. Nothing is deleted behind the daemon's back. |
| mount, no daemon, no state.db yet | The provider's `delete` is called directly, under the profile lock. |
| mount, no daemon, state.db exists | Refused, `daemon_not_running`: a delete that bypasses the daemon would leave its rows, cache copies and pending uploads stale. Start the daemon and run `rm` again. |
| mirror | Refused, `mirror_profile` (exit 78): the next sync would upload the item again from the sync root. Delete the local copy and sync instead. |
| no `mode` | Refused, `mode_missing` (exit 78), like every mode-bound command. |

## What the provider's delete does

| Provider | `deleted_as` | Effect |
|---|---|---|
| Internxt | `trash` | Moved to the Internxt trash. |
| OneDrive | `trash` | Moved to the OneDrive recycle bin. |
| Dropbox | `trash` | Restorable from the deleted files for the plan's retention. |
| localfs | `permanent` | Deleted from the disk (a folder recursively; symlinks are not followed). |
| S3 | `permanent` | The object (a folder: every key below it) is deleted; a bucket with versioning keeps the old versions. |

`--permanent` (archive#1178) deletes for good, bypassing the provider's trash where one exists: Internxt (`DELETE /files/{uuid}`
/ `DELETE /folders` with the folder uuid in the body), OneDrive (`POST .../items/{id}/permanentDelete`), Dropbox (`files/permanently_delete`,
plan-gated: a Basic account's refusal is a failed delete, `delete_failed`); localfs and S3 delete for good anyway, so
their `--permanent` is their delete. Every provider of this repo has the capability (`Capability.PermanentDelete`);
a third-party provider without it is refused with `permanent_delete_unsupported` (the daemon answers
`permanent_unsupported` and the CLI maps it). `--permanent` always needs a confirmation — a real console yes or
`--yes` — for a file and a folder alike; with `-r` there is exactly one question. The daemon route asks its
confirmation BEFORE the unlink type-probe (a file would be gone at once otherwise). The reply's `deleted_as` says
`permanent`.

## Folders and confirmation

- A folder without `-r` is refused (`path_is_folder`) before anything is touched.
- A folder with `-r` needs a yes on an interactive console, or `--yes`. Without a console and without `--yes` (and
  always with `--json`, which never prompts) it is refused with `confirmation_required`.
- The root (`rm /`) is refused (`root_refused`).

## `--json`

Success: `{"ok":true,"path":"/dir","kind":"folder","route":"daemon","deleted_as":"trash"}` (`kind` file/folder,
`route` daemon/provider). A refusal: `{"ok":false,"error":"<token>","message":"..."}`, exit 1 (78 for the mode
refusals). Tokens: `root_refused`, `mirror_profile`, `mode_missing`, `profile_in_use`, `daemon_not_answering`,
`no_reply` (the daemon took the request but did not answer in time; the delete may still happen),
`daemon_not_running`, `not_found`, `path_is_folder`, `path_is_file`, `folder_not_empty`, `confirmation_required`,
`cancelled`, `busy` (an upload in flight), `unsynced_below` (a recursive delete over a file whose bytes the cloud has
not received, archive#1035), `read_only`, `outside_scope`, `auth_required`, `delete_failed`, plus the profile-resolution
tokens shared with `logout` (`config_not_found`, `profile_not_found`, ...).

## What the mount shows afterwards (archive#1199)

The daemon tombstones the row and sends `view.invalidated` for the deleted path (and for the folder, once) to its
subscribers, so a client drops the item within one refresh without waiting for a listing. A provider whose listing
lags a trash (Internxt: an hour or more, `mount-view-refresh-design.md` §3.2) still returns the item; the mount's
enumeration skips an item whose cloud id belongs to a row the mount tombstoned within the last two hours
(`RemoteGather.LAGGING_LISTING_WINDOW`), so the file does not come back as new. An item restored from the trash inside
that window shows again once the window has passed.
