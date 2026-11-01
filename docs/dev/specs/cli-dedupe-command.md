# `unidrive dedupe`: duplicate remote items

`unidrive -p <profile> dedupe [--path <remotePath>] [--keep newest|largest|id:<id>] [--apply] [--json]` lists the
duplicate remote items of a profile and what a keep rule would do with each set; with `--apply` it moves the members the
rule does not keep to the provider's trash. Source: `core/app/cli/.../DedupeCommand.kt`, `DedupePlan.kt`,
`DedupeApply.kt`.

A drive that allows two live items of one name in one folder (Internxt) can hold duplicates. The engine keeps both
(archive#403): the canonical holder at the name, every other twin under `name (dup-xxxxxxxx).ext`, and `status` lists
the decorated ones. `dedupe` is the command that resolves them on purpose.

## The dry run (without `--apply`)

It reads the profile's state.db read-only, calls no provider and changes nothing: no row, no file, no cloud item. It
works with or without a running daemon, for mount and mirror profiles alike. A profile without a state.db has no
duplicates (and none is created).

## Duplicate sets

A set is the alive rows of one local folder whose cloud name is the same and whose local name is that name or a twin
decoration of it, with at least one decorated member. Rows below a twin folder are not a set of their own. Rows without
a cloud id (an upload not done yet) take no part.

Each set reports `content`:

| `content` | When |
|---|---|
| `same` | Every member names the same stored object (`remote_content_id`), or every member carries a content hash (a provider with a separate write token) and they agree; the sizes agree. |
| `different` | The sizes differ, or two content hashes differ. |
| `unknown` | Anything else: folders, a provider whose `remote_hash` is a version token (Internxt), two stored objects that may hold the same bytes. Never guessed. |

## Keep rules

Without `--keep` every set's `action` is `none`: the sets are shown, nothing is planned. With `--keep`:

| Rule | Keeps | Skipped (`action` `skip`, `reason`) |
|---|---|---|
| `newest` | The member with the latest cloud modification time. | `tie` (two share it), `modified_unknown` (a member has none). |
| `largest` | The largest member. | `tie`. |
| `id:<id>` | The member with that cloud id. Sets without it stay `none`. | An id that is in no set (within `--path`) is refused: `unknown_id`. |

Every other member of a planned set is listed in `trash_ids`: what `--apply` moves to the provider's trash by its id. A
set with a folder in it is always listed and skipped (`folder`), with or without `--apply`: a folder twin holds a tree
of its own (owner decision: `--apply` handles files only).

## `--path`

Only the sets whose cloud path or a member's local path is the given path or lies below it (whole path segments).

## `--json`

Success:

```json
{"ok":true,"profile":"p","dry_run":true,"keep":"newest","count":1,"groups":[
 {"path":"/docs/a.txt","kind":"file","content":"unknown","action":"trash","keep_id":"bbbbbbbb-…","trash_ids":["aaaaaaaa-…"],
  "members":[{"id":"aaaaaaaa-…","local_path":"/docs/a.txt","canonical":true,"folder":false,"size":5,"modified":"…"},
             {"id":"bbbbbbbb-…","local_path":"/docs/a (dup-bbbbbbbb).txt","canonical":false,"folder":false,"size":5,"modified":"…"}]}]}
```

`kind` is `file`, `folder` or `mixed`; `action` is `none`, `trash` or `skip` (with `reason`). Members come canonical
first. A refusal is `{"ok":false,"error":"<token>","message":"..."}`, exit 1. Tokens: `invalid_keep`, `unknown_id`,
`state_unreadable`, plus the profile-resolution tokens shared with `rm` (`config_not_found`, `profile_not_found`, ...),
and the `--apply` refusals below.

## `--apply`

`--apply` needs `--keep`: without it the command refuses before anything is read (`keep_required`, exit 2, the usage
exit). Which twin stays is never guessed, whether the members' content is `same`, `different` or `unknown`.

Refused before anything is read or changed:

| Token | Exit | When |
|---|---|---|
| `keep_required` | 2 | `--apply` without `--keep`. |
| `mode_missing`, `mirror_profile` | 78 | The profile has no `mode`, or is a mirror: as with `rm`, the mirror's next sync could upload a trashed twin again from the sync root. |
| `trash_unsupported` | 1 | The provider's delete is permanent (localfs, S3, any provider not known to trash). Only Internxt, OneDrive and Dropbox, whose delete moves the item to their trash, are served. |
| `daemon_running` | 1 | A daemon holds the profile: stop it and run `--apply` again. Handing the trash to a running daemon over IPC is not part of this version. |
| `profile_in_use` | 1 | Another unidrive process (a sync) holds the profile. |
| `auth_required` | 1 | The provider refused the stored credentials (or none are stored). |

Otherwise the profile lock is held from the plan to the last delete, so no daemon or sync starts meanwhile. The plan is
the dry run's, from state.db. For each `trash` set, the set's folder is listed again (`listChildren` of the parent of
the set's cloud path) right before anything of it is deleted, and every member is compared with its row:

- `not_listed`: the listing has no live item of the member's id (gone, trashed, moved; or the set lies below a folder
  twin, whose name lists the other folder).
- `changed`: the item is a folder now, or its name, size, modification time or token (`remote_hash`; not compared when
  the row has none) differs from the row.

When the member to keep is `not_listed` or `changed`, nothing of the set is trashed: every other member is skipped
with `keep_changed`. Otherwise each other member that is unchanged is moved to the provider's trash with
`deleteById(id, path)`, never by its name (which could reach the other twin) and never `deletePermanently`; a changed
one is skipped. A failed listing (`relist_failed`) or delete (`delete_failed`, with the exception's type in `error`) is
reported and the other members and sets go on. The member to keep is never deleted.

state.db is only read: the rows of the trashed items stay until the next sync of the daemon sees the items gone, as for
any delete made in the cloud. A run with nothing planned (no set, no `trash` set) does not call the provider.

Exit: 0 when every planned member was trashed (or none was planned), 3 when at least one was skipped, 4 when at least
one failed. Sets the rule skips (`folder`, `tie`, `modified_unknown`) or does not touch (`none`) do not change the exit.

```json
{"ok":true,"profile":"p","dry_run":false,"keep":"id:aaaaaaaa-…","count":1,"trashed":1,"skipped":1,"failed":0,"groups":[
 {"path":"/a.txt","kind":"file","content":"unknown","action":"trash","keep_id":"aaaaaaaa-…",
  "trash_ids":["bbbbbbbb-…","cccccccc-…"],"members":[…],
  "results":[{"id":"bbbbbbbb-…","outcome":"skipped","reason":"changed"},{"id":"cccccccc-…","outcome":"trashed"}]}]}
```

`results` (only on a `trash` set) holds one entry per member of `trash_ids`: `outcome` is `trashed`, `skipped` (with
`reason` `changed`, `not_listed` or `keep_changed`) or `failed` (with `reason` `relist_failed` or `delete_failed` and
`error`). `trashed`, `skipped` and `failed` count these entries over all sets.
