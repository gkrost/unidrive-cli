# `unidrive dedupe`: duplicate remote items

`unidrive -p <profile> dedupe [--path <remotePath>] [--keep newest|largest|id:<id>] [--json]` lists the duplicate remote
items of a profile and what a keep rule would do with each set. Source: `core/app/cli/.../DedupeCommand.kt`,
`DedupePlan.kt`.

A drive that allows two live items of one name in one folder (Internxt) can hold duplicates. The engine keeps both
(archive#403): the canonical holder at the name, every other twin under `name (dup-xxxxxxxx).ext`, and `status` lists
the decorated ones. `dedupe` is the command that resolves them on purpose.

## What this version does

A dry run, always. It reads the profile's state.db read-only, calls no provider and changes nothing: no row, no file,
no cloud item. It works with or without a running daemon, for mount and mirror profiles alike. A profile without a
state.db has no duplicates (and none is created). There is no flag that removes anything yet; `--apply` is not an
option and is refused by the command line (exit 2).

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

Every other member of a planned set is listed in `trash_ids`: what an apply step would move to the provider's trash
by its id. A set with a folder in it is always skipped (`folder`): a folder twin holds a tree of its own.

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
`state_unreadable`, plus the profile-resolution tokens shared with `rm` (`config_not_found`, `profile_not_found`, ...).

## Still to do

The apply step: trash the planned members by id through the provider's `deleteById`, never a permanent delete; refuse
while a daemon holds the profile and for a provider whose `deleteById` does not trash; re-read each member right before
deleting it and skip one that changed since the plan; leave the rows to the next sync.
